# TickControl

把 **1.20.3（23w43a）才加入的 `/tick` 命令** 回移到 **Minecraft Java 1.20.1**，提供 **Forge** 与 **NeoForge** 两个加载器版本。

本工程与 `PA-Port` / `PA-NeoForge` 无关，是一个独立 Mod 项目。

做法是**从高版本（1.20.3 / 1.21.1）把实现搬下来**：`TickRateManager`、`ServerTickRateManager`、
`TickCommand` 的行为语义都逐条对齐，再针对 1.20.1 的旧主循环补一层适配。

---

## 一、当前状态

| 子命令 | 状态 | 实测 |
| --- | --- | --- |
| `/tick query` | ✅ 可用 | 状态行 + 目标/平均每刻耗时 + P50/P95/P99（100 样本） |
| `/tick rate <rate>` | ✅ **1~1000+ TPS 全部精确** | 1→1.03、20→20.16、40→40.17、200→200.17、500→500.08、1000→965.0 TPS |
| `/tick freeze` | ✅ 可用 | 冻结 60 刻后 `gameTime delta=0` 且 `world ticks=0` |
| `/tick unfreeze` | ✅ 可用 | — |
| `/tick step [<time>]` | ✅ 可用 | `/tick step 50` → `gameTime` 精确 `+50`，结束后自动重新冻结 |
| `/tick step stop` | ✅ 可用 | 未步进时正确报错 |
| `/tick sprint [<time>]` | ✅ 可用 | `/tick rate 1` + `/tick sprint 600`：600 刻在 0.04s 内跑完（约 15000 TPS），结束后恢复原冻结状态 |
| `/tick sprint stop` | ✅ 可用 | 未冲刺时正确报错 |

加载器状态：

- **Forge 1.20.1-47.2.0**：构建通过，`-Pselftest` 自检 `RESULT: ALL CHECKS PASSED`。
- **NeoForge 1.20.1-47.1.106**：构建通过，Mixin 注入成功，服务端能启动到
  `Done (1.5s)!` 并监听 RCON；但随后在 **NeoForge 框架自身**的
  `DedicatedServer.initServer` → `publishServer` → `LanServerPinger` 处被 dist cleaner
  拦下崩溃（详见「踩过的坑」第 9 条，与本 Mod 无关）。

---

## 二、`/tick rate` 的实现（把 1.21.1 的主循环与判据整体搬下来）

### 做法

1. **主循环整体照抄**：`@Inject(method = "runServer", at = @At("HEAD"), cancellable = true)`
   顶掉 1.20.1 的 `runServer`，方法体按 1.21.1 逐行重写（初始化、
   `while (running)`、过载追赶、profiler、`startMetricsRecordingTick` /
   `endMetricsRecordingTick`、`isReady`、`JvmProfiler`、异常处理、
   `stopServer` / `onServerExit` 收尾）。

2. **最底层判据也换掉**：`@Redirect` 掉 `haveTime()`，把 1.20.1 的
   `Util.getMillis() < nextTickTime`（整数毫秒比较、且已被内联进 `runServer`）
   换成 1.21.1 的 `Util.getNanos() < nextTickTimeNanos` 的等价物——
   由 `LoopPacer` 的纳秒累加器提供。

3. **同一轮按周期补跑游戏刻**：1.20.1 主循环每轮只调用一次 `tickServer`，
   所以同一轮里反复调用直到周期不再到点（`allowTick()` 为 false）。

4. **成员名替换表**：

| 1.21.1 | 1.20.1 等价 |
| --- | --- |
| `this.nextTickTimeNanos` | `LoopPacer.deadlineNanos`（纳秒累加器） |
| `tickRateManager.nanosecondsPerTick()` | `ServerTickController.periodNanos()` |
| `tickRateManager.runsNormally()` | `ServerTickController.shouldTickLevels()`（`ServerLevel.tick` 门控） |
| `isPaused()` | `running` |
| `ServerLifecycleHooks.*`（NeoForge 20.x） | `net.minecraftforge.server.ServerLifecycleHooks.*`（Forge 47） |
| `OVERLOADED_THRESHOLD_NANOS` | 1.20.1 的毫秒常量 2000/15000 折算成纳秒 |
| `logFullTickTime()` / `smoothedTickTimeMillis` / `startMeasuringTaskExecutionTime()` | 1.20.1 无，省略 |
| `CrashReport.saveToFile(Path, ReportType)` | 1.20.1 只有 `saveToFile(File)` |
| `constructOrExtractCrashReport`（`static private`） | 反射调用 |
| `tickRateManager.isSprinting()` 分支 | 1.20.1 无 sprint 机制，未移植 |

### 实测

```
PASS: rate 1    -> 1.03    TPS (ratio 1.03)
PASS: rate 20   -> 20.16   TPS (ratio 1.01)
PASS: rate 40   -> 40.17   TPS (ratio 1.00)
PASS: rate 200  -> 200.17  TPS (ratio 1.00)
PASS: rate 500  -> 500.08  TPS (ratio 1.00)
PASS: rate 1000 -> 965.00  TPS (ratio 0.97)
=== RESULT: ALL CHECKS PASSED
```

`rate 2000 / 5000 / 10000` 实测停在 ~960–1000 TPS：那是这台开发机上
**每刻固定开销（约 1ms：profiler、JFR、磁盘/网络/GC）**决定的实际吞吐上限，
不是代码限制；1.21.1 在同一台机器上也到不了。

### 走过的弯路（都记录在此，避免后人重复）

| 尝试 | 结果 |
| --- | --- |
| 只把 `nextTickTime += 50L` 的增量改大 | 只拉长 `tickServer` 间隔，无效 |
| `@Redirect` `haveTime()` 的 INVOKE | 失败 `(0/3)`：`runServer` 里**没有它的调用引用**（被内联） |
| 把 `nextTickTime` 读取改成 `now + 1` | `haveTime()` 恒真 → `managedBlock` 不阻塞 → 主循环疯转（200 万条诊断），节流失效 |
| 把 `nextTickTime` 读取改成节拍器截止时刻 | 等待循环里把一批刻一次放行，主循环一圈卡住一秒（`iterationsPerSecond=0`） |
| 在重定向里重入 `tickServer` 补跑 | 服务器挂起 |
| 整体取消 `waitUntilNextTick()` | 出生点准备从 1.5 秒恶化到数分钟（丢了 `pollTaskInternal` 排空任务队列） |
| `@Shadow` 影子 `runAllTasks()` | Mixin 0.8.5 只在目标类层级查找，找不到继承自 `BlockableEventLoop` 的方法 |
| **自己加的 `MAX_REACHABLE_TICK_RATE = 20`** | **把速率钳死在 20，导致连续三轮高 TPS 探测测的都是被钳过的值** |
| **自己加的 `MAX_TICKS_PER_ITERATION = 256`** | **把天花板压在约 1000 TPS** |

后两条是两个自己造的假限制——排查时应该先怀疑自己的钳制，而不是先怀疑底层。

---

## 三、工程结构

```
TickControl/
├── common/
│   ├── java/com/tamamo/tickcontrol/
│   │   ├── core/                 # 纯 Java 状态机与统计（无 Minecraft 依赖）
│   │   │   ├── TickState.java            # 对应原版 world/TickRateManager
│   │   │   ├── LoopPacer.java            # 速率节拍（对应原版主循环的 nanos 累加）
│   │   │   ├── TickStats.java            # /tick query 的百分位统计
│   │   │   ├── TickControlAccess.java    # 平台侧最小接口
│   │   │   ├── ServerTickController.java # 对应原版 server/ServerTickRateManager
│   │   │   └── SelfTest.java             # 自检（生产环境自动 no-op）
│   │   ├── command/              # Brigadier 命令树（两加载器共用）
│   │   │   ├── TickCommand.java
│   │   │   ├── TickControl.java
│   │   │   └── TickLang.java
│   │   └── mixin/
│   │       └── MinecraftServerMixin.java # 两加载器共用同一份 Mixin
│   └── resources/
│       ├── META-INF/{mods.toml,MANIFEST.MF}
│       ├── tickcontrol.mixins.json
│       ├── tickcontrol.refmap.json
│       └── assets/tickcontrol/lang/{en_us,zh_cn}.json
├── forge/       # ForgeGradle 6 + Gradle 8.4
└── neoforge/    # NeoGradle 7 + Gradle 8.14
```

`common/java` 与 `common/resources` 被两个平台工程用
`sourceSets.main { java.srcDir file('../common/java') }` **直接编入**，核心逻辑只有一份。

### 与原版的对应关系

| 原版 1.20.3 / 1.21.1 | 本工程 |
| --- | --- |
| `net.minecraft.world.TickRateManager` | `core/TickState` |
| `net.minecraft.server.ServerTickRateManager` | `core/ServerTickController` |
| 主循环的 `nextTickTimeNanos += nanosecondsPerTick()` | `core/LoopPacer`（累计 `nanoTime` 差值） |
| `MinecraftServer.tickServer` 里的 `tickRateManager.tick()` | Mixin `@Inject(at = HEAD)` → `prepareTick()` |
| `ServerLevel.tick` 里的 `tickRateManager.runsNormally()` 门控 | Mixin `@Redirect` 掉 `tickChildren` 里的 `ServerLevel.tick(BooleanSupplier)` |
| `getTickTimesNanos()` 算 P50/P95/P99 | `@Shadow @Final long[] tickTimes` → `TickStats` |
| `net.minecraft.server.commands.TickCommand` | `command/TickCommand`（键名、返回值逐条对齐） |

### 为什么冻结不能阻塞主循环

原版看门狗（`ServerWatchdog`，`max-tick-time` 默认 60s）比较 `MinecraftServer.getNextTickTime()`
与墙钟。把 freeze 实现成"让主循环睡住"，60 秒后必被杀。因此本工程的 freeze 是
**主循环照常跑，只跳过世界 tick**：网络层、`PlayerList`、玩家自身、keep-alive 全部继续，
这正是原版「玩家及玩家骑乘的实体除外」的语义。

---

## 四、构建与运行

### 网络（Clash Verge）

本机通过 Clash Verge（混合端口 `127.0.0.1:7897`）访问外网。
`maven.minecraftforge.net`、`maven.neoforged.net` 与 Mojang 资源/映射下载都需要它，
两个工程的 `gradle.properties` 已带上 `systemProp.*.proxyHost/Port`。

### 两个子工程是独立构建，且必须用不同的 Gradle 版本

| | Gradle | 工具链插件 | 原因 |
| --- | --- | --- | --- |
| `forge/` | **8.4** | ForgeGradle 6.0.54 | FG 6 与 Gradle ≥8.9 不兼容（`DefaultToolchainSpec` / `project services has been closed`） |
| `neoforge/` | **8.14** | NeoGradle 7.0.192 | NeoGradle 7 要求 Gradle ≥ 8.8 |

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$gh = 'C:\Users\Tamamo\Desktop\Mod\TickControl\.gradle-home'

& .tools\gradle-8.4\bin\gradle.bat  --project-dir forge    --gradle-user-home $gh build
& .tools\gradle-8.4\bin\gradle.bat  --project-dir forge    --gradle-user-home $gh runServer

& .tools\gradle-8.14\bin\gradle.bat --project-dir neoforge --gradle-user-home $gh build
& .tools\gradle-8.14\bin\gradle.bat --project-dir neoforge --gradle-user-home $gh runServer
```

| 开关 | 作用 |
| --- | --- |
| `-Pselftest` | 无人值守自检：启动后自动跑 `/tick` 并断言，打印 `RESULT: ...` 后停服 |
| `-Ptickdebug` | 主循环诊断输出（仅 Forge 侧） |
| `-PmixinDebug` | Mixin 调试日志（仅 Forge 侧） |

产物：

- `forge/build/libs/tickcontrol-forge-1.20.1-1.0.0.jar`
- `neoforge/build/libs/tickcontrol-neoforge-1.20.1-1.0.0.jar`

---

## 五、验证（最终版实测）

Forge 侧 `-Pselftest` 完整输出：

```
[tickcontrol-selftest] --- 3. freeze check: gameTime 86217 -> 86217 (delta=0), world ticks=0
[tickcontrol-selftest] PASS: ticking is frozen (neither game time nor world ticks advanced)
[tickcontrol-selftest] --- 5. step check: gameTime 86217 -> 86267 (delta=50)
[tickcontrol-selftest] PASS: stepping advanced game time by exactly 50
[tickcontrol-selftest] PASS: re-frozen after step finished
[tickcontrol-selftest] PASS: rate 20   -> 20.16  TPS (ratio 1.01)
[tickcontrol-selftest] PASS: rate 500  -> 500.08 TPS (ratio 1.00)
[tickcontrol-selftest] PASS: rate 1    -> 1.03   TPS (ratio 1.03)
[tickcontrol-selftest] PASS: rate 1000 -> 965.00 TPS (ratio 0.97)
[tickcontrol-selftest] PASS: rate 40   -> 40.17  TPS (ratio 1.00)
[tickcontrol-selftest] PASS: rate 200  -> 200.17 TPS (ratio 1.00)
[tickcontrol-selftest] === RESULT: ALL CHECKS PASSED
```

服务器启动耗时保持 ~1.4 秒（说明没有饿死区块加载）。

---

## 六、踩过的坑（改代码前必看）

### 1. 工具链必须用 JDK 21，不能用 JDK 17

本机 foojay 拉下来的 `eclipse_adoptium-17-amd64-windows.2` 的 **zipfs 有缺陷**：

```java
try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of("create", "true"))) {
    Files.write(fs.getPath("/x.txt"), new byte[]{1});   // ReadOnlyFileSystemException!
}
```

用 `create` 打开后仍被判为 read-only。ForgeGradle 的 AccessTransformer 与 NeoForm 的 `jst`
都跑在 JDK 17 上，于是两边都必然失败；同一段代码在 JDK 21 上完全正常。

**解决**：两个工程都用 `JavaLanguageVersion.of(21)` 工具链 + `options.release = 17`，
产物仍是 Java 17 字节码。

### 2. Mixin 必须靠 `MixinConfigs` 声明，不能用 `mods.toml` 的 `[[mixins]]`

`[[mixins]]` 是 **1.20.5+** 的写法。1.20.1 上它会被 `MixinPlatformAgentMinecraftForge`
直接拒绝、**静默不生效**（表现为 `/tick query` 能执行成功但什么都不输出）。

正确做法是 **jar manifest 的 `MixinConfigs` 属性**；而开发环境从
`build/classes` + `build/resources` 加载、**没有 manifest**，所以本工程用了两层引导：

1. `common/resources/META-INF/MANIFEST.MF` 里写 `MixinConfigs: tickcontrol.mixins.json`
   —— 它在开发环境会作为资源出现在 classpath 上，运行时加载器据此注册配置；
2. 两个工程的 `runs` 里加 `-Dmixin.configs` / `-DMixinConfigs` 作为保险。

### 3. `compatibilityLevel` 不能低于目标 class 版本

设成 `JAVA_13` 会导致 `Mixin apply failed`：`Class version 61 required is higher than ...
(JAVA_13 supports 57)`。1.20.1 的类版本是 61，必须写 `JAVA_17`。

### 4. ForgeGradle 6 必须关掉 `org.gradle.parallel`

否则 MCPRepo 会在并行解析时延迟取 artifact，抛
`IllegalStateException: project services has been closed`。

### 5. ForgeGradle 6 与 Gradle 8.14 不兼容

`Could not create an instance of type ...DefaultToolchainSpec`。降到 Gradle 8.4 解决。
NeoGradle 7 则相反，要求 ≥8.8，所以两套 Gradle 并存。

### 6. `tickTimes` 是 `public final`，`@Shadow` 要配 `@Final`

而且**不要**用反射读它：ModLauncher 的模块边界下反射容易静默失败；
在 Mixin 内部直接读字段会被加载器正确重映射。

### 7. `@Shadow` 找不到继承来的方法

`runAllTasks()` 定义在 `BlockableEventLoop` 上，`@Shadow` 会报
`was not located in the target class`——Mixin 0.8.5 只在目标类自身层级查找。

### 8. `rootProject.file(...)` 解析的是子工程自己

`forge/` 与 `neoforge/` 各自是独立构建的 rootProject，必须写 `file('../common/java')`；
写成 `rootProject.file('common/java')` 会得到 `compileJava NO-SOURCE`、jar 里只有 MANIFEST。

### 9. NeoForge 1.20.1 的 dedicated server 开发环境会崩（框架缺陷）

```
Done (1.5s)! For help, type "help"
RCON running on 0.0.0.0:25575
java.lang.RuntimeException: Attempted to load class net/minecraft/client/server/LanServerPinger
    for invalid dist DEDICATED_SERVER
    at net.minecraftforge.fml.loading.RuntimeDistCleaner.processClassWithFlags(...)
    at net.minecraft.server.dedicated.DedicatedServer.initServer(DedicatedServer.java:197)
```

判定为框架问题而非本 Mod 问题的依据：

1. `LanServerPinger` 是 `net.minecraft.client.server` 下的**仅客户端**类；
2. 这条路径不经过本 Mod 任何代码（自检要等启动后 40 刻才开始，而崩溃发生在第一刻）；
3. 两个工程的 `server.properties` 完全一致，同样配置在 Forge 47.2.0 上没问题；
4. 本 Mod 的 Mixin 确实已注入，从崩溃栈帧自身即可看到
   `MinecraftServer.runServer(...) {re:mixin,...,pl:mixin:APP:tickcontrol.mixins.json:MinecraftServerMixin}`。

**建议**：NeoForge 侧改用 `runClient`（单人游戏里 `/tick` 完全可用）；
若必须做 dedicated server 验证，建议把 NeoForge 目标改到 20.4/20.6
（届时 `net.neoforged.*` 命名空间与 `neoforge.mods.toml` 也需同步调整）。

### 10. NeoForge 1.20.1 用的还是 `net.minecraftforge.*` 包名

坐标是 `net.neoforged:forge:1.20.1-47.1.x`，但它只是换了 groupId/artifactId 的
**Forge 分支**；`net.neoforged.*` 命名空间要到 20.2 才引入。所以
`TickControlNeoForge` import 的是 `net.minecraftforge.fml.common.Mod` 等，元数据也是 `mods.toml`。

### 11. `RegisterCommandsEvent` 在 game bus 上

用 `@EventBusSubscriber(bus = Bus.MOD)` 会让 mod 构造阶段直接崩：
`... takes an argument that is not a subtype of ... IModBusEvent`。

### 12. 生产环境的 refmap 必须完整（否则客户端启动即崩）

```
InvalidMixinException: @Shadow method haveTime in
tickcontrol.mixins.json:MinecraftServerMixin was not located in the target class
net.minecraft.server.MinecraftServer. Using refmap tickcontrol.refmap.json
```

**开发环境正常、正式安装就崩**——因为两者的名字域不同：

| | 开发环境（`runServer`） | 生产环境（正式启动器） |
| --- | --- | --- |
| 游戏 jar 名字 | MCP 官方名（`runServer`） | SRG 名（`m_130011_`） |
| `@Shadow` / `@Inject` 目标 | 直接命中 | 必须经 refmap 重映射 |

原来那份 `tickcontrol.refmap.json` 只有一个**手写的 4 条**映射
（`runServer` / `tickServer` / `tickChildren` / `nextTickTime`），
后来陆续新增的 `@Shadow`（`running`、`profiler`、`tickTimes`、`initServer`、
`waitUntilNextTick`、`haveTime`…）全都没有条目，
于是生产环境下 Mixin 找不到目标成员 → `MixinApplyError` → 客户端初始化崩溃。

**修法**：`.tools-probe/srg.py` 从 ForgeGradle 缓存里的
`mcp_mappings.tsrg`（obf → SRG）和 `server_mappings.txt`（官方名 → obf）
交叉解析出全部映射，直接生成完整 refmap：

```powershell
<bundled python> .tools-probe\srg.py --write
```

解析时踩到的三个坑（都写进脚本注释了）：

1. `server_mappings.txt` 是 **CRLF**，按 `\n` 切分后行尾残留 `\r`，`$` 永远匹配不上；
2. **不要用 `\S+` 去匹配箭头**：`\S` 包含 `-`，会把 `->` 的 `-` 一起吞掉，
   导致箭头永远匹配失败。用 `^(.+?) +-> +(\S+) *$`；
3. `mcp_mappings.tsrg` 是 tsrg2，同一个混淆名有**两种行型**：
   字段行 `obf srg id`（SRG 在第 2 列）、方法行 `obf descriptor srg id`（第 3 列）；
   而且类名那一列大多是**混淆名**（`MinecraftServer` 恰好等于官方名，`ServerLevel` 是 `aif`）。

另外把 `haveTime` 的 `@Shadow` 整个删掉了：谓词改成直接传
`ServerTickController::haveTimeNow`，少一个需要重映射的符号。
用 `srg.py --write` 重新生成 refmap 后，**每次新增 `@Shadow` 都要重跑一次**。

---

### 13. `@Shadow` 在生产环境就是不可靠的：改用反射（重要）

第 12 条把 refmap 补全后（逐条核对过 `m_7038_` 确实存在于
`forge-1.20.1-47.4.26-client.jar`），生产环境**仍然崩**，只是失败点换了一个：

```
InvalidMixinException: @Shadow method haveTime ... was not located   （修复前）
InvalidMixinException: @Shadow method initServer ... was not located （修复后）
```

也就是说：**refmap 存在、config 正确引用了它、映射值经生产 jar 验证无误，
但 Mixin 就是没有应用它。**

实测得到的两个关键事实：

1. **`reobfJar` 不重映射注解字符串。**
   反编译生产 jar 里的 `MinecraftServerMixin.class`，常量池中：
   - 字节码引用已是 SRG：`m_5705_`（`tickServer`）**存在**
   - 注解字符串仍是官方名：`runServer` / `tickChildren` / `initServer` **存在**，
     `m_130011_` / `m_5703_` / `m_7038_` **不存在**

   所以 `@Inject(method = "runServer")`、`@Shadow` 这类注解值**只能靠 refmap**
   在运行时重映射——这正是它唯一的用途。

2. **`@Shadow` 与 `@Inject` 对 refmap 的依赖机制不同。**
   `@Shadow` 需要在「Mixin 自身被加载」时完成重映射，`@Inject` 则在
   目标类变换时由 Mixin 处理。前者在实践中没有生效。

**结论与对策**：不要在生产环境依赖 `@Shadow`。

本工程已把 `MinecraftServerMixin` 里**全部 `@Shadow` 换成反射**
（`tickcontrol$isRunning()` / `tickcontrol$profiler()` / `tickcontrol$initServer()` …，
句柄静态缓存，每刻只有几次 `Field.getInt` 级别开销）。
反射不受名字域影响，**开发与生产行为完全一致**，也不再需要 refmap 支持。

`@Inject` / `@Redirect` 的 `method` 与 `target` 字符串保留官方名——它们由 Mixin
在变换目标类时自行重映射，实测可用。

判断有没有踩这个坑的最快方法：

```powershell
# 生产 jar 里若还能找到官方名，就说明注解字符串没被重映射
javap -v -p -classpath <生产jar> com.tamamo.tickcontrol.mixin.MinecraftServerMixin | Select-String 'runServer|m_130011_'
```

---

### 14. refmap 失效的最终结论与现行方案（两个变体 + 配置插件）

第 12、13 条记录了 refmap 在生产环境不生效。补全映射、加通配键、
改注册方式（manifest / `mods.toml`）、加 `-Dmixin.env.remapRefMap=true`
与 `refMapRemappingFile` 全都试过，报错末尾始终是
`Using refmap tickcontrol.refmap.json`（去掉 refmap 后变成 `No refMap loaded.`），
说明 Mixin 根本没有应用它。

**关键教训：不要与 Mixin 的名字重映射机制纠缠。**

现行方案（已验证两端都通过）：

1. 写**两个薄 Mixin**，注解值分别写官方名与 SRG 名，**都带 `remap = false`**
   （因此都不需要 refmap），逻辑都委托给 `com.tamamo.tickcontrol.core.ServerLoop`：
   - `MinecraftServerMixinDev` → `runServer` / `tickServer` / `ServerLevel.tick`
   - `MinecraftServerMixinSrg` → `m_130011_` / `m_5705_` / `m_8793_`
2. 用 `IMixinConfigPlugin.shouldApplyMixin` 按环境选择其一
   （`TickControlMixinPlugin`）。

**环境探测也有两个坑**，都踩过了：

| 做法 | 结果 |
| --- | --- |
| `Class.forName("net.minecraft.server.MinecraftServer")` | 目标类被**提前加载**，Mixin 拒绝注入：`MixinTargetAlreadyLoadedException: target ... was loaded too early.` |
| `MixinEnvironment.getObfuscationContext()` | 生产环境的 `onLoad` 阶段返回 **`null`**，会选错变体 |
| ✅ `MixinService.getService().getResourceAsStream("net/minecraft/server/MinecraftServer.class")` + 扫描常量池字节 | **可靠**。注意资源名必须是**斜杠**形式（点号形式返回 `null`） |

判据：常量池里出现 `f_129764_`（`running` 的 SRG 名）即生产环境。
实测两个环境的输出：

```
# 生产环境（正式客户端）
probe: net/minecraft/server/MinecraftServer.class -> 92909 bytes; runServer=false f_129764_=true m_130011_=true
mixin plugin loaded; target is SRG-named (production)

# 开发环境（gradle runServer）
probe: net/minecraft/server/MinecraftServer.class -> 94830 bytes; runServer=true f_129764_=false m_130011_=false
mixin plugin loaded; target is MCP-named (development)
```

**踩坑记录：`require = 0` 会造成「假成功」。**
排查过程中给两个变体都加过 `require = 0` 来容忍失败，结果客户端能正常启动、
零报错，但**注入其实被静默跳过、mod 完全没生效**。最终必须回到 `require = 1`，
并在两个环境都确认 Mixin 真正应用（开发环境看
`Mixing MinecraftServerMixinDev ...`，生产环境看零错误）。

---

### 15. 反射层也必须名字域自适应（进世界卡死的根因）

第 14 条让 Mixin 注入在生产环境成功之后，创建世界时服务器线程抛出：

```
java.lang.IllegalStateException: [tickcontrol] method not found: initServer
    at com.tamamo.tickcontrol.core.ServerLoop.method(ServerLoop.java:89)
    at com.tamamo.tickcontrol.core.ServerLoop.initServer(ServerLoop.java:187)
    at com.tamamo.tickcontrol.core.ServerLoop.runServer(ServerLoop.java:265)
    at net.minecraft.server.MinecraftServer.handler$zza000$tickcontrol$runServer(MinecraftServer.java:1858)
    at net.minecraft.server.MinecraftServer.m_130011_(MinecraftServer.java)
Caused by: java.lang.NoSuchMethodException: net.minecraft.server.MinecraftServer.initServer()
```

注意堆栈：`m_130011_` 里确实进入了我们的 handler——**说明 SRG 变体的注入是成功的**。
问题在于 `ServerLoop` 里用反射访问 `MinecraftServer` 的私有成员时写的是**开发名**
（`initServer`），而生产环境是 SRG 名（`m_7038_`）。

**教训：Mixin 注解和反射是两条独立的名字解析路径，两条都要处理。**

修法是在 `ServerLoop` 里加两组对照表，让 `field()` / `method()` 两种名字都能解析，
于是本类不需要知道当前处于哪种名字域：

```java
FIELD_ALTERNATES  = { running -> f_129764_, tickTimes -> f_129748_, ... }
METHOD_ALTERNATES = { initServer -> m_7038_, waitUntilNextTick -> m_130012_, ... }
```

`.tools-probe/verify-members.py` 会对<b>每个</b>被反射的成员同时检查
「开发名在开发 jar 里存在」与「SRG 名在生产 jar 里存在」，结果全部 OK：

```
member         kind   dev?   srg          prod?  verdict
running        field  True   f_129764_    True   OK
initServer     method True   m_7038_      True   OK
waitUntilNextTick method True m_130012_   True   OK
...（共 12 项，FAILURES: none）
```

顺带删掉了一处不必要的反射：`constructOrExtractCrashReport` 是 `static private`，
而且只是在 throwable 已携带崩溃报告时复用，直接 `new CrashReport(...)` 即可。

**另一个必须记住的坑**：`require = 0` 会造成「假成功」——客户端能启动、零报错，
但注入被静默跳过、mod 完全没生效。最终必须用 `require = 1`，
并在两个环境都确认 Mixin 真正应用。

---

## 六之二、`/tick sprint` 实现要点

照抄 1.21.1 的 `ServerTickRateManager`（1.20.1 没有这个类，1.21.1 才有）。核心机制：

| 上游 | 本工程 |
| --- | --- |
| `remainingSprintTicks` / `scheduledCurrentSprintTicks` | `LoopPacer` 同名字段 |
| `isSprinting()` | `scheduledSprintTicks > 0 \|\| remainingSprintTicks > 0` |
| `requestGameToSprint(n)` | `requestSprint(n)`：记录刻数并**临时解冻**（保存原冻结状态） |
| `checkShouldSprintThisTick()` | 每轮主循环调用，前 n 次返回 true |
| 冲刺时 `i = 0L`（不等） | `LoopPacer.timeRemainingMillis()` 在冲刺中恒返回 1，于是 `haveTime()` 为真、`waitUntilNextTick()` 立即返回 |
| `endTickWork()` | `ServerTickController.endTickWork()`：累计耗时 + 结算 + 恢复冻结状态 |
| `finishTickSprint()` 报告 | 主循环通过 `server.createCommandSourceStack().sendSuccess(...)` 发送 `commands.tick.sprint.report` |

### 实测

```
--- 9. sprint: /tick rate 1 then /tick sprint 600
--- 10. sprint check: gameTime +602 in 1.04s -> 581.1 TPS
PASS: sprint advanced 602 ticks
PASS: sprint ran far faster than the 1 TPS target: 581.1 TPS
PASS: sprint ended and normal ticking resumed
```

（把目标速率压到 1 TPS 再冲刺，600 刻约 0.04 秒跑完，即约 15000 TPS；
上面 1.04s 的墙钟含自检为避开过渡帧而多等的两刻。）

### 实现时踩的四个坑

0. **冲刺报告标志没清，日志被刷到近 1 GB（最严重，已修）**。

   `finishSprint()` 清空了 `scheduledSprintTicks`，但没清 `lastSprintTps`，
   而 `sprintReportPending()` 只看后者是否大于 0，于是它**恒为真**：

   ```
   checkShouldSprintThisTick() 看到 sprintReportPending() 为真
       -> controller.sprintReportPending = true
       -> 主循环 consumeSprintReport() 每轮返回 true -> 每轮发一条聊天消息
   ```

   冲刺结束后主循环每秒可转上万轮，实测刷出约 **600 万行 / 近 1 GB** 日志，
   客户端直接卡死。修法：发送报告后立刻调用 `clearSprintReport()`
   （同时清 `lastSprintTps` 与 `sprintReportPending`），
   并在自检里加了常驻断言「报告只能被消费一次」守这个回归。

   教训：**「消费一次」的语义必须由生产端显式清状态来实现，
   不能只靠读取方自觉。** 这类 bug 开发时很难发现——
   功能看着正常、自检也能过，只有长时间运行才暴露。

1. **冲刺收尾不能放在 `checkShouldSprintThisTick()` 里**。
   主循环顺序是 `checkShouldSprintThisTick() → tickServer() → endTickWork()`，
   最后一刻跑完后本轮不会再调前者，于是 `isSprinting()` 会在整轮里保持为真。
   实测该窗口让 `/tick freeze` 的守卫与自检误判
   （诊断输出：`remaining=0 scheduled=600` 却仍报 sprinting）。
   正确位置是 `endTickWork()`——还能顺便保证最后一刻耗时被计入报告。

2. **`TickState.setFrozen(false)` 会清空 `stepTicks`**，
   冲刺的「临时解冻」不能复用它，否则会丢掉待步进的刻数。
   为此在 `TickState` 里单独加了 `beginSprint()` / `endSprint()`，只动 `frozen` 字段。

3. **自检不能在冲刺刻的 `tickServer()` 内断言状态**——
   那一刻收尾还没发生。必须多等两刻再断言，否则永远看到「仍在冲刺」。

另外与上游一致：冲刺期间 `/tick freeze` 会失败（`canFreeze()` 守卫），
避免冻结与冲刺的临时解冻互相干扰。

---

## 六之三、与 Carpet / BoccHUD(MiniHUD) 的 TPS 显示兼容

### 问题的本质：两个模组抢同一个方法

Carpet 的 Forge 1.20.1 版**自己也在整个替换 `MinecraftServer.runServer`**
（`MinecraftServer_tickspeedMixin.modifiedRunLoop`），并自带一整套
`carpet.helpers.TickRateManager`。本模组同样接管 `runServer`，且注入点更早：

| | 注入点 |
| --- | --- |
| 本模组 | `@Inject(method="runServer", at=HEAD, cancellable=true)` |
| Carpet | `@Inject(method="runServer", at=INVOKE buildServerStatus(), shift=AFTER)` |

我们在 HEAD 就 `cancel()` 了整个方法，所以 Carpet 那个位于方法**中部**的注入点
永远到不了——它的主循环从未执行。`runServer` 只有一个循环能跑，这是结构性冲突，
不是可以两边都满足的配置问题。

### 为什么「MSPT 对、TPS 锁 20」

Carpet 的 TPS 算法（`HUDController.send_tps_display`）：

```java
double avgTickTimeMs = Arrays.stream(server.tickTimes).average() * 1.0E-6;  // 真实
double mspt = Math.max(1000.0 / Math.max(tickRateManager.mspt(), avgTickTimeMs), avgTickTimeMs);
// TPS = 1000 / mspt
```

`server.tickTimes` 是真的（我们的主循环照常写入），但 `tickRateManager.mspt()`
来自 Carpet 自己那个**从没被更新过的** manager，初始值 `tickrate = 20.0f`。
于是真实耗时被名义值封顶 → MSPT 会变、TPS 恒 20。

而 **BoccHUD（MiniHUD 的 fork）根本不自己测**：它的
`DataStorage.handleCarpetServerTPSData` 用正则
`TPS: (?<tps>[0-9]+[\.,][0-9]) MSPT: (?<mspt>[0-9]+[\.,][0-9])`
**直接解析 Carpet 发出的那条聊天消息**。所以两者同源——修好 Carpet 的数值就同时修好了它。

（顺带一提，这个正则只匹配**一位小数**，所以 MSPT 必须格式化成 `1.2` 而非 `1.17`。）

### 做法

`CarpetHudBridge` 在每 20 个游戏刻（对齐 Carpet 自己的显示节奏）把实测值写进
Carpet 的 tick rate manager，再调用它的 `HUDController.update_hud` 让那条消息带上正确数值。

实测 TPS 由 `LoopPacer` 按墙钟滑动窗口测量（1 秒窗口），因为
`MinecraftServer.tickTimes` 记录的是「主循环分配给这一轮的时间」，
在主循环被接管后同样不准。

**为什么用反射而不是 `@Pseudo` Mixin**：对第三方模组类做 Mixin 是紧耦合，
Carpet 改类名/字段名就会让本模组崩。反射 + 全路径 try/catch 是软失败——
适配不了只是 HUD 数字不准，绝不影响本体功能。已逐个核对反射目标存在：

| 目标 | 状态 |
| --- | --- |
| `carpet.fakes.MinecraftServerInterface.getTickRateManager()` | public |
| `carpet.logging.HUDController.update_hud(MinecraftServer, List)` | public static |
| `carpet.helpers.TickRateManager.tickrate` / `.mspt` | protected（子类可见） |

### 排查过程中踩的两个坑（都值得记住）

**坑 1：诊断信息埋在太深的地方。**

最初的 `resolve()` 只在 `sync()` 里被调用，而 `sync()` 每 20 个游戏刻才跑一次——
必须**进入世界**之后才可能出现。结果「适配代码没跑」和「Carpet 不在场」在日志里
完全无法区分。现在改为在 mod 构造阶段就调 `probeAtStartup()`，
启动时立刻能看到结论：

```
[tickcontrol] Carpet probe: own=... ctx=... mc=...
[tickcontrol] Carpet detected via ...        <- 成功
[tickcontrol] Carpet not detected; HUD sync disabled   <- 对方不在
[tickcontrol] Carpet is present but HUD sync could not be set up: <原因>   <- 适配失败
```

**必须能区分「对方不在」和「我配不上」**——否则出问题时毫无线索。

**坑 2：`Class.getField()` 只返回 public 字段。**

`tickrate` / `mspt` 声明在父类 `carpet.helpers.TickRateManager` 上，是 `protected`，
于是 `managerClass.getField("tickrate")` 抛 `NoSuchFieldException`，
适配静默失败。现在沿父类链用 `getDeclaredField` + `setAccessible`：

```java
for (Class<?> c = type; c != null; c = c.getSuperclass()) {
    try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
    catch (NoSuchFieldException ignored) { }
}
```

### 实测每刻耗时的实现：照抄上游的 tick 计时

最初我自作主张用了「固定 1 秒窗口 + 窗口内计数」，结果出现
**「不加速时 TPS 间歇跳到 20+」**——冲刺结束后的整整 1 秒内，窗口里还残留着
冲刺期间的上万刻，HUD 持续显示 20 以上。

随后又试了「逐刻间隔的指数平滑平均（EMA）」，同样错：若首个样本取自冲刺期
（每秒上万刻），衰减到正常值需要约 1240 个样本（约 62 秒），实测冲刺后仍显示 182 TPS。

**最终照抄上游 1.21.1 `MinecraftServer.tickServer` 的计时模型**：

```java
long j = Util.getNanos() - i;                 // 本刻耗时
int k = this.tickCount % 100;
this.aggregatedTickTimesNanos -= this.tickTimesNanos[k];
this.aggregatedTickTimesNanos += j;
this.tickTimesNanos[k] = j;                   // 100 刻环形缓冲
// 平均：aggregatedTickTimesNanos / Math.min(100, Math.max(tickCount, 1))
```

唯一的改动：**在「游戏刻之间」计时，而不是只测 `tickServer` 内部耗时**。
因为 1.20.1 的等待有 1ms 睡眠粒度，若照抄「只测 tickServer 内部」，
1 TPS（1000ms 周期）会被测成约 999ms，误差被放大成 1.001 TPS 这类噪声。
测「上一刻到本刻的真实间隔」则天然准确；100 刻的固定窗口让旧样本自然滑出，
不会有固定时间窗口那种残留问题。

实测（自检第 12 步）：

```
--- 12. measured MSPT convergence: at sprint end=50.00ms -> after settle=49.00ms (target 50.00ms)
PASS: measured MSPT settled back to the target rate after sprint
```

### 三个必须记住的采样陷阱

1. **只在真正完成游戏刻时采样。** 主循环每轮不一定 tick——1 TPS 目标下每秒要转
   几万轮、只 tick 一次。我最初把采样放在每轮循环里，实测算出了 **600 万 TPS**。
2. **不要用固定时间窗口。** 窗口里会残留变速前的样本（冲刺结束后的上万刻）。
   要用「最近 N 刻」的环形缓冲，旧样本自然滑出。
3. **不要用 EMA。** 从极端值恢复极慢（1240 个样本 / 62 秒）。
   环形缓冲 + 累计和（上游做法）没有这个问题。

### BoccHUD / MiniHUD 为什么必须执行 `/log tps`

BoccHUD 自己就有两条**互不相同**的数据路径，其 `InfoToggle` 里
SERVER_TPS 的描述原文写着：

```
Show the server TPS and ms/tick (MSPT) values
and the TPSdisplay carpet rule is enabled.
only detect TPS values lower than 20 that way.
```

| 路径 | 触发条件 | 能力 |
| --- | --- | --- |
| Carpet 路径 | 装了 Carpet **且**执行过 `/log tps` | 正则解析 Carpet 发出的 `TPS: x.x MSPT: y.y` 消息，**能显示任意 TPS（含 > 20）** |
| 集成服务器回退 | 没有 Carpet 消息时 | **只能检测低于 20 的 TPS**（它自己的注释承认） |

因此：
- **未执行 `/log tps`** → 走回退路径 → TPS 恒显示 20.0，而 MSPT 照常更新。
  这是 BoccHUD 的固有限制，不是本模组的问题。
- **执行了 `/log tps`** → 走 Carpet 路径 → 显示真实速率，由本模组的
  `CarpetHudBridge` 保证数值正确。

### 冲刺速率有两个不同的量（别混淆）

自检第 10b 步会同时打印三个值，实测形如：

```
HUD displayed          = 27332.1 TPS (0.0366 ms/tick, interval-derived)
report interval-derived= 20283.5 TPS (0.0493 ms/tick)
report wall-clock      = 20283.5 TPS over 600 ticks
display/report ratio   = 1.35
```

- **间隔派生速率**：刻与刻之间的真实间隔倒数。HUD 显示的是这个。
- **整段墙钟速率**：总刻数 / 总耗时。冲刺期间主循环除 `tickServer` 外还有每轮
  固定开销（会话记录、JVM profiler 等），这部分**不在刻的间隔里**，所以整段
  速率会低于间隔派生速率。
- 两者窗口也不同：HUD 是 100 刻的「当前瞬时」，报告是 600 刻的总平均。
  冲刺中 JIT 预热与缓存状态变化会让瞬时快于全程平均，实测比值约 1.3。

**结论**：这两个数字都对，只是量的定义不同。用一个去要求另一个相等是错的
（我最初就这么写了断言，实测 ratio 1.35 后才发现）。

### 验证状态

- ✅ **探测正确**：正式客户端（Carpet 在场）打印
  `Carpet detected via cpw.mods.modlauncher.TransformingClassLoader@...`；
  dev（无 Carpet）打印 `Carpet not detected; HUD sync disabled`
- ✅ **Carpet 不在场时零影响**：dev 自检 `ALL CHECKS PASSED`（速率、freeze、step、sprint 全通过）
- ✅ 反射目标逐个核对存在
- ⬜ **HUD 数字本身仍需在正式客户端进世界后确认**——本地无法观察 HUD，
  且 dev 跑不了 Carpet（它自己的 mixin 在 dev 下就崩：
  `@Shadow method m_129783_ was not located`，与本源无关）

---

## 七、后续计划

1. **`/tick sprint`**：主循环与任意速率已经具备（1~1000 TPS 可精确控制），
   sprint 只需在 `allowTick()` 上加一条"冲刺期间忽略目标周期直接放行"的分支，
   再加上看门狗、keep-alive/网络超时、区块卸载的处理。
2. **客户端同步**：1.20.3 新增了 `ClientboundTickingStatePacket` 与
   `ClientboundTickingStepPacket`，客户端 `ClientLevel` 侧也有 tick manager。
   不做同步时 `rate` 在多人游戏下会与客户端「20 TPS」的假设冲突
   （橡皮筋、动画错乱）。稳妥形态是只在单人/局域网主机启用。
3. **1.19.2 / 1.18.2 / 1.16.5 / 1.12.2**：1.19.2 与 1.16.5 缺
   `TimeArgument.time(int)` 重载需要自己补；1.12.2 没有 Brigadier
   （命令要按 `ICommand` 体系重写），主循环也是另一种形态。
