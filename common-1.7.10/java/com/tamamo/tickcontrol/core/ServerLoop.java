package com.tamamo.tickcontrol.core;

import net.minecraft.server.MinecraftServer;

/**
 * 1.7.10 的主循环接管 —— 对齐 1.12.2 / 1.20.3 的架构。
 *
 * <h2>为什么必须接管循环,而不是"改睡眠常量"</h2>
 *
 * <p>原版 {@code run()} 的循环是自校正的:
 *
 * <pre>
 *   i += elapsed;                        // 累积欠账
 *   while (i &gt; 50) { i -= 50; tick(); }  // 落后就补跑
 *   sleep(max(1, 50 - i));
 * </pre>
 *
 * <p>{@code i} 是累积欠账,所以任何"只把睡眠拉长"的改动都会被下一轮的追赶循环用补跑抵消。
 * 实测证据(BENCH 探针):主循环已降到 1 次/秒,而世界时间仍涨 20 刻/秒 —— 正好抵消。
 *
 * <p>高版本的做法是每轮只问一次节拍器、放行才 tick、然后睡到<b>绝对截止时刻</b>。
 * 本类移植该结构,循环体里没有追赶,速率因此守得住。
 */
public final class ServerLoop {

    private ServerLoop() {
    }

    /** 只在首次 tick 失败时告警一次,避免主循环刷屏。 */
    private static boolean warnedTickFailure;

    /**
     * 接管原版 while 循环,直到服务器停止才返回。
     *
     * @param server 服务器实例(原版 {@code aload_0})
     */
    public static void runServerLoop(MinecraftServer server) {
        while (LoopAccess.isRunning(server)) {
            ServerTickController controller = TickControlAccessHolder.controller(server);
            long now = System.currentTimeMillis();

            // ---- 每轮一次:复位本刻标志(必须在 prepareTick 之前) ----
            //
            // 接管循环后 tickSleepMillis() 不再执行,而它是 resetTickFlags() 的唯一调用点。
            // 不复位则 prepareTickDone 永远为 true:状态机只推进一次(冻结/步进失效),
            // 且刻耗时统计与 BENCH 计数只跑一次(/tick query 数字不再更新)。
            TickControlRuntime.beginTick();

            // ---- 每轮一次:推进状态机(冻结/步进/速率) ----
            //
            // ⚠️ 这一步曾经漏掉,导致 /tick freeze 完全失效:
            // 旧代码在 worldTickIfRunning(位于 updateTimeLightAndEntities 内)里调用
            // prepareTick(),而接管循环后我们直接调 server.tick(),那条路径不再负责推进
            // 状态机,于是 runGameElements 永远保持旧值,世界门控
            //   if (!controller.runsGameElements()) return;
            // 从不拦截 —— 熔炉照烧、红石照转、实体照动(用户实测)。
            //
            // 顺序要求:必须在 tick 之前。prepareTick() 决定的 runGameElements 是<b>本刻</b>
            // 的取值,而 worldTickIfRunning / worldUpdateEntitiesIfRunning 会在
            // server.tick() 执行期间读它。
            controller.prepareTick();

            // ---- 速率门控:只有节拍器放行(或冲刺中)才推进一个游戏刻 ----
            boolean sprint = controller.checkShouldSprintThisTick();
            boolean ticked = sprint || controller.allowTick();
            if (ticked) {
                // ---- 实测"处理耗时",与 1.20.1 / 1.21.1 的语义一致 ----
                //
                // 上游显示的是<b>每刻处理用时</b>(1.21.1 实测 0.1 毫秒),而不是刻间隔。
                // 之前用 LoopPacer.measuredMillisPerTick() 量的是"相邻两刻的间隔"
                // (实测 1.19 毫秒,因为每轮还含睡眠与循环开销),语义不同,所以和高版本对不上。
                //
                // 同时 TickStats 一直空着 —— 接管循环后没人再调用 recordTickTime(),
                // 于是百分位显示全是 0.00。这里在 tick() 前后直接计时并喂给 TickStats。
                long tickStart = System.nanoTime();
                if (!LoopAccess.tick(server) && !warnedTickFailure) {
                    warnedTickFailure = true;
                    System.err.println("[tickcontrol] WARNING: tick() could not be invoked;"
                            + " the loop is running but no game ticks advance");
                }
                controller.recordTickTime(System.nanoTime() - tickStart);
                controller.endTickWork();
                controller.recordTickCompletion();
                controller.noteLevelTick();
            }

            // ---- 冲刺报告投递 ----
            //
            // 必须在每轮都问一次:endTickWork() 在最后一刻跑完时置报告标志,
            // consumeSprintReport() 只在那一轮返回 true,内部随即清掉标志,所以只会发一条。
            // (接管循环后原来的投递点 doSleep 不再执行,这里是新的唯一投递处。)
            TickControlRuntime.deliverSprintReportIfReady(server, controller);

            // 维护原版时钟字段(看门狗判据依赖它)。
            LoopAccess.setCurrentTime(server, now);
            // 每轮写 ready 标志,与原版一致。
            LoopAccess.markReady(server);

            // ---- 睡到下一个刻的绝对截止时刻 ----
            //
            // ⚠️ 冲刺期间必须<b>完全不睡</b>。
            //
            // loopPeriodMillis() 是按 rate 算出来的,冲刺时它并不会变成 0,所以若照常睡,
            // 一场 /tick sprint 1d(24000 刻)就要 24000 × 50ms ≈ 20 分钟 —— 实测 34 秒内
            // 一点都跑不完,命令看起来"没生效"。高版本冲刺时把 nanosecondsPerTick 视为 0、
            // waitUntilNextTick() 立即返回,就是这个道理。
            if (sprint) {
                continue;               // 冲刺:不睡,直接下一轮
            }
            long periodMillis = Math.max(1L, controller.periodNanos() / 1_000_000L);
            long deadline = now + periodMillis;
            while (true) {
                long waitMs = deadline - System.currentTimeMillis();
                if (waitMs <= 0L) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(waitMs, 25L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
