# 主循环移植方案（1.21.1 → 1.20.1）

目标：让 `/tick rate` 突破 20 TPS，与 1.20.3+ 行为一致。

本文是**动手前的逐行对照**，依据是：

- 本地 1.21.1 源码 `mc-src-1.21.1/net/minecraft/server/MinecraftServer.java`（`runServer` 在 668–754 行）；
- 1.20.1 的 `MinecraftServer.runServer()` 字节码（`javap -p -c`，带偏移量）。

---

## 一、两个版本的主循环逐行对照

### 1.21.1（纳秒制，`nextTickTimeNanos`）

```java
this.nextTickTimeNanos = Util.getNanos();
...
while (this.running) {
    long i;
    if (!this.isPaused() && this.tickRateManager.isSprinting() && this.tickRateManager.checkShouldSprintThisTick()) {
        i = 0L;                                        // sprint：不等待
        this.nextTickTimeNanos = Util.getNanos();
        this.lastOverloadWarningNanos = this.nextTickTimeNanos;
    } else {
        i = this.tickRateManager.nanosecondsPerTick();  // ← 一刻多长，由这里决定
        long k = Util.getNanos() - this.nextTickTimeNanos;
        if (k > OVERLOADED_THRESHOLD_NANOS + 20L * i && ...) {   // 过载追赶
            long j = k / i;
            LOGGER.warn("Can't keep up! ...");
            this.nextTickTimeNanos += j * i;
            this.lastOverloadWarningNanos = this.nextTickTimeNanos;
        }
    }

    boolean flag = i == 0L;                             // sprint 中
    ...
    this.nextTickTimeNanos += i;                        // ← 推进截止时刻
    this.startMetricsRecordingTick();
    this.profiler.push("tick");
    this.tickServer(flag ? () -> false : this::haveTime);   // ← 每轮都 tick
    this.profiler.popPush("nextTickWait");
    this.mayHaveDelayedTasks = true;
    this.delayedTasksMaxNextTickTimeNanos = Math.max(Util.getNanos() + i, this.nextTickTimeNanos);
    this.startMeasuringTaskExecutionTime();
    this.waitUntilNextTick();                           // ← 等到 nextTickTimeNanos
    this.finishMeasuringTaskExecutionTime();
    if (flag) this.tickRateManager.endTickWork();
    this.profiler.pop();
    this.logFullTickTime();
    this.endMetricsRecordingTick();
    this.isReady = true;
    JvmProfiler.INSTANCE.onServerTick(this.smoothedTickTimeMillis);
}
```

### 1.20.1（毫秒制，`nextTickTime`，字节码偏移）

```java
this.nextTickTime = Util.getMillis();                   // off 23
...
while (this.running) {                                  // off 63..64 / 287 goto 63
    // ---- 循环体开始 ----
    long i = Util.getMillis() - this.nextTickTime;      // off 70..78  (lstore_1)
    if (i > 2000L                                       // off 79..84
        && this.nextTickTime - this.lastOverloadWarning >= 15000L) {   // off 87..100
        long j = i / 50L;                               // off 103..108
        LOGGER.warn("Can't keep up! Is the server overloaded? Running {}ms or {} ticks behind", ...);  // off 109..123
        this.nextTickTime += j * 50L;                   // off 128..139  (PUTFIELD nextTickTime)
        this.lastOverloadWarning = this.nextTickTime;   // off 142..147
    }
    if (this.debugCommandProfilerDelayStart) { ... }     // off 150..177
    this.nextTickTime += 50L;                           // off 180..189  ← 固定 50ms（热路径）
    this.startMetricsRecordingTick();                   // off 192
    this.profiler.push("tick");                         // off 196..203
    this.tickServer(this::haveTime);                    // off 208..215
    this.profiler.popPush("nextTickWait");              // off 218..225
    this.mayHaveDelayedTasks = true;                    // off 230..232
    this.delayedTasksMaxNextTickTime =
        Math.max(Util.getMillis() + 50L, this.nextTickTime);   // off 235..250
    this.waitUntilNextTick();                           // off 253..254
    this.profiler.pop();                                // off 257..261
    this.endMetricsRecordingTick();                     // off 266..267
    this.isReady = true;                                // off 270..272
    JvmProfiler.INSTANCE.onServerTick(this.averageTickTime);   // off 275..282
    // ---- 循环体结束，goto 63 ----
}
ServerLifecycleHooks.handleServerStopping(this);        // off 290
...  // 停服与异常处理，全部保持原样
```

**逐项差异**：

| 项目 | 1.21.1 | 1.20.1 | 移植动作 |
| --- | --- | --- | --- |
| 时间单位 | 纳秒 | 毫秒 | 用 `Util.getNanos()` 自行维护纳秒累加器，不复用 `nextTickTime` |
| 一刻多长 | `tickRateManager.nanosecondsPerTick()` | 常量 `50L` | 由本工程的 `LoopPacer` 提供 |
| 截止时刻 | 字段 `nextTickTimeNanos` | 字段 `nextTickTime` | 不再依赖它 |
| `tickServer` 调用 | 每轮调用，谓词随 sprint 变 | 每轮调用，谓词恒为 `this::haveTime` | 保留每轮调用；游戏刻门控放在 `ServerLevel.tick`（已实现） |
| sprint 分支 | 有（`i = 0L`） | 无 | 第二期实现 `/tick sprint` 时再加 |
| 过载追赶 | 纳秒阈值 | 毫秒阈值 `2000/15000` | 保留 1.20.1 原逻辑（不改） |
| `waitUntilNextTick` | 等到 `nextTickTimeNanos` | 等到 `nextTickTime` | **保持原样**（它内部用 `pollTaskInternal()` 排空任务队列，动不得） |
| `delayedTasksMaxNextTickTime` | nanos 版 | ms 版 | 保持原样（它用 `Util.getMillis()`，与我们的加速无关） |

---

## 二、上一轮失败的确切原因

1. `@Redirect` 那个 `haveTime()` 的 **INVOKE** 匹配不上（实测 `(0/3) succeeded`）。
   原因已查明：**`runServer` 的字节码里根本没有 `haveTime()` 的调用引用**——
   `javap` 在 `runServer` 范围内只找到 `nextTickTime` 的字段读写，说明 `haveTime()`
   在编译期被内联了（它只有 `runningTask()` + `nextTickTime` 比较，足够简单）。
   所以没有可挂钩的 INVOKE 指令。

2. 改写 `nextTickTime` 的字段读/写（`GETFIELD` 180 / `PUTFIELD` 181）虽然能匹配
   （8 处），但会让 `haveTime()` 恒为真 → `managedBlock` 不再阻塞 → 主循环满速自旋，
   而游戏刻要等到期才放行，表现为**活锁**（日志停止、世界不再推进）。

3. 整体接管 `waitUntilNextTick()` 会让任务排空语义丢失 →
   出生点准备从 1.5 秒恶化到数分钟。

**教训**：`haveTime()` 与 `managedBlock` 的自旋、`nextTickTime` 的毫秒语义三者耦合，
任何单点改写都会破坏其中一项。必须**从循环体层面替换**。

---

## 三、移植方案

> **实施状态（记录一次失败，避免重复）**
>
> 已按 3.2 的"接管两个调用点"做过一次实现，结果是：
>
> - ✅ **1~20 TPS 完全可用**，且比之前更干净：不碰 `haveTime()` 与 `nextTickTime`，
>   启动时间保持 1.3 秒（没有饿死区块加载），20/10/5 TPS 全部 ratio = 1.00；
> - ❌ **>20 TPS 仍不行**：主循环每轮只调用一次 `tickServer`，而
>   `waitUntilNextTick()` 被我们替换后主循环仍按 50ms 一轮转，
>   所以一轮只有一次游戏刻机会 → 上限仍是 20。
>
> 为突破它试过"同一轮里补跑"（在 `tickServer` 重定向里循环调用，并把
> `nextTickTime` 往回调 `(n-1)*(50-周期)`），结果**服务器挂起**
> （连自检都没开始）。原因未查明，推测与 `tickServer` 的重入
> （profiler push/pop 栈、`averageTickTime`、Forge 的 tick 事件）有关。
>
> **结论**：`tickServer` 不能在重定向里被重入调用。要支持 >20 TPS，
> 必须走 3.1 那条路——**整体替换 `while(running){...}` 循环体**，
> 在同一个循环里按 1.20.3 的结构「先累加 nanosecond，再决定跑几个刻」，
> 而不是在既有的一轮里补跑。
>
> 当前代码处于"已验证的 1~20 TPS"状态。

用 `@Inject(method = "runServer", at = @At("HEAD"), cancellable = true)` **整体接管**，
在方法内部按 1.21.1 的结构重写。

### 3.1 为什么可以整体接管

`runServer` 的生命周期是：

```
initServer() → handleServerStarted → 初始化 status → while(running){...}
             → handleServerStopping → expectServerStopped → stopServer → handleServerStopped → onServerExit
```

我们只需要重写 `while(running){...}` 这一段。**但如果直接在 HEAD 取消整个方法，
就必须自己把初始化与停服部分也写一遍**——包括 `initServer()`、`handleServerStarted`、
`status`/`statusIcon` 初始化、`handleServerStopping`、`stopServer`、
`handleServerStopped`、`onServerExit`，以及三处 `services.profileCache().clearExecutor()`。

这样做风险偏高（Forge 的 `ServerLifecycleHooks` 调用顺序不能错，
否则模组加载/卸载会出问题）。

### 3.2 更稳的做法：只替换循环体

改为在 **循环体起点** 注入并接管循环：

- 注入点：`@Inject(method = "runServer", at = @At(value = "JUMP", opcode = Opcodes.GOTO, ordinal = 0), cancellable = true)`
  或者更稳妥地——用 `@Redirect` 掉 `waitUntilNextTick()` 的调用点，
  在那里判断"是否还要继续本轮循环"，不满足条件就自己 sleep 且不返回。

权衡后建议**分两步走**：

**第一步（低风险，先验证可行性）**

保留 1.20.1 的循环体，只把「循环节流」从 `haveTime()` 上挪开：

1. `@Redirect` `runServer` 里 `waitUntilNextTick()` 的调用点，替换为
   `ServerTickController.awaitNextTick(...)`：内部先 `runAllTasks()`（保持任务排空），
   再按纳秒累加器 park 到下一个游戏刻时刻。
   —— 但这一步单独做会让 `haveTime()` 仍限住 `tickServer`，所以还要：

2. `@Redirect` `runServer` 里 `tickServer(...)` 的调用点，替换为
   `if (pacer.allowTick()) server.tickServer(haveTime)`；
   即在**调用点**做门控，而不是改 `haveTime()`／`nextTickTime`。

这一步不需要动 `haveTime()` 和 `nextTickTime`：
`haveTime()` 恒真的问题不存在（我们没改它），`tickServer` 由我们的门控决定，
`nextTickTime` 每轮 `+= 50` 只影响 `waitUntilNextTick` 的等待时长——
而第 1 步已经把 `waitUntilNextTick` 换成我们自己的等待。

**第二步**：确认第一步稳定后，再考虑把 `nextTickTime` 一并接管，
或补 `/tick sprint`。

### 3.3 需要新增/修改的代码

- `LoopPacer`：已有 `allowTick()` / `shouldRun()` / `reset()`，
  需恢复"累计纳米"语义（当前版本已在）。
- `ServerTickController`：
  - `allowTick()`：暴露给 `tickServer` 调用点的门控；
  - `awaitNextTick(ParkAction)`：暴露给 `waitUntilNextTick` 调用点的等待。
- `MinecraftServerMixin`：
  - 新增 `@Redirect(method = "runServer", @At(INVOKE, "tickServer(...)"))`；
  - 新增 `@Redirect(method = "runServer", @At(INVOKE, "waitUntilNextTick()"))`；
  - **移除**对 `haveTime()` / `nextTickTime` 的任何重定向（已证明会活锁）。
- 任务排空：`awaitNextTick` 里必须调用 `runAllTasks()`。
  它是 `BlockableEventLoop` 的 `protected` 方法，Mixin 的 `@Shadow` 找不到继承方法，
  且 mixin 类与目标类不同包 → **用一次性反射**（已验证可行）。

### 3.4 验收标准

`-Pselftest` 的 rate 探针需要恢复成 `{20, 100, 10, 200, 5}` 并全部通过
（ratio 在 0.75~1.25 之间），同时：
- 出生点准备时间保持 ~1.5 秒（回归检查，防止再次饿死区块加载）；
- `freeze` / `step` 仍然精确；
- 服务器启动后日志持续输出（防止静默活锁）。

### 3.5 风险

- `tickServer` 调用点的 `@Redirect` 已经验证**可以匹配**（上一轮就是靠它跑通 1~20 TPS 的节流），
  所以第一步的可行性较高。
- `waitUntilNextTick()` 调用点的 `@Redirect` 需要注意：**不能**像上次那样取消整个方法，
  而是要在 handler 里调用一次原始的 `runAllTasks()` 再自己 park。

---

## 四、第二轮实施（整体照抄 runServer）与结论

已按"直接抄高版本底层"做了一次完整实施：

- 用 `@Inject(method = "runServer", at = @At("HEAD"), cancellable = true)` **顶掉整个
  `runServer`**，方法体按 1.21.1 的结构逐行重写（初始化、`while (running)`、
  过载追赶、profiler、`startMetricsRecordingTick`/`endMetricsRecordingTick`、
  `isReady`、`JvmProfiler`、异常处理、`stopServer`/`onServerExit` 收尾）；
- 成员名按 1.20.1 实际存在的一一替换：
  `isPaused()` → `running`；`tickRateManager.*` → `LoopPacer`（nanosecond 累加器）；
  `ServerLifecycleHooks.*`（NeoForge 20.x）→ `net.minecraftforge.server.ServerLifecycleHooks.*`（Forge 47）；
  `logFullTickTime()`/`smoothedTickTimeMillis`/`startMeasuringTaskExecutionTime()` 在 1.20.1 不存在，省略；
  `CrashReport.saveToFile(Path, ReportType)` → 1.20.1 只有 `saveToFile(File)`；
  `constructOrExtractCrashReport` 是 `static private` → 反射。
- 结果：**1~20 TPS 全部精确**（20/10/5 → ratio 1.01/1.02/1.02），启动 1.4 秒正常，
  freeze/step 完全正确，`ALL CHECKS PASSED`。

### 但 >20 TPS 仍然不行，原因已定位到最后一层

开 `-Ptickdebug` 打印「每秒主循环迭代数」后得到决定性数据：

```
loop: rate=20.0 periodNanos=50000000 runsThisIteration=0 iterationsPerSecond=0 granted=20  levelTicks=60
loop: rate=20.0 periodNanos=50000000 runsThisIteration=0 iterationsPerSecond=0 granted=40  levelTicks=120
```

`iterationsPerSecond=0` 表示**主循环一秒只转一圈**，而这一圈里 `granted` 涨了 20——
说明 `allowTick()` 在 `waitUntilNextTick()` → `managedBlock` 的等待循环里被反复调用，
把多个游戏刻一口气放行，而主循环本身整整卡住一秒。20 TPS 只是这种"一圈放行一批"的
副产物，与目标周期无关；所以把 rate 设成 100/200 时仍是 20.17。

**这是 1.20.1 与 1.21.1 在 `waitUntilNextTick()` 上的语义差异**：

| | 1.21.1 | 1.20.1 |
| --- | --- | --- |
| 等待对象 | `nextTickTimeNanos`（字段，主循环里 `+= nanosecondsPerTick`） | `nextTickTime`（字段，主循环里固定 `+= 50L`） |
| `haveTime()` | `Util.getNanos() < nextTickTimeNanos` | `Util.getMillis() < nextTickTime`，且**在 `runServer` 里被内联** |
| 结果 | `managedBlock` 每轮精确阻塞一个周期 | 只要 `nextTickTime` 被外部改写，`haveTime()` 的整数毫秒比较就会在等待循环里反复为真，把一批刻一次放行 |

已试过的规避手段（都会挂或无效）：

1. 只改 `nextTickTime += 50L` 的增量 → 只拉长间隔，无效；
2. `@Redirect` `haveTime()` 的 INVOKE → 失败 `(0/3)`（它被内联，字节码里没有调用引用）；
3. 把 `nextTickTime` 读取改成 `now + 1` 让 `haveTime()` 恒真 → `managedBlock` 不阻塞、
   主循环疯转（实测 200 万条诊断），节流失效；
4. 把 `nextTickTime` 读取改成节拍器截止时刻 → 就是上面这个「一圈放行一批」；
5. 在 `tickServer` 重定向里重入 `tickServer` 补跑 → 服务器挂起；
6. 整体取消 `waitUntilNextTick()` → 出生点准备从 1.5 秒恶化到数分钟（丢了
   `pollTaskInternal` 排空任务队列的语义）。

### 下一步真的要做的话

必须**同时**搬 `waitUntilNextTick()`：即用自建等待替换它（保留任务排空），
并且让「等待对象」与「放行判据」共用同一个 nanosecond 累加器——
也就是把 1.21.1 里 `nextTickTimeNanos` 的角色完整复制过来，
而不是让 `haveTime()` 去读那个每轮固定 +50 的 `nextTickTime`。

关键约束（已实测）：自建等待里必须调用 `runAllTasks()` / `pollTaskInternal()`
排空任务队列，否则区块加载会被饿死。