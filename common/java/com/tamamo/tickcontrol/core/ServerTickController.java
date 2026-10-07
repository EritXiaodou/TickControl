
package com.tamamo.tickcontrol.core;

/**
 * 每个 {@code MinecraftServer} 实例持有一份的运行时控制器。
 *
 * <p>由各平台的 Mixin 在 {@code MinecraftServer.tickServer} 里创建/取得，
 * 命令层通过 {@link com.tamamo.tickcontrol.command.TickControl} 取用。
 *
 * <p>所有字段都只在服务器主线程被访问，因此不需要加锁。
 */
public final class ServerTickController implements TickControlAccess {

    /**
     * 每刻耗时统计窗口大小。
     *
     * <p>取 100 与原版 {@code MinecraftServer.tickTimes} 的长度一致。
     */
    private static final int STATS_WINDOW = 100;

    private final TickState state = new TickState();
    private final TickStats stats = new TickStats(STATS_WINDOW);
    private final LoopPacer pacer = new LoopPacer();

    /** 本刻游戏内容是否应当推进；由 {@link #prepareTick()} 每刻刷新。 */
    private boolean runGameElements = true;

    /** 世界 tick 计数，供自检断言「冻结期间世界完全没推进」。 */
    private long levelTicks;

    /** 主循环迭代计数，仅用于诊断。 */
    private long loopIterations;

    /** 记录一次服务器 tick 的耗时，供 {@code /tick query} 使用。 */
    public void recordTickTime(long nanos) {
        this.stats.record(nanos);
    }

    /** 世界被真正 tick 一次。 */
    public void noteLevelTick() {
        this.levelTicks++;
    }

    public long levelTicks() {
        return this.levelTicks;
    }

    /** 主循环迭代计数，仅用于诊断。 */
    public void noteLoopIteration() {
        this.loopIterations++;
    }

    public long loopIterations() {
        return this.loopIterations;
    }

    // ------------------------------------------------------------------
    // 主循环：速率控制（见 LoopPacer 的类注释）
    // ------------------------------------------------------------------

    /** 目标周期（毫秒）。 */
    public long loopPeriodMillis() {
        return nextTickIntervalMillis();
    }

    /** 目标周期（纳秒）：20 TPS 到 50_000_000。 */
    public long periodNanos() {
        return LoopPacer.millisToNanos(loopPeriodMillis());
    }

    /**
     * 本刻是否应当推进游戏内容。
     *
     * <p>供两处使用：{@code haveTime()} 的替换（决定 {@code tickServer} 内部
     * 是否推进世界），以及主循环里「本轮补跑几个游戏刻」的循环条件。
     */
    public boolean allowTick() {
        return this.pacer.allowTick(periodNanos());
    }

    /** 等到下一个游戏刻时刻（替代 1.20.1 的 {@code waitUntilNextTick()}）。 */
    public void awaitNextTick(LoopPacer.TaskDrain drainTasks, LoopPacer.RunningFlag keepRunning) {
        this.pacer.awaitNextTick(periodNanos(), drainTasks, keepRunning);
    }

    // ---- 冲刺（/tick sprint）：委托给节拍器，见 LoopPacer ----

    /** 冲刺期间不允许冻结（与上游一致）。 */
    public boolean canFreeze() {
        return this.state.canFreeze();
    }

    /** 是否正在冲刺。 */
    public boolean isSprinting() {
        return this.pacer.isSprinting();
    }

    /**
     * 请求冲刺指定刻数。
     *
     * <p>与上游 {@code requestGameToSprint} 一致：临时解冻（由 {@link TickState} 记录
     * 原冻结状态，结束后恢复），并返回之前是否已经在冲刺。
     */
    public boolean requestSprint(int ticks) {
        boolean already = this.pacer.requestSprint(ticks);
        if (ticks > 0) {
            this.state.beginSprint();
            // 冲刺前后一刻的长度会突变（冲刺期间不等待），重置测量
            this.pacer.resetMeasurement();
        }
        return already;
    }

    /**
     * 每轮主循环调用：返回本刻是否以「不等待」方式立即推进。
     *
     * <p>冲刺结束时负责恢复冲刺前的冻结状态并产出实测 TPS 报告。
     */
    public boolean checkShouldSprintThisTick() {
        boolean sprint = this.pacer.checkShouldSprintThisTick();
        if (!sprint && !this.pacer.isSprinting()) {
            if (this.state.isSprinting()) {
                // 冲刺刚结束：恢复原冻结状态
                // （对应上游 finishTickSprint 里的 setFrozen(previousIsFrozen)）
                this.state.endSprint();
                // 刻长从「尽可能快」回到目标周期，重置测量避免陈旧采样拖尾
                this.pacer.resetMeasurement();
            }
            if (this.pacer.sprintReportPending()) {
                this.sprintReportPending = true;
            }
        }
        return sprint;
    }

    /** 是否有待发送的冲刺报告（主循环消费一次后清除）。 */
    public boolean consumeSprintReport() {
        if (this.sprintReportPending) {
            this.sprintReportPending = false;
            // 留下副本：报告本身只发一次，但「这次冲刺的实测速率」需要能被后续
            // 交叉验证读取（自检会把显示值与独立的墙钟实测值对比）。
            this.finishedSprintTps = this.pacer.lastSprintTps();
            this.finishedSprintTicks = this.pacer.lastSprintTicks();
            this.finishedSprintMspt = this.pacer.lastSprintMillisPerTick();
            return true;
        }
        return false;
    }

    /** 上一次已结束冲刺的实测 TPS（消费报告后仍可读；&lt;= 0 表示无）。 */
    public double finishedSprintTps() {
        return this.finishedSprintTps;
    }

    /** 上一次已结束冲刺的实际刻数。 */
    public long finishedSprintTicks() {
        return this.finishedSprintTicks;
    }

    /**
     * 上一次已结束冲刺的「间隔派生」每刻毫秒数。
     *
     * <p>与 {@link #finishedSprintTps()}（整段墙钟速率）是<b>两个不同的量</b>：
     * 冲刺期间主循环除 {@code tickServer} 外还有每轮固定开销（会话记录、
     * JVM profiler 等），这部分不在刻与刻的间隔里，所以整段墙钟速率会明显低于
     * 间隔派生速率。HUD 显示的是后者。
     */
    public double finishedSprintMspt() {
        return this.finishedSprintMspt;
    }

    private double finishedSprintMspt = -1.0D;

    private double finishedSprintTps = -1.0D;
    private long finishedSprintTicks;

    /**
     * 清掉冲刺结果，保证报告只发一次。
     *
     * <p>不清的话 {@link LoopPacer#sprintReportPending()}（只看 {@code lastSprintTps > 0}）
     * 会一直为真，冲刺结束后主循环每轮都会再报一次，把日志刷到数百万行。
     */
    public void clearSprintReport() {
        this.sprintReportPending = false;
        this.pacer.clearSprintReport();
    }

    /**
     * 供自检使用：标记一份待发报告，用于验证「只发一次」语义。
     *
     * <p>保留它是因为该回归一旦发生会造成灾难性后果
     * （日志被刷到数百万行），值得有一个常驻的断言守着。
     */
    public void markSprintReportPendingForTest() {
        this.sprintReportPending = true;
    }

    private boolean sprintReportPending;

    /**
     * 冲刺刻收尾：累计本刻耗时，并在冲刺跑完时结算、恢复原冻结状态。
     *
     * <p>由主循环在 {@code tickServer} 之后调用（对应上游 {@code endTickWork()}）。
     *
     * <p><b>为什么必须在这里收尾而不是 {@code checkShouldSprintThisTick()}</b>：
     * 主循环顺序是 {@code checkShouldSprintThisTick() → tickServer() → endTickWork()}，
     * 最后一刻跑完后本轮不会再调 {@code checkShouldSprintThisTick()}，
     * 于是 {@code isSprinting()} 会在整轮里保持为真。实测该窗口会让
     * {@code /tick freeze} 的守卫与自检误判（诊断输出：
     * {@code remaining=0 scheduled=600} 却仍报 sprinting）。
     * 放在这里还能保证最后一刻的耗时被计入报告。
     */
    public void endTickWork() {
        boolean wasSprinting = this.pacer.isSprinting();
        this.pacer.finishSprintTick();
        if (wasSprinting && !this.pacer.isSprinting()) {
            // 冲刺跑完：恢复冲刺前的冻结状态，并标记有报告待发
            this.state.endSprint();
            // 冲刺结束：刻长突变，重置测量避免陈旧采样拖尾
            this.pacer.resetMeasurement();
            if (this.pacer.sprintReportPending()) {
                this.sprintReportPending = true;
            }
        }
    }

    /** 停止冲刺并立刻出结果。 */
    public boolean stopSprint() {
        boolean stopped = this.pacer.stopSprint();
        if (stopped) {
            this.state.endSprint();
            this.pacer.resetMeasurement();
        }
        return stopped;
    }

    /** 刚结束的冲刺实测 TPS；&lt;= 0 表示暂无结果。 */
    public double lastSprintTps() {
        return this.pacer.lastSprintTps();
    }

    /** 刚结束的冲刺实际刻数。 */
    public long lastSprintTicks() {
        return this.pacer.lastSprintTicks();
    }

    /** 刚结束的冲刺每刻毫秒数（对应上游报告的 s 参数）。 */
    public double lastSprintMillisPerTick() {
        return this.pacer.lastSprintMillisPerTick();
    }

    /** 诊断用：描述冲刺相关状态（自检失败时打印，便于定位过渡帧问题）。 */
    public String debugSprintState() {
        return "pacerSprinting=" + this.pacer.isSprinting()
                + " stateSprinting=" + this.state.isSprinting()
                + " frozen=" + this.state.isFrozen()
                + " stepTicks=" + this.state.getStepTicks();
    }

    // ---- 实测 TPS / MSPT（委托给节拍器，见 LoopPacer）----

    /**
     * 记录一个已完成的游戏刻（由主循环在真正 tick 之后调用）。
     *
     * <p>照抄 1.21.1 的 tick 计时：内部维护 100 刻环形缓冲与累计和。
     */
    public void recordTickCompletion() {
        this.pacer.recordTickCompletion();
    }

    /** 实测平均每刻毫秒数（对应上游 aggregatedTickTimesNanos / min(100, tickCount)）。 */
    public double measuredMillisPerTick() {
        return this.pacer.measuredMillisPerTick(periodNanos());
    }
    /** 供诊断。 */
    public long grantedTicks() {
        return this.pacer.grantedTicks();
    }

    /**
     * 节拍器截止时刻（毫秒）。
     *
     * <p>这是给 {@code haveTime()} 用的：1.21.1 的判据是
     * {@code now >= nextTickTimeNanos}，1.20.1 的等价物是
     * {@code net.minecraft.util.Util.milliTime() < nextTickTime}。把 {@code nextTickTime} 的读取
     * 换成这个值，{@code haveTime()} 就变成「到点了吗」，
     * 于是 {@code waitUntilNextTick()} 里的 {@code managedBlock} 会正确阻塞到
     * 下一个游戏刻时刻——这才是有节流的版本。
     */
    public long pacerDeadlineMillis() {
        return this.pacer.waitUntilMillis();
    }

    /**
     * {@code MinecraftServer.haveTime()} 的等价物，照抄 1.21.1：
     * {@code net.minecraft.util.Util.nanoTime() < nextTickTimeNanos}。
     *
     * <p>把 {@code deadlineNanos} 换算成毫秒与当前毫秒比较即可：
     * {@code haveTime()} 为真表示"还没到下一个游戏刻时刻 / 本刻还有时间"，
     * 因此 {@code managedBlock} 会继续等待，而传给 {@code tickServer} 的谓词
     * 则表示"本刻可以干活"。
     */
    public boolean haveTimeNow() {
        return this.pacer.timeRemainingMillis() > 0L;
    }

    // ---- 主循环时钟（对应 1.21.1 的 nextTickTimeNanos / lastOverloadWarningNanos）----

    /** 对应 1.21.1 的 {@code this.nextTickTimeNanos = net.minecraft.util.Util.nanoTime();}。 */
    public void resetClock() {
        this.pacer.resetClock();
    }

    /**
     * 距下一个游戏刻还剩多少纳秒（可为负）。
     *
     * <p>供主循环的等待逻辑使用：因为不再调用原版的
     * {@code MinecraftServer.waitUntilNextTick()}（它的轮询条件依赖原版计时字段，
     * 而我们不更新那些字段，会导致死循环），改由本值自己睡。
     */
    public long timeRemainingNanos() {
        return this.pacer.timeRemainingNanos();
    }

    /** 落后量（纳秒）。 */
    public long behindNanos() {
        return this.pacer.behindNanos();
    }

    /** 距上次过载警告的纳秒数。 */
    public long nanosSinceOverloadWarning() {
        return this.pacer.nanosSinceOverloadWarning();
    }

    /** 记录一次过载警告。 */
    public void noteOverloadWarning() {
        this.pacer.noteOverloadWarning();
    }

    /** 当前截止时刻（毫秒），对应 1.21.1 的 nextTickTimeNanos 折算值。 */
    public long deadlineMillis() {
        return this.pacer.deadlineMillis();
    }
    // ------------------------------------------------------------------
    // 冻结门控
    // ------------------------------------------------------------------

    /** 本刻游戏内容是否推进；对应原版 {@code TickRateManager#runsNormally()} 的本刻取值。 */
    public boolean runsGameElements() {
        return this.runGameElements;
    }

    /**
     * 世界（{@code ServerLevel}）本刻是否应当推进。
     *
     * <p>与原版 1.20.3 一致：冻结时不 tick 世界，但主循环、网络层
     * （{@code ServerConnectionListener}）、{@code PlayerList} 与玩家自身照常 tick，
     * 从而保证不触发看门狗、也不会把玩家踢下线。
     */
    public boolean shouldTickLevels() {
        return this.runGameElements;
    }

    // ------------------------------------------------------------------
    // TickControlAccess
    // ------------------------------------------------------------------

    /**
     * 每刻开头调用（对应原版 {@code TickRateManager#tick()}），并额外并入速率节拍。
     *
     * <p>{@code runGameElements} 是「本刻是否推进游戏内容」的最终判据，
     * 它同时受两件事约束：
     * <ul>
     *   <li><b>冻结</b>：原版语义 {@code !isFrozen || frozenTicksToRun > 0}
     *       （见 {@link TickState#prepareTick()}）；</li>
     *   <li><b>速率</b>：由 {@link LoopPacer} 判断本刻是不是一个游戏刻。</li>
     * </ul>
     *
     * <p>世界层面的门控会用 {@link #shouldTickLevels()} 读取这个结果
     * （对应原版 {@code ServerLevel.tick} 里的
     * {@code tickRateManager.runsNormally()}）。
     */
    @Override
    public boolean prepareTick() {
        boolean frozenAllows = state.prepareTick();
        // 冻结/步进语义由 TickState 决定。步进期间必须逐刻放行（否则 /tick step N
        // 会被节拍吃掉），因此节拍器只在「未冻结」时参与限制。
        this.runGameElements = frozenAllows;
        return this.runGameElements;
    }




    @Override
    public float tickRate() {
        return state.getTickRate();
    }

    @Override
    public boolean setTickRate(float rate) {
        float before = state.getTickRate();
        state.setTickRate(rate);
        // 速率变化时按「新周期」重新对齐节拍。
        // 必须传周期进去：若只把截止时刻清零，allowTick() 会立刻额外放行一刻，
        // 低速率下会把 /tick rate 1 测成 2.86 TPS。
        this.pacer.reset(periodNanos());
        // 一刻多长变了，旧的 TPS 采样不再有参考价值
        this.pacer.resetMeasurement();
        return state.getTickRate() != before;
    }

    /** 重置实测 TPS 的采样（由状态突变处调用）。 */
    public void resetMeasurement() {
        this.pacer.resetMeasurement();
    }

    @Override
    public boolean isFrozen() {
        return state.isFrozen();
    }

    @Override
    public void setFrozen(boolean frozen) {
        state.setFrozen(frozen);
        // 冻结/解冻会改变刻的疏密，重置测量避免旧样本拖尾
        this.pacer.resetMeasurement();
    }

    @Override
    public boolean isStepping() {
        return state.isStepping();
    }

    @Override
    public int stepTicks() {
        return state.getStepTicks();
    }

    @Override
    public boolean runsNormally() {
        return state.runsNormally();
    }

    @Override
    public boolean requestStep(int ticks) {
        return state.requestStep(ticks);
    }

    @Override
    public boolean stopStepping() {
        return state.stopStepping();
    }

    @Override
    public long nextTickIntervalMillis() {
        return state.nextTickIntervalMillis(state.getTickRate());
    }

    @Override
    public TickStats stats() {
        return this.stats;
    }
}
