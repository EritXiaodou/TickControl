# TickControl 多版本移植 —— 已核验事实清单

本文件只记录**有证据**的结论(来源:本地实测日志 / 已抓取的 maven 产物 / 官方映射转储)。
凡未经核验的一律标注 `UNVERIFIED`。

---

## 0. 本机工具链矩阵(实测)

| 用途 | JDK | Gradle | 备注 |
| --- | --- | --- | --- |
| ForgeGradle 5(1.16.5) | **JDK 17**(`C:\Program Files\Zulu\zulu-17`) | **7.6.4** | JDK 21 + Gradle 7.6.4 **不行**:Groovy 编译构建脚本时 `Unsupported class file major version 65`,只要构建脚本有改动就必崩。**实测构建成功,jar 已产出** |
| ForgeGradle 6(1.17.1) | **JDK 16**(`C:\Program Files\Java\jdk-16.0.2-7`) | 8.4 | 官方 1.17.x MDK 本身就把 toolchain 钉在 Java 16。**实测构建成功,jar 已产出** |
| ForgeGradle 6(1.18.2/1.19.2/1.20.1) | JDK 21(Adoptium) | 8.4 | FG6 要求 Gradle ≥8.1 |
| 1.12.2(待定) | JDK 8(Zulu) | 见下 | Gradle ≤6.x 无法在 JDK 21 上运行 |
| 1.7.10(待定) | JDK 8(Zulu) | RFG 用 9.5 / GTNH 用 9.7.1 | 不用经典 FG1.2 |

可用 JDK(路径均为实测存在):
`Zulu 8`(`C:\Program Files\Zulu\zulu-8`,另有 `.tools\jdk8`)、
**`Temurin 16`(`C:\Program Files\Java\jdk-16.0.2-7`,16.0.2+7)**、
`Zulu 17`(`C:\Program Files\Zulu\zulu-17`)、
**`Temurin 11`(`C:\Program Files\Java\jdk-11.0.32.1-1`,11.0.32.1+1)**、
`Adoptium 21`(`C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`)、
`Adoptium 25`(`C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot`)、`GraalVM 25`。

**16 与 11 是本次新装的**,经由一个提权的 robocopy 助手装入(`C:\Program Files\Java`
的写入需要管理员权限,普通会话装不进去)。
`.gradle-home\jdks` 里 foojay 下过 `eclipse_adoptium-17-amd64-windows.2`(**zipfs 有缺陷**,见 README 第 1 条)。

---

## 1. 编码陷阱(本次踩过,务必记住)

**判据只看码点,不看控制台。** 本机 `[Console]::OutputEncoding` 是 GBK,而 pwsh 又把输出转成 UTF-8,
于是**任何中文在控制台都会显示成 `��`**——这是显示假象,文件可能是好的。

正确的判据(用 `.tools-probe/_probe_encoding.py` 的码点法):
- 文件能按 UTF-8 解码 → 再看码点是否有 **U+3125(注音符号)、U+E1E7(私用区)** 这类"二次编码特征";
- 有 → 真损坏;没有且是正常汉字码点(如 U+7684=的)→ **文件是好的**。

实测结论:
- `common-1.16.5/.../ServerLoop.java` → **真损坏**(含 U+3125 / U+E1E7)。该文件已随
  1.16.5 的重写被替换,现在能按 UTF-8 正常解码。
- 其余 **93 个文件全部干净**(`scan-mojibake.py` 用"常用字占比"判据:正常文件 58~65%,损坏文件 9%)。
- `forge-1.17.1/gradle.properties` 等经 PowerShell 改过的文件 → **也是好的**,首字符 U+7684。

> 曾两次误判:因为"第一个非 ASCII 字节附近的片段"恰好落在英文注释前,看起来正常。

---

## 2. 1.17.1(已核验,**构建已打通**)

### 2.1 ForgeGradle 版本(原结论已修正)

> ⚠️ 原先"**必须用 ForgeGradle 5.1.+、不能用 FG6**"的结论**已被推翻**。
> 实际构建成功的是 **FG6(`[6.0.24,6.2)`)+ Gradle 8.4 + JDK 16**;此前 FG6 的失败
> 是**缺 JDK 16**造成的(见 2.6.1),不是 FG6 本身不可用。

- 官方 1.17.x MDK:`classpath 'net.minecraftforge.gradle:ForgeGradle:5.1.+'`,Gradle wrapper **7.5.1**。
- FG6 在本机失败:`MinecraftUserRepo.findBinpatched` → `DefaultJavaToolchainService.launcherFor`
  → `ObjectInstantiationException: Could not create an instance of type DefaultToolchainSpec`
  → `ProjectScopeServices has been closed`,最终表现为
  `Could not find net.minecraftforge:forge:1.17.1-37.1.1_mapped_official_1.17.1`。
- **删掉 `java { toolchain {} }` 块不能解决**——`launcherFor` 是 ForgeGradle 自己懒调用的。

### 2.2 1.17.1 **不是纳秒制**(修正重要误判)
- 1.17.1 **没有** `nextTickTimeNanos`、**没有** `tickTimesNanos`(整份官方映射零命中)。
- 字段是 `long nextTickTime`(SRG `f_129726_`),常量 `int MS_PER_TICK` = **毫秒制**。
- 证据:Forge 补丁里 `m_130011_()` 含 `this.f_129726_ = Util.m_137550_();`,而 `m_137550_` = `Util.getMillis()`。
- ⇒ 1.17.1 的限速逻辑可以**复用 1.16.5/1.12.2 的毫秒模型**,不要写纳秒。

### 2.3 字节码与 Mixin 上限
- Forge 1.17.1-37.1.1 自带 **Mixin 0.8.4**(不是 0.8.5)、**ASM 9.1**。
- ASM 9.1 最高只认 class file **60(Java 16)**。
- 官方 MDK 用 `JavaLanguageVersion.of(16)`。
- ⇒ 必须 `options.release = 16`,`mixins.json` 的 `compatibilityLevel` **不能是 JAVA_17**。
  本机**原先没有 JDK 16**,只能退而用 JDK 21 + `--release 16` 产出 class file 60;
  现已装上 JDK 16(`C:\Program Files\Java\jdk-16.0.2-7`)并直接用它 toolchain 构建成功,
  见 2.6.1。

### 2.4 mixin 配置注册:只走 manifest
`common-1.17.1/resources/META-INF/mods.toml` 里的 **`[[mixins]]` 段在 1.17.1 上无效**(那是 1.20.5+ 的写法),
必须删掉;只保留 jar manifest 的 `MixinConfigs`(已存在)。

### 2.5 已核验的 1.17.1 SRG 名(双源:1.17.1 joined.tsrg × Mojang 官方映射)

| 成员 | 官方名 | SRG |
| --- | --- | --- |
| 主循环 | `runServer()` | `m_130011_` |
| 单刻 | `tickServer(BooleanSupplier)` | `m_5705_` |
| 世界循环 | `tickChildren(BooleanSupplier)` | `m_5703_` |
| 等待 | `waitUntilNextTick()` | `m_130012_` |
| 时间 | `haveTime()` | `m_129960_` |
| 初始化 | `initServer()` | `m_7038_` |
| 停止 | `stopServer()` | `m_7041_` |
| 字段 | `running` | `f_129764_` |
| 字段 | `nextTickTime` | `f_129726_` |
| 字段 | `tickTimes` | `f_129748_` |
| 字段 | `averageTickTime` | `f_129737_` |
| 字段 | `isReady` | `f_129717_` |
| 世界推进 | `ServerLevel.tick(BooleanSupplier)` | `m_8793_` |

⇒ 现有 `MinecraftServerMixinSrg.java` 的四个名字**全部正确**,1.17.1 的问题纯粹是构建工具链。

### 2.6 曾经判定的"硬阻塞":ForgeGradle 无法为 1.17.1 产出 mapped 产物

> ⚠️ **本节结论已被 2.6.1 推翻**(真正的根因只是缺 JDK 16,装上即通)。
> 以下内容仅作为排查历史保留,不需要照着做。

**症状(会误导人)**:
```
Could not find net.minecraftforge:forge:1.17.1-37.1.1_mapped_official_1.17.1
```

**真实调用链**(已用 `--info` 完整抓取):
```
MCPRepo.findSrg (MCPRepo.java:300)
  → MCPRuntime.execute → Step.execute (MCPRuntime.java:180)
  → ExecuteFunction.execute (ExecuteFunction.java:119)
  → DefaultJavaToolchainService.launcherFor (line 69)
  → configureToolchainSpec (line 92) → DefaultObjectFactory.newInstance
  → Caused by: IllegalStateException: ProjectScopeServices has been closed
```
另一条包装路径:`MinecraftUserRepo.findBinpatched` (line 767) → `BaseRepo$Builder` (line 120)。

**根因**:`launcherFor` 在**artifact 提供者内部**被懒调用,那一刻 Gradle 已经关闭了
project-scoped service registry,于是连 `DefaultToolchainSpec` 都 new 不出来。

**为什么 1.18.2 / 1.19.2 / 1.20.1 不报这个错**(关键,别误以为它们"更兼容"):
它们的 `mcp_repo/de/oceanlabs/mcp/mcp_config/<ver>/joined/` 产物**早已落盘**
(当年的首次成功构建留下的),binpatch/join 步骤被缓存命中直接跳过;
1.17.1 的 `joined` 目录从来没生成过,所以**它是本仓库第一个真正执行该步骤的版本**。

**已排除的修法(全部实测失败)**:
| 尝试 | 结果 |
| --- | --- |
| 删掉 `java { toolchain {} }` 块 | 无效,`launcherFor` 是 ForgeGradle 自己调的 |
| 换 ForgeGradle 5.1.+ | 同样失败,只是失败点换成 `:runtimeClasspathCopy` |
| 换 ForgeGradle 6.0.54 + Gradle 8.4 | 失败 |
| Gradle 7.6.4 | FG6 直接拒绝(<8.1) |
| `org.gradle.java.installations.paths=...` 显式登记 JDK | 无效 |
| `org.gradle.java.installations.auto-download=false` | 无效 |
| `gradle.taskGraph.whenReady { configurations.compileClasspath.resolve() }` | **无效**:`whenReady` 触发于 `finalizeWorkGraph`,此时 project scope 已经关闭 |
| `--stop` 清掉 37 个陈旧 daemon + `--no-daemon` | 无效(daemon 不是原因) |
| Gradle 8.1.1 + JDK 21 | **Gradle 起不来**:`BUG! ... Unsupported class file major version 65`(8.1.1 的 Groovy ASM 不认 JDK 21 class;Gradle 8.5 才支持 JDK 21) |
| Gradle 8.1.1 + JDK 17 | **越过了 `ProjectScopeServices`**,推进到真正的 rename 步骤,然后崩在 `org.cadixdev.atlas.jar.JarFile` → `jdk.zipfs.ZipFileSystem.checkWritable` → `ReadOnlyFileSystemException`,即 README 第 1 条记录的 **JDK 17 zipfs 把空 zip 判为只读**(`VignetteMain` 往 zip 里建目录时触发) |

**结论:Gradle 8.1.x 路线已穷尽。**(⇒ 已由 2.6.1 取代:真正要的是 JDK 16,
不是 Gradle 8.1.x。)它要求 JDK ≤20,而 JDK 17 又会撞 zipfs 只读;
JDK 21 能避开 zipfs 却又跑不了 Gradle 8.1.1。两个前提互相排斥。

**尚未尝试的可行方向**(⇒ 均**已不需要**,JDK 16 方案已解决):
1. 预置 `mcp_repo/.../1.17.1-20210706.113038/joined/` 下的产物(需要先搞清 `join` 步骤的确切输出结构);
2. 只对 `MinecraftUserRepo` / `ExecuteFunction` **补一个 toolchain**(即给 ForgeGradle 打补丁),
   让它在 configuration 阶段就把 `JavaToolchainService` 抓成字段;
3. ~~改用 **Gradle 8.1.x**(FG6 的下限,唯一没试过的版本)~~ —— **已试,已否决**,见上表
   (JDK 21 上起不来,JDK 17 上崩在 zipfs 只读);
4. 彻底不依赖 FG 的 mapped 产物:直接用 `.tools/_dl/mc1171` 里**已有的**
   `forge-1.17.1-37.1.1-userdev.jar` + `client-1.17.1.txt`(官方映射)+ `mixin-0.8.4.jar`
   自己拼 classpath 编译,再自行 reobf。

**有益旁证**:`.tools/_dl/mc1171` 里已存在 `client-1.17.1.jar`、`server-1.17.1.jar`、
`client-1.17.1.txt`(6.4 MB 官方映射)、`mcp_config-1.17.1.zip`、`forge-1.17.1-37.1.1-userdev.jar`,
以及 `mixin-0.8.4.jar` / `mixin-0.8.5.jar`,说明**这台机器上曾经完整解出过 1.17.1**。

### 2.6.1 ✅ 解除:根因就是缺 JDK 16(**本节结论已被推翻,以本条为准**)

**上面那一整串 `ProjectScopeServices has been closed` 并不是 ForgeGradle 的固有缺陷,
而是这台机器没有 JDK 16。** 1.17.1 是唯一一个官方 MDK 把 toolchain 钉在 Java 16 的目标
(见 2.3),缺了它,ForgeGradle 只能在 JDK 21/17 之间被迫选择,于是分别撞上
`launcherFor` 关闭时序和 zipfs 只读。

**解决办法**:装 **Temurin 16.0.2+7** 到 `C:\Program Files\Java\jdk-16.0.2-7`,
用 Gradle **8.4** 以 `JAVA_HOME` 指向它构建:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-16.0.2-7'
& C:\Users\Tamamo\Desktop\Mod\.tools\gradle-8.4\bin\gradle.bat `
  --project-dir C:\Users\Tamamo\Desktop\Mod\TickControl\forge-1.17.1 `
  --gradle-user-home C:\Users\Tamamo\Desktop\Mod\TickControl\.gradle-home `
  build --console=plain
```

**结果**:构建**一次通过**,`forge-1.17.1-37.1.1_mapped_official_1.17.1.jar` 正常产出,
`build/libs/tickcontrol-forge-1.17.1-1.0.0.jar` 已生成并部署到
`E:\.minecraft\versions\1.17.1-Forge_37.1.1\mods\`。

> 因此本节的"硬阻塞""Gradle 8.1.x 路线已穷尽""尚未尝试的可行方向 1/2/4"
> **都只作为历史记录保留**,不再需要执行。1.17.1 的工具链问题已经结束。


---

## 3. 1.16.5(实测,已核验,**构建已打通**)

### 3.1 工具链与可复现构建命令
- ForgeGradle **5.1.+** 配 Gradle **7.6.4** + **JDK 17**(`C:\Program Files\Zulu\zulu-17`)。
  **FG5 拒绝 Gradle 8.x**,原话:`Versions Gradle 8.0 and newer are not supported`。
  (另一条已知坑:JDK 21 配 Gradle 7.6.4 会因为 Groovy 不认 class-file 65 而崩。)
- 有效配置(`forge-1.16.5/gradle.properties`):`mapping_channel=snapshot`、
  `mapping_version=20210309-1.16.5`、`forge_version=1.16.5-36.2.34`
  (用户实例是 **36.2.42**,在范围内,构建与部署均已通过)。
- 产物:`build/libs/tickcontrol-forge-1.16.5-1.0.0.jar` —— 37 个条目、manifest 带
  `MixinConfigs`、`ServerLoop.class` 是 class-file **52**(Java 8);已部署到
  `E:\.minecraft\versions\1.16.5-Forge_36.2.42\mods\tickcontrol-forge-1.16.5-1.0.0.jar`。

### 3.2 ⚠️ 核心冲突:snapshot(MCP)名 vs 官方名,**两者不可混用**
`common-1.16.5` 之前配的是 **snapshot 映射(MCP 名)**,而从 `common-1.19.2` 移植下来的
代码写的是**官方名**。按 snapshot 域改写时,以下成员名**必须**跟着换:

| 1.19.2 官方名 | 1.16.5 snapshot/MCP 域 |
| --- | --- |
| `net.minecraft.commands.CommandSourceStack` | `net.minecraft.command.CommandSource` |
| `net.minecraft.network.chat.Component` | `net.minecraft.util.text.ITextComponent` |
| `net.minecraft.server.level.ServerLevel` | `net.minecraft.world.server.ServerWorld` |
| `net.minecraft.util.profiling.ProfilerFiller` | `net.minecraft.profiler.IProfiler` |
| `Util.getMillis()` / `getNanos()` | `Util.milliTime()` / `nanoTime()` |
| `Language.getInstance()` | `net.minecraft.util.text.LanguageMap` |
| `sendSuccess` / `sendFailure` | `CommandSource.sendFeedback` / `sendErrorMessage` |
| `hasPermission(int)` | `CommandSource.hasPermissionLevel(int)` |

**1.16.5 snapshot 域里不存在**:`MinecraftServer.tickServer`、`onServerExit`、
`getServerDirectory`、`createCommandSourceStack`;`TimeArgument` **只有无参构造**
(没有静态 `time()`)。

**签名/可见性与 1.19.2 不同的**:`MinecraftServer.tick(BooleanSupplier)` 是 **protected**;
`MinecraftServer.driveOne()`(**不是** `pollTask()`);`MinecraftServer.stopServer()` 是 **protected**;
`IProfiler.startSection` / `endStartSection` / `endSection`(**不是** push/pop/popPush);
`net.minecraft.crash.CrashReport.saveToFile(File)`。

### 3.3 ❌ 官方映射路线:试过,**被否决(原因很具体)**
把映射换成官方名也走过一遍,失败点是 ForgeGradle 5 的 MCP 流程会因为 Gradle 选中
**Java 8** toolchain(`temurin-8-amd64-windows\jdk8u504-b01`)而崩在
`java.lang.IllegalArgumentException: invalid flag: --release`。
⇒ **1.16.5 走 snapshot 映射这条线,不要再试 official。**

### 3.4 Java 8 源码级别带来的额外改动(不只是改 API 名)
在 `options.release = 8` 之下,1.19.2 代码里这些写法直接编译不过:
- `Map.of(...)`(Java 9)→ 静态初始化块 + `HashMap`;
- `InputStream.readAllBytes()`(Java 9)→ 手写读取循环;
- Java 16 的 `instanceof` 模式匹配 → 传统 `instanceof` + 强转。

### 3.5 ⚠️⚠️ mixin 目标名必须**双域各自核验**,不能从 1.19.2 抄
这是本项目踩过的最严重的 bug(表现是 mixin 完全不注入)。**根因:`reobfJar`
不会重写 mixin 注解里的方法名字符串**,抄错就是静默失效。

对着**映射后的 dev jar** 和真实的 `forge-1.16.5-36.2.34-srg.jar` **双向核验**:
1.16.5 在 MCP 域**没有** `runServer`/`tickServer`,在 SRG 域**没有**
`m_130011_`/`m_5705_`/`m_5703_`/`m_8793_`。正确值:

| 位置 | Dev(MCP/snapshot)名 | 生产(SRG)名 |
| --- | --- | --- |
| `MinecraftServer` 主循环 | `func_240802_v_` | `func_240802_v_`(**两个域同名**) |
| `MinecraftServer` 单刻 | `tick` | `func_71217_p` |
| `MinecraftServer` 世界循环 | `updateTimeLightAndEntities` | `func_71190_q` |
| `ServerWorld#tick` | `tick` | `func_72835_b` |

> 注意 SRG 风格:`m_xxxxx_` 是 **1.17+** 才有的,1.16.5 的 SRG 名一律是 `func_xxxxx_`。
> 从 1.19.2/1.20.1 抄名字到 1.16.5,**必然全错**。

---

## 4. 1.12.2(调研结论,已核验)

### 4.1 工具链
- **FG5/FG6 无法用于 1.12.2**:`forge-1.12.2-*-userdev.jar` = 404(该布局从未发布)。
- 官方 1.12.2 MDK 用 `ForgeGradle:3.+`,wrapper **gradle-4.9**。
- `ForgeGradle:2.3.10` 由 **Gradle 9.0.0** 构建、Java 8 variant、无 plugin-api-version 门禁,
  FG_2.3 分支 **2025/2026 仍有提交** → 有可能直接在 Gradle 7.6.4/8.x 上跑,`UNVERIFIED`(值得先花 5 分钟试)。
- 经典 FG1.2 需要 **Gradle 2.x/4.x + JDK 8**;且 `de.oceanlabs.mcp:mcp_stable:12` 已被改名为 **`12-1.7.10`**。
- 推荐 Forge 版本:1.12.2-recommended = `14.23.5.2859`(最新 `14.23.5.2864`)。
- 映射:`mappings channel: 'stable', version: '39-1.12'`(= `mcp_stable:39-1.12`,200)。

### 4.2 **1.12.2 没有 Brigadier**(已确认)
整份 1.12.2 joined.tsrg 中 `brigadier` 零命中。命令必须按 legacy `ICommand` / `CommandBase` 重写,
注册走 Forge 的 `FMLServerStartingEvent#registerServerCommand`。

### 4.3 1.12.2 的 `run()` 结构(可直接照抄,来自 forged-carpet 生产 mixin)
```java
long i = 0L;                                  // 毫秒累加器,是<b>局部变量</b>
while (this.serverRunning) {
    long k = getCurrentTimeMillis();
    long j = k - this.currentTime;
    if (j > 2000L && this.currentTime - this.timeOfLastWarning >= 15000L) {
        LOGGER.warn("Can't keep up! ... Running {}ms behind, skipping {} tick(s)", j, j / 50L);
        j = 2000L; this.timeOfLastWarning = this.currentTime;
    }
    if (j < 0L) { LOGGER.warn("Time ran backwards! ..."); j = 0L; }
    i += j;
    this.currentTime = k;
    if (this.worlds[0].areAllPlayersAsleep()) { this.tick(); i = 0L; }
    else { while (i > 50L) { i -= 50L; this.tick(); } }
    Thread.sleep(Math.max(1L, 50L - i));
    this.serverIsRunning = true;
}
```
- `run()V` 里 `50L` 恰好出现两次 ⇒ TickrateChanger 用 ASM 把所有 `LDC 50L` 换成
  `GETSTATIC TickrateChanger.MILISECONDS_PER_TICK` 就能改变速率。
- **不存在的成员**(我原先的假设有误):`serverTime`、`runTasksUntil`、`isRunningScheduledTasks`、`getWorlds()`。
  对应物是 `currentTime`(`field_175591_ab`)、`timeOfLastWarning`(`field_71299_R`)、`worlds`(`field_71305_c`,`WorldServer[]`)。
- 世界类叫 **`WorldServer`**(不是 `ServerWorld`)。

### 4.4 1.12.2 hook 策略
推荐 **`@Redirect` 掉 `serverRunning` 让原版 while 不执行**,再 `@Inject` 到 `run()` 里
`applyServerIconToResponse` 调用之后,自己重写整个循环。
更省事的替代(若只需改周期):**纯 ASM coremod**,只替换 `run()V` 里的 `50L` 常量。

### 4.5 1.12.2 Mixin
- 用 **`org.spongepowered:mixin:0.8.2`**(forged-carpet 实际在用的版本),
  仓库 `https://repo.spongepowered.org/repository/maven-public/`。
- 注册方式与他版**不同**:不是 manifest 的 `MixinConfigs`,而是
  `IFMLLoadingPlugin` 构造器里 `MixinBootstrap.init(); Mixins.addConfiguration("mixins.xxx.json");`
  + manifest 的 `FMLCorePlugin` / `FMLCorePluginContainsFMLMod` / `ForceLoadAsMod`
  / `TweakClass: org.spongepowered.asm.launch.MixinTweaker`。
- 生产环境**需要 refmap**;MixinGradle `0.6-SNAPSHOT`(FG2.3 线)。
- `TickCommand` 这个类名在 1.12.2 上**没有冲突**(1.12.2 不存在 `TickCommand`)。
- 真陷阱:stable_39 里 **141 组仅大小写不同的成员名**,写错大小写会**静默不注入**。

---

## 5. 1.7.10(调研结论,已核验)

### 5.1 工具链:用 RetroFuturaGradle,不要用经典 FG1.2
| 方案 | 插件 | Gradle | 维护状态 |
| --- | --- | --- | --- |
| RFG 2.0.2 | `com.gtnewhorizons.retrofuturagradle` | 9.5.0 | 2025-11-30 |
| GTNH ExampleMod | `com.gtnewhorizons.gtnhconvention` 2.0.33 | 9.7.1 | 2026-09-29 |

经典路径的坑:`mcp_stable:12` → `12-1.7.10`;需要 Gradle 2.x/4.x + JDK 8。
`forge-1.7.10-10.13.4.1614-1.7.10-src.zip` 仍可下载(209,316 B)。
Mixin 由 **UniMixins**(`io.github.legacymoddingmc:unimixins`)提供;1.7.10 上 manifest 的 `MixinConfigs` **可用**。
测试实例 **`E:\.minecraft\versions\1.7.10-Forge_10.13.4.1614` 已存在**,
Forge **`10.13.4.1614`** 已装好,`mods\` 目前为空。

### 5.2 1.7.10 **没有 Brigadier**(已确认,零命中)
用 legacy `ICommand`:`getCommandName`/`func_71517_b`、`getCommandUsage`/`func_71518_a`、
`getCommandAliases`/`func_71514_a`、`processCommand`/`func_71515_b`、
`canCommandSenderUseCommand`/`func_71519_b`、`addTabCompletionOptions`/`func_71516_a`、
`isUsernameIndex`/`func_82358_a`。

### 5.3 1.7.10 的 `run()` 与 `tick()` **已经是分开的两个方法**
`run()` 持有 while 循环;`tick()` = `func_71217_p ()V`。`methods.csv` 原文:
`func_71217_p,tick,2,Main function called by run() every loop.`
其余:`worldServers`/`field_71305_c`、`tickTimeArray`/`field_71311_j`、
`serverIsRunning`/`field_71296_Q`、`serverRunning`/`field_71317_u`、
`getCommandManager`/`func_71187_D`、`WorldServer.tick`/`func_72835_b`。
- 生产跑 **SRG** 名,dev 跑 **MCP** 名 ⇒ coremod 变形器必须同时匹配两者(`transform(name, name2, bytes)`)。
- 参考实现:TickrateChanger 分支 **`1.7`**(不是 `1.7.10`),它 patch `run()V` 里的 `50L`。

---

## 6. 参考模组实况

| 模组 | 分支 | 机制 |
| --- | --- | --- |
| `Guichaguri/TickrateChanger` | `1.7` / `1.12`(无 `master`) | **ASM coremod**,替换 `run()V` 里的 `50L` 常量;命令用 legacy `CommandBase` |
| `DeadlyMC/forged-carpet` | 仅 `master`,**就是 1.12.2** | Mixin 0.8.2 + MixinGradle 0.6 + Gradle 4.10,`@Redirect` 掉 `serverRunning` 后整个重写 `run()` |

**结论:1.12.2 抄 forged-carpet(同版本、同功能、可 diff),1.7.10 抄 TickrateChanger 的 `1.7` 分支。**

---

## 7. ⚠️⚠️ 四个"整类失败模式"——全部在实测中真实发生过

本节是这份文档里最该先读的部分。下面每一条都**不是理论风险**,而是已经让用户看到过
"卡死 / 崩溃 / 功能无反应 / 显示原始翻译键"的具体缺陷。它们的共同点是:
**构建全绿、日志安静、代码看起来完全正确。**

### 7.1 反射:**名字对**不等于**能查到**——还要签名对

`getDeclaredMethod(name, params...)` 同时匹配名字**和参数类型**。1.16.5 上
`func_240787_a_` 的真实签名是 `(IChunkStatusListener)V`,而我们从 1.19.2 基线抄来的
调用是 `method("func_240787_a_")`(**不传参数**),于是**永远查不到**。

| 版本 | 现象 | 根因 |
| --- | --- | --- |
| 1.16.5 | 进存档直接 NPE 崩溃 | 查到 `null` 后直接 `invoke` |
| 1.16.5 | 主循环最内层每刻抛 `NoSuchMethodException` | `tickServerReflect` 写死 `"tick"`,而 SRG 运行时**没有**这个名字 |

**规则:**
1. **每个新增的反射查找都要对着「运行时 jar」核对名字 + 签名**,可用
   `.tools-probe/audit-lookups-1165.py`(它会报签名不匹配)。
2. **反射失败一律降级,不许抛异常**。一个名字对不上只能损失一个功能,
   绝不能变成"进不去存档"。`.tools-probe/preflight.py` 会检查这条没有回退。

### 7.2 日志:**兜底路径自己的依赖也必须存在**

给 1.16.5 的反射加"失败就记日志"时,日志实现回退到了
`org.slf4j.LoggerFactory`。而 **1.16.5 的运行时类路径上根本没有 slf4j**
(libraries 里只有 log4j-api,一个 slf4j jar 都没有),于是那个"防崩"的调用自己抛
`NoClassDefFoundError`,把进存档卡死在 100%。

**规则:兜底代码也是生产代码,它的依赖要单独验证。** 1.7.10 / 1.12.2 / 1.16.5 上
用 **Log4j(`LogManager`)**;`org.slf4j` 在 1.16.5 上不可用。

### 7.3 Mixin:自己的包不能被 `TransformerExclusions` 排除

`TickControlCore` 曾经写 `@IFMLLoadingPlugin.TransformerExclusions({"com.tamamo.tickcontrol"})`,
**把 mixin 包一起排除了**,于是 Mixin 拿不到自己的注入类,启动日志出现:

```
[mixin]: Classloader restrictions [PACKAGE_TRANSFORMER_EXCLUSION] encountered
         loading tickcontrol.mixins.json:MinecraftServerMixinSrg
```

**结果:所有 Mixin 注入静默失效。** 指纹很好认——**一半功能能用、一半不能**:
`/tick rate` 与 `/tick sprint` 走 `ServerLoop` 主循环不受影响,而 `/tick freeze`
只依赖世界门控的 `@Redirect`,于是完全无反应。

**规则:这个注解要么不写,要么只写真正需要防自举递归的 `.core` 包**
(本项目只有 1.7.10 需要,因为它确实有自己的 `IClassTransformer`)。

### 7.4 消息:1.12.2 的 `notifyCommandListener` 在单人下**什么都不做**

命令反馈走了 `CommandBase.notifyCommandListener`,而它的实现是:

```java
if (commandListener != null) commandListener.notifyListener(...);
```

`commandListener` 是**静态字段,只在专用服务器上由命令方块日志器安装**。
单人下恒为 `null` → **消息根本没发出去**。玩家在聊天栏看到的是服务端回显里那条
未经翻译的键名,于是"显示原始翻译键"这个现象看起来像语言文件问题,**实际是投递问题**。

另外 1.12.2 **没有**接受 `boolean` 的 `notifyCommandListener` 重载(那是 1.13+);
4 参重载内部传的是 `useTranslation=false`。

**规则:**
1. 1.12.2 一律 `source.sendMessage(new TextComponentTranslation(key, args))`。
2. 报告类消息(如冲刺报告)**不是命令的即时回执**,它在事件结束后由主循环发出,
   那时 `sender` 已不在作用域 —— **必须在命令执行时把发起者记下来**
   (1.12.2 存 `ICommandSender`,1.7.10 存玩家名以免单例命令持有离线玩家引用)。
   1.16.5 用 `func_195573_aM`/`getCommandSource`,**不是** 1.17+ 的
   `createCommandSourceStack`、也不是 1.19+ 的 `createCommandSource`。

### 7.5 附:已实现但**从未接线**的功能

1.7.10 的冲刺报告机制(`consumeSprintReport` / `finishedSprintTps` /
`finishedSprintMspt`)整套都在,但 `consumeSprintReport()` **零调用** ——
报告算了然后被丢弃,且没有任何日志。

**规则:新增能力时,除了检查"符号存在",还要检查"有人调用"。**
可用 `.tools-probe/audit-wiring.py`(它专门抓"只声明未调用")。
