
package com.tamamo.tickcontrol.core;

import java.util.Locale;

import com.tamamo.tickcontrol.command.TickControl;

import net.minecraft.command.CommandSource;
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

    /** 启动后先等这么多刻，让世界与命令系统就绪。 */
    private static final int START_DELAY = 40;

    /** 冻结后等这么多刻再比对（应完全不变）。 */
    private static final int FREEZE_WAIT = 60;

    /** 步进多少刻。 */
    private static final int STEP_TICKS = 50;

    /** 速率测量窗口（毫秒，墙钟）。 */
    private static final long RATE_WINDOW_MILLIS = 6000L;

    /** 依次测量的目标速率。 */
    private static final float[] RATE_PROBES = {20.0F, 500.0F, 1.0F, 1000.0F, 40.0F, 200.0F};

    /** 冲刺请求的刻数：足够大到能观察出「远快于目标速率」。 */
    private static final int SPRINT_TICKS = 600;

    /** 冲刺期间目标速率压到 1 TPS，这样「快」与「慢」的差别极其明显。 */
    private static final float SPRINT_BASE_RATE = 1.0F;

    private enum Stage {
        START,
        FROZEN_MARK,
        FROZEN_WAIT,
        STEP_ISSUE,
        STEP_WAIT,
        STEP_CHECK,
        RATE_SET,
        RATE_WAIT,
        SPRINT_ISSUE,
        SPRINT_SETTLE,
        DONE
    }

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
                emit("--- 2. /tick freeze");
                run(server, "tick freeze");
                run(server, "tick query");
                stage = Stage.FROZEN_MARK;
                waited = 0;
            }
            case FROZEN_MARK -> {
                markGameTime = gameTime(server);
                markLevelTicks = controller(server).levelTicks();
                stage = Stage.FROZEN_WAIT;
                waited = 0;
            }
            case FROZEN_WAIT -> {
                if (waited < FREEZE_WAIT) {
                    return;
                }
                long nowGameTime = gameTime(server);
                long levelTicks = controller(server).levelTicks() - markLevelTicks;
                long delta = nowGameTime - markGameTime;
                emit("--- 3. freeze check: gameTime " + markGameTime + " -> " + nowGameTime
                        + " (delta=" + delta + "), world ticks=" + levelTicks);
                if (delta == 0L && levelTicks == 0L) {
                    pass("ticking is frozen (neither game time nor world ticks advanced)");
                } else {
                    fail("still advancing while frozen (gameTime delta=" + delta
                            + ", world ticks=" + levelTicks + ")");
                }
                emit("--- 4. /tick step " + STEP_TICKS);
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
                if (System.currentTimeMillis() - markWallMillis < RATE_WINDOW_MILLIS) {
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
                if (ratio > 0.75D && ratio < 1.25D) {
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
        // Classic instanceof + cast: 1.16.5 is a Java 8 target, so Java 16
        // pattern matching does not compile (see ServerLoop's identical note).
        if (access instanceof ServerTickController) {
            return (ServerTickController) access;
        }
        throw new IllegalStateException("TickControl controller not registered for server");
    }

    private static void run(MinecraftServer server, String command) {
        try {
            CommandSource source = commandSource(server);
            server.getCommands().performPrefixedCommand(source, command);
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

    private static void emit(String message) {
        System.out.println(PREFIX + message);
        System.out.flush();
    }
}