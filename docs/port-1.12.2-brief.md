# 1.12.2 移植简报(交给子代理执行,完成后由主会话验证)

## 目标

让 `C:\Users\Tamamo\Desktop\Mod\TickControl\forge-1.12.2` 的 `gradle build` 成功,
产出可安装的 `tickcontrol-forge-1.12.2-1.0.0.jar`,
实现 1.20.3 的 `/tick` 命令族:
`query` / `rate <rate>` / `freeze` / `unfreeze` / `step [<time>]` / `step stop` / `sprint [<time>]` / `sprint stop`。

## 构建命令(已验证可用,不要改工具链)

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-8'
& C:\Users\Tamamo\Desktop\Mod\.tools\gradle-4.9\bin\gradle.bat `
  --project-dir C:\Users\Tamamo\Desktop\Mod\TickControl\forge-1.12.2 `
  --gradle-user-home C:\Users\Tamamo\Desktop\Mod\TickControl\.gradle-home `
  build --console=plain
```

**必须 Gradle 4.9**。4.10.3 会在 `reobfJar` 报 `ProjectScopeServices has been closed`。
**必须 JDK 8**。ForgeGradle 3 + `sourceCompatibility 1.8`。

## 当前状态

`forge-1.12.2` 工具链已打通(普通源码 `compileJava` + `reobfJar` 均通过)。
`common-1.12.2` 是从 `common-1.16.5` 复制的,**还不能编译**,当前 31 个错误。

## 错误分类与修法

### A. 命令层必须整体重写(最大工作量)

**1.12.2 完全没有 Brigadier**(已核验:1.12.2 的 joined.tsrg 中 brigadier 零命中)。
所以这些文件要从 Brigadier 改成 legacy `ICommand`:

- `common-1.12.2/java/com/tamamo/tickcontrol/command/TickCommand.java`
- `common-1.12.2/java/com/tamamo/tickcontrol/command/TickControl.java`
- `common-1.12.2/java/com/tamamo/tickcontrol/core/VersionAdapter.java`
- `common-1.12.2/java/com/tamamo/tickcontrol/core/LegacyVersionAdapter.java`
- `common-1.12.2/java/com/tamamo/tickcontrol/core/VersionAdapterHolder.java`

1.12.2 的 legacy 命令 API(已核验的 MCP `stable_39` 名):

| 用途 | 签名 |
| --- | --- |
| 命令名 | `String getName()` / `func_71517_b` |
| 用法 | `String getUsage(ICommandSender)` / `func_71518_a` |
| 别名 | `List<String> getAliases()` / `func_71514_a` |
| 执行 | `void execute(MinecraftServer, ICommandSender, String[]) throws CommandException` / `func_184881_a` |
| 权限 | `boolean checkPermission(MinecraftServer, ICommandSender)` / `func_184882_a` |
| 补全 | `List<String> getTabCompletions(MinecraftServer, ICommandSender, String[], BlockPos)` / `func_184883_a` |
| 权限等级 | `CommandBase.getRequiredPermissionLevel()` / `func_82362_a` |
| 反馈 | `CommandBase.notifyCommandListener(ICommandSender, ICommand, String, Object...)` 或 `ICommandSender.sendMessage(ITextComponent)` |
| 报错 | `throw new CommandException("key", args)` |
| 消息类 | `net.minecraft.util.text.TextComponentString`、`TranslationTextComponent` |

类名用 `TickControlCommand`,**不要用 `TickCommand`**(1.12.2 虽无冲突,但与
1.20.3 的原版类同名容易混淆;forged-carpet 用的也是 `CommandTick`)。

行为语义必须与其它版本一致(见 `docs/verified-facts.md` 与 README):
- 根命令权限等级 **3**(用 `checkPermission` + 自己判断,或 `getRequiredPermissionLevel()` 返回 3)
- `rate` 取值 [1.0, 10000.0],返回值 `(int) rate`
- `freeze` 返回 1,`unfreeze` 返回 0
- `step` 无论成功与否返回 1
- `sprint` 总是输出「冲刺中」并返回 1;已在冲刺时先提示已停止
- 冲刺期间 `freeze` 失败

**版本适配层的意义**:把「发消息」「时间参数」「构造文本」三件事抽成接口,
命令逻辑本身尽量少改。1.12.2 没有 `ArgumentType`,所以 `timeArgument()` 这类
Brigadier 专属成员从接口里去掉,改成手工解析 `int`(支持 `t`/`s`/`d` 后缀可选)。

### B. 纯改名(机械化)

| 1.16.5 名 | 1.12.2 名 |
| --- | --- |
| `net.minecraft.world.server.ServerWorld` | `net.minecraft.world.WorldServer` |
| `org.slf4j.Logger` | `org.apache.logging.log4j.Logger`(1.12.2 无 slf4j) |
| `net.minecraft.command.CommandSource` | `net.minecraft.command.ICommandSender` |
| `net.minecraft.util.text.ITextComponent` | 同名,保留 |

`ServerLoop.java:630` 的 `tickLevel` 参数类型要改成 `WorldServer`。
`ServerLoop.java:262` / `:680` 的具体 symbol 缺失请按编译错误逐条处理。

### C. 1.12.2 的 `MinecraftServer` 成员差异(**1.12.2 没有**这些)

`serverTime`、`runTasksUntil`、`isRunningScheduledTasks`、`getWorlds()`、
`getServerDirectory()`、`createCommandSourceStack()`、`onServerExit()`、`tickServer()`。
`tick()` 与 `run()` **是两个分开的方法**,`run()` 持有 while 循环。

对应物:

| 1.16.5 | 1.12.2 |
| --- | --- |
| `serverTime` | `currentTime` / `field_175591_ab` |
| `runTasksUntil` | `futureTaskQueue` / `field_175589_i` + `addScheduledTask` / `func_152344_a` |
| `getWorlds()` | `worlds` 数组 / `field_71305_c`(类型 `WorldServer[]`) |
| `getServerDirectory()` | 无 → 用反射,失败就跳过保存崩溃报告 |
| `stopServer()` | protected → 反射 |
| `onServerExit()` / `onServerCrash()` | 无/不可见 → 反射 |
| `pollTask()` | 无 → `driveOne()` 或省略 |
| `Util.getMillis()/getNanos()` | `net.minecraft.util.Util.milliTime()/nanoTime()`(1.16.5 同) |

`run()` 里 `50L` 恰好出现两次(追赶循环与 `Thread.sleep`),这是限速核心。

## D. Mixin 接线(1.12.2 的 Forge **不自带** Mixin)

`dependencies` 里已有 `compile 'org.spongepowered:mixin:0.8.2'`。
还需:

1. **Coremod 引导类**(例如 `com.tamamo.tickcontrol.core.TickControlCore`),
   实现 `net.minecraftforge.fml.relauncher.IFMLLoadingPlugin`,构造器里:
   ```java
   MixinBootstrap.init();
   Mixins.addConfiguration("tickcontrol.mixins.json");
   ```
   并给类加 `@IFMLLoadingPlugin.MCVersion("1.12.2")` 与 `@IFMLLoadingPlugin.TransformerExclusions`。
2. **jar manifest** 加:
   `FMLCorePlugin`、`FMLCorePluginContainsFMLMod: true`、`ForceLoadAsMod: true`、
   `TweakClass: org.spongepowered.asm.launch.MixinTweaker`。
   (1.12.2 **不用** `MixinConfigs` manifest 属性——它走 `Mixins.addConfiguration`)
3. `tickcontrol.mixins.json` 里 `compatibilityLevel` 必须是 **`JAVA_8`**,
   `minVersion` 用 **`0.8.2`**(不能用 0.8.5——超过实际 Mixin 版本会导致加载失败,
   1.16.5 就是这样崩的)。
4. Mixin 目标名:1.12.2 生产域是 SRG。已核验:
   `run()` **名字不变**(来自 `Runnable`,不混淆);
   `tick()` = `func_71217_p`;`updateTimeLightAndEntities()` = `func_71190_q`;
   `WorldServer.tick()` = `func_72835_b`。
   现有 `MinecraftServerMixinSrg` 里的 `func_240802_v_` 是 1.16.5 的,**1.12.2 不适用**,
   应改成 `run`。

## E. Forge 入口类

1.12.2 用 `@Mod` + `FMLServerStartingEvent` 注册命令:

```java
@Mod(modid = "tickcontrol", version = "1.0.0")
public class TickControlForge {
    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new TickControlCommand());
    }
}
```

`mcmod.info` 已存在。`mods.toml` 已在 sourceSet 里排除。

## 交付要求

1. `gradle build` 成功,产出 jar。
2. 用正式 jar 验证,禁止 dev 运行、禁止 demo 模式(用户硬性要求)。
3. 不要改动其它版本目录(`common-1.16.5`、`forge-1.16.5`、`1.17.1` 等)与
   `docs/` 下文件。
4. 完成后报告:改了哪些文件、最终构建命令、产物路径与大小、
   以及核验过的内容(字节码版本、manifest、mixins.json、mixin 目标名)。
5. 诚实报告:哪些地方是猜的、哪些没验证。
