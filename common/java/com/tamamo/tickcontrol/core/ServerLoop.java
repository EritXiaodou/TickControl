package com.tamamo.tickcontrol.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.CrashReport;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.jfr.JvmProfiler;

/**
 * 1.21.1 主循环在 1.20.1 上的实现主体（平台与名字域无关）。
 *
 * <h2>为什么把逻辑放在这里</h2>
 *
 * 本工程需要同时支持两种名字域，而 Mixin 注解里的 {@code method = "runServer"}
 * 这类值是<b>编译期常量</b>，无法在运行时改成 {@code m_130011_}：
 *
 * <ul>
 *   <li><b>开发环境</b>：游戏 jar 用 MCP 官方名（{@code runServer}）</li>
 *   <li><b>生产环境</b>：游戏 jar 用 SRG 名（{@code m_130011_}）</li>
 * </ul>
 *
 * 常规做法是由 refmap 完成这一步重映射，但本工程的 refmap 在生产环境
 * <b>完全没有被应用</b>（详见 {@code PatchMixinNames} 与 README 第 12/13 条）。
 * 因此改为写两个薄 Mixin（{@code MinecraftServerMixinDev} /
 * {@code MinecraftServerMixinSrg}），由 Mixin 配置插件按环境选择其一，
 * 而两份 Mixin 都把实际逻辑委托给本类，避免重复。
 *
 * <h2>为什么用反射</h2>
 *
 * {@code @Shadow} 同样需要 refmap，因此这里统一用反射访问
 * {@code MinecraftServer} 的私有成员。反射不受名字域影响，
 * 开发与生产行为完全一致。句柄静态缓存，每刻只有几次
 * {@code Field.getInt} 级别的开销。
 */
public final class ServerLoop {

    private ServerLoop() {
    }

    private static final boolean DEBUG = Boolean.getBoolean("tickcontrol.debug");

    /** 给 Carpet 系 HUD 同步实测值的节拍计数（每 20 刻一次）。 */
    private static long tickcontrol$hudTicker;

    private static long debugLastMs;
    private static long debugLastIterations;

    /** 累计进入 {@code tickServer} 的次数；供「循环在转但一刻没跑」的看门狗使用。 */
    private static long tickServerCalls;
    /** 主循环退出原因，写进 finally 的收尾日志。 */
    private static String exitReason = "unknown";

    /**
     * 循环存活看门狗。
     *
     * <p>为什么必须有：主循环一旦「还在转但再也没进过 tickServer」，服务器线程
     * 既不报错也不推进世界——表现为<b>日志停止、方块/区块不再落盘</b>，而外部只能
     * 看到「卡住了」。这里每 5 秒核对一次「迭代数在涨但 tickServer 计数没涨」，
     * 命中就记一条 ERROR，把诊断从「猜测」变成「日志里有一行」。
     */
    private static final long WATCHDOG_INTERVAL_NANOS =
            TimeUtil.NANOSECONDS_PER_MILLISECOND * 5000L;
    private static long watchdogLastNanos;
    private static long watchdogLastIterations;
    private static long watchdogLastTickServerCalls;
    private static boolean stallReported;

    /** 1.20.1 的过载阈值（毫秒）折算成纳秒，对应 1.21.1 的 OVERLOADED_THRESHOLD_NANOS。 */
    private static final long OVERLOADED_THRESHOLD_NANOS = TimeUtil.NANOSECONDS_PER_MILLISECOND * 2000L;
    private static final long OVERLOADED_WARNING_INTERVAL_NANOS =
            TimeUtil.NANOSECONDS_PER_MILLISECOND * 15000L;

    // ------------------------------------------------------------------
    // 反射句柄（懒加载并缓存）
    // ------------------------------------------------------------------

    private static Field fRunning;
    private static Field fTickTimes;
    private static Field fIsReady;
    private static Field fMayHaveDelayedTasks;
    private static Field fDelayedTasksMaxNextTickTime;
    /** 原版 nextTickTime（毫秒）；看门狗读它，必须维护。 */
    private static Field fNextTickTime;
    private static Field fAverageTickTime;
    private static Field fProfiler;
    private static Field fLogger;
    private static Method mInitServer;
    private static Method mWaitUntilNextTick;
    private static Method mStartMetricsRecordingTick;
    private static Method mEndMetricsRecordingTick;

    /**
     * 成员名在开发环境是 MCP 官方名、生产环境是 SRG 名（{@code initServer} vs
     * {@code m_7038_}）。下面两张表让 {@link #field}/{@link #method} 两种名字都能解析，
     * 因此本类不需要知道当前处于哪种名字域。
     */
    private static final java.util.Map<String, String> FIELD_ALTERNATES = java.util.Map.of(
            "running", "f_129764_",
            "tickTimes", "f_129748_",
            "isReady", "f_129717_",
            "mayHaveDelayedTasks", "f_129728_",
            "delayedTasksMaxNextTickTime", "f_129727_",
            "nextTickTime", "f_129726_",
            "averageTickTime", "f_129737_",
            "profiler", "f_129754_",
            "LOGGER", "f_129750_");

    private static final java.util.Map<String, String> METHOD_ALTERNATES = java.util.Map.of(
            "initServer", "m_7038_",
            "waitUntilNextTick", "m_130012_",
            "startMetricsRecordingTick", "m_177945_",
            "endMetricsRecordingTick", "m_177946_",
            "stopServer", "m_7041_");

    /** 把官方名与 SRG 名互相补全：无论传入哪个，都返回一对候选名。 */
    private static String[] candidates(java.util.Map<String, String> alternates, String name) {
        String other = alternates.get(name);
        if (other != null) {
            return new String[] {name, other};
        }
        for (java.util.Map.Entry<String, String> e : alternates.entrySet()) {
            if (e.getValue().equals(name)) {
                return new String[] {name, e.getKey()};
            }
        }
        return new String[] {name};
    }

    private static Field field(String name) {
        NoSuchFieldException failure = null;
        for (String candidate : candidates(FIELD_ALTERNATES, name)) {
            try {
                Field f = MinecraftServer.class.getDeclaredField(candidate);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                failure = e;
            }
        }
        throw new IllegalStateException("[tickcontrol] field not found: " + name, failure);
    }

    private static Method method(String name, Class<?>... params) {
        NoSuchMethodException failure = null;
        for (String candidate : candidates(METHOD_ALTERNATES, name)) {
            try {
                Method m = MinecraftServer.class.getDeclaredMethod(candidate, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                failure = e;
            }
        }
        throw new IllegalStateException("[tickcontrol] method not found: " + name, failure);
    }

    private static boolean isRunning(MinecraftServer server) {
        try {
            if (fRunning == null) {
                fRunning = field("running");
            }
            return fRunning.getBoolean(server);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] read running failed", e);
        }
    }

    private static long[] tickTimes(MinecraftServer server) {
        try {
            if (fTickTimes == null) {
                fTickTimes = field("tickTimes");
            }
            return (long[]) fTickTimes.get(server);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] read tickTimes failed", e);
        }
    }

    private static void setReady(MinecraftServer server) {
        try {
            if (fIsReady == null) {
                fIsReady = field("isReady");
            }
            fIsReady.setBoolean(server, true);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] write isReady failed", e);
        }
    }

    private static void setMayHaveDelayedTasks(MinecraftServer server, boolean value) {
        try {
            if (fMayHaveDelayedTasks == null) {
                fMayHaveDelayedTasks = field("mayHaveDelayedTasks");
            }
            fMayHaveDelayedTasks.setBoolean(server, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] write mayHaveDelayedTasks failed", e);
        }
    }

    private static void setDelayedTasksMaxNextTickTime(MinecraftServer server, long value) {
        try {
            if (fDelayedTasksMaxNextTickTime == null) {
                fDelayedTasksMaxNextTickTime = field("delayedTasksMaxNextTickTime");
            }
            fDelayedTasksMaxNextTickTime.setLong(server, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] write delayedTasksMaxNextTickTime failed", e);
        }
    }

    /**
     * 维护原版的 {@code nextTickTime} 字段（毫秒时刻）。
     *
     * <h2>为什么必须维护它</h2>
     *
     * 本模组的主循环用自己的 {@code LoopPacer} 时钟驱动，原本不碰原版这个字段，
     * 结果在 1.19.2 上触发两个问题：
     *
     * <ol>
     *   <li>{@code DedicatedServer} 的服务器看门狗读的就是
     *       {@code getNextTickTime()}：它计算 {@code now - nextTickTime}，
     *       一旦超过 {@code maxTickTime}（60 秒）就判定服务器崩溃并<b>强制关闭</b>。
     *       我们从不更新该值，于是它会一直增长——
     *       实测日志：{@code A single server tick took 60.00 seconds}。</li>
     *   <li>原版 {@code waitUntilNextTick()} 的轮询条件也依赖它（见上文的说明）。</li>
     * </ol>
     *
     * <p>把它设成「现在 + 一个周期」，看门狗看到的「距上次 tick 的时间」就始终正常，
     * 而低速率（例如 1 TPS）也不会被误判为崩溃。
     *
     * <p>1.20.1 上原本没有暴露这个问题，但那只是因为它恰好没触发 60 秒阈值；
     * 维护该字段在任何版本上都是正确的做法。
     */
    private static void setNextTickTime(MinecraftServer server, long millis) {
        try {
            if (fNextTickTime == null) {
                fNextTickTime = field("nextTickTime");
            }
            fNextTickTime.setLong(server, millis);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] write nextTickTime failed", e);
        }
    }

    /** 单轮最多排空多少个任务：防止「自我重排队的任务」把一轮主循环卡住。 */
    private static final int MAX_TASKS_PER_ROUND = 100_000;

    /**
     * 排空服务器待执行任务队列（原版 {@code managedBlock} 在等待期间做的事）。
     *
     * <h2>为什么必须由主循环自己做</h2>
     *
     * 原版的主循环把排空任务交给了 {@code waitUntilNextTick()} 里的
     * {@code managedBlock(haveTime)}：它的循环是「<b>队列里有任务就立刻执行</b>，
     * 然后重新判断是否到点」。本模组为了摆脱原版计时字段，把等待换成了
     * {@code parkNanos}，如果只睡不排空，队列里的任务就再也没有执行时机。
     *
     * <h2>症状</h2>
     *
     * <b>右键交互（开熔炉/箱子）没有任何反应，而服务器看起来一切正常</b>：
     * 方块可以放（纯客户端预测），但交互要等服务端把
     * {@code ServerboundUseItemOnPacket} 对应的任务执行完才会回包。
     * 1.19.2 上实测过同样的症状，那里的 {@code waitUntilNextTick} 末尾有一句
     * {@code while (server.pollTask()) {}}；1.18.2 移植成纳秒节拍器时把这句丢了。
     *
     * <p>数值对照（无头复现，每 4ms 投递一个任务，跑 6 秒）：
     * <pre>
     * NO-DRAIN : tasks submitted=1275  executed=0     queued=1275   ← 完全饿死
     * DRAIN    : tasks submitted=1287  executed=1273  queued=14
     * </pre>
     *
     * <p>调用时机很关键：必须在 {@code LoopPacer.allowTick()} <b>推进截止时刻之后</b>
     * 才排空，因为 {@code pollTask()} → {@code shouldRun()} → {@code haveTime()}
     * 看的就是节拍器的剩余时间；只有在刻刚被放行时它才为真。
     */
    private static void drainPendingTasks(MinecraftServer server) {
        ServerTickController ctrl = TickControlAccessHolder.controller(server);
        for (int i = 0; i < MAX_TASKS_PER_ROUND; i++) {
            if (!ctrl.haveTimeNow() || !server.pollTask()) {
                return;
            }
        }
    }

    private static float averageTickTime(MinecraftServer server) {
        try {
            if (fAverageTickTime == null) {
                fAverageTickTime = field("averageTickTime");
            }
            return fAverageTickTime.getFloat(server);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] read averageTickTime failed", e);
        }
    }

    private static ProfilerFiller profiler(MinecraftServer server) {
        try {
            if (fProfiler == null) {
                fProfiler = field("profiler");
            }
            return (ProfilerFiller) fProfiler.get(server);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] read profiler failed", e);
        }
    }

    private static org.slf4j.Logger logger() {
        try {
            if (fLogger == null) {
                Field f = MinecraftServer.class.getDeclaredField("LOGGER");
                f.setAccessible(true);
                fLogger = f;
            }
            return (org.slf4j.Logger) fLogger.get(null);
        } catch (ReflectiveOperationException e) {
            // 不应发生；绝不因为日志失败而崩
            return org.slf4j.LoggerFactory.getLogger(MinecraftServer.class);
        }
    }

    private static boolean initServer(MinecraftServer server) throws java.io.IOException {
        try {
            if (mInitServer == null) {
                mInitServer = method("initServer");
            }
            return (Boolean) mInitServer.invoke(server);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.io.IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("[tickcontrol] initServer failed", cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] invoke initServer failed", e);
        }
    }

    /**
     * 等到下一个游戏刻的时刻。
     *
     * <h2>为什么不用原版的 {@code MinecraftServer.waitUntilNextTick()}</h2>
     *
     * 原版那个方法是 {@code managedBlock(() -> !haveTime())} 的<b>轮询循环</b>，
     * 退出条件依赖原版自己的计时字段（{@code nextTickTime} /
     * {@code mayHaveDelayedTasks} / {@code delayedTasksMaxNextTickTime}）。
     * 本模组的主循环用自己的 {@code LoopPacer} 时钟驱动，<b>从不更新原版那些字段</b>，
     * 于是轮询条件永远为真 —— 1.19.2 上实测表现为服务器线程死循环，
     * 看门狗随后报「单刻耗时 60 秒」：
     * <pre>
     * at MinecraftServer.waitUntilNextTick(MinecraftServer.java:726)
     * at ServerLoop.waitUntilNextTick(ServerLoop.java:258)
     * </pre>
     *
     * <p>上游 1.21.1 的主循环本来就是自己睡（{@code Thread.sleep} /
     * {@code LockSupport}），并不复用 {@code waitUntilNextTick}。
     * 这里照做：仍然把「还要等多久」交给 {@code LoopPacer} 计算，
     * 只用 {@link java.util.concurrent.locks.LockSupport#parkNanos} 真正睡下去，
     * 因此不再依赖任何原版计时状态。
     *
     * <p><b>但「自己睡」必须补回 {@code managedBlock} 的另一半职责：排空任务队列。</b>
     * 见 {@link #drainPendingTasks}——漏掉它会让右键开熔炉/箱子永远没反应。
     */
    private static void waitUntilNextTick(MinecraftServer server) {
        ServerTickController ctrl = TickControlAccessHolder.controller(server);
        while (true) {
            long remainingNanos = ctrl.timeRemainingNanos();
            if (remainingNanos <= 0L) {
                break;
            }
            // 分片睡眠（最多 1ms）：既让出 CPU，也能及时响应速率变化/关服。
            java.util.concurrent.locks.LockSupport.parkNanos(
                    Math.min(remainingNanos, 1_000_000L));
        }
        // 不能只睡不干活：原版 waitUntilNextTick() 的 managedBlock 会在等待期间
        // 持续把任务队列排空，本方法既然替换了它，就必须自己补上这一步，
        // 否则右键开熔炉这类"要等服务端任务执行完才回包"的交互永远不会完成。
        drainPendingTasks(server);
    }

    /**
     * 生成崩溃报告文件名用的时间戳。
     *
     * <p>原版用的是 {@code Util.getFilenameFormattedDateTime()}，但它在 1.18.2 还不存在
     * （编译报「找不到符号」）。各版本通用的做法是自己格式化。
     */
    private static String timestampForFilename() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"));
    }

    /**
     * 通知服务器发生了崩溃并保存报告。
     *
     * <p>{@code MinecraftServer.onServerCrash(CrashReport)} 在 1.18.2 是
     * {@code protected}（1.19+ 才是 public），直接调用编译不过，因此走反射。
     * 找不到该方法时只记日志，不影响随后的 {@code stopServer()}。
     */
    private static void onServerCrash(MinecraftServer server, CrashReport report) {
        try {
            if (mOnServerCrash == null) {
                mOnServerCrash = MinecraftServer.class.getDeclaredMethod(
                        "onServerCrash", CrashReport.class);
                mOnServerCrash.setAccessible(true);
            }
            mOnServerCrash.invoke(server, report);
        } catch (Throwable t) {
            logger().error("[tickcontrol] could not call onServerCrash", t);
        }
    }

    private static Method mOnServerCrash;

    private static void startMetricsRecordingTick(MinecraftServer server) {
        try {
            if (mStartMetricsRecordingTick == null) {
                mStartMetricsRecordingTick = method("startMetricsRecordingTick");
            }
            mStartMetricsRecordingTick.invoke(server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[tickcontrol] invoke startMetricsRecordingTick failed", e);
        }
    }

    private static void endMetricsRecordingTick(MinecraftServer server) {
        try {
            if (mEndMetricsRecordingTick == null) {
                mEndMetricsRecordingTick = method("endMetricsRecordingTick");
            }
            mEndMetricsRecordingTick.invoke(server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("[tickcontrol] invoke endMetricsRecordingTick failed", e);
        }
    }

    /**
     * 构造崩溃报告。
     *
     * <p>1.20.1 的 {@code constructOrExtractCrashReport} 是 {@code static private}，
     * 且它只是在 throwable 已经携带崩溃报告时复用；这里直接构造即可，
     * 少一处易错的反射（该方法在两种名字域下的签名解析曾经出过错）。
     */
    private static CrashReport constructCrashReport(Throwable throwable) {
        return new CrashReport("Exception in server tick loop", throwable);
    }

    // ==================================================================
    // 主循环（1.21.1 runServer 的逐行对应）
    // ==================================================================

    /**
     * 顶掉 1.20.1 的 {@code runServer}，换成 1.21.1 的逻辑。
     *
     * <p>调用方（两个名字域的 Mixin）负责在 {@code @At("HEAD")} 注入、
     * 并在本方法返回后 {@code ci.cancel()}。
     */
    public static void runServer(MinecraftServer server) throws java.io.IOException {
        // 从这里开始就是「本模组真的接管了主循环」。下面的 logger 一行是唯一
        // 可靠的证据来源：曾经用 System.out 打印过同样的意思，但正式客户端的
        // stdout 不进 logs/*.log，于是「日志里没有这行」被误判成「注入没生效」。
        ServerTickController controller = TickControlAccessHolder.controller(server);

        // ---- 1.21.1: if (!this.initServer()) throw new IllegalStateException(...) ----
        if (!initServer(server)) {
            throw new IllegalStateException("Failed to initialize server");
        }
        net.minecraftforge.server.ServerLifecycleHooks.handleServerStarted(server);

        // ---- 1.21.1: this.nextTickTimeNanos = Util.getNanos(); ----
        controller.resetClock();

        // 接管成功的第一条证据：一定要用 logger()，不能 System.out ——
        // 正式客户端的 stdout 不会被写进 logs/*.log，用 println 会得到
        // 「日志里什么都没有」这种最误导的现象（会被当成主循环没被接管）。
        logger().info("[tickcontrol] runServer takeover: loop entered"
                + " tickRate=" + controller.tickRate()
                + " periodMillis=" + controller.loopPeriodMillis()
                + " levelTicks=" + controller.levelTicks());
        tickServerCalls = 0L;
        watchdogLastNanos = System.nanoTime();
        watchdogLastIterations = controller.loopIterations();
        watchdogLastTickServerCalls = 0L;
        stallReported = false;
        exitReason = "loop still running (unexpected)";

        try {
            // ---- 1.21.1: while (this.running) { ... } ----
            while (isRunning(server)) {
                // 1.21.1: i = this.tickRateManager.nanosecondsPerTick()
                long periodNanos = controller.periodNanos();

                // 过载提示（保留 1.21.1 的行为与文案）
                long behindNanos = controller.behindNanos();
                if (behindNanos > OVERLOADED_THRESHOLD_NANOS + 20L * periodNanos
                        && controller.nanosSinceOverloadWarning()
                                >= OVERLOADED_WARNING_INTERVAL_NANOS + 100L * periodNanos) {
                    long ticks = behindNanos / periodNanos;
                    logger().warn(
                            "Can't keep up! Is the server overloaded? Running {}ms or {} ticks behind",
                            behindNanos / TimeUtil.NANOSECONDS_PER_MILLISECOND, ticks);
                    controller.noteOverloadWarning();
                }



                // ---- 1.21.1: 冲刺判定与 tickServer ----
                // 上游主循环：
                //   if (!isPaused() && tickRateManager.isSprinting()
                //           && tickRateManager.checkShouldSprintThisTick()) {
                //       i = 0L;                                   // 一刻视作 0 纳秒 → 不等待
                //       this.nextTickTimeNanos = Util.getNanos();
                //   }
                //   this.tickServer(flag ? () -> false : this::haveTime);
                //   ...
                //   if (flag) this.tickRateManager.endTickWork();
                // 也就是说冲刺期间每轮都立即 tick、完全不等待；
                // 此处等价实现：冲刺时 checkShouldSprintThisTick() 为 true，
                // 且 LoopPacer.timeRemainingMillis() 恒报「还有时间」，
                // 于是 waitUntilNextTick() 不会阻塞。
                int runs = 0;
                boolean sprint = controller.checkShouldSprintThisTick();
                if (sprint || controller.allowTick()) {
                    // ---- 先排空任务队列，再跑这一轮的游戏刻 ----
                    // allowTick() 刚把截止时刻推进了一个周期，此刻 haveTime() 为真，
                    // pollTask() 才会真的执行任务；放到等待之后做就晚了（那时剩余时间已 <= 0）。
                    drainPendingTasks(server);

                    // ---- 1.21.1: startMetricsRecordingTick(); profiler.push("tick"); ----
                    // 与后面的 pop/endMetricsRecordingTick 严格配对：只有真正跑
                    // tickServer 的轮次才 push，否则会破坏原版 profiler/metrics 状态。
                    startMetricsRecordingTick(server);
                    profiler(server).push("tick");
                    tickServerCalls++;
                    server.tickServer(controller::haveTimeNow);
                    runs = 1;
                }
                if (sprint) {
                    controller.endTickWork();
                }

                // ---- 存活看门狗：循环在转，但游戏刻没在推进 ----
                long watchdogNow = System.nanoTime();
                if (watchdogNow - watchdogLastNanos >= WATCHDOG_INTERVAL_NANOS) {
                    long iterDelta = controller.loopIterations() + 1L - watchdogLastIterations;
                    long tickDelta = tickServerCalls - watchdogLastTickServerCalls;
                    if (tickDelta == 0L && !stallReported) {
                        stallReported = true;
                        logger().error("[tickcontrol] STALL: main loop is spinning (iterations +{})"
                                        + " but tickServer has not been entered for {} ms"
                                        + " (tickRate={} periodMillis={} frozen={} sprinting={}"
                                        + " levelTicks={}); the world is neither advancing nor"
                                        + " reaching the chunk-save path until this clears",
                                iterDelta,
                                WATCHDOG_INTERVAL_NANOS / TimeUtil.NANOSECONDS_PER_MILLISECOND,
                                controller.tickRate(),
                                controller.loopPeriodMillis(),
                                controller.isFrozen(),
                                controller.isSprinting(),
                                controller.levelTicks());
                    }
                    watchdogLastNanos = watchdogNow;
                    watchdogLastIterations = controller.loopIterations() + 1L;
                    watchdogLastTickServerCalls = tickServerCalls;
                }

                // ---- 1.21.1: profiler.popPush("nextTickWait"); mayHaveDelayedTasks = true; ----
                profiler(server).popPush("nextTickWait");
                setMayHaveDelayedTasks(server, true);

                // ---- 1.21.1: delayedTasksMaxNextTickTimeNanos = max(now + i, nextTickTimeNanos) ----
                setDelayedTasksMaxNextTickTime(server, Math.max(
                        System.currentTimeMillis() + controller.loopPeriodMillis(),
                        controller.deadlineMillis()));

                // ---- 维护原版 nextTickTime ----
                // 看门狗（DedicatedServer）就是靠 now - getNextTickTime() 判断服务器是否卡死，
                // 超过 60 秒就强制关服。我们的循环不更新它，低速率下会被误判崩溃
                // （实测：A single server tick took 60.00 seconds）。
                setNextTickTime(server,
                        System.currentTimeMillis() + controller.loopPeriodMillis());

                // ---- 1.21.1: this.waitUntilNextTick(); ----
                // 不用原版那个方法：它的轮询条件依赖原版计时字段，而本循环不驱动那些字段，
                // 会导致死循环（1.19.2 实测）。这里自己睡，见 waitUntilNextTick 的说明。
                waitUntilNextTick(server);

                // ---- 1.21.1: profiler.pop(); endMetricsRecordingTick(); ----
                // 与上面的 push/startMetrics 配对，只在真正 tick 过的轮次执行。
                if (runs == 1) {
                    profiler(server).pop();
                    endMetricsRecordingTick(server);
                }

                // ---- 1.21.1: this.isReady = true; ----
                setReady(server);

                // ---- 1.21.1: JvmProfiler.INSTANCE.onServerTick(smoothedTickTimeMillis); ----
                JvmProfiler.INSTANCE.onServerTick(averageTickTime(server));

                // ---- 实测 TPS / MSPT + 同步给 Carpet 系 HUD ----
                // Carpet 的 Forge 1.20.1 版自己也想整个替换 runServer（它的注入点位于
                // 本方法中部，因为我们在 HEAD 就 cancel 了，所以永远到不了），
                // 于是它的 tickRateManager 一直停在初始的 20 TPS，导致
                // 「MSPT 会变、TPS 锁 20」。这里按 Carpet 自己的显示节奏
                // （每 20 刻）把实测值写回去。
                // BoccHUD / MiniHUD 直接解析 Carpet 发出的那条 TPS 消息，
                // 所以同步了 Carpet 就等于同步了它们。
                //
                // 注意：必须在<b>真正完成了游戏刻</b>（runs == 1）时才采样。
                // 主循环每轮不一定 tick——1 TPS 目标下每秒要转几万轮、只 tick 一次，
                // 若每轮都采样，实测 TPS 会算出数百万这种荒谬值
                // （实测踩过：600 万 TPS）。
                if (runs == 1) {
                    controller.recordTickCompletion();
                    if (++tickcontrol$hudTicker % 20 == 0) {
                        CarpetHudBridge.sync(server, controller.measuredMillisPerTick());
                    }
                }

                // ---- 冲刺结束：发送报告，对应上游 finishTickSprint 里的
                //      server.createCommandSourceStack().sendSuccess(
                //          translatable("commands.tick.sprint.report", tps, mspt)) ----
                if (controller.consumeSprintReport()) {
                    int sprintTps = (int) Math.round(controller.lastSprintTps());
                    String mspt = String.format(java.util.Locale.ROOT, "%.2f",
                            controller.lastSprintMillisPerTick());
                    // 必须先清掉报告标志再发消息：sprintReportPending() 只看
                    // lastSprintTps > 0，若不清，冲刺结束后主循环每轮都会再报一次
                    // （每秒上万轮 → 日志被刷到数百万行 / 近 1 GB）。
                    controller.clearSprintReport();
                    // 诊断：确认冲刺报告的语言键在「发送时刻」能否被解析。
                    // 这里踩过两个坑，都记录在此：
                    //   1) 键名少了 tickcontrol. 前缀（写成了原版的
                    //      commands.tick.sprint.report）→ 客户端聊天框直接显示
                    //      原始键 `[commands.tick.sprint.report] [5121, 0.20]`；
                    //   2) 1.19.2 客户端把这条消息显示成原始键，而启动时的探针
                    //      却是 resolved=true —— 说明两者看到的语言表不同。
                    // 因此这里统一用 TickLang 常量，并直接查一次语言表。
                    try {
                        net.minecraft.locale.Language lang =
                                net.minecraft.locale.Language.getInstance();
                        String probeKey = com.tamamo.tickcontrol.command.TickLang.SPRINT_REPORT;
                        logger().info("[tickcontrol] sprint-report lang: key=" + probeKey
                                + " resolved=" + !probeKey.equals(lang.getOrDefault(probeKey))
                                + " value=" + lang.getOrDefault(probeKey));
                    } catch (Throwable ignored) {
                        // 诊断失败不影响功能
                    }
                    VersionAdapterHolder.get().sendSuccess(
                            server.createCommandSourceStack(),
                            () -> VersionAdapterHolder.get().translatable(
                                    com.tamamo.tickcontrol.command.TickLang.SPRINT_REPORT,
                                    sprintTps, mspt),
                            true);
                }

                if (DEBUG) {
                    long nowMs = System.currentTimeMillis();
                    if (debugLastMs == 0L) {
                        debugLastMs = nowMs;
                        debugLastIterations = controller.loopIterations();
                    } else if (nowMs - debugLastMs >= 1000L) {
                        long iterDelta = controller.loopIterations() - debugLastIterations;
                        // 这里也必须用 logger()：System.out 在正式客户端的日志文件里看不到。
                        logger().info("[tickcontrol-debug] loop: rate=" + controller.tickRate()
                                + " periodNanos=" + periodNanos
                                + " runsThisIteration=" + runs
                                + " iterationsPerSecond=" + iterDelta
                                + " tickServerCalls=" + tickServerCalls
                                + " granted=" + controller.grantedTicks()
                                + " levelTicks=" + controller.levelTicks());
                        debugLastMs = nowMs;
                        debugLastIterations = controller.loopIterations();
                    }
                }
            }

            // ---- 1.21.1: handleServerStopping ----
            exitReason = "running=false (normal shutdown)";
            net.minecraftforge.server.ServerLifecycleHooks.handleServerStopping(server);
        } catch (Throwable throwable1) {
            exitReason = "unexpected exception: " + throwable1;
            logger().error("Encountered an unexpected exception", throwable1);
            CrashReport crashreport = constructCrashReport(throwable1);
            server.fillSystemReport(crashreport.getSystemReport());
            // 1.20.1 的 CrashReport 只有 saveToFile(File)（1.21.1 才有 Path + ReportType 重载）
            //
            // 时间戳不能直接用 Util.getFilenameFormattedDateTime()：它在 1.18.2 还不存在
            // （编译报「找不到符号」）。这里自己按同样的格式生成，各版本通用。
            java.io.File file = new java.io.File(server.getServerDirectory(),
                    "crash-reports/crash-" + timestampForFilename() + "-server.txt");
            if (crashreport.saveToFile(file)) {
                logger().error("This crash report has been saved to: {}", file.getAbsolutePath());
            } else {
                logger().error("We were unable to save this crash report to disk.");
            }
            onServerCrash(server, crashreport);
        } finally {
            // 停服路径的收尾日志：把「谁触发了退出」「跑了多少刻」「stopServer 有没有
            // 正常返回」三件事写进日志。方块不落盘的排查全靠这三条。
            logger().info("[tickcontrol] runServer exiting: reason=" + exitReason
                    + " tickServerCalls=" + tickServerCalls
                    + " levelTicks=" + controller.levelTicks()
                    + " -> stopServer()");
            try {
                server.stopServer();
                logger().info("[tickcontrol] stopServer() returned normally; worlds flushed to disk");
            } catch (Throwable throwable) {
                logger().error("Exception stopping the server", throwable);
            } finally {
                server.onServerExit();
            }
        }
    }

    /** 每刻开头：刷新「本刻游戏内容是否推进」，并采集每刻耗时样本。 */
    public static void prepareTick(MinecraftServer server) {
        ServerTickController controller = TickControlAccessHolder.controller(server);
        controller.prepareTick();
        controller.stats().replaceSamples(tickTimes(server));
        SelfTest.tick(server);
    }

    /**
     * 世界门控：对应 1.21.1 {@code ServerLevel.tick} 里的
     * {@code tickRateManager.runsNormally()}。
     *
     * <p>由两个变体的 {@code @Redirect(method = "tickChildren")} 转调。
     */
    public static void tickLevel(net.minecraft.server.level.ServerLevel level,
                                 java.util.function.BooleanSupplier haveTime) {
        MinecraftServer server = level.getServer();
        ServerTickController controller = TickControlAccessHolder.controller(server);
        if (controller.shouldTickLevels()) {
            level.tick(haveTime);
            controller.noteLevelTick();
        }
    }
}
