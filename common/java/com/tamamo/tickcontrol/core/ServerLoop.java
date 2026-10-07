
package com.tamamo.tickcontrol.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.crash.CrashReport;
import net.minecraft.util.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.profiler.IProfiler;

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

    /**
     * 用 Log4j,不用 slf4j。
     *
     * <p>1.16.5 的运行时类路径上没有 {@code org.slf4j},而日志调用位于主循环最热
     * 的路径上——用 slf4j 会抛 {@code NoClassDefFoundError} 并把进入存档卡死在
     * 100%。详见 {@link #logger()}。
     */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("tickcontrol");


    /** 给 Carpet 系 HUD 同步实测值的节拍计数（每 20 刻一次）。 */
    private static long tickcontrol$hudTicker;

    private static long debugLastMs;
    private static long debugLastIterations;

    /** 1.20.1 的过载阈值（毫秒）折算成纳秒，对应 1.21.1 的 OVERLOADED_THRESHOLD_NANOS。 */
    private static final long OVERLOADED_THRESHOLD_NANOS = 1_000_000L * 2000L;
    private static final long OVERLOADED_WARNING_INTERVAL_NANOS =
            1_000_000L * 15000L;

    // ------------------------------------------------------------------
    // 反射句柄（懒加载并缓存）
    // ------------------------------------------------------------------

    /** tickTimes 取不到时返回它,避免调用方为 null 再判一次。 */
    private static final long[] EMPTY_TICK_TIMES = new long[0];

    private static Field fRunning;
    private static Field fTickTimes;
    private static Field fIsReady;
    private static Field fMayHaveDelayedTasks;
    private static Field fDelayedTasksMaxNextTickTime;
    /** 原版 nextTickTime（毫秒）；看门狗读它，必须维护。 */
    private static Field fNextTickTime;
    private static Field fAverageTickTime;
    private static Field fProfiler;
    private static Method mInitServer;
    private static Method mWaitUntilNextTick;
    private static Method mStartMetricsRecordingTick;
    /** 已确认查不到就不再重试,避免每刻都打一条 error 日志。 */
    private static boolean mStartMetricsMissing;
    private static Method mEndMetricsRecordingTick;
    /** 见 mStartMetricsMissing。 */
    private static boolean mEndMetricsMissing;

    /**
     * 成员名在开发环境是 MCP 官方名、生产环境是 SRG 名（{@code initServer} vs
     * {@code m_7038_}）。下面两张表让 {@link #field}/{@link #method} 两种名字都能解析，
     * 因此本类不需要知道当前处于哪种名字域。
     *
     * <p><b>为什么用静态初始化块而不是 {@code Map.of(...)}</b>：{@code Map.of} 是
     * Java 9 才加入的静态工厂，而 1.16.5 的目标语言级别是 Java 8
     * （{@code options.release = 8}），编译时会直接报找不到符号。
     * 本文件是从 1.19.2 基线移植下来的，那边用 {@code Map.of} 没问题。
     */
    private static final java.util.Map<String, String> FIELD_ALTERNATES = new java.util.HashMap<>();

    private static final java.util.Map<String, String> METHOD_ALTERNATES = new java.util.HashMap<>();

    static {
        // ------------------------------------------------------------------
        // 这些是 1.16.5 的 SRG 名，逐个用 javap 从「运行时真正加载的那个 jar」
        // (E:\.minecraft\libraries\net\minecraftforge\forge\1.16.5-36.2.42\
        //  forge-1.16.5-36.2.42-client.jar) 读出来的。
        //
        // 之前这里填的是 1.19.2 的 m_*/f_* 名，而 1.16.5 的 SRG 域用的是老的
        // func_*/field_* 形式 —— 表中 14 个名字在运行时全部不存在，导致进入存档时
        //     IllegalStateException: [tickcontrol] method not found: initServer
        // 并卡死。
        //
        // 教训与 README 记录的一致：反射的名字域必须对着**运行时 jar** 核验，
        // 不能对着 ForgeGradle 的开发产物推断。
        // ------------------------------------------------------------------
        FIELD_ALTERNATES.put("serverRunning", "field_71317_u");
        FIELD_ALTERNATES.put("serverIsRunning", "field_71296_Q");
        FIELD_ALTERNATES.put("serverStopped", "field_71316_v");
        FIELD_ALTERNATES.put("tickTimeArray", "field_71311_j");
        FIELD_ALTERNATES.put("tickTime", "field_211152_ao");
        FIELD_ALTERNATES.put("runTasksUntil", "field_213213_ab");
        FIELD_ALTERNATES.put("isRunningScheduledTasks", "field_213214_ac");
        FIELD_ALTERNATES.put("serverTime", "field_211151_aa");
        FIELD_ALTERNATES.put("profiler", "field_71304_b");
        FIELD_ALTERNATES.put("LOGGER", "field_147145_h");

        METHOD_ALTERNATES.put("func_71197_b", "func_71197_b");
        METHOD_ALTERNATES.put("func_213202_o", "func_213202_o");
        METHOD_ALTERNATES.put("func_240787_a_", "func_240787_a_");
        METHOD_ALTERNATES.put("func_240790_aQ_", "func_240790_aQ_");
        METHOD_ALTERNATES.put("func_71260_j", "func_71260_j");
        METHOD_ALTERNATES.put("func_71240_o", "func_71240_o");
    }

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

    /**
     * 解析字段,<b>失败返回 {@code null},不抛异常</b>。
     *
     * <p>这里刻意不抛:反射名是版本相关的,一旦某个名字没对上(本项目在 1.16.5 上
     * 就发生过——整张表用的是 1.19.2 的 {@code m_*}/{@code f_*} 名,而 1.16.5 运行时
     * 是 {@code func_*}/{@code field_*}),抛异常会让<b>进入存档时直接卡死</b>。
     * 返回 null 则退化成「该功能不生效」,服务器照常启动,日志里留下一条线索。
     *
     * <p>名字难查是配置/移植错误,但不该以"客户端卡死"的形式呈现给用户。
     */
    private static Field field(String name) {
        for (String candidate : candidates(FIELD_ALTERNATES, name)) {
            try {
                Field f = MinecraftServer.class.getDeclaredField(candidate);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 试下一个候选名
            }
        }
        logger().error("[tickcontrol] field not resolved on this version: {}"
                + " -- the feature depending on it will be inactive", name);
        return null;
    }

    /** 解析方法,失败返回 {@code null}(理由同 {@link #field(String)})。 */
    private static Method method(String name, Class<?>... params) {
        for (String candidate : candidates(METHOD_ALTERNATES, name)) {
            try {
                Method m = MinecraftServer.class.getDeclaredMethod(candidate, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // 试下一个候选名
            }
        }
        logger().error("[tickcontrol] method not resolved on this version: {}"
                + " -- the feature depending on it will be inactive", name);
        return null;
    }

    /** 读一个 boolean 字段;字段不可用时返回 {@code fallback},绝不抛异常。 */
    private static boolean readBoolean(Field cached, String name, MinecraftServer server,
                                       boolean fallback) {
        try {
            Field f = cached;
            if (f == null) {
                f = field(name);
            }
            if (f == null) {
                return fallback;
            }
            return f.getBoolean(server);
        } catch (Throwable t) {
            logger().error("[tickcontrol] reading {} failed", name, t);
            return fallback;
        }
    }

    /** 写一个 boolean 字段;字段不可用时静默跳过,绝不抛异常。 */
    private static void writeBoolean(Field cached, String name, MinecraftServer server,
                                     boolean value) {
        try {
            Field f = cached;
            if (f == null) {
                f = field(name);
            }
            if (f == null) {
                return;
            }
            f.setBoolean(server, value);
        } catch (Throwable t) {
            logger().error("[tickcontrol] writing {} failed", name, t);
        }
    }

    /** 读一个 long 字段;字段不可用时返回 {@code fallback}。 */
    private static long readLong(Field cached, String name, MinecraftServer server,
                                 long fallback) {
        try {
            Field f = cached;
            if (f == null) {
                f = field(name);
            }
            if (f == null) {
                return fallback;
            }
            return f.getLong(server);
        } catch (Throwable t) {
            logger().error("[tickcontrol] reading {} failed", name, t);
            return fallback;
        }
    }

    private static boolean isRunning(MinecraftServer server) {
        // 字段取不到时按"服务器没在跑"处理:主循环会立刻退出,而不是卡死。
        return readBoolean(fRunning, "serverRunning", server, false);
    }

    private static long[] tickTimes(MinecraftServer server) {
        // 只用于统计样本;取不到返回空数组,统计层会当作"无样本"。
        Field f = fTickTimes;
        if (f == null) {
            fTickTimes = f = field("tickTimeArray");
        }
        if (f == null) {
            return EMPTY_TICK_TIMES;
        }
        try {
            long[] times = (long[]) f.get(server);
            return times == null ? EMPTY_TICK_TIMES : times;
        } catch (Throwable t) {
            logger().error("[tickcontrol] reading tickTimeArray failed", t);
            return EMPTY_TICK_TIMES;
        }
    }

    private static void setReady(MinecraftServer server) {
        writeBoolean(fIsReady, "serverIsRunning", server, true);
    }

    private static void setMayHaveDelayedTasks(MinecraftServer server, boolean value) {
        // 这个标志只影响待处理任务的调度时机,取不到就跳过,不影响主循环。
        Field f = fMayHaveDelayedTasks;
        if (f == null) {
            fMayHaveDelayedTasks = f = field("isRunningScheduledTasks");
        }
        writeBoolean(f, "isRunningScheduledTasks", server, value);
    }

    private static void setDelayedTasksMaxNextTickTime(MinecraftServer server, long value) {
        Field f = fDelayedTasksMaxNextTickTime;
        if (f == null) {
            fDelayedTasksMaxNextTickTime = f = field("runTasksUntil");
        }
        if (f == null) {
            return;
        }
        try {
            f.setLong(server, value);
        } catch (Throwable t) {
            logger().error("[tickcontrol] writing runTasksUntil failed", t);
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
        Field f = fNextTickTime;
        if (f == null) {
            fNextTickTime = f = field("serverTime");
        }
        if (f == null) {
            return;
        }
        try {
            f.setLong(server, millis);
        } catch (Throwable t) {
            logger().error("[tickcontrol] writing serverTime failed", t);
        }
    }

    /** 读取原版 {@code nextTickTime}（毫秒时刻）。 */
    private static long getNextTickTime(MinecraftServer server) {
        // 取不到就返回"现在":waitUntilNextTick 会立刻返回,不会空等。
        return readLong(fNextTickTime(), "serverTime", server, System.currentTimeMillis());
    }

    /** 缓存并返回 serverTime 字段句柄,供读写两侧共用。 */
    private static Field fNextTickTime() {
        if (fNextTickTime == null) {
            fNextTickTime = field("serverTime");
        }
        return fNextTickTime;
    }

    private static float averageTickTime(MinecraftServer server) {
        Field f = fAverageTickTime;
        if (f == null) {
            fAverageTickTime = f = field("tickTime");
        }
        if (f == null) {
            return 0.0F;
        }
        try {
            return f.getFloat(server);
        } catch (Throwable t) {
            logger().error("[tickcontrol] reading tickTime failed", t);
            return 0.0F;
        }
    }

    private static IProfiler profiler(MinecraftServer server) {
        // profiler 只用于分段计时,取不到就返回 null,调用方会跳过分段。
        Field f = fProfiler;
        if (f == null) {
            fProfiler = f = field("profiler");
        }
        if (f == null) {
            return null;
        }
        try {
            return (IProfiler) f.get(server);
        } catch (Throwable t) {
            logger().error("[tickcontrol] reading profiler failed", t);
            return null;
        }
    }

    /**
     * 日志器。
     *
     * <p><b>为什么必须用 Log4j,不能用 slf4j</b>:这个调用点在主循环最热的路径上,
     * 而 1.16.5 的运行时类路径上<b>根本没有 {@code org.slf4j}</b>
     * (实测:libraries 里有 log4j-api,一个 slf4j jar 都没有)。
     *
     * <p>之前这里回退到 {@code org.slf4j.LoggerFactory},结果进入存档时抛
     * {@code NoClassDefFoundError},直接卡在「准备生成区域」100%。
     * 教训:兜底路径本身也必须验证依赖存在——否则"防崩"的代码就是新的崩溃点。
     *
     * <p>Log4j 是 1.7.10 / 1.12.2 / 1.16.5 三个版本都稳定可用的 API。
     */
    private static org.apache.logging.log4j.Logger logger() {
        return LOGGER;
    }

    private static boolean initServer(MinecraftServer server) throws java.io.IOException {
        try {
            if (mInitServer == null) {
                mInitServer = method("func_71197_b");
            }
            if (mInitServer == null) {
                // 解析不到就不启动:返回 false 让主循环干净退出,而不是 NPE。
                // method() 已经打过一条 error 指明是哪个名字解析失败。
                return false;
            }
            return (Boolean) mInitServer.invoke(server);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            // NOTE: written with classic instanceof + cast rather than Java 16
            // pattern matching (`cause instanceof IOException io`), because
            // 1.16.5 targets Java 8 bytecode and the Java 8 language level.
            // The 1.19.2 baseline this file was ported from uses the newer form.
            if (cause instanceof java.io.IOException) {
                throw (java.io.IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
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
     *   || net.minecraft.util.Util.milliTime() &lt; (mayHaveDelayedTasks
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
        while (server.driveOne()) {
            // pollTask() 内部已执行任务
        }
    }

    /**
     * 原版 metrics 记录的"开始"钩子——<b>可选装饰,取不到就跳过</b>。
     *
     * <h2>这里踩过的坑,值得写下来</h2>
     *
     * <p>之前这里写 {@code method("func_240787_a_")} 后直接 {@code invoke},进存档立刻 NPE。
     * 两个独立错误叠在一起:
     *
     * <ol>
     *   <li><b>名字对、签名不对</b>。1.16.5 上 {@code func_240787_a_} 的签名是
     *       {@code (IChunkStatusListener)V} —— <b>有参数</b>,而
     *       {@code getDeclaredMethod(name)} 不传参数类型就永远查不到。名字是从 1.19.2
     *       基线带来的,那边它无参;1.16.5 不是。查不到会返回 null(加固生效),
     *       但调用点没判空,于是崩在 invoke。</li>
     *   <li><b>加固只做了一半</b>:字段的调用点我全部判了空,方法调用点没有。</li>
     * </ol>
     *
     * <p>结论:这类<b>不影响正确性的可选钩子</b>,绝不允许因反射失败而崩。
     * 解析不到就安静跳过——少一段 metrics 统计,远好过进不去存档。
     */
    private static void startMetricsRecordingTick(MinecraftServer server) {
        if (mStartMetricsRecordingTick == null && !mStartMetricsMissing) {
            // 不传参数类型:1.16.5 的实际签名带参数,这里注定查不到,于是走下面的跳过分支。
            mStartMetricsRecordingTick = method("func_240787_a_");
            if (mStartMetricsRecordingTick == null) {
                mStartMetricsMissing = true;
            }
        }
        if (mStartMetricsRecordingTick == null) {
            return;
        }
        try {
            mStartMetricsRecordingTick.invoke(server);
        } catch (Throwable t) {
            logger().warn("[tickcontrol] startMetricsRecordingTick failed;"
                    + " continuing without it", t);
        }
    }

    /** 见 {@link #startMetricsRecordingTick(MinecraftServer)}:同样是可选装饰。 */
    private static void endMetricsRecordingTick(MinecraftServer server) {
        if (mEndMetricsRecordingTick == null && !mEndMetricsMissing) {
            mEndMetricsRecordingTick = method("func_240790_aQ_");
            if (mEndMetricsRecordingTick == null) {
                mEndMetricsMissing = true;
            }
        }
        if (mEndMetricsRecordingTick == null) {
            return;
        }
        try {
            mEndMetricsRecordingTick.invoke(server);
        } catch (Throwable t) {
            logger().warn("[tickcontrol] endMetricsRecordingTick failed;"
                    + " continuing without it", t);
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
    /**
     * 客户端环境粒子是否应当被抑制。
     *
     * <p>由服务器刻在 {@link #prepareTick} 里写入,由 {@code ClientWorldMixin} 读取 ——
     * 客户端代码拿不到服务端控制器,所以用一个静态标志传递。
     *
     * <p>{@code volatile} 是必要的:集成服务器与客户端虽在同进程,但不保证同一线程
     * 可见性;粒子生成发生在渲染线程。
     */
    private static volatile boolean frozenForClientFx;

    /**
     * 冻结时是否应当抑制客户端<b>环境粒子</b>(熔炉火焰/烟、火把、岩浆、传送门)。
     *
     * <h2>为什么服务端门控管不到它</h2>
     *
     * <p>服务端门控刻意不碰客户端:冻住客户端会让玩家自己的挖掘/放置失去反馈,
     * 比粒子问题更糟。但环境粒子是<b>客户端自己按帧生成的</b>,与服务器刻无关,
     * 于是冻结后熔炉照样冒烟 —— 用户先在 1.12.2 上发现,随后确认 1.16.5~1.20.1 同样存在。
     *
     * <h2>为什么拦一个方法就够</h2>
     *
     * <p>已用 ASM 按<b>方法形状</b>(而非名字)扫过 1.16.5 的 srg jar:每个方块的环境粒子钩子
     * ({@code Block.func_180655_c},描述符
     * {@code (LBlockState;LWorld;LBlockPos;LRandom;)V})在客户端侧<b>只有一处调用者</b> ——
     * {@code ClientWorld.func_184153_a}。所以在这里拦一次即可覆盖全部环境方块粒子。
     *
     * <p><b>玩家自己的粒子不受影响</b>:那类粒子走 {@code World.addParticle},不经过这里。
     */
    public static boolean shouldSuppressAmbientParticles() {
        return frozenForClientFx;
    }

    public static void runServer(MinecraftServer server) throws java.io.IOException {
        ServerTickController controller = TickControlAccessHolder.controller(server);

        // ---- 1.21.1: if (!this.initServer()) throw new IllegalStateException(...) ----
        if (!initServer(server)) {
            throw new IllegalStateException("Failed to initialize server");
        }
        net.minecraftforge.fml.server.ServerLifecycleHooks.handleServerStarted(server);

        // ---- 1.21.1: this.nextTickTimeNanos = net.minecraft.util.Util.nanoTime(); ----
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
                            behindNanos / 1_000_000L, ticks);
                    controller.noteOverloadWarning();
                }

                int runs = 0;
                boolean sprint = controller.checkShouldSprintThisTick();
                if (sprint || controller.allowTick()) {
                    // 这里原本还有一次 startMetricsRecordingTick()（1.21.1 的 metrics
                    // 记录钩子）。1.16.5 上<b>不存在</b>对应的无参方法：从 1.19.2 基线
                    // 带过来的 func_240787_a_ 在 1.16.5 的真实签名是
                    // (IChunkStatusListener)V，它根本不是 metrics 方法。
                    //
                    // 去调一个签名不明的方法是纯粹的负担（而且曾在主循环上抛 NPE、
                    // 直接把"进入存档"变成崩溃），所以整段去掉，不再留空操作。
                    // metrics 少一段统计，对 /tick 的任何功能都没有影响。
                    IProfiler tickProfiler = profiler(server);
                    if (tickProfiler != null) {
                        tickProfiler.startSection("tick");
                    }
                    tickServerReflect(server, controller);
                    runs = 1;
                }
                if (sprint) {
                    controller.endTickWork();
                }

                // ---- 1.21.1: profiler.endStartSection("nextTickWait"); mayHaveDelayedTasks = true; ----
                IProfiler waitProfiler = profiler(server);
                if (waitProfiler != null) {
                    waitProfiler.endStartSection("nextTickWait");
                }
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

                // ---- 1.21.1: profiler.endSection(); endMetricsRecordingTick(); ----
                // profiler 的 push/pop 必须配对，因此只在真正跑过 tickServer 的轮次里
                // 执行。主循环并不每轮都 tick（速率限制下要多轮才放行一次），若每轮都
                // pop，就会出现「没 start 过却 end」的不配对调用，破坏原版 profiler 状态。
                //
                // endMetricsRecordingTick() 与上面那处 start 一样被去掉了：1.16.5 上没有
                // 对应的无参方法（详见上面的说明）。
                if (runs == 1) {
                    IProfiler p = profiler(server);
                    if (p != null) {
                        p.endSection();
                    }
                }

                // ---- 1.21.1: this.isReady = true; ----
                setReady(server);


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
                //      commandSource(server).sendSuccess(
                //          translatable("commands.tick.sprint.report", tps, mspt)) ----
                if (controller.consumeSprintReport()) {
                    int sprintTps = (int) Math.round(controller.lastSprintTps());
                    String mspt = String.format(java.util.Locale.ROOT, "%.2f",
                            controller.lastSprintMillisPerTick());
                    // 必须先清掉报告标志再发消息：sprintReportPending() 只看
                    // lastSprintTps > 0，若不清，冲刺结束后主循环每轮都会再报一次
                    // （每秒上万轮 → 日志被刷到数百万行 / 近 1 GB）。
                    controller.clearSprintReport();
                    // 1.16.5 上取不到命令来源时跳过这次报告，不能让报告把主循环带崩。
                    net.minecraft.command.CommandSource reportSource = commandSource(server);
                    if (reportSource != null) {
                        VersionAdapterHolder.get().sendSuccess(
                                reportSource,
                                () -> VersionAdapterHolder.get().translatable(
                                        com.tamamo.tickcontrol.command.TickLang.SPRINT_REPORT,
                                        sprintTps, mspt),
                                true);
                    } else {
                        logger().info("[tickcontrol] sprint report suppressed:"
                                + " no CommandSource available on this version");
                    }
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
            net.minecraftforge.fml.server.ServerLifecycleHooks.handleServerStopping(server);
        } catch (Throwable throwable1) {
            logger().error("Encountered an unexpected exception", throwable1);
            // 1.16.5 的 MinecraftServer 没有 getServerDirectory /
            // onServerCrash / onServerExit 的公开入口（stopServer 还是 protected），
            // 所以整段走反射——这与本项目在 1.16.5 上已验证可行的做法一致。
            java.io.File dir = (java.io.File) invokeOptional(server, "getServerDirectory");
            CrashReport crashreport = constructCrashReport(throwable1);
            if (dir != null) {
                java.io.File file = new java.io.File(dir,
                        "crash-reports/crash-" + timestamp() + "-server.txt");
                if (crashreport.saveToFile(file)) {
                    logger().error("This crash report has been saved to: {}", file.getAbsolutePath());
                } else {
                    logger().error("We were unable to save this crash report to disk.");
                }
            } else {
                logger().error("Could not determine the server directory; crash report not saved.");
            }
            invokeOptional(server, "onServerCrash", crashreport);
        } finally {
            try {
                invokeOptional(server, "func_71260_j");
            } catch (Throwable throwable) {
                logger().error("Exception stopping the server", throwable);
            } finally {
                invokeOptional(server, "func_71240_o");
            }
        }
    }

    /** 每刻开头：刷新「本刻游戏内容是否推进」，并采集每刻耗时样本。 */
    public static void prepareTick(MinecraftServer server) {
        ServerTickController controller = TickControlAccessHolder.controller(server);
        controller.prepareTick();
        // 把"本刻游戏内容是否推进"同步给客户端可见的标志(见 shouldSuppressAmbientParticles)。
        // 必须在 prepareTick() 之后读,否则拿到的是上一刻的取值。
        frozenForClientFx = !controller.runsNormally();
        controller.stats().replaceSamples(tickTimes(server));
        // SelfTest 在 build 里被排除，只能反射调用（与 1.17.1 一致）
        try {
            Class<?> cls = Class.forName("com.tamamo.tickcontrol.core.SelfTest");
            Method m = cls.getMethod("tick", MinecraftServer.class);
            m.invoke(null, server);
        } catch (Throwable ignored) {
            // 生产环境没有 SelfTest，静默跳过
        }
    }

    /**
     * 世界门控：对应 1.21.1 {@code ServerLevel.tick} 里的
     * {@code tickRateManager.runsNormally()}。
     *
     * <p>由两个变体的 {@code @Redirect(method = "tickChildren")} 转调。
     */
    public static void tickLevel(net.minecraft.world.server.ServerWorld level,
                                 java.util.function.BooleanSupplier haveTime) {
        MinecraftServer server = level.getServer();
        ServerTickController controller = TickControlAccessHolder.controller(server);
        if (controller.shouldTickLevels()) {
            level.tick(haveTime);
            controller.noteLevelTick();
        }
    }

    /**
     * 调用 1.16.5 上可能不存在或不可见的 {@code MinecraftServer} 方法。
     *
     * <p>1.16.5 的 {@code stopServer()} 是 protected，{@code onServerExit()} /
     * {@code onServerCrash(CrashReport)} / {@code getServerDirectory()} 则不存在于
     * MCP 名域里，所以不能直接调。找不到就返回 {@code null}，绝不让收尾路径再抛异常。
     *
     * @param args 方法参数；同时用于匹配参数类型
     */
    private static Object invokeOptional(Object target, String name, Object... args) {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i].getClass();
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
                // 试父类
            } catch (Throwable t) {
                logger().warn("[tickcontrol] invocation of {} failed: {}", name, t.toString());
                return null;
            }
        }
        return null;
    }

    /**
     * 取一个可用的命令来源，用于发送冲刺报告。
     *
     * <h2>为什么候选名是这样排的</h2>
     *
     * <p>这个方法的原名候选一直是从 1.19.2 基线抄来的，而那两个名字
     * <b>在 1.16.5 上都不存在</b>：
     *
     * <table border="1">
     *   <caption>各版本"取命令来源"的成员名</caption>
     *   <tr><th>名字</th><th>出现在</th></tr>
     *   <tr><td>{@code createCommandSourceStack}</td><td>1.17+</td></tr>
     *   <tr><td>{@code createCommandSource}</td><td>1.19+</td></tr>
     *   <tr><td>{@code getCommandSource} / {@code func_195573_aM}</td>
     *       <td><b>1.16.5</b></td></tr>
     * </table>
     *
     * <p>后果是 {@link #commandSource} 恒返回 {@code null}，冲刺报告被静默跳过——
     * 玩家在聊天栏里什么都看不到（日志里那句
     * {@code "sprint report suppressed: no CommandSource available on this version"}
     * 就是它）。1.16.5 的 SRG 名是 {@code func_195573_aM}，开发名是
     * {@code getCommandSource}，两者都返回 {@code CommandSource} 且无参，
     * 已对着开发 jar 与运行时 jar 双向核对，是唯一匹配的一对。
     *
     * <p>命令来源仍然只做反射尝试：拿不到就返回 {@code null}，调用方跳过报告。
     * 冲刺报告只是提示信息，绝不能因为它让主循环出错。
     */
    private static net.minecraft.command.CommandSource commandSource(MinecraftServer server) {
        // 顺序按"最可能命中"排:1.16.5 先试，然后才是更高版本的名字。
        String[] candidates = {
                "func_195573_aM",     // 1.16.5 production (SRG)
                "getCommandSource",   // 1.16.5 development (MCP)
                "createCommandSourceStack", // 1.17+
                "createCommandSource",      // 1.19+
        };
        for (String candidate : candidates) {
            Object src = invokeOptional(server, candidate);
            if (src instanceof net.minecraft.command.CommandSource) {
                return (net.minecraft.command.CommandSource) src;
            }
        }
        logger().warn("[tickcontrol] no CommandSource accessor found on this version;"
                + " the sprint report cannot be delivered to players");
        return null;
    }

    /** 1.16.5 没有 Util.getFilenameFormattedDateTime()（1.18+），自行格式化。 */
    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss",
                java.util.Locale.ROOT).format(new java.util.Date());
    }

    /** 缓存的 {@code MinecraftServer.tick(BooleanSupplier)}；1.16.5 上是 protected。 */
    private static Method mTickServer;
    /** 已确认解析不到就不再重试。 */
    private static boolean mTickServerMissing;

    /**
     * 推进一次服务器刻。
     *
     * <p>1.19.2 有公开的 {@code tickServer(BooleanSupplier)}，1.16.5 只有
     * {@code protected void tick(BooleanSupplier)}，所以这里走反射。
     * 句柄静态缓存，每刻只多一次 {@code Method.invoke} 的开销。
     */
    private static void tickServerReflect(MinecraftServer server, ServerTickController controller) {
        // 必须用 SRG 名查,不能用 "tick":生产环境(以及本项目的测试实例)加载的是 SRG
        // 名,那里<b>没有</b>叫 "tick" 的方法。之前写死 "tick" 会在主循环最内层抛
        // NoSuchMethodException,表现是根本进不去存档。两个名字域都试,任一命中即可。
        if (mTickServer == null && !mTickServerMissing) {
            for (String candidate : new String[] {"func_71217_p", "tick"}) {
                try {
                    mTickServer = MinecraftServer.class.getDeclaredMethod(
                            candidate, java.util.function.BooleanSupplier.class);
                    mTickServer.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // 试下一个候选名
                }
            }
            if (mTickServer == null) {
                mTickServerMissing = true;
                logger().error("[tickcontrol] MinecraftServer.tick(BooleanSupplier) could not"
                        + " be resolved on this version -- the custom server loop cannot"
                        + " drive ticks, so tick control is INACTIVE");
            }
        }
        if (mTickServer == null) {
            // 一个候选都没命中:说明本版本 tick 的签名与预期不同。这里<b>不抛</b>——
            // 抛了服务器根本起不来,而这只是一个"推进一刻"的调用。
            return;
        }
        try {
            mTickServer.invoke(server, (java.util.function.BooleanSupplier) controller::haveTimeNow);
        } catch (Throwable e) {
            // 同样不抛:记录后继续,而不是让整个服务器停摆。
            logger().error("[tickcontrol] MinecraftServer.tick failed", e);
        }
    }
}
