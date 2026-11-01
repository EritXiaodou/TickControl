# 1.7.10 工具链(已实测)

## 结论:`RetroFuturaGradle 2.0.2` + `Gradle 9.5.0` + **JDK 25 守护进程** + Java 8 toolchain

已被一次完整 `build` 验证通过(整条流水线:解压 → 反混淆到 SRG → 反编译 → 打补丁 →
remap → 编译 → reobf),产物为 Java 8 字节码(class-file 52)。

## 为什么每一步都是这样,不是随便配的

| 项 | 值 | 依据 |
| --- | --- | --- |
| 插件 | `com.gtnewhorizons.retrofuturagradle` **2.0.2** | 经典 ForgeGradle 1.2 已不可用:需要 Gradle 2.x 守护进程,且 MCP 坐标被改名(`mcp_stable:12` 已不存在,现在是 `12-1.7.10`) |
| 插件仓库 | `https://nexus.gtnewhorizons.com/repository/public/` | RFG 不在 Gradle Plugin Portal 上 |
| Gradle | **9.5.0** | RFG 2.x 面向 Gradle 9 线(其自身 wrapper 是 9.2.0) |
| **守护进程 JDK** | **JDK 25**(`C:\Program Files\Java\jdk-25.0.3`) | **两个硬性下限叠加**:Gradle 9.5.0 要求 JVM ≥17(用 JDK 8 直接报 `Gradle requires JVM 17 or later`);而 RFG 2.0.2 的插件类编译到 **class-file 69(Java 25)**,JDK 17 加载不了(`UnsupportedClassVersionError ... class file version 69.0, this version ... up to 61.0`)。**只有 JDK 25 同时满足两者。** |
| 编译目标 | Java 8 toolchain | 1.7.10 运行时是 Java 8 |

即:**构建用 JDK 25,产物是 Java 8**——与 1.16.5 的「Gradle 跑 JDK 17、产出 release 8」
是同一类分工,只是这里的上限被 RFG 抬到了 25。

## 可用构建命令

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'
& C:\Users\Tamamo\Desktop\Mod\.tools\gradle-9.5.0\bin\gradle.bat `
  --project-dir C:\Users\Tamamo\Desktop\Mod\TickControl\forge-1.7.10 `
  --gradle-user-home C:\Users\Tamamo\Desktop\Mod\TickControl\.gradle-home `
  build --console=plain
```

`gradle.properties` 里已显式登记 JDK 路径(关掉 auto-download),避免 Gradle 自己挑错:

```
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=C:\Program Files\Java\jdk8u312-b07,C:\Program Files\Zulu\zulu-17,C:\Program Files\Java\jdk-16.0.2-7,C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
```

## ✅ 当前进度:已实现并构建产出(本节曾写"只有骨架",已过期)

`forge-1.7.10/build/libs/tickcontrol-forge-1.7.10-1.0.0.jar`(约 25 KB)。
**下面那段"还缺什么"的清单已经全部完成**,保留于此仅作历史参考;
现状如下:

| 项目 | 状态 |
| --- | --- |
| 命令层(legacy `ICommand`) | ✅ `command/CommandTick.java`,8 个子命令齐全 |
| 注册 | ✅ `FMLServerStartingEvent#registerServerCommand` 已接线 |
| 主循环 hook | ✅ 纯 ASM coremod(`TickControlTransformer`),**不用 Mixin** |
| 元数据 | ✅ `mcmod.info` + `pack.mcmeta`(format 1) |
| 冲刺报告投递 | ✅ `TickControlRuntime.deliverSprintReportIfReady` |

**注意 jar 名是小写 `tickcontrol-forge-...`**(由 `base.archivesName` 决定);
旧文档里写的 `TickControl-Forge-1.7.10-1.0.0.jar` 已不再产出。

### ASM 注入点(实测,不是推断)

`run()` 里有**四处** `ldc2_w 50L`,作用各不相同,**只有两处能改**:

| 偏移 | 用途 | 处理 |
| --- | --- | --- |
| 123 | 日志文案里 `j/50` 的除数 | 保留 |
| 196 | 追赶循环条件 `i > 50L` | **保留**(改成 0 会空转) |
| 204 | 追赶循环 `i -= 50L` | → `TickControlRuntime.tickBatch()`(门控) |
| 217 | `Thread.sleep` 的周期 | → `TickControlRuntime.waitForNextTick()`(速率) |

变换器按**指令模式**定位(偏移会随 Forge 补丁漂移),不要硬编码偏移。
`.tools-probe/test-1710-transform.py` 可在**离线**验证这两个点确实被命中,
并确认剩下的恰好是 2 处 `50L`。**每次改动变换器后都应重跑它。**

### 还缺什么(原始清单,已完成,保留备查)

1. **命令层**:1.7.10 与 1.12.2 一样**没有 Brigadier**(FML 1.7.10 joined.srg 中零命中),
   必须按 legacy `ICommand` 重写。已核验的 1.7.10 签名:
   - `getCommandName` / `func_71517_b`
   - `getCommandUsage(ICommandSender)` / `func_71518_a`
   - `getCommandAliases()` / `func_71514_a`
   - `processCommand(ICommandSender, String[])` / `func_71515_b`
   - `canCommandSenderUseCommand(ICommandSender)` / `func_71519_b`
   - `addTabCompletionOptions(ICommandSender, String[])` / `func_71516_a`
   - `isUsernameIndex(String[], int)` / `func_82358_a`
   注意:1.7.10 的 `ICommand` 与 1.12.2 **不同**——`processCommand` 是 `void` 且**不接
   `MinecraftServer`**,权限方法也不叫 `checkPermission`。不要照抄 1.12.2 的写法。
2. **注册**:`FMLServerStartingEvent#registerServerCommand`(已在入口类里留好位置)。
3. **主循环 hook**:1.7.10 的 `run()` 与 `tick()` **已经是分开的两个方法**
   (`tick` = `func_71217_p`);`run()` 的名字**未被混淆**(来自 `Runnable`)。
   TickrateChanger 的 1.7 分支做法是 `IFMLLoadingPlugin` + `IClassTransformer`
   替换 `run()V` 里那两处 `50L` 常量。
4. **Mixin(可选)**:1.7.10 的 Forge 不带 Mixin;GTNH 生态用 **UniMixins**
   (`io.github.legacymoddingmc:unimixins`)提供,manifest 的 `MixinConfigs` 在 1.7.10 上可用。
   **若走 ASM coremod 路线则完全不需要 Mixin**——对只改 `run()` 周期这一件事,coremod 更简单。
5. **元数据**:1.7.10 没有 `mods.toml`,用 `mcmod.info`。

## 测试实例

`E:\.minecraft\versions\1.7.10-Forge_10.13.4.1614`(已带 Forge `10.13.4.1614`,`mods\` 为空)。
