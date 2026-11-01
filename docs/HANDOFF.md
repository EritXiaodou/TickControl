# 接手说明(新会话从这里开始)

> 上一位会话已接近上下文上限。**先读本文件,再读 `docs/verified-facts.md`**,不要重新调研。

---

## 一、任务

在 `C:\Users\Tamamo\Desktop\Mod\TickControl` 里,为 **1.17.1 / 1.16.5 / 1.12.2 / 1.7.10**
四个版本实现 1.20.3 加入的 `/tick` 命令族:

```
/tick query              /tick step [<time>]
/tick rate <rate>        /tick step stop
/tick freeze             /tick sprint [<time>]
/tick unfreeze           /tick sprint stop
```

**完成标准(用户已确认):`gradle build` 成功并产出 jar。运行验证由用户自己做。**

## 二、用户已明确的三条决定(不要推翻)

1. **1.16.5 的现有代码弃用**——用户实测"功能就是错的"。**不要参考**
   `common-1.16.5/` 的旧 `ServerLoop`(而且它的中文注释已编码损坏)。1.16.5 必须
   从 `common-1.19.2` 重新移植。**✅ 这条已经执行完毕:重写完成、构建通过、
   jar 已部署(见第五节 5.2)。**
2. **基线 = `common-1.19.2`**。它是唯一干净可用的:构建通过、注释完好,而且它的
   `VersionAdapter` 注释里自己就写明了 `Component.translatable` 是 1.19 才有的静态工厂
   (即 `common-1.18.2` 是结构性坏的,已被 1.17.1 取代,不必修)。
3. **验证必须用正式 jar,禁止 dev 运行和 demo 模式。** 测试实例在 `E:\.minecraft`,
   按版本隔离,jar 放到 `E:\.minecraft\versions\<实例>\mods`(见第九节)。

## 三、当前状态

> **本节原内容已严重过期**(曾写"1.12.2 无 Java 源码、1.7.10 零代码")。
> 以下是**本次会话结束时的真实状态**,以 `gradle build` 产物与实际测试为准。

四个目标版本**全部构建成功、jar 已产出并部署到各自测试实例**:

| 目标 | 构建 | 8 个子命令 | 运行验证 |
| --- | --- | --- | --- |
| **1.17.1** | ✅ | ✅ | ✅ **用户已确认功能正常** |
| **1.16.5** | ✅ | ✅ | ✅ **用户已确认可进存档且功能全部可用**(含冲刺报告) |
| **1.12.2** | ✅ | ✅ | ✅ **用户已确认功能全部通过**(含 freeze / step / sprint) |
| **1.12.2-MixinBooter** | ✅ | ✅ | ⏳ 可选变体,待测(需先装 MixinBooter) |
| **1.7.10** | ✅ | ⚠️ **部分** | ⚠️ **freeze / step / query 已确认可用;`rate` 与 `sprint` 仍未生效,已搁置** |
| **1.18.2 / 1.19.2 / 1.20.1-forge / 1.20.1-neoforge** | ✅ | — | ✅ **用户已确认正常(随 1.16.5+ 一并通过)** |

> **"1.16.5+" 的含义**(2026-10,用户澄清;此前我误读成只有 1.16.5):
> 指 **1.16.5 及以上的全部版本**,即 1.16.5 / 1.17.1 / 1.18.2 / 1.19.2 / 1.20.1-forge /
> 1.20.1-neoforge。用户确认"1.16.5+ 正常"时,这些版本**全部**已通过。
>
> 另注:`common-1.18.2` / `common-1.19.2` / `common`(1.20.1)这三个目录**不在**上面
> "四个目标"的表述里 —— 那是早期的范围认知,实际仓库有 7 个构建目标 + 1 个
> MixinBooter 变体 = **8 个 jar**。以 `.build/` 的内容为准。

### ✅ 1.7.10 架构改造完成(2026-10-31)

主循环已改成与 1.12.2 / 1.20.3 相同的结构,**`rate` 与 `sprint` 现已真正生效**。
在真实专用服务器上跑完全部 8 条命令并逐条核对回执。

**旧方案为什么无效**:原版 `run()` 的循环是自校正的 ——
`i += elapsed; while (i > 50) { i -= 50; tick(); } sleep(max(1, 50 - i))`。
`i` 是**累积欠账**,所以任何"只把睡眠拉长"的改动都会被下一轮的追赶循环用补跑抵消。
实测 BENCH 证据:主循环降到 1 次/秒,世界时间仍涨 20 刻/秒 —— 正好抵消。
只换 `Thread.sleep` 里那个 50L 不够;四个 50L 全换也只是把上限做得好看一点。

**新方案**:接管整个循环(`ServerLoop.runServerLoop`),每轮只问一次节拍器,
放行才 tick,然后睡到**绝对截止时刻**,循环体里没有追赶。

**关键实现细节(每条都对应一次真实故障):**

1. **注入位置绝不能在分支目标上。** 在循环头插入会让既有 stackmap frame 偏移错位,
   报 `VerifyError: Expecting a stackmap frame`。改为**重定向回边 `goto`**,
   把接管片段追加到方法末尾(无 frame 目标的位置)。
2. **必须用 `COMPUTE_FRAMES`**,`COMPUTE_MAXS` 不重算栈帧。
3. 默认 `getCommonSuperClass` 会加载类,核心变形期有递归风险;但**无脑返回 `Object`**
   会把同类里无关的代码弄坏:
   `Type 'java/lang/Object' is not assignable to 'WorldServer'`(`loadAllWorlds`)。
   最终用 **`LaunchClassLoader.getClassBytes()`** 只读字节解析继承关系(不定义类),
   解析不到才退回 `Object`。
4. **冲刺期间必须完全不睡。** `loopPeriodMillis()` 按 rate 算,冲刺时不会变成 0;
   照常睡的话 `sprint 1d`(24000 刻)要跑约 20 分钟 —— 实测 34 秒毫无进展。
5. **冲刺报告的投递点要跟着搬家。** 原投递挂在 `doSleep` 上,接管后不再执行,
   导致"冲刺跑完但没有任何报告"。现在由新循环每轮投递
   (`consumeSprintReport()` 内部只返回一次 true,不会刷屏)。

**字段名全部来自官方映射,不用猜:**

```
mt  = net/minecraft/world/WorldServer          <-- 不是 MinecraftServer!
   mt.b()V -> func_72835_b -> WorldServer.tick
   mt.h()V -> func_72939_s -> WorldServer.updateEntities
   mt.e()Z -> func_73056_e -> areAllPlayersAsleep   (世界查询,不是运行标志)

net/minecraft/server/MinecraftServer
   u()V -> func_71217_p -> tick
   v()V -> func_71190_q -> updateTimeLightAndEntities
   v:Z  -> field_71317_u -> serverRunning   (循环条件,run() 里 GET=1)
   P:J  -> field_71299_R -> 时钟
   O:Z  -> field_71296_Q -> 每轮写入的就绪标志
```

> 曾把 `[Lmt;` 误读成"MinecraftServer 的数组",于是把 `mt` 当作 MinecraftServer、
> 把 `mt.e()` 当成运行标志 —— 那会让接管挂到错误的字段上。是用户指出后才纠正。

**实测回执(真实专用服务器,JDK 8 + FMLServerTweaker):**

```
tick query      -> Ticking is running / Target tick rate: 20.0 / P50=0.62 P95 P99 avg
tick freeze     -> Froze ticking
tick step 20    -> Stepping forward 20 tick(s)
tick unfreeze   -> Unfroze ticking
tick sprint 1d  -> The game is sprinting -> Sprint completed: 19874 TPS, 0.05 ms/tick
tick rate 200   -> Set the target tick rate to 200.0 per second
tick query      -> Target tick rate: 200.0 per second
tick rate 20    -> Set the target tick rate to 20.0 per second
```

**状态:全部子命令已由用户实测确认可用,版本升至 1.0.0。**

**客户端验证由用户自行完成**;本次部署的是正式版(非 `-dev`),
三处 sha256 一致(构建输出 / 实例 mods / `.build`)。


## 四、【历史存档】曾计划先做 1.12.2

> 1.12.2 与 1.7.10 **均已完成**,本节仅存档当时的调研,不必再执行。

1.17.1 和 1.16.5 都**已经构建成功**(命令见第五节),原先的构建阻塞全部解除。
下一个目标是 **1.12.2**:目前**没有 Java 源码**,需要新建。既有调研结论完整保留在
`docs/verified-facts.md` 第 4 节与本文第八节,照抄即可,不必重新调研。要点:

> 现状提示:`forge-1.12.2/` 下已存在一个**尚未纳入 git** 的工程骨架
> (`build.gradle` / `gradle.properties` / `settings.gradle` + `src/main/resources/mcmod.info`,
> `.gradle/` 里有 4.10.3 与 8.14 两代缓存),但**没有任何 `*.java`**,也没有 `common-1.12.2`。
> 该骨架的 `build.gradle` 注释里**记录了实测结论**:FG 2.3 只能在 **Gradle 4 期 + JDK 8**
> 上跑,modern-Gradle 抄近路不行(`SourceSet.getCompileConfigurationName` 已被 Gradle 移除),
> 且 `compile` 兼容 shim 会造成 `Cyclic extendsFrom`。**先读那份注释再动手。**

- FG5/FG6 **无法**用于 1.12.2(`forge-1.12.2-*-userdev.jar` = 404)。官方 MDK 用
  `ForgeGradle:3.+` + Gradle 4.9 + JDK 8。调研建议**先花 5 分钟试 `ForgeGradle:2.3.10` 配
  Gradle 8.14 + JDK 8**(该版本由 Gradle 9.0.0 构建、无 plugin-api-version 门禁,
  FG_2.3 分支 2025/2026 仍有提交);但上面那份已有骨架试的是 `2.3-SNAPSHOT`,
  并记下"modern Gradle 走不通",所以 **`2.3.10` 在本仓库仍是未验证项**。
  失败就退回 Gradle 4.10.3。
- 1.12.2 **没有 Brigadier**(tsrg 零命中)。命令层必须按 legacy `ICommand` /
  `CommandBase` **整体重写**,注册走 `FMLServerStartingEvent#registerServerCommand`。
  这是工程量最大的部分。
- Mixin 用 **`org.spongepowered:mixin:0.8.2`**,注册方式是 `MixinBootstrap.init()`
  + `Mixins.addConfiguration(...)`(与他版**不同**,不走 manifest 的 `MixinConfigs`)。
- **抄 `DeadlyMC/forged-carpet`(它就是 1.12.2、同功能、可 diff),不要抄 TickrateChanger。**
- 构建时以**实例的** loader 版本为准:1.12.2 实例是 `14.23.5.2864`。

### 附:为什么"试 Gradle 8.1.x 修 1.17.1"这条线失败了(存档,别再试)

上一版把"试 Gradle 8.1.1"当作 1.17.1 的下一步,实测**两条 JDK 都走不通**:

- 配 **JDK 21**:Gradle 8.1.1 **根本起不来**——Groovy 的 ASM 不认 JDK 21 的 class,
  报 `BUG! ... Unsupported class file major version 65`(**Gradle 8.5 才是第一个支持
  JDK 21 的版本**)。
- 配 **JDK 17**:确实越过了 `ProjectScopeServices has been closed`,推进到 ForgeGradle
  真正的 rename 步骤,然后崩在 `jdk.zipfs.ZipFileSystem.checkWritable`
  → `ReadOnlyFileSystemException`,即已知的"**JDK 17 的 zipfs 把空 zip 判为只读**"。

也就是说这条线需要的两个 JDK 前提**互相排斥**。**但这条结论现在只是存档**:
1.17.1 已经用 **JDK 16 + Gradle 8.4** 正常构建,不需要再碰 Gradle 8.1.x。

## 五、1.17.1 / 1.16.5 的关键修正与可复现构建命令(别再改回去)

### 5.1 1.17.1(已成功构建)

**可复现命令**(Gradle **8.4** 跑在 **JDK 16** 上):

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-16.0.2-7'
& C:\Users\Tamamo\Desktop\Mod\.tools\gradle-8.4\bin\gradle.bat `
  --project-dir C:\Users\Tamamo\Desktop\Mod\TickControl\forge-1.17.1 `
  --gradle-user-home C:\Users\Tamamo\Desktop\Mod\TickControl\.gradle-home `
  build --console=plain
```

**根因**:上一版判定的"ForgeGradle 硬阻塞"其实就是**这台机器没有 JDK 16**。
1.17.1 是唯一一个官方 MDK 把 toolchain 钉在 Java 16 的目标;装上 Temurin
`16.0.2+7` 之后**构建一次通过**,`forge-1.17.1-37.1.1_mapped_official_1.17.1.jar`
正常产出。

工程侧的三处修正(别再改回去):

1. `options.release = 16`(不是 17),toolchain `languageVersion = JavaLanguageVersion.of(16)`,
   `sourceCompatibility/targetCompatibility = VERSION_16`。Forge 1.17.1-37.1.1
   自带 **Mixin 0.8.4 + ASM 9.1**,ASM 9.1 上限是 class-file 60(Java 16)。
2. `common-1.17.1/resources/tickcontrol.mixins.json` 的 `compatibilityLevel` 是 **`JAVA_16`**,
   `minVersion` 是 **`0.8.4`**。
3. `common-1.17.1/resources/META-INF/mods.toml` **已删掉 `[[mixins]]` 段**——那是 1.20.5+ 写法,
   1.17.1 只认 jar manifest 的 `MixinConfigs`。

**为 1.17.1 修掉的 API 差异**(每一条都对着官方 1.17.1 映射核验过,不是猜的):

| 项目 | 1.17.1 的实际情况 | 处理 |
| --- | --- | --- |
| `net.minecraft.util.profiling.jfr.JvmProfiler` | 1.17.1 **不存在**(1.18+ 才有) | 删掉 import 和每刻的 `onServerTick` 调用 |
| `ServerLifecycleHooks` | 在 `net.minecraftforge.fmllegacy.server`(1.18.2 才挪到 `net.minecraftforge.server`) | 改包名 |
| `Commands.performPrefixedCommand` | 1.17.1 叫 **`performCommand`**(prefixed 是 1.19+) | 改名 |
| `Util.getFilenameFormattedDateTime()` | 1.18+ | 自己写本地 `timestamp()` 辅助方法 |
| `MinecraftServer.onServerCrash` | 1.17.1 上只是**包私有** | 走反射 |
| `Component.translatable` / `Component.literal` | 1.19+ 静态工厂 | 1.17.1 适配层用 `new TranslatableComponent(...)` / `new TextComponent(...)` |
| `com.mojang.logging.LogUtils` | 1.18+ | 改用 Log4j 的 `LogManager` |

**产物已核验**:40 个条目;manifest 有 `MixinConfigs: tickcontrol.mixins.json`;
`tickcontrol.mixins.json` 的 `minVersion 0.8.4`、`compatibilityLevel JAVA_16`;
`ServerLoop.class` 是 class-file **60**(Java 16);Dev mixin 里是官方名
(`runServer`/`tickServer`/`tickChildren`),Srg mixin 里是
`m_130011_`/`m_5705_`/`m_5703_`/`m_8793_`。
已部署到 `E:\.minecraft\versions\1.17.1-Forge_37.1.1\mods\`。

### 5.2 1.16.5(已成功构建,旧 jar 已被替换)

**可复现命令**(Gradle **7.6.4** 跑在 **JDK 17** 上;ForgeGradle 5 **拒绝 Gradle 8.x**,
报 `Versions Gradle 8.0 and newer are not supported`):

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-17'
& C:\Users\Tamamo\Desktop\Mod\.tools\gradle-7.6.4\bin\gradle.bat `
  --project-dir C:\Users\Tamamo\Desktop\Mod\TickControl\forge-1.16.5 `
  --gradle-user-home C:\Users\Tamamo\Desktop\Mod\TickControl\.gradle-home `
  build --console=plain
```

关键配置:`mapping_channel=snapshot`、`mapping_version=20210309-1.16.5`、
`forge_version=1.16.5-36.2.34`(用户实例是 **36.2.42**,在范围内,够用)。

**最重要的发现**:`common-1.16.5` 之前配的是 **snapshot(MCP)名**,而从
`common-1.19.2` 移植下来的代码用的是**官方名**,两者**不兼容**。1.16.5 的
MCP/snapshot 域是:`net.minecraft.command.CommandSource`、
`net.minecraft.util.text.ITextComponent`、`net.minecraft.world.server.ServerWorld`、
`net.minecraft.profiler.IProfiler`、`net.minecraft.util.Util.milliTime/nanoTime`、
`net.minecraft.util.text.LanguageMap`、`MinecraftServer.tick(BooleanSupplier)`(protected)、
`MinecraftServer.driveOne()`(不是 `pollTask()`)、`MinecraftServer.stopServer()`(protected),
**没有** `tickServer`/`onServerExit`/`getServerDirectory`/`createCommandSourceStack`;
`CommandSource.sendFeedback`/`sendErrorMessage`(不是 `sendSuccess`/`sendFailure`)、
`CommandSource.hasPermissionLevel`(不是 `hasPermission`)、
`TimeArgument` 只有无参构造(没有静态 `time()`)、
`IProfiler.startSection`/`endStartSection`/`endSection`(不是 push/pop/popPush)、
`net.minecraft.crash.CrashReport.saveToFile(File)`。

**官方映射路线也试过并被否决**:ForgeGradle 5 的 MCP 流程会因为 Gradle 选中
Java 8 toolchain(`temurin-8-amd64-windows\jdk8u504-b01`)而崩在
`java.lang.IllegalArgumentException: invalid flag: --release`。

**Java 8 源码级别带来的额外改动**(不只是改 API 名):`Map.of`(Java 9)→ 静态初始化块 + `HashMap`;
`InputStream.readAllBytes()`(Java 9)→ 手写读取循环;Java 16 的 `instanceof` 模式匹配 →
传统 `instanceof` + 强转。

**严重 bug(已修)**:mixin 的目标名是从 1.19.2 抄过来的。对着**映射后的 dev jar**
和真实的 `forge-1.16.5-36.2.34-srg.jar` **双向核验**,1.16.5 在 MCP 域**没有**
`runServer`/`tickServer`,在 SRG 域**没有** `m_130011_`/`m_5705_`/`m_5703_`/`m_8793_`
(那是 1.17+ 的 `m_` 风格;1.16.5 用 `func_` 风格)。正确值是:主循环 `func_240802_v_`,
单刻 `tick`(dev)/ `func_71217_p`(SRG),世界循环 `updateTimeLightAndEntities`(dev)/
`func_71190_q`(SRG),以及 `ServerWorld.tick` / `func_72835_b`。
**这条很要命:`reobfJar` 不会重写 mixin 注解里的字符串。**

**产物已核验**:37 个条目;manifest 有 `MixinConfigs`;`ServerLoop.class` 是 class-file
**52**(Java 8);没有残留的 `net/minecraft/server/level/`、`net/minecraft/commands/`、
`net/minecraft/network/chat/`、`net/minecraft/util/profiling/` 引用。
已部署到 `E:\.minecraft\versions\1.16.5-Forge_36.2.42\mods\tickcontrol-forge-1.16.5-1.0.0.jar`
(覆盖了用户实测"功能就是错的"的那只旧 jar)。用户三条决定里的第 1 条
(1.16.5 按 `common-1.19.2` 重写)**已经执行完毕**。

## 六、一条必须记住的环境铁律

**判断源码编码只看码点,不看控制台。** 本机 `[Console]::OutputEncoding` 是 GBK,
而 pwsh 又把输出转成 UTF-8,**任何中文在控制台都显示成 `��`**——这是显示假象。
上一位会话因此**两次误判**文件损坏。

正确做法:
```powershell
& <bundled python> TickControl\.tools-probe\scan-mojibake.py C:\Users\Tamamo\Desktop\Mod\TickControl
```
判据是"常用汉字占比":正常文件 58~65%,损坏文件 9%。上一轮实测**只有
`common-1.16.5/.../ServerLoop.java` 一个文件真损坏**(含 U+3125 注音符号、U+E1E7 私用区);
该文件已随 1.16.5 的重写被替换,现在能按 UTF-8 正常解码。

## 七、工具链(实测,`docs/verified-facts.md` 第 0 节有完整表)

| 目标 | JDK | Gradle | 状态 |
| --- | --- | --- | --- |
| **1.17.1**(FG6) | **JDK 16**(`C:\Program Files\Java\jdk-16.0.2-7`) | 8.4 | ✅ 构建通过 |
| **1.16.5**(FG5) | **JDK 17**(`C:\Program Files\Zulu\zulu-17`) | **7.6.4** | ✅ 构建通过 |
| 1.18.2 / 1.19.2 / 1.20.1(FG6) | JDK 21(Adoptium) | 8.4 | 继承来的 |
| 1.12.2 | **JDK 8**(`jdk8u312-b07`) | **4.9**(4.10.3 在 `reobfJar` 失败,勿用) | ✅ 构建通过 |
| 1.7.10 | **JDK 25**(`jdk-25.0.3`) | **9.5.0**(RFG 2.0.2;类为 Java 25,JDK 8/17 都不行) | ✅ 构建通过 |

**本机已装可用的 JDK(`C:\Program Files\Java` 需要管理员权限才能写入)**:

| JDK | 路径 | 来源 |
| --- | --- | --- |
| **16** | `C:\Program Files\Java\jdk-16.0.2-7` | **本次新装**(Temurin `16.0.2+7`,经提权的 robocopy 助手装入) |
| **11** | `C:\Program Files\Java\jdk-11.0.32.1-1` | **本次新装**(`11.0.32.1+1`) |
| 8 | `C:\Program Files\Zulu\zulu-8`(见 `.tools\jdk8`) | 原有 |
| 17 | `C:\Program Files\Zulu\zulu-17` | 原有 |
| 21 | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` | 原有 |

**陷阱:Gradle 7.6.4 配 JDK 21 不行**——编译构建脚本时报
`Unsupported class file major version 65`,只要脚本有改动就必崩。必须配 JDK 17。

**另一条线已彻底否决**:Gradle **8.1.1** 修不了任何东西——JDK 21 上起不来
(`Unsupported class file major version 65`),JDK 17 上崩在 zipfs 只读。详见第四节附注。

## 八、1.12.2 / 1.7.10 的关键结论(省你几小时)

**两者都没有 Brigadier**(tsrg 零命中)。命令层必须按 legacy `ICommand` / `CommandBase`
整体重写,注册走 `FMLServerStartingEvent#registerServerCommand`。这是它们工程量最大的部分。

- **1.12.2**:FG5/FG6 **无法**用于 1.12.2(`-userdev.jar` = 404)。官方 MDK 用 `ForgeGradle:3.+`
  + Gradle 4.9 + JDK 8。**先花 5 分钟试 `ForgeGradle:2.3.10` 配 Gradle 8.14 + JDK 8**
  (该版本由 Gradle 9.0.0 构建、无 plugin-api-version 门禁,FG_2.3 分支 2025/2026 仍有提交)。
  失败就退回 Gradle 4.10.3。Mixin 用 **`org.spongepowered:mixin:0.8.2`**,
  注册方式是 `MixinBootstrap.init()` + `Mixins.addConfiguration(...)`(与他版**不同**)。
  **抄 `DeadlyMC/forged-carpet`(它就是 1.12.2),不要抄 TickrateChanger。**
  (旁证:`forge-1.12.2/build.gradle` 的注释用 `2.3-SNAPSHOT` 试过 modern Gradle,
  结论是"FG 2.3 只能在 Gradle 4 期 + JDK 8 上跑";`2.3.10` 尚未验。)
- **1.7.10**:不要用经典 FG1.2(`mcp_stable:12` 已被改名为 `12-1.7.10`)。
  用 **RetroFuturaGradle 2.0.2** 或 GTNH ExampleMod1.7.10,Mixin 由 **UniMixins** 提供。
  `run()` 与 `tick()`(`func_71217_p`)**已经是分开的两个方法**。

## 九、测试与验证协议(用户硬性要求)

**禁止 `gradle runClient` / `runServer`(dev 运行),禁止 demo 模式。**
验证一律走正式 jar + 用户自己的实例。

测试实例根目录 **`E:\.minecraft`**(PCL2 启动器,`PCL.ini`)。**mods 按版本隔离**,
放置路径固定为 `E:\.minecraft\versions\<实例目录>\mods\`。

| 目标 | 实例目录 | 该目录现有 mods | 我方产物应放 |
| --- | --- | --- | --- |
| 1.7.10 | `1.7.10-Forge_10.13.4.1614` | **空(已备好,Forge 10.13.4.1614 已装)** | `tickcontrol-forge-1.7.10-1.0.0.jar` |
| 1.12.2 | `1.12.2-Forge_14.23.5.2864` | `forged-carpet-1.12.2-1.3.2.jar` | `tickcontrol-forge-1.12.2-1.0.0.jar` |
| 1.16.5 | `1.16.5-Forge_36.2.42` | `tickcontrol-forge-1.16.5-1.0.0.jar`(**已更新为本次构建的产物**) | 同名覆盖 |
| 1.17.1 | `1.17.1-Forge_37.1.1` | `tickcontrol-forge-1.17.1-1.0.0.jar`(**本次新放入**) | `tickcontrol-forge-1.17.1-1.0.0.jar` |
| 1.18.2 | `1.18.2-Forge_40.3.12` | 1 个 | `tickcontrol-forge-1.18.2-1.0.0.jar` |
| 1.19.2 | `1.19.2-Forge_43.5.1` | 2 个 | `tickcontrol-forge-1.19.2-1.0.0.jar` |
| 1.20.1 | `1.20.1-Forge_47.4.26`(另有 `..._47.4.9`) | 26 / 205 个 | `tickcontrol-forge-1.20.1-1.0.0.jar` |

**已核验的加载器版本(务必让 build 的 `loaderVersionRange` 覆盖它们)**:

| 实例 | Forge 库坐标 |
| --- | --- |
| 1.12.2 | `1.12.2-14.23.5.2864` |
| 1.16.5 | `1.16.5-36.2.42` |

注意两处不一致,构建时要用**实例的**版本为准:

- 1.16.5 实例是 **36.2.42**,而 `forge-1.16.5/gradle.properties` 写的是 **36.2.34**
  (范围内,构建与部署均已通过;若要排查问题先用 36.2.42 复现)。
- 1.12.2 实例是 **14.23.5.2864**(最新),而调研给的"recommended"是 `14.23.5.2859`。
  **以实例的 2864 为准。**

### 1.7.10 实例(缺口已闭合)

`E:\.minecraft\versions\1.7.10-Forge_10.13.4.1614` **现已存在**,Forge
**`10.13.4.1614`** 已经装好(目录里有 `mods`、`PCL`、`resourcepacks`,
以及 `1.7.10-Forge_10.13.4.1614.jar` 和同名 `.json`)。上一版记录的
"没有任何 1.7.10 实例"已经不成立,做 1.7.10 时不必再问用户要不要新建实例。

`E:\.minecraft\versions` 下的完整清单:`mod测试专用`、`20260615`、
`1.7.10-Forge_10.13.4.1614`、`1.12.2-Forge_14.23.5.2864`、`1.16.5-Forge_36.2.42`、
`1.17.1-Forge_37.1.1`、`1.18.2-Forge_40.3.12`、`1.19.2-Forge_43.5.1`、
`1.20.1-Forge_47.4.9`、`1.20.1-Forge_47.4.26`、`1.21.1-NeoForge_21.1.229`。

另注:1.17.1 等实例只有 `mods`、`PCL`、`resourcepacks` 和 `versions/<v>/<v>.json`,
**没有 Forge 库引用**,说明它们继承自全局 `libraries/`。这不影响放 jar,但意味着
**这些实例目前可能还没被 PCL 成功启动过**——第一次测试前建议先在 PCL 里确认实例
能正常进游戏,再放 mod,避免把"实例装错了"误判成"mod 崩了"。

## 十、当前 git 状态

仓库在 `TickControl/.git`(上一位会话新建的,原本没有版本控制)。**建议每个可工作的阶段都提交一次**
——上一位会话靠这个才敢大改。`git` 不在 PATH,用 `C:\Users\Tamamo\Desktop\Mod\.tools\git\cmd\git.exe`。

## 十一、写给下一位的话

**本次会话产出了两个可用的 jar**:1.17.1 与 1.16.5 均构建成功,并已部署到用户实例
(命令与产物核验结果见第五节)。上一版"1.17.1 被 ForgeGradle 硬阻塞、无解"的结论
**已被推翻**——真正的原因只是这台机器缺 JDK 16。

已确认的事实值得你信任、不必重验:

1. 1.17.1 的 `MCPRepo.findSrg → ExecuteFunction.execute → launcherFor →
   ProjectScopeServices has been closed` 失败链出现在 **Gradle 8.4 + JDK 21** 组合下;
   改用 MDK 指定的 **JDK 16** 后它不再出现,构建一次通过。
2. 1.18.2/1.19.2/1.20.1 不报错**只是因为它们的 MCP 产物早就在缓存里**,不是更兼容;
3. 1.17.1 是**毫秒制**(没有 `nextTickTimeNanos`),可复用毫秒限速模型;
4. `.tools/_dl/mc1171` 里有完整的 1.17.1 client/server jar、官方映射、userdev、mcp_config、
   mixin 0.8.4/0.8.5 —— 说明这台机器上**曾经完整解出过 1.17.1**,路是通的;
5. 1.16.5 的 mixin 名**绝对不能从 1.19.2 抄**——`reobfJar` 不重写注解里的字符串,
   必须对着映射后的 dev jar 和 `-srg.jar` 双向核验(见第五节 5.2)。

**四个目标版本(1.17.1 / 1.16.5 / 1.12.2 / 1.7.10)均已完成构建并产出可安装 jar**,
各自部署到对应测试实例。剩余的是**运行验证**:1.17.1 与 1.16.5 已由用户确认可用,
**1.12.2 与 1.7.10 待测**(见第三节的状态表)。

**接手后最该先读的两处**:
1. `docs/verified-facts.md` **第 7 节** —— 四个"整类失败模式",每个都真实发生过且构建全绿;
2. 本文件**第三节**的验证工具表 —— 改完代码跑一遍,比重新踩坑快得多。

不要因为"jar 已经构建出来"就认为工作结束:本项目多次出现**能构建、能加载、
但功能静默失效**的情况,而那类问题只有靠核验脚本 + 用户实测才能发现。
