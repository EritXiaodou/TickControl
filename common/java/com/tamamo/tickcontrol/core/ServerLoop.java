
package com.tamamo.tickcontrol.core;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tamamo.tickcontrol.command.TickControlCommand;
import com.tamamo.tickcontrol.command.TickLang;

import net.minecraft.crash.CrashReport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.StartupQuery;

/**
 * 主循环在 1.12.2 上的实现主体（平台与名字域无关）。
 *
 * <h2>为什么把逻辑放在这里</h2>
 *
 * Mixin 注解里的 {@code method = "run"} 这类值是<b>编译期常量</b>，无法在运行时改成
 * SRG 名，所以本工程写两个薄 Mixin（{@code MinecraftServerMixinDev} /
 * {@code MinecraftServerMixinSrg}），由配置插件按名字域选择其一；两份都只做委托，
 * 真正的逻辑在本类，避免重复。
 *
 * <h2>1.12.2 与 1.16.5 的结构差异（逐条核验过）</h2>
 *
 * <ul>
 *   <li>{@code run()} 与 {@code tick()} 是<b>两个分开的方法</b>：{@code run()} 持有
 *       while 循环，{@code tick()}{@code = func_71217_p} 是「一刻」。
 *       因此这里整个替换 {@code run()}，内部调 {@code server.tick()}。</li>
 *   <li>没有 {@code startMetricsRecordingTick}/{@code endMetricsRecordingTick}，
 *       也没有 {@code nextTickTime}/{@code isReady}/{@code mayHaveDelayedTasks}/
 *       {@code delayedTasksMaxNextTickTime}/{@code averageTickTime}。
 *       1.12.2 的对应字段是 {@code currentTime}（{@code field_175591_ab}）与
 *       {@code serverIsRunning}（{@code field_71296_Q}）。</li>
 *   <li>{@code tickTimes} 叫 {@code tickTimeArray}（{@code field_71311_j}，public）；</li>
 *   <li>{@code getWorlds()} 不存在，世界数组是 {@code worlds}
 *       （{@code field_71305_c}，public）；</li>
 *   <li>{@code ServerWorld} 叫 {@code WorldServer}，且它的 {@code tick()} 没有参数；</li>
 *   <li>1.12.2 没有 slf4j，日志是 log4j2；</li>
 *   <li>服务器生命周期走 {@code FMLCommonHandler.instance()}，不是
 *       {@code ServerLifecycleHooks}；</li>
 *   <li>{@code initServer()} 在 1.12.2 叫 {@code init()}（{@code func_71197_b}）。</li>
 * </ul>
 *
 * <h2>为什么仍然用反射</h2>
 *
 * {@code @Shadow} 需要 refmap，而本工程刻意绕开 refmap（注解全带 {@code remap = false}），
 * 所以这里统一用反射访问 {@code MinecraftServer} 的私有/受保护成员。反射不受名字域影响，
 * 因此下面每张表都同时登记 MCP 名与 SRG 名，句柄静态缓存，每刻只有几次
 * {@code Field.getInt} 级别的开销。
 */
public final class ServerLoop {

    private ServerLoop() {
    }

    private static final boolean DEBUG = Boolean.getBoolean("tickcontrol.debug");

    /**
     * Mixin 活性探针的一次性开关。
     *
     * <p>由 {@code MinecraftServerMixinSrg#tickcontrol\} 的注入置位，
     * 用于在日志里明确证实“世界门控注入到底有没有生效”。
     * 只写不读，可见性优先于封装。
     */
    public static volatile boolean PREPARE_TICK_PROBE_LOGGED;

    /** 与 MinecraftServer 自己的 {@code LOGGER} 同名（{@code LogManager.getLogger()} 的默认名）。 */
    private static final Logger LOGGER = LogManager.getLogger(MinecraftServer.class);

    /** 给 Carpet 系 HUD 同步实测值的节拍计数（每 20 刻一次）。 */
    private static long hudTicker;

    private static long debugLastMs;
    private static long debugLastIterations;

    /** 过载阈值（毫秒）折算成纳秒，对应 1.21.1 的 OVERLOADED_THRESHOLD_NANOS。 */
    private static final long OVERLOADED_THRESHOLD_NANOS = 1_000_000L * 2000L;
    private static final long OVERLOADED_WARNING_INTERVAL_NANOS = 1_000_000L * 15000L;

    /** 单次 park 的上限：让出 CPU，同时保证关服/变速能被及时响应。 */
    private static final long MAX_PARK_NANOS = 1_000_000L;

    // ------------------------------------------------------------------
    // 反射句柄（懒加载并缓存）
    // ------------------------------------------------------------------

    private static Field fRunning;
    private static Field fReady;
    private static Field fCurrentTime;
    private static Field fServerStopped;

    /**
     * 成员名在开发环境是 MCP 名、生产环境是 SRG 名（{@code serverRunning} vs
     * {@code field_71317_u}）。下面这张表让 {@link #field} 两种名字都能解析，
     * 因此本类不需要知道当前处于哪种名字域。
     *
     * <p>SRG 名全部来自 1.12.2 的 {@code forge-1.12.2-14.23.5.2860-srg.jar}
     * （用 {@code javap} 逐个核对），不是猜的。
     *
     * <p>用静态初始化块而不是 {@code Map.of(...)}：{@code Map.of} 是 Java 9 API，
     * 而本项目的目标语言级别是 Java 8。
     */
    private static final java.util.Map<String, String> FIELD_ALTERNATES = new java.util.HashMap<>();

    static {
        FIELD_ALTERNATES.put("serverRunning", "field_71317_u");
        FIELD_ALTERNATES.put("serverIsRunning", "field_71296_Q");
        FIELD_ALTERNATES.put("currentTime", "field_175591_ab");
        FIELD_ALTERNATES.put("serverStopped", "field_71316_v");
    }

    /** 把 MCP 名与 SRG 名互相补全：无论传入哪个，都返回一对候选名。 */
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
     * <p>与 1.16.5 的同类改动保持一致,理由在那里已经吃过一次亏:名字对不上版本
     * 只是<b>配置/移植错误</b>,而这里抛异常会把它变成"客户端卡死"或"服务器起不来"。
     * 更糟的是本方法是在主循环条件 {@code while (isRunning(server))} 里被调用的,
     * 一旦抛出,连"服务器还在跑"这件事都判断不了。
     *
     * <p>返回 null 则退化成"该功能不生效 + 日志一条 error",服务器照常启动。
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
        System.err.println("[tickcontrol] field not resolved on this version: " + name
                + " -- the feature depending on it will be inactive");
        return null;
    }

    /** 读一个 boolean 字段;不可用时返回 {@code fallback},绝不抛异常。 */
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
            System.err.println("[tickcontrol] reading " + name + " failed: " + t);
            return fallback;
        }
    }

    /** 写一个 boolean 字段;不可用时静默跳过,绝不抛异常。 */
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
            System.err.println("[tickcontrol] writing " + name + " failed: " + t);
        }
    }

    private static boolean isRunning(MinecraftServer server) {
        // 字段取不到时按"服务器没在跑"处理:主循环立刻退出,而不是卡死。
        return readBoolean(fRunning, "serverRunning", server, false);
    }

    /** 1.12.2 的 {@code serverIsRunning}；原版在 run() 每轮把它置真。 */
    private static void setReady(MinecraftServer server) {
        writeBoolean(fReady, "serverIsRunning", server, true);
    }

    private static void setServerStopped(MinecraftServer server) {
        writeBoolean(fServerStopped, "serverStopped", server, true);
    }

    /**
     * 维护原版 {@code currentTime} 字段（毫秒时刻）。
     *
     * <h2>为什么必须维护它</h2>
     *
     * {@code DedicatedServer} 的卡死看门狗（{@code ServerHangWatchdog}）比较的就是
     * {@code getCurrentTimeMillis() - server.getCurrentTime()}：一旦超过
     * {@code maxTickTime}（默认 60 秒）就判定服务器崩溃并<b>强制关服</b>。
     * 原版 {@code run()} 每轮都把这个字段设成「现在」，而本模组整个替换了
     * {@code run()}，若不维护它，字段就停在服务器实例化那一刻——
     * 无论速率多少，服务器跑满 60 秒必被看门狗杀掉。
     *
     * <p>顺带一提，1.12.2 里它同时还是 {@code ServerHangWatchdog} 判断
     * 「本刻有没有推进」的依据，因此每轮刷新一次是正确做法。
     */
    private static void setCurrentTime(MinecraftServer server, long millis) {
        try {
            if (fCurrentTime == null) {
                fCurrentTime = field("currentTime");
            }
            if (fCurrentTime == null) {
                // 写不进去的后果是看门狗可能在 60 秒后关服,而不是立刻崩溃;
                // field() 已经打过一条 error,这里不再重复刷屏。
                return;
            }
            fCurrentTime.setLong(server, millis);
        } catch (Throwable t) {
            System.err.println("[tickcontrol] writing currentTime failed: " + t);
        }
    }

    // ==================================================================
    // 客户端环境粒子门控
    // ==================================================================

    /**
     * 冻结时是否应当抑制客户端<b>环境粒子</b>(熔炉火焰/烟、火把、岩浆、传送门)。
     *
     * <p>由 {@code WorldClient.func_184153_a} / {@code doVoidFogParticles} 的
     * {@code @Inject(HEAD, cancellable)} 调用(两个名字域各一个 Mixin)。
     *
     * <h2>为什么服务端门控管不到它</h2>
     *
     * <p>服务端门控刻意不碰客户端:那会让玩家自己的挖掘/放置失去反馈,比粒子问题更糟。
     * 但环境粒子是<b>客户端按帧自己生成的</b>,与服务器刻无关,所以冻结后熔炉照样冒烟
     * —— 用户在 1.12.2 上正是这样发现的。
     *
     * <p>为什么拦这一个方法就够:已核验 1.12.2 全 jar 里
     * {@code Block.func_180655_c}(即 {@code randomDisplayTick})的调用者中,
     * <b>客户端路径只有 {@code WorldClient.func_184153_a} 一处</b>;
     * 另外三处是 {@code BlockStairs} / {@code BlockMycelium} /
     * {@code BlockEnchantmentTable} 调用自己的 {@code super},不是新的粒子入口。
     *
     * <p><b>玩家自己的粒子不受影响</b>:那类粒子走 {@code World.func_175688_a}
     * ({@code spawnParticle}),不经过这里。
     *
     * <p>客户端每帧都会调用,所以只做只读判断、绝不推进状态机、绝不抛异常。
     */
    /**
     * 当前服务端实例,供<b>客户端</b>代码查询冻结状态。
     *
     * <p>1.12.2 没有 {@code MinecraftServer.getServer()}(那是 1.7.10 的 API),
     * 而客户端 Mixin({@code WorldClient})拿不到服务端引用,所以在这里留一个静态指针。
     *
     * <p>只在两个入口赋值,都是主线程每刻必经之处:{@link #runServer}(整体替换
     * {@code run()} 的路径)与 {@link #prepareTick}(常规注入路径)。
     * 只读使用,不参与任何状态机判断,所以即使短暂为 {@code null}(例如尚未开服)
     * 也只会退化成"不抑制粒子",不会出错。
     */
    private static volatile MinecraftServer currentServerInstance;

    public static boolean shouldSuppressAmbientParticles() {
        try {
            MinecraftServer server = currentServerInstance;
            if (server == null) {
                return false;
            }
            return !TickControlAccessHolder.controller(server).runsNormally();
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 主循环（1.12.2 run() 的替换实现）
    // ==================================================================

    /**
     * 顶掉 1.12.2 的 {@code run()}，换成带速率/冻结/步进/冲刺门控的循环。
     *
     * <p>调用方（两个名字域的 Mixin）在 {@code @At("HEAD")} 注入，
     * 并在本方法返回后 {@code ci.cancel()}，因此原版 {@code run()} 的主体
     * <b>完全不会执行</b>——原版在 {@code run()} 里做的收尾（
     * {@code handleServerStarted} / {@code handleServerStopping} /
     * {@code stopServer} / {@code handleServerStopped} / {@code systemExitNow}）
     * 必须在这里逐条复刻，见方法末尾。
     */
    public static void runServer(MinecraftServer server) throws java.io.IOException {
        currentServerInstance = server;
        ServerTickController controller = TickControlAccessHolder.controller(server);

        // ---- 原版: if (this.init()) { ... } else { expectServerStopped(); finalTick(null); } ----
        if (!server.init()) {
            FMLCommonHandler.instance().expectServerStopped();
            server.finalTick(null);
            shutdown(server);
            return;
        }

        FMLCommonHandler.instance().handleServerStarted();
        setCurrentTime(server, MinecraftServer.getCurrentTimeMillis());
        controller.resetClock();

        try {
            // ---- 原版: while (this.serverRunning) { ... } ----
            while (isRunning(server)) {
                long periodNanos = controller.periodNanos();

                // 过载提示（保留 1.21.1 的行为与文案）
                long behindNanos = controller.behindNanos();
                if (behindNanos > OVERLOADED_THRESHOLD_NANOS + 20L * periodNanos
                        && controller.nanosSinceOverloadWarning()
                                >= OVERLOADED_WARNING_INTERVAL_NANOS + 100L * periodNanos) {
                    long ticks = behindNanos / periodNanos;
                    LOGGER.warn(
                            "Can't keep up! Is the server overloaded? Running {}ms or {} ticks behind",
                            behindNanos / 1_000_000L, ticks);
                    controller.noteOverloadWarning();
                }

                // ---- 速率门控：只有节拍器放行（或冲刺中）才推进一个游戏刻 ----
                boolean sprint = controller.checkShouldSprintThisTick();
                boolean ticked = sprint || controller.allowTick();
                if (ticked) {
                    // 1.12.2 的 tick() 是 public 且无参：不需要反射
                    // （1.16.5 是 protected tick(BooleanSupplier)，才需要）。
                    // 冻结/步进的门控在 tick() 内部由 @Inject + @Redirect 完成，
                    // 见 prepareTick() 与 tickLevel()。
                    server.tick();
                    if (sprint) {
                        controller.endTickWork();
                    }
                }

                // ---- 维护原版时钟（看门狗依赖它）----
                setCurrentTime(server, MinecraftServer.getCurrentTimeMillis());

                // ---- 临时诊断：冻结期间每秒打印一次门控状态 ----
                // 判读要点:
                //   levelTicks  —— 由 tickLevel() 在 shouldTickLevels() 为真时递增,
                //                  冻结期间必须**完全不变**(世界不再推进);
                //   serverTicks —— server.tick() 的调用次数,冻结期间仍应**持续增长**
                //                  (网络/玩家列表要照常跑,否则会被看门狗杀掉或踢人)。
                // 这两者一个停、一个涨,才说明"游戏刻冻结"确实生效。
                //
                // 该诊断已于 2026-10 完成使命并删除:它读出的正是上述两个相反趋势,
                // 证明 1.12.2 的冻结真实生效。留着只会每秒刷屏(实测单个会话 150+ 行),
                // 污染玩家的 latest.log。同类探针一并清除(audit-wiring.py 可查残留)。

                // ---- 等到下一个游戏刻时刻，期间把排队任务跑掉 ----
                waitUntilNextTick(server, controller);

                // ---- 原版: this.serverIsRunning = true; ----
                setReady(server);

                // ---- 实测 TPS / MSPT + 同步给 Carpet 系 HUD ----
                // 必须在「真正完成了游戏刻」时才采样：1 TPS 目标下每秒要转上万轮、
                // 只 tick 一次，若每轮都采样，实测 TPS 会算出数百万这种荒谬值。
                if (ticked) {
                    controller.recordTickCompletion();
                    if (++hudTicker % 20L == 0L) {
                        CarpetHudBridge.sync(server, controller.measuredMillisPerTick());
                    }
                }

                // ---- 冲刺结束：发送报告（对应上游 finishTickSprint 里的
                //      commandSource(server).sendSuccess(commands.tick.sprint.report, tps, mspt)）----
                if (controller.consumeSprintReport()) {
                    int sprintTps = (int) Math.round(controller.lastSprintTps());
                    String mspt = String.format(java.util.Locale.ROOT, "%.2f",
                            controller.lastSprintMillisPerTick());
                    // 必须先清掉报告标志再发消息：sprintReportPending() 只看
                    // lastSprintTps > 0，若不清，冲刺结束后主循环每轮都会再报一次。
                    controller.clearSprintReport();
                    // 报告要发给"发起冲刺的那个人",而不是服务器本身。
                    // 1.12.2 的 MinecraftServer 虽然实现了 ICommandSender,但它的
                    // sendMessage 只写服务端日志——直接用它当来源,玩家看不到任何东西
                    // (聊天栏只会出现一条 [Server: <原始键名>])。
                    // 发起者在命令层被记进控制器,这里取出来;万一没有记录才退回服务器。
                    net.minecraft.command.ICommandSender reportTarget =
                            controller.sprintReportTarget();
                    if (reportTarget == null) {
                        reportTarget = server;
                    }
                    VersionAdapterHolder.get().sendSuccess(
                            reportTarget,
                            TickControlCommand.INSTANCE,
                            TickLang.SPRINT_REPORT,
                            new Object[] {Integer.valueOf(sprintTps), mspt},
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
                                + " tickedThisIteration=" + ticked
                                + " iterationsPerSecond=" + iterDelta
                                + " granted=" + controller.grantedTicks()
                                + " levelTicks=" + controller.levelTicks());
                        debugLastMs = nowMs;
                        debugLastIterations = controller.loopIterations();
                    }
                }
            }

            // ---- 原版: handleServerStopping(); expectServerStopped(); ----
            FMLCommonHandler.instance().handleServerStopping();
            FMLCommonHandler.instance().expectServerStopped();
        } catch (StartupQuery.AbortedException aborted) {
            // 原版单独处理它：启动被中止不是崩溃
            LOGGER.warn("[tickcontrol] server start aborted");
            FMLCommonHandler.instance().expectServerStopped();
        } catch (Throwable throwable) {
            LOGGER.error("Encountered an unexpected exception", throwable);
            // 1.12.2 有 addServerInfoToCrashReport/finalTick/getDataDirectory 的公开入口，
            // 不需要 1.16.5 那套反射兜底。
            CrashReport crashreport = throwable instanceof ReportedException
                    ? server.addServerInfoToCrashReport(
                            ((ReportedException) throwable).getCrashReport())
                    : server.addServerInfoToCrashReport(
                            new CrashReport("Exception in server tick loop", throwable));
            File file = new File(new File(server.getDataDirectory(), "crash-reports"),
                    "crash-" + timestamp() + "-server.txt");
            if (crashreport.saveToFile(file)) {
                LOGGER.error("This crash report has been saved to: {}", file.getAbsolutePath());
            } else {
                LOGGER.error("We were unable to save this crash report to disk.");
            }
            FMLCommonHandler.instance().expectServerStopped();
            server.finalTick(crashreport);
        } finally {
            shutdown(server);
        }
    }

    /**
     * 原版 {@code run()} 的收尾序列。
     *
     * <p>顺序照抄 1.12.2 的 {@code run()}：{@code stopServer()} →
     * {@code handleServerStopped()} → {@code serverStopped = true} →
     * {@code systemExitNow()}（单人游戏的 {@code IntegratedServer} 里它是空实现，
     * 专用服务器才会真的 {@code System.exit(0)}）。
     */
    private static void shutdown(MinecraftServer server) {
        try {
            server.stopServer();
            FMLCommonHandler.instance().handleServerStopped();
            setServerStopped(server);
            server.systemExitNow();
        } catch (Throwable throwable) {
            LOGGER.error("Exception stopping the server", throwable);
            try {
                FMLCommonHandler.instance().handleServerStopped();
                setServerStopped(server);
            } catch (Throwable ignored) {
                // 收尾路径绝不能再抛出去
            }
            server.systemExitNow();
        }
    }

    /**
     * 等到下一个游戏刻时刻。
     *
     * <p>1.12.2 的原版 {@code run()} 是 {@code Thread.sleep(Math.max(1L, 50L - i))}，
     * 目标周期写死 50ms。这里把周期换成控制器算出来的值，因此
     * {@code /tick rate} 才会真正生效；等待用 {@code LockSupport.parkNanos} 分片，
     * 保证关服与变速能被及时响应。冲刺期间不等待（上游把一刻视作 0 纳秒）。
     *
     * <p>等待期间顺带把 {@code futureTaskQueue} 排空：原版只在
     * {@code updateTimeLightAndEntities()} 的开头（即 tick 内部）处理任务队列，
     * 低速率下周期长达 1 秒，若不在这里处理，
     * {@code addScheduledTask}（以及阻塞等待它的区块加载回调）会被拖到下一刻。
     */
    private static void waitUntilNextTick(MinecraftServer server, ServerTickController controller) {
        long deadlineMillis = controller.isSprinting()
                ? 0L
                : MinecraftServer.getCurrentTimeMillis() + controller.loopPeriodMillis();
        while (isRunning(server)) {
            long remainingNanos = (deadlineMillis - MinecraftServer.getCurrentTimeMillis())
                    * 1_000_000L;
            if (remainingNanos <= 0L) {
                break;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
                    Math.min(remainingNanos, MAX_PARK_NANOS));
            drainPendingTasks(server);
        }
        drainPendingTasks(server);
    }

    /** 把 {@code futureTaskQueue} 排空；与原版 {@code updateTimeLightAndEntities} 的 "jobs" 段同序。 */
    private static void drainPendingTasks(MinecraftServer server) {
        java.util.Queue<java.util.concurrent.FutureTask<?>> queue = server.futureTaskQueue;
        synchronized (queue) {
            while (!queue.isEmpty()) {
                Util.runTask(queue.poll(), LOGGER);
            }
        }
    }

    /** 每刻开头：刷新「本刻游戏内容是否推进」，并采集每刻耗时样本。 */
    public static void prepareTick(MinecraftServer server) {
        currentServerInstance = server;
        ServerTickController controller = TickControlAccessHolder.controller(server);
        controller.prepareTick();
        // 1.12.2 里这个数组叫 tickTimeArray（1.16.5 叫 tickTimes），public 直接读
        controller.stats().replaceSamples(server.tickTimeArray);
        // SelfTest 在 build 里被排除，只能反射调用（与其它版本一致）
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
     * <p>由两个变体的 {@code @Redirect}（目标 {@code WorldServer.tick()} /
     * {@code func_72835_b}）转调。注意 1.12.2 的 {@code WorldServer.tick()}
     * <b>没有参数</b>（1.16.5 是 {@code tick(BooleanSupplier)}），
     * 所以这里的签名也只有世界一个参数。
     */
    public static void tickLevel(WorldServer level) {
        // 这里曾有一个 redirectCalls 计数器,用于证明 @Redirect 确实被调用 ——
        // 因为"levelTicks 恒定"既可能是冻结生效,也可能是 redirect 压根没接上,
        // 两者观感完全一样。该计数已确认增长(@Redirect 确实生效),故连同探针一并删除。
        MinecraftServer server = level.getMinecraftServer();
        ServerTickController controller = TickControlAccessHolder.controller(server);
        if (!controller.shouldTickLevels()) {
            return;
        }
        level.tick();
        controller.noteLevelTick();
        drainStepTicks(level, controller, server);
    }

    /**
     * 递归保护:{@code level.tick()} 会再次进入两个 redirect,
     * 排空循环只能在最外层跑一次,否则会指数级重复推进。
     */
    private static boolean drainingSteps;

    /**
     * 把 {@code /tick step N} 请求的 N 刻在本服务器刻内<b>尽快排空</b>。
     *
     * <h2>为什么必须排空,而不是每刻放行一刻</h2>
     *
     * <p>原版 1.20.3 的 {@code /tick step N} 语义是"往前赶 N 刻,尽快跑完"。
     * 本模组最初的实现依赖 {@code TickState.prepareTick()} 每服务器刻递减一次
     * {@code stepTicks},于是 {@code step N} 要花 <b>N 个服务器刻</b>:
     * 用户执行 {@code /tick step 8000} 后世界看起来"解冻并持续运行"——
     * 实测日志里 levelTicks 从 172 一路涨到 717,而那要跑 6.7 分钟才结束,
     * 完全不像"步进",反倒像是冻结失效。用户就是这样报告的。
     *
     * <p>这里改成在<b>一个服务器刻的时间预算内</b>循环推进,直到刻数用完或超时。
     * 超时上限取一个完整周期({@code loopPeriodMillis()}),所以即使 step 请求
     * 很大也不会把这一帧拖死;剩下的会在后续服务器刻继续排空。
     *
     * <p>{@code prepareTick()} 在上一轮 {@code waitForNextTick()} 里已经递减过一次,
     * 所以这里从 {@code getStepTicks() - 1} 开始,保证总共恰好推进 N 刻。
     */
    private static void drainStepTicks(WorldServer level, ServerTickController controller,
                                       MinecraftServer server) {
        if (drainingSteps) {
            return;
        }
        drainingSteps = true;
        try {
            long budgetMillis = controller.loopPeriodMillis();
            long deadline = System.currentTimeMillis() + Math.max(1L, budgetMillis);
            // prepareTick() 在上一轮 waitForNextTick() 里已经递减过一次,
            // 所以本次还要补的刻数是 getStepTicks() - 1(不小于 0)。
            int remaining = controller.stepTicks() - 1;
            int drained = 0;
            while (remaining > 0) {
                if (System.currentTimeMillis() >= deadline) {
                    break;
                }
                level.tick();
                level.updateEntities();
                controller.noteLevelTick();
                drained++;
                remaining--;
            }
            // 让计数器与实际排空的刻数一致:下一轮 waitForNextTick() 会再递减一次,
            // 于是"排空 + 那一次"正好等于用户请求的 N。
            controller.consumeStepTicks(drained);
        } catch (Throwable t) {
            // 步进排空失败绝不能影响主循环。
            System.err.println("[tickcontrol] step drain failed: " + t);
        } finally {
            drainingSteps = false;
        }
    }

    /**
     * 世界门控的<b>第二个</b>注入点:实体与方块实体的更新。
     *
     * <h2>为什么必须和 {@link #tickLevel} 配对</h2>
     *
     * <p>1.12.2 的 {@code MinecraftServer.func_71190_q()} 对每个世界分别调用两个方法:
     *
     * <pre>
     *   WorldServer.func_72835_b()   // 世界 tick      —— 由 tickLevel 门控
     *   WorldServer.func_72939_s()   // 更新实体/方块实体 —— 由本方法门控
     * </pre>
     *
     * <p>只拦前者时,世界时间/天气确实停了,但方块实体仍在每刻更新,
     * 于是<b>熔炉继续烧、还在推进冶炼进度</b>。这是用户实测发现的:
     * "聊天栏提示冻结成功,但熔炉里的物品仍然在烧"。
     *
     * <p>两者必须用同一个判据 {@link ServerTickController#shouldTickLevels()},
     * 否则会出现"世界停了但实体在跑"这种半冻结状态。
     */
    public static void tickLevelEntities(WorldServer level) {
        MinecraftServer server = level.getMinecraftServer();
        ServerTickController controller = TickControlAccessHolder.controller(server);
        if (controller.shouldTickLevels()) {
            level.updateEntities();
        }
    }

    /** 1.16.5 没有 Util.getFilenameFormattedDateTime()（1.18+），自行格式化。 */
    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss",
                java.util.Locale.ROOT).format(new java.util.Date());
    }
}
