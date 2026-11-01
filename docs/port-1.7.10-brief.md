# 1.7.10 剩余工作简报

## 已完成(可编译、可构建)

| 项 | 状态 |
| --- | --- |
| 工具链 | ✅ RFG 2.0.2 + **Gradle 9.5.0 跑在 JDK 25** + Java 8 toolchain(见 `toolchain-1.7.10.md`) |
| `forge-1.7.10` 工程 | ✅ 可 `build`,产出 Java 8 字节码 |
| `common-1.7.10` 核心逻辑 | ✅ 6 个类已移植并**编译通过** |

已移植(基本是纯 Java,无需改 API):

```
core/TickState.java
core/TickStats.java
core/TickControlAccess.java
core/ServerTickController.java
core/LoopPacer.java
core/TickControlAccessHolder.java   ← 已改写为自带 WeakHashMap 注册表
```

**关键结论**:`ServerTickController` / `LoopPacer` / `TickState` / `TickStats`
**不依赖任何 Minecraft 类型**,核心状态机与限速逻辑可以整体跨版本复用。
`TickControlAccessHolder` 唯一引用 `MinecraftServer`。

我把它改成**自带注册表**而不是复用 1.12.2 的 `command.TickControl`,
这样 core 层不依赖 command 层,避免循环依赖。

构建命令:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'
& C:\Users\Tamamo\Desktop\Mod\.tools\gradle-9.5.0\bin\gradle.bat `
  --project-dir C:\Users\Tamamo\Desktop\Mod\TickControl\forge-1.7.10 `
  --gradle-user-home C:\Users\Tamamo\Desktop\Mod\TickControl\.gradle-home `
  build --console=plain
```

## ⚠️ 尚未实现(当前 jar 只是骨架 + 核心类,没有 /tick)

### 1. 命令层(必须重写,不能抄 1.12.2)

1.7.10 的 `ICommand` 与 1.12.2 **不同**:

| 用途 | 1.7.10 | 1.12.2(对照) |
| --- | --- | --- |
| 命令名 | `getCommandName()` / `func_71517_b` | `getName()` |
| 用法 | `getCommandUsage(ICommandSender)` / `func_71518_a` | `getUsage(...)` |
| 别名 | `getCommandAliases()` / `func_71514_a` | `getAliases()` |
| 执行 | `processCommand(ICommandSender, String[])` / `func_71515_b` | `execute(MinecraftServer, ICommandSender, String[])` |
| 权限 | `canCommandSenderUseCommand(ICommandSender)` / `func_71519_b` | `checkPermission(MinecraftServer, ICommandSender)` |
| 补全 | `addTabCompletionOptions(ICommandSender, String[])` / `func_71516_a` | `getTabCompletions(...)` |
| 用户索引 | `isUsernameIndex(String[], int)` / `func_82358_a` | 同名 |

注意:`processCommand` 是 **void 且不接 `MinecraftServer`**,要拿服务器得用
`MinecraftServer.getServer()`(静态);权限等级由 `CommandBase.getRequiredPermissionLevel()`
提供。行为语义与其它版本一致(query / rate / freeze / unfreeze / step / step stop /
sprint / sprint stop)。

### 2. 主循环 hook(两条路线,建议选 B)

**路线 A:Mixin + UniMixins** —— 需要**往用户的测试实例安装 UniMixins**
(`io.github.legacymoddingmc:unimixins`),或者把 Mixin 打包进 jar。
manifest 的 `MixinConfigs` 在 1.7.10 上可用。
代价:改动用户实例,或引入一个不小的打包依赖。

**路线 B(建议):纯 ASM coremod** —— 不依赖 Mixin,不碰用户实例。
TickrateChanger 的 `1.7` 分支就是这么做且已验证可行:

- `IFMLLoadingPlugin` + `IClassTransformer`;
- 目标 `net.minecraft.server.MinecraftServer` 的 **`run()V`**
  (`run` 来自 `Runnable`,**两个域里都不混淆**,名字不变);
- `run()` 里有一个 `50L` 常量(每秒刻周期,毫秒制),替换它即可控速;
- 还需处理冻结/步进:在 `run()` 里注入"是否允许本刻推进"的判断,
  或对 `WorldServer.tick()`(`func_72835_b`)做门控。

生产域是 **SRG**,开发域是 **MCP**,而 `run` 两边同名 —— 这是它好用的原因。

### 3. 元数据与资源

- 1.7.10 **没有** `mods.toml`,用 **`mcmod.info`**;
- `pack.mcmeta` 的 `pack_format` 1.7.10 用 **1**;
- `assets/tickcontrol/lang/{en_us,zh_cn}.json`:1.7.10 的原版语言表**没有**
  `commands.tick.*`(那是 1.20.3 的键),所以这些键只能由本模组提供;
  1.7.10 的语言文件是 **`assets/<modid>/lang/*.json`** 或旧的 `.lang` 格式,
  二者需确认 RFG 的打包方式。
- 入口类已存在(`forge-1.7.10/.../TickControlForge.java`),命令注册**已完成**:
  `event.registerServerCommand(new CommandTick());`
  (本节此前写作"命令注册处是 TODO",已过期。)

> **状态更新**:1.7.10 已实现并构建产出,8 个子命令齐全,
> 主循环走**纯 ASM coremod**(不使用 Mixin)。详见 `toolchain-1.7.10.md` 的
> "当前进度"一节,其中记录了 `run()` 里四处 `50L` 中**哪两处才能改**,
> 以及`.tools-probe/test-1710-transform.py` 这一离线验证手段。

## 从其它三个版本带过来的教训(务必遵守)

1. **mixin/ASM 目标名必须对着运行时真正加载的 jar 核验**,不能对着 ForgeGradle
   的开发产物。1.16.5 因此崩了两次:
   开发域 `tick` / `updateTimeLightAndEntities`,而运行时是 `func_71217_p` /
   `func_71190_q`。详见 `rewrite-1.16.5-findings.md`。
2. 若用 Mixin:`compatibilityLevel` 用 `JAVA_8`,`minVersion` **不得超过**实际打包的
   Mixin 版本(1.16.5 就因 `minVersion 0.8.5` 超过自带的 0.8.4 而崩)。
3. **不要把选择名字域的工作交给"探测"**:探测猜错 = 客户端启动即崩。
   要么用确定性的构建期参数,要么干脆避免双变体。
4. `require = 1` / `defaultRequire: 1`:**不要**用 `require = 0` 回避问题,
   它会静默失效,表现为"模组加载了但完全没生效"。
5. 1.7.10 的语言/资源格式与 1.16+ 不同,别直接照搬。

## 测试实例

`E:\.minecraft\versions\1.7.10-Forge_10.13.4.1614`(已带 Forge `10.13.4.1614`,`mods\` 为空)。
注意:该实例**没有安装任何 Mixin 库** —— 这正是不建议走 Mixin 路线的原因。
