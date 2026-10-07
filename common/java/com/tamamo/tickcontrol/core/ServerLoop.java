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

    /** 读取原版 {@code nextTickTime}（毫秒时刻）。 */
    private static long getNextTickTime(MinecraftServer server) {
        try {
            if (fNextTickTime == null) {
                fNextTickTime = field("nextTickTime");
            }
            return fNextTickTime.getLong(server);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("[tickcontrol] read nextTickTime failed", e);
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
     * 原版那个方法是 {@code managedBlock(() -> !haveTime())}。而
     * {@code haveTime()} 的实际条件是：
     * <pre>
     *   runningTask()
     *   || Util.getMillis() &lt; (mayHaveDelayedTasks
     *                           ? delayedTasksMaxNextTickTime : nextTickTime)
     * </pre>
     * 本模组的主循环每轮都会把 {@code delayedTasksMaxNextTickTime} 设成
     * 「现在 + 一个周期」且 {@code mayHaveDelayedTasks = true}，而
     * {@code managedBlock} 的循环是「<b>队列里有任务就立刻执行</b>，然后重新判断」。
     * 于是长等待期间任务一到就重启等待计时：
     *
     * <ul>
     *   <li>低速率（周期 1 秒）下任务被无限处理、游戏刻永远不到点 ——
     *       表现为「服务器在跑，但方块交互（例如右键熔炉）没有反应」；</li>
     *   <li>更早的版本里连 {@code nextTickTime} 都不维护，条件恒为真，
     *       直接死循环，看门狗报「单刻耗时 60 秒」。</li>
     * </ul>
     *
     * <p>上游 1.21.1 的主循环本来就是自己睡，不复用 {@code waitUntilNextTick()}。
     * 这里照做：<b>退出条件用一个固定的绝对时刻</b>（{@code nextTickTime}，
     * 上面刚维护过），任务不会重置它；睡完一轮后把排队的任务跑一遍，
     * 再回到主循环 —— 既不会饿死游戏刻，也不会饿死任务。
     */
    private static void waitUntilNextTick(MinecraftServer server) {
        long deadlineMillis = getNextTickTime(server);
        while (System.currentTimeMillis() < deadlineMillis) {
            long remainingNanos =
                    (deadlineMillis - System.currentTimeMillis()) * 1_000_000L;
            if (remainingNanos <= 0L) {
                break;
            }
            // 分片睡眠（最多 1ms）：让出 CPU，也保证速率变化/关服能被及时响应
            java.util.concurrent.locks.LockSupport.parkNanos(
                    Math.min(remainingNanos, 1_000_000L));
        }
        // 等待结束后把所有排队任务跑干净，避免它们被推给下一轮
        while (server.pollTask()) {
            // pollTask() 内部已执行任务
        }
    }

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
        ServerTickController controller = TickControlAccessHolder.controller(server);

        // ---- 1.21.1: if (!this.initServer()) throw new IllegalStateException(...) ----
        if (!initServer(server)) {
            throw new IllegalStateException("Failed to initialize server");
        }
        net.minecraftforge.server.ServerLifecycleHooks.handleServerStarted(server);

        // ---- 1.21.1: this.nextTickTimeNanos = Util.getNanos(); ----
        controller.resetClock();

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

                int runs = 0;
                boolean sprint = controller.checkShouldSprintThisTick();
                if (sprint || controller.allowTick()) {
                    // ---- 1.21.1: startMetricsRecordingTick(); profiler.push("tick"); ----
                    // 放在这里而不是循环开头：只有真正要跑 tickServer 时才 push，
                    // 与后面的 pop/endMetricsRecordingTick 严格配对，
                    // 否则会破坏原版的 profiler/metrics 状态。
                    startMetricsRecordingTick(server);
                    profiler(server).push("tick");
                    server.tickServer(controller::haveTimeNow);
                    runs = 1;
                }
                if (sprint) {
                    controller.endTickWork();
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
                //
                // 冲刺期间必须置 0（= 立即到点）：冲刺不该等待，否则下面的等待循环
                // 会按目标周期把冲刺拖慢——实测 sprint 只跑出 1 TPS（43 刻 / 43 秒）。
                setNextTickTime(server, controller.isSprinting()
                        ? 0L
                        : System.currentTimeMillis() + controller.loopPeriodMillis());

                // ---- 1.21.1: this.waitUntilNextTick(); ----
                // 不用原版的 waitUntilNextTick()：它是
                //   managedBlock(() -> !haveTime())
                // 而 haveTime() 在 mayHaveDelayedTasks 为真时比较的是
                // delayedTasksMaxNextTickTime。由于该值每轮都会被设成
                // 「现在 + 一个周期」，只要期间有任务到达，等待计时就会不断重置——
                // 低速率下任务被无限处理、游戏刻永远不到点，表现为
                // 「服务器在跑但方块交互（例如右键熔炉）没反应」。
                // 上游 1.21.1 本来就是自己睡，这里照做：退出条件用固定的
                // nextTickTime（下面刚维护过），不会被任务重置。
                waitUntilNextTick(server);

                // ---- 1.21.1: profiler.pop(); endMetricsRecordingTick(); ----
                // 必须与上面的 startMetricsRecordingTick()/push 配对，因此只在
                // 真正跑过 tickServer 的轮次里执行。
                //
                // 踩过的坑：主循环并不每轮都 tick（速率限制下要多轮才放行一次），
                // 若每轮都 pop/endMetrics，就会出现「没 start 过却 end」的不配对调用，
                // 破坏原版的 metrics/profiler 状态。原版里这一对永远是配对的。
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
                    // 踩过的坑：1.19.2 客户端把这条消息显示成原始键
                    // commands.tick.sprint.report，而启动时的探针却是 resolved=true，
                    // 说明两者看到的语言表不同——这里直接查一次。
                    try {
                        net.minecraft.locale.Language lang =
                                net.minecraft.locale.Language.getInstance();
                        String probeKey = com.tamamo.tickcontrol.command.TickLang.SPRINT_REPORT;
                        System.out.println("[tickcontrol] sprint-report lang: resolved="
                                + !probeKey.equals(lang.getOrDefault(probeKey))
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
                        System.out.println("[tickcontrol-debug] loop: rate=" + controller.tickRate()
                                + " periodNanos=" + periodNanos
                                + " runsThisIteration=" + runs
                                + " iterationsPerSecond=" + iterDelta
                                + " granted=" + controller.grantedTicks()
                                + " levelTicks=" + controller.levelTicks());
                        debugLastMs = nowMs;
                        debugLastIterations = controller.loopIterations();
                    }
                }
            }

            // ---- 1.21.1: handleServerStopping ----
            net.minecraftforge.server.ServerLifecycleHooks.handleServerStopping(server);
        } catch (Throwable throwable1) {
            logger().error("Encountered an unexpected exception", throwable1);
            CrashReport crashreport = constructCrashReport(throwable1);
            server.fillSystemReport(crashreport.getSystemReport());
            // 1.20.1 的 CrashReport 只有 saveToFile(File)（1.21.1 才有 Path + ReportType 重载）
            java.io.File file = new java.io.File(server.getServerDirectory(),
                    "crash-reports/crash-" + Util.getFilenameFormattedDateTime() + "-server.txt");
            if (crashreport.saveToFile(file)) {
                logger().error("This crash report has been saved to: {}", file.getAbsolutePath());
            } else {
                logger().error("We were unable to save this crash report to disk.");
            }
            server.onServerCrash(crashreport);
        } finally {
            try {
                server.stopServer();
            } catch (Throwable throwable) {
                logger().error("Exception stopping the server", throwable);
            } finally {
                server.onServerExit();
            }
        }
    }

    /**
     * 客户端环境粒子是否应当被抑制。
     *
     * <p>由服务器刻在 {@link #prepareTick} 里写入,由客户端 Mixin 读取 ——
     * 客户端代码拿不到服务端控制器,所以用一个静态标志传递。
     *
     * <p>{@code volatile} 是必要的:集成服务器与客户端虽在同进程,但粒子生成发生在
     * 渲染线程,不保证同一线程可见性。
     */
    private static volatile boolean frozenForClientFx;

    /**
     * 冻结时是否应当抑制客户端<b>环境粒子</b>(熔炉火焰/烟、火把、岩浆、传送门)。
     *
     * <h2>为什么服务端门控管不到它</h2>
     *
     * <p>服务端门控刻意不碰客户端:冻住客户端会让玩家自己的挖掘/放置失去反馈。
     * 但环境粒子是<b>客户端自己按帧生成的</b>,与服务器刻无关,于是冻结后熔炉照样冒烟
     * —— 用户先在 1.12.2 上发现,随后确认 1.16.5~1.20.1 同样存在。
     *
     * <h2>为什么拦一个方法就够</h2>
     *
     * <p>已用 ASM 按<b>方法形状</b>(而非名字)扫过 1.19.2 的 srg jar:每个方块的环境粒子钩子
     * {@code Block.m_214162_(BlockState, Level, BlockPos, RandomSource)V} 在客户端侧
     * <b>只有一处调用者</b> —— {@code ClientLevel.m_233612_}。其余是 {@code StairBlock}
     * 调用自己的 {@code super} 以及服务端变体 {@code m_213898_}/{@code m_213897_}。
     *
     * <p><b>玩家自己的粒子不受影响</b>:那类粒子走 {@code Level.addParticle},不经过这里。
     */
    public static boolean shouldSuppressAmbientParticles() {
        return frozenForClientFx;
    }

    /** 每刻开头：刷新「本刻游戏内容是否推进」，并采集每刻耗时样本。 */
    public static void prepareTick(MinecraftServer server) {
        ServerTickController controller = TickControlAccessHolder.controller(server);
        controller.prepareTick();
        // 把"本刻游戏内容是否推进"同步给客户端可见的标志(见 shouldSuppressAmbientParticles)。
        // 必须在 prepareTick() 之后读,否则拿到的是上一刻的取值。
        frozenForClientFx = !controller.runsNormally();
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
