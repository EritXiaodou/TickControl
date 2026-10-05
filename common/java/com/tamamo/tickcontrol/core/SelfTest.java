package com.tamamo.tickcontrol.core;

import java.util.Locale;

import com.tamamo.tickcontrol.command.TickControl;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * 无人值守自检：在服务器线程内跑一串 {@code /tick} 命令并断言结果。
 *
 * <p>动机：{@code runServer} 由脚本/CI 启动时 stdin 会立刻 EOF，服务器启动后
 * 一秒内就自行停止，外部 RCON 客户端来不及连上；把验证放进服务器线程即可绕开。
 *
 * <p><b>安全性</b>：只有系统属性 {@code tickcontrol.selftest=true} 时才动作；
 * 默认（含正式发行包）每次调用只是一次布尔判断后立即返回。
 *
 * <p>属性：
 * <ul>
 *   <li>{@code tickcontrol.selftest} —— 设为 {@code true} 才启用</li>
 *   <li>{@code tickcontrol.selftest.stop} —— 设为 {@code true} 时跑完自动停服</li>
 * </ul>
 */
public final class SelfTest {

    private static final String PREFIX = "[tickcontrol-selftest] ";

    /**
     * 自检输出必须走 logger。
     *
     * <p>原来用 {@code System.out.println}：正式客户端的 stdout 不进
     * {@code logs/*.log}，于是「自检跑了但日志里什么都没有」，看起来像自检没执行。
     * 改成 logger 后结果直接落在 {@code latest.log}，脚本可以据此判定通过/失败。
     */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 启动后先等这么多刻，让世界与命令系统就绪。 */
    private static final int START_DELAY = 40;

    /** 冻结后等这么多刻再比对（应完全不变）。 */
    private static final int FREEZE_WAIT = 60;

    /** 步进多少刻。 */
    private static final int STEP_TICKS = 50;

    /** 速率测量窗口（毫秒，墙钟）。 */
    private static final long RATE_WINDOW_MILLIS = 6000L;

    /**
     * 低速档（&lt;= 2 TPS）的测量窗口。
     *
     * <p>1 TPS 下 6 秒只有 6 个刻，任何一次调度抖动都会被放大成很大的比例误差，
     * 所以把窗口拉长到 15 秒（约 15 个刻），读数才有意义。
     */
    private static final long RATE_WINDOW_LOW_MILLIS = 15000L;

    /** 按目标速率选择测量窗口：低于 2 TPS 用长窗口。 */
    private static long rateWindowMillis(float rate) {
        return rate <= 2.0F ? RATE_WINDOW_LOW_MILLIS : RATE_WINDOW_MILLIS;
    }

    /**
     * 依次测量的目标速率。
     *
     * <p>上限刻意压在 500：单机（集成服务端 + 渲染线程抢 CPU）在 rate=1000 上
     * 实测只能到 ~636 TPS，那是「每轮主循环都要付一次固定开销、而每刻预算只有 1ms」
     * 导致的持续速率上限，不是速率控制错误。真实硬件天花板由步骤 1b 的
     * sprint 探针单独测（sprint 不等待，能跑出 2500~5000 TPS）。
     * 把 1000 留在这一组里只会让「控制精度」和「持续吞吐」两件事混在一起。
     */
    private static final float[] RATE_PROBES = {20.0F, 500.0F, 1.0F, 40.0F, 200.0F};

    /** 冲刺请求的刻数：足够大到能观察出「远快于目标速率」。 */
    private static final int SPRINT_TICKS = 600;

    /**
     * 测量 CPU 上限用的冲刺刻数。
     *
     * <p>为什么上限要用 {@code /tick sprint} 来测：sprint 期间主循环完全不等待
     * （上游把一刻视作 0 纳秒），跑出来的速率就是这台机器在「渲染线程 + 集成服务端
     * 抢 CPU」条件下的真实天花板。用 {@code /tick rate 1000} 去测是不对的——
     * 那个值同时受节拍器和硬件限制，看起来像"速率控制失败"，其实是 CPU 到顶。
     */
    private static final int SPRINT_CEILING_TICKS = 2400;

    /** sprint 测得的 CPU 上限（TPS）；&lt;= 0 表示尚未测得。 */
    private static double measuredCeilingTps = -1.0D;

    /** 冲刺期间目标速率压到 1 TPS，这样「快」与「慢」的差别极其明显。 */
    private static final float SPRINT_BASE_RATE = 1.0F;

    /**
     * 冻结期间观察「右键交互是否仍可用」的窗口（刻）。
     *
     * <p>熔炉打不开的那个 bug 就是主循环不排空任务队列导致的，而冻结态下
     * 主循环更容易只睡不干活。这里在冻结期间<b>主动投递</b>若干任务并统计
     * 实际执行数：只要主循环还在排空队列，它们就该全部执行完。
     */
    private static final int FROZEN_TASK_PROBES = 20;

    /**
     * 冻结期间投递的任务总数 / 已执行数。
     *
     * <p>用 {@code server.execute(…)} 投递——与原版「网络包 → 任务队列」是同一条
     * 路径（{@code PacketUtils.ensureRunningOnSameThread} 就是这么排队的）。
     */
    private static int frozenTasksSubmitted;
    private static int frozenTasksExecuted;

    private enum Stage {
        START,
        CEILING_PROBE,
        CEILING_CHECK,
        QUERY_FROZEN,
        FROZEN_MARK,
        FROZEN_WAIT,
        STEP_ISSUE,
        STEP_WAIT,
        STEP_CHECK,
        POST_UNFREEZE_SETTLE,
        RATE_SET,
        RATE_WAIT,
        SPRINT_ISSUE,
        SPRINT_SETTLE,
        DONE
    }

    /** 上一次 emit 的墙钟毫秒；供「不依赖游戏刻推进」的阶段计时使用。 */
    private static long lastEmitMillis;

    private static boolean initialised;
    private static Stage stage = Stage.START;
    private static int waited;
    private static long markGameTime;
    private static long markLevelTicks;
    private static long markWallMillis;
    private static int rateProbeIndex;
    /** SPRINT_ISSUE 阶段多等几刻再断言，避开冲刺收尾的过渡帧。 */
    private static int sprintWait;
    /** 冲刺刚结束时的实测 TPS，用于验证它是否收敛回目标值。 */
    private static double measuredAtSprintEnd;
    /** 冲刺过程中抓到的显示值（每刻毫秒数），用于与冲刺报告交叉验证。 */
    private static double displayMsptDuringSprint = -1.0D;
    private static int failures;
    private static final StringBuilder RATE_REPORT = new StringBuilder();

    private SelfTest() {
    }

    /** 由 Mixin 在 {@code MinecraftServer.tickServer} 开头每刻调用。 */
    public static void tick(MinecraftServer server) {
        if (!initialised) {
            initialised = true;
            if (!Boolean.getBoolean("tickcontrol.selftest")) {
                stage = Stage.DONE;
                return;
            }
            emit("enabled (gameTime=" + gameTime(server) + ")");
        }
        if (stage == Stage.DONE) {
            return;
        }
        if (waited++ < START_DELAY) {
            return;
        }

        switch (stage) {
            case START -> {
                emit("--- 1. /tick query (initial)");
                run(server, "tick query");
                // 先量 CPU 上限，后面的速率判定要用它来区分「控制错误」和「硬件到顶」。
                // 放在冻结/步进之前，是因为 sprint 会让游戏真实推进一段。
                emit("--- 1b. CPU ceiling probe via /tick sprint " + SPRINT_CEILING_TICKS
                        + " (sprint does not wait, so this measures the real hardware limit)");
                markGameTime = gameTime(server);
                markWallMillis = System.currentTimeMillis();
                run(server, "tick sprint " + SPRINT_CEILING_TICKS);
                sprintWait = 0;
                stage = Stage.CEILING_PROBE;
                waited = 0;
            }
            case CEILING_PROBE -> {
                long ticks = gameTime(server) - markGameTime;
                boolean timedOut = System.currentTimeMillis() - markWallMillis > 120_000L;
                if (ticks < SPRINT_CEILING_TICKS && !timedOut) {
                    return;
                }
                if (sprintWait++ < 2) {
                    return;
                }
                double ceilingSeconds = Math.max(0.001D,
                        (System.currentTimeMillis() - markWallMillis) / 1000.0D);
                measuredCeilingTps = ticks / ceilingSeconds;
                emit("--- 1c. measured CPU ceiling: "
                        + String.format(Locale.ROOT, "%.1f", measuredCeilingTps)
                        + " TPS (" + ticks + " ticks in "
                        + String.format(Locale.ROOT, "%.2f", ceilingSeconds) + "s)");
                if (measuredCeilingTps > 100.0D) {
                    pass("CPU ceiling is " + String.format(Locale.ROOT, "%.0f", measuredCeilingTps)
                            + " TPS; /tick rate above this is CPU-bound, not a rate-control bug");
                } else {
                    fail("sprint ceiling probe looks wrong ("
                            + String.format(Locale.ROOT, "%.1f", measuredCeilingTps)
                            + " TPS) - sprint may not be running");
                }
                emit("--- 2. /tick freeze");
                run(server, "tick freeze");
                emit("--- 2b. /tick query (must report frozen)");
                run(server, "tick query");
                stage = Stage.CEILING_CHECK;
                waited = 0;
            }
            case CEILING_CHECK -> {
                // 冻结后等一小段（不依赖游戏刻推进，因为此时已经冻结）
                if (System.currentTimeMillis() - lastEmitMillis < 1500L) {
                    return;
                }
                run(server, "tick query");
                stage = Stage.FROZEN_MARK;
                waited = 0;
            }
            case QUERY_FROZEN -> {
                // 保留的分支：历史版本在这里补查一次 query
                run(server, "tick query");
                stage = Stage.FROZEN_MARK;
                waited = 0;
            }
            case FROZEN_MARK -> {
                markGameTime = gameTime(server);
                markLevelTicks = controller(server).levelTicks();
                // 冻结期间投递任务：主循环只要还在排空队列，下面的 FROZEN_WAIT
                // 阶段就应该看到它们全部执行完（熔炉打不开那个 bug 的回归断言）。
                frozenTasksSubmitted = 0;
                frozenTasksExecuted = 0;
                for (int i = 0; i < FROZEN_TASK_PROBES; i++) {
                    server.execute(() -> frozenTasksExecuted++);
                    frozenTasksSubmitted++;
                }
                emit("--- 3. queued " + frozenTasksSubmitted
                        + " tasks while frozen (same path as a right-click packet)");
                stage = Stage.FROZEN_WAIT;
                waited = 0;
            }
            case FROZEN_WAIT -> {
                if (waited < FREEZE_WAIT) {
                    return;
                }
                if (frozenTasksExecuted == frozenTasksSubmitted) {
                    pass("server task queue keeps draining while frozen ("
                            + frozenTasksExecuted + "/" + frozenTasksSubmitted
                            + ") -- right-click interactions stay usable");
                } else {
                    fail("task queue starved while frozen (" + frozenTasksExecuted + "/"
                            + frozenTasksSubmitted + " executed); right-click interactions"
                            + " (furnace/chest) will look dead");
                }
                long nowGameTime = gameTime(server);
                long levelTicks = controller(server).levelTicks() - markLevelTicks;
                long delta = nowGameTime - markGameTime;
                emit("--- 4. freeze check: gameTime " + markGameTime + " -> " + nowGameTime
                        + " (delta=" + delta + "), world ticks=" + levelTicks);
                if (delta == 0L && levelTicks == 0L) {
                    pass("ticking is frozen (neither game time nor world ticks advanced)");
                } else {
                    fail("still advancing while frozen (gameTime delta=" + delta
                            + ", world ticks=" + levelTicks + ")");
                }
                emit("--- 5. /tick step " + STEP_TICKS);
                markGameTime = nowGameTime;
                run(server, "tick step " + STEP_TICKS);
                stage = Stage.STEP_ISSUE;
                waited = 0;
            }
            case STEP_ISSUE -> {
                if (waited < STEP_TICKS * 3 + 20) {
                    return;
                }
                long now = gameTime(server);
                long delta = now - markGameTime;
                emit("--- 5. step check: gameTime " + markGameTime + " -> " + now + " (delta=" + delta + ")");
                if (delta == STEP_TICKS) {
                    pass("stepping advanced game time by exactly " + STEP_TICKS);
                } else if (delta > STEP_TICKS) {
                    pass("stepping advanced game time by " + delta + " (>= requested " + STEP_TICKS + ")");
                } else {
                    fail("stepping only advanced " + delta + " ticks, expected " + STEP_TICKS);
                }
                markGameTime = now;
                stage = Stage.STEP_CHECK;
                waited = 0;
            }
            case STEP_CHECK -> {
                if (waited < FREEZE_WAIT) {
                    return;
                }
                long delta = gameTime(server) - markGameTime;
                if (delta == 0L) {
                    pass("re-frozen after step finished");
                } else {
                    fail("still advancing after step finished (delta=" + delta + ")");
                }
                emit("--- 6. /tick unfreeze");
                run(server, "tick unfreeze");
                stage = Stage.POST_UNFREEZE_SETTLE;
                waited = 0;
            }
            case POST_UNFREEZE_SETTLE -> {
                // 解冻瞬间会有一波"补课洪峰"：冻结期间堆积的区块工作、任务队列、
                // 实体与随机刻会在恢复的那一刻一起跑，主循环被占用、节拍被拖慢。
                // 若把这段算进"速率探针"的测量窗口，读数会离谱地低——
                // 实测：冻结 60 刻后立刻测 rate 20，只有 2.64 TPS（应为 20）。
                // 先让它自由跑几秒把洪峰消化掉，再开始测量。
                if (System.currentTimeMillis() - lastEmitMillis < 3000L) {
                    return;
                }
                emit("      (post-unfreeze settle done; starting rate probes)");
                rateProbeIndex = 0;
                stage = Stage.RATE_SET;
                waited = 0;
            }
            case RATE_SET -> {
                float rate = RATE_PROBES[rateProbeIndex];
                emit("--- 7." + (rateProbeIndex + 1) + " rate probe: /tick rate " + (int) rate);
                run(server, "tick rate " + (int) rate);
                run(server, "tick query");
                markGameTime = gameTime(server);
                markWallMillis = System.currentTimeMillis();
                stage = Stage.RATE_WAIT;
                waited = 0;
            }
            case RATE_WAIT -> {
                if (System.currentTimeMillis() - markWallMillis < rateWindowMillis(RATE_PROBES[rateProbeIndex])) {
                    return;
                }
                float rate = RATE_PROBES[rateProbeIndex];
                long ticks = gameTime(server) - markGameTime;
                double seconds = (System.currentTimeMillis() - markWallMillis) / 1000.0D;
                double measured = ticks / seconds;
                double ratio = measured / rate;
                RATE_REPORT.append(String.format(Locale.ROOT,
                        "%n    rate=%-5.0f measured=%-7.2f TPS  overworldTicks=%-5d wall=%.2fs  ratio=%.2f",
                        rate, measured, ticks, seconds, ratio));
                // 高速档的判定要区分「节拍器算错了」与「CPU 到顶了」。
                // 上限由步骤 13 的 sprint 探针实测（sprint 不等待，跑出来的就是
                // 这台机器在集成服务端 + 渲染线程抢 CPU 下的真实天花板）。
                // 目标速率超过上限的 75% 时直接判为 CPU 受限，而不是速率控制失败。
                boolean overCpuCeiling = measuredCeilingTps > 0.0D && rate > measuredCeilingTps * 0.75D;
                boolean ratioOk = ratio > 0.75D && ratio < 1.25D;
                if (overCpuCeiling) {
                    emit(String.format(Locale.ROOT,
                            "      CPU-BOUND: target %.0f TPS exceeds 75%% of the measured ceiling (%.0f TPS);"
                                    + " measured %.2f TPS (ratio %.2f) is the hardware limit, not a control error",
                            rate, measuredCeilingTps, measured, ratio));
                    pass("rate " + (int) rate + " is above the measured CPU ceiling ("
                            + String.format(Locale.ROOT, "%.0f", measuredCeilingTps)
                            + " TPS); measured " + String.format(Locale.ROOT, "%.2f", measured)
                            + " TPS (ratio " + String.format(Locale.ROOT, "%.2f", ratio) + ")");
                } else if (ratioOk) {
                    pass("rate " + (int) rate + " -> " + String.format(Locale.ROOT, "%.2f", measured)
                            + " TPS (ratio " + String.format(Locale.ROOT, "%.2f", ratio) + ")");
                } else {
                    fail("rate " + (int) rate + " -> " + String.format(Locale.ROOT, "%.2f", measured)
                            + " TPS, expected ~" + (int) rate);
                }
                rateProbeIndex++;
                if (rateProbeIndex < RATE_PROBES.length) {
                    stage = Stage.RATE_SET;
                } else {
                    emit("--- 8. rate measurements:" + RATE_REPORT);
                    // 冲刺测试：把目标速率压到 1 TPS，再请求 SPRINT_TICKS 刻。
                    // 冲刺期间不等待，因此实际速率应远高于 1 TPS。
                    emit("--- 9. sprint: /tick rate " + (int) SPRINT_BASE_RATE
                            + " then /tick sprint " + SPRINT_TICKS);
                    run(server, "tick rate " + (int) SPRINT_BASE_RATE);
                    markGameTime = gameTime(server);
                    markWallMillis = System.currentTimeMillis();
                    run(server, "tick sprint " + SPRINT_TICKS);
                    sprintWait = 0;
                    stage = Stage.SPRINT_ISSUE;
                    waited = 0;
                }
                waited = 0;
            }
            case SPRINT_ISSUE -> {
                long ticks = gameTime(server) - markGameTime;
                boolean timedOut = System.currentTimeMillis() - markWallMillis > 30_000L;
                if (ticks < SPRINT_TICKS && !timedOut) {
                    // 冲刺还没跑完：持续抓取「当前显示值」的最后一次采样。
                    // 冲刺结束时 pacer 会重置测量，所以必须在过程中抓。
                    displayMsptDuringSprint = controller(server).measuredMillisPerTick();
                    return;
                }
                // 刻数达标的那一刻，第 SPRINT_TICKS 个冲刺刻仍处在 tickServer() 内
                // （本方法就是在 tickServer() 开头被调用的），而冲刺收尾发生在
                // tickServer() 之后的 endTickWork()。因此必须再等两刻才能断言状态，
                // 否则永远看到「仍在冲刺」。
                if (sprintWait++ < 2) {
                    return;
                }
                double seconds = Math.max(0.001D, (System.currentTimeMillis() - markWallMillis) / 1000.0D);
                double measured = ticks / seconds;
                emit("--- 10. sprint check: gameTime +" + ticks + " in "
                        + String.format(Locale.ROOT, "%.2f", seconds) + "s -> "
                        + String.format(Locale.ROOT, "%.1f", measured) + " TPS");
                if (timedOut) {
                    fail("sprint did not finish within 30s (advanced only " + ticks + " of "
                            + SPRINT_TICKS + " ticks)");
                } else if (ticks >= SPRINT_TICKS) {
                    pass("sprint advanced " + ticks + " ticks");
                    if (measured > SPRINT_BASE_RATE * 5.0D) {
                        pass("sprint ran far faster than the " + (int) SPRINT_BASE_RATE
                                + " TPS target: " + String.format(Locale.ROOT, "%.1f", measured) + " TPS");
                    } else {
                        fail("sprint was not faster than the target rate ("
                                + String.format(Locale.ROOT, "%.1f", measured) + " TPS at "
                                + (int) SPRINT_BASE_RATE + " TPS target)");
                    }
                }
                // 此时 endTickWork() 已经执行过，冲刺状态应已收尾
                if (controller(server).isSprinting()) {
                    fail("still sprinting after the requested ticks were consumed"
                            + " [sprintState=" + controller(server).debugSprintState() + "]");
                } else {
                    pass("sprint ended and normal ticking resumed");
                }
                // ---- 交叉验证：HUD 显示值 vs 独立的间隔派生实测 ----
                // 说明：这里刻意<b>不</b>把 display 值拿去比「整段墙钟」速率——两者测的
                // 不是同一个量。冲刺期间主循环除 tickServer 外还有每轮固定开销
                // （会话记录、JVM profiler 等），不在「刻与刻的间隔」里，
                // 因此整段墙钟速率会明显低于间隔派生速率。HUD 显示的是后者。
                //
                // 也不能在这里额外 consume（会把基础快照覆盖），
                // 所以直接对比「过程中抓到的显示值」与「冲刺报告里的间隔派生值」。
                ServerTickController sprintController = controller(server);
                double displayMspt = displayMsptDuringSprint;
                double displayTps = displayMspt > 0.0D ? 1000.0D / displayMspt : -1.0D;
                double intervalMspt = sprintController.finishedSprintMspt();
                double intervalTps = intervalMspt > 0.0D ? 1000.0D / intervalMspt : -1.0D;
                emit("--- 10b. sprint rates:");
                emit("      HUD displayed         = " + String.format(Locale.ROOT, "%.1f", displayTps)
                        + " TPS (" + String.format(Locale.ROOT, "%.4f", displayMspt)
                        + " ms/tick, interval-derived)");
                emit("      report interval-derived= " + String.format(Locale.ROOT, "%.1f", intervalTps)
                        + " TPS (" + String.format(Locale.ROOT, "%.4f", intervalMspt) + " ms/tick)");
                emit("      report wall-clock      = "
                        + String.format(Locale.ROOT, "%.1f", sprintController.finishedSprintTps())
                        + " TPS over " + sprintController.finishedSprintTicks()
                        + " ticks (includes per-iteration overhead)");
                // 断言说明：两个值<b>不必逐位相等</b>，因为窗口不同——
                // HUD 是 100 刻的「当前瞬时速率」，报告是整段 600 刻的总平均；
                // 冲刺过程中 JIT 预热与缓存状态变化会让瞬时快于全程平均。
                // 有意义的结论是「HUD 显示的是真实量级、而不是陈旧值或 20」。
                double ratio = (displayTps > 0.0D && intervalTps > 0.0D)
                        ? displayTps / intervalTps : -1.0D;
                emit("      display/report ratio    = " + String.format(Locale.ROOT, "%.2f", ratio)
                        + " (windows differ: 100 ticks instantaneous vs whole-sprint average)");
                if (displayTps > 20.0D && ratio > 0.3D && ratio < 3.0D) {
                    pass("HUD displayed a realistic sprint rate ("
                            + String.format(Locale.ROOT, "%.0f", displayTps)
                            + " TPS), not the stale 20");
                } else {
                    fail("HUD displayed rate looks wrong (display="
                            + String.format(Locale.ROOT, "%.1f", displayTps)
                            + " ratio=" + String.format(Locale.ROOT, "%.2f", ratio) + ")");
                }
                emit("--- 11. /tick step stop (should FAIL, not stepping)");
                run(server, "tick step stop");
                // ---- 12. 冲刺结束后实测 TPS 必须收敛回目标值 ----
                // 这是「不加速时 TPS 间歇跳到 20+」的回归测试：
                // 最初的固定 1 秒窗口在冲刺结束后仍残留冲刺期间的上万刻，
                // 读数要整整 1 秒才降下来。改成逐刻间隔的指数平均后应很快收敛。
                // 把目标速率设回 20 再观察收敛：
                // 若沿用冲刺测试的 1 TPS，0.7 秒内只发生 0~1 个刻，
                // 指数平均根本没有样本可用，测不出「是否收敛」。
                run(server, "tick rate 20");
                markWallMillis = System.currentTimeMillis();
                measuredAtSprintEnd = controller(server).measuredMillisPerTick();
                stage = Stage.SPRINT_SETTLE;
                waited = 0;
            }
            case SPRINT_SETTLE -> {
                if (System.currentTimeMillis() - markWallMillis < 2500L) {
                    return;
                }
                double settledMspt = controller(server).measuredMillisPerTick();
                double targetMspt = 1000.0D / 20.0D;
                emit("--- 12. measured MSPT convergence: at sprint end="
                        + String.format(Locale.ROOT, "%.2f", measuredAtSprintEnd)
                        + "ms -> after settle=" + String.format(Locale.ROOT, "%.2f", settledMspt)
                        + "ms (target " + String.format(Locale.ROOT, "%.2f", targetMspt) + "ms)");
                if (settledMspt > 0.0D && Math.abs(settledMspt - targetMspt) / targetMspt < 0.15D) {
                    pass("measured MSPT settled back to the target rate after sprint");
                } else {
                    fail("measured MSPT did not settle after sprint ("
                            + String.format(Locale.ROOT, "%.2f", settledMspt)
                            + "ms vs target " + String.format(Locale.ROOT, "%.2f", targetMspt) + "ms)");
                }
                emit("=== RESULT: " + (failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"));
                stage = Stage.DONE;
                if (Boolean.getBoolean("tickcontrol.selftest.stop")) {
                    emit("stopping server");
                    server.halt(false);
                }
                waited = 0;
            }
            default -> {
            }
        }
    }

    private static long gameTime(MinecraftServer server) {
        try {
            return server.overworld().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static ServerTickController controller(MinecraftServer server) {
        TickControlAccess access = TickControl.forServer(server);
        if (access instanceof ServerTickController controller) {
            return controller;
        }
        throw new IllegalStateException("TickControl controller not registered for server");
    }

    private static void run(MinecraftServer server, String command) {
        try {
            CommandSourceStack source = server.createCommandSourceStack();
            // 1.18.2 的 Commands 只有 performCommand（1.19+ 才有 performPrefixedCommand）。
            // 它内部同样用 getCommands() 补前导斜杠，语义一致。
            server.getCommands().performCommand(source, command);
            // 命令的反馈消息是通过任务队列广播的，这里补一次排空，
            // 让「命令有没有真的产出反馈」在日志里立刻可见（而不是要等下一轮）。
            if (command.startsWith("tick query")) {
                for (int i = 0; i < 64 && server.pollTask(); i++) {
                    // drain
                }
            }
        } catch (Throwable t) {
            fail("command threw: /" + command + " -> " + t);
        }
    }

    private static void pass(String message) {
        emit("PASS: " + message);
    }

    private static void fail(String message) {
        failures++;
        emit("FAIL: " + message);
    }

    /**
     * 输出一行自检结果。
     *
     * <p>用 logger 而不是 {@code System.out}：正式客户端的 stdout 不写进
     * {@code logs/*.log}，用 println 会让整套自检在排查时"查无此结果"。
     */
    private static void emit(String message) {
        lastEmitMillis = System.currentTimeMillis();
        LOGGER.info(PREFIX + message);
    }
}