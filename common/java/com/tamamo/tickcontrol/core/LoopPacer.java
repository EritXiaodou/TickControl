
package com.tamamo.tickcontrol.core;

import java.util.concurrent.locks.LockSupport;


/**
 * 游戏刻节拍器：1.20.3+ {@code TickRateManager} 与主循环调度的等价实现（纳秒制）。
 *
 * <p>1.21.1 的主循环是：
 * <pre>
 * i = this.tickRateManager.nanosecondsPerTick();   // 一刻多长
 * this.nextTickTimeNanos += i;
 * this.tickServer(flag ? () -&gt; false : this::haveTime);
 * this.waitUntilNextTick();                        // 等到 nextTickTimeNanos
 * </pre>
 * 本工程把 1.20.1 的 {@code while (this.running)} 条件读取接管掉，
 * 在同一轮里按这个结构自行决定「跑几个游戏刻」（1.20.1 的主循环每轮只调一次
 * {@code tickServer}，不补跑就到不了 20 TPS 以上）。
 */
public final class LoopPacer {

    /** 单次 park 的上限，保证能及时检查 running 与任务队列。 */
    private static final long MAX_PARK_NANOS = 25_000_000L;

    /**
     * 一轮最多补跑多少个游戏刻。
     *
     * <p>1.21.1 没有这种人为上限（它靠 {@code nextTickTimeNanos} 累加器自然节流），
     * 这里只保留一个"防止异常情况下一轮永久跑不完"的兜底值。
     * 早期版本设成 256，直接把速率天花板压在约 1000 TPS
     * （2000/5000 TPS 实测都停在 ~960-997），这就是那个天花板的来源。
     */
    public static final int MAX_TICKS_PER_ITERATION = 1_000_000;

    /** 下一次允许出游戏刻的时刻（纳秒）；0 表示尚未对齐。 */
    private long deadlineNanos;

    /** 供诊断：累计放行的游戏刻数。 */
    private long grantedTicks;

    /**
     * 判断本刻是否应当推进游戏内容，并按需推进截止时刻。
     *
     * @param periodNanos 目标周期（纳秒）：20 TPS 到 50_000_000
     */
    public boolean allowTick(long periodNanos) {
        long period = Math.max(1L, periodNanos);
        this.lastPeriodNanos = period;
        long now = System.nanoTime();

        if (this.deadlineNanos == 0L) {
            this.deadlineNanos = now + period;
            this.grantedTicks++;
            return true;
        }
        if (now >= this.deadlineNanos) {
            this.deadlineNanos += period;
            if (this.deadlineNanos <= now) {
                // 服务器跟不上或刚变速：重新对齐，避免突然补一串游戏刻
                this.deadlineNanos = now + period;
            }
            this.grantedTicks++;
            return true;
        }
        return false;
    }

    /**
     * 等到下一个游戏刻时刻；等待期间反复排空任务队列。
     *
     * <p>对应 1.21.1 {@code waitUntilNextTick()} 里的 {@code managedBlock(haveTime)}：
     * 它在等待时用 {@code pollTaskInternal()} 取任务，并在取到任务时立刻处理——
     * 区块加载与世界生成的完成回调依赖这一点。
     *
     * @param periodNanos 目标周期（纳秒）
     * @param drainTasks  排空任务的动作，返回是否取到了任务
     * @param keepRunning 主循环是否还在运行
     */
    public void awaitNextTick(long periodNanos, TaskDrain drainTasks, RunningFlag keepRunning) {
        long period = Math.max(1L, periodNanos);
        this.lastPeriodNanos = period;
        long now = System.nanoTime();
        if (this.deadlineNanos == 0L || this.deadlineNanos <= now) {
            this.deadlineNanos = now + period;
        }

        while (keepRunning.running()) {
            long remaining = this.deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            if (drainTasks.drain()) {
                continue;
            }
            LockSupport.parkNanos(this, Math.min(remaining, MAX_PARK_NANOS));
        }
    }

    /** 供诊断。 */
    public long grantedTicks() {
        return this.grantedTicks;
    }

    /**
     * 速率变化时按新周期重新对齐节拍。
     *
     * <p><b>不能简单地把 {@code deadlineNanos} 清零</b>：{@code allowTick()} 见到 0
     * 会把截止时刻设成 {@code now + period} 并<b>立刻放行一刻</b>，于是变速后的
     * 第一轮额外跑掉一批刻。低速率下偏差极其显眼——实测 {@code /tick rate 1}
     * 之后立刻测 6 秒，得到 2.86 TPS（应为 1.00）。
     *
     * @param periodNanos 新的目标周期（纳秒）
     */
    public void reset(long periodNanos) {
        long period = Math.max(1L, periodNanos);
        this.lastPeriodNanos = period;
        long now = System.nanoTime();
        this.deadlineNanos = now + period;
    }

    // ------------------------------------------------------------------
    // 主循环需要的时钟查询（对应 1.21.1 的 nextTickTimeNanos / lastOverloadWarningNanos）
    // ------------------------------------------------------------------

    /** 对应 1.21.1 的 {@code this.nextTickTimeNanos = net.minecraft.util.Util.nanoTime();}。 */
    public void resetClock() {
        long now = System.nanoTime();
        this.deadlineNanos = now + this.lastPeriodNanos;
        this.lastOverloadWarningNanos = now;
    }

    /** 落后量：{@code now - deadline}，恒 &lt;= 0 时表示没有落后。 */
    public long behindNanos() {
        return System.nanoTime() - this.deadlineNanos;
    }

    /** 距离上次过载警告过了多少纳秒。 */
    public long nanosSinceOverloadWarning() {
        return System.nanoTime() - this.lastOverloadWarningNanos;
    }

    /** 记录一次过载警告。 */
    public void noteOverloadWarning() {
        this.lastOverloadWarningNanos = System.nanoTime();
    }

    /** 当前截止时刻（毫秒）。 */
    public long deadlineMillis() {
        long millis = this.deadlineNanos / 1_000_000L;
        return millis == 0L ? System.currentTimeMillis() : millis;
    }

    /**
     * 供 {@code haveTime()} 使用：等到这个时刻就说明"到点了"。
     *
     * <p>与 {@link #allowTick(long)} 用<b>同一个</b> {@code deadlineNanos}，
     * 这正是上游 1.21.1 的语义：{@code nextTickTimeNanos} 既是累加器、
     * 又是 {@code haveTime()} 的比较对象。两者共用同一时刻，
     * {@code managedBlock} 才会恰好阻塞「一个周期」然后放行一次，
     * 而不是在等待循环里把多个刻一口气放行（那样主循环会一圈卡住一秒）。
     */
    public long waitUntilMillis() {
        return this.deadlineMillis();
    }

    /**
     * 距下一个游戏刻时刻还剩多少毫秒（可为负）。
     *
     * <p>对应上游 {@code nextTickTimeNanos - net.minecraft.util.Util.nanoTime()} 的符号：
     * 大于 0 表示还没到点。
     */
    /**
     * 距下一个游戏刻还剩多少<b>纳秒</b>（可为负）。
     *
     * <p>{@link #timeRemainingMillis()} 的整除会丢掉亚毫秒精度，
     * 而主循环的等待逻辑需要精确值——1 TPS 时 1ms 的截断误差会被反复放大。
     */
    public long timeRemainingNanos() {
        // 冲刺中不需要等待，直接视为「已到点」
        if (isSprinting()) {
            return 0L;
        }
        return this.deadlineNanos - System.nanoTime();
    }

    public long timeRemainingMillis() {
        // 冲刺中：上游把一刻视作 0 纳秒，waitUntilNextTick() 立即返回，
        // 因此这里恒报「还有时间」，让 haveTime() 为真、不产生任何等待。
        if (isSprinting()) {
            return 1L;
        }
        long remaining = this.deadlineNanos - System.nanoTime();
        return remaining / 1_000_000L;
    }

    private long lastPeriodNanos = 1_000_000L * 50L;
    private long lastOverloadWarningNanos;

    // ------------------------------------------------------------------
    // 冲刺（/tick sprint）：照抄 1.21.1 的 ServerTickRateManager
    // ------------------------------------------------------------------

    /** 剩余冲刺刻数；> 0 即处于冲刺中（对应上游 remainingSprintTicks）。 */
    private long remainingSprintTicks;
    /** 本次冲刺计划的总刻数（对应上游 scheduledCurrentSprintTicks）。 */
    private long scheduledSprintTicks;
    /** 当前这一冲刺刻的开始时刻。 */
    private long sprintTickStartNanos;
    /** 本次冲刺累计耗时（对应上游 sprintTimeSpend）。 */
    private long sprintTimeSpendNanos;
    /** 冲刺前的冻结状态，结束后恢复。 */
    private boolean sprintPreviousFrozen;
    /** 刚结束的一次冲刺的实测 TPS；&lt;= 0 表示没有可报告的结果。 */
    private double lastSprintTps = -1.0D;
    /** 刚结束的一次冲刺的实际刻数。 */
    private long lastSprintTicks;
    /** 刚结束的一次冲刺的每刻毫秒数。 */
    private double lastSprintMillisPerTick;

    /** 对应上游 {@code ServerTickRateManager#isSprinting()}。 */
    public boolean isSprinting() {
        return this.scheduledSprintTicks > 0L || this.remainingSprintTicks > 0L;
    }

    /**
     * 请求冲刺指定刻数，对应上游 {@code requestGameToSprint(int)}。
     *
     * <p>关键行为：冲刺会<b>临时解冻</b>并保存原冻结状态，结束后恢复
     * （所以 {@code /tick sprint} 在冻结状态下也能用，这也正是原版 {@code /tick sprint}
     * 的用途之一）。
     *
     * @return 之前是否已经在冲刺
     */
    public boolean requestSprint(int ticks) {
        boolean already = this.remainingSprintTicks > 0L;
        this.sprintTimeSpendNanos = 0L;
        this.scheduledSprintTicks = Math.max(0, ticks);
        this.remainingSprintTicks = this.scheduledSprintTicks;
        this.lastSprintTps = -1.0D;
        this.lastSprintTicks = 0L;
        return already;
    }

    /**
     * 每轮主循环调用一次，对应上游 {@code checkShouldSprintThisTick()}。
     *
     * <p>返回 true 表示本刻应当以「不等待」的方式立刻推进（上游此时把
     * {@code nanosecondsPerTick} 视为 0，于是 {@code nextTickTimeNanos} 不再前进、
     * {@code waitUntilNextTick()} 立即返回）。
     */
    public boolean checkShouldSprintThisTick() {
        if (this.remainingSprintTicks > 0L) {
            this.sprintTickStartNanos = System.nanoTime();
            this.remainingSprintTicks--;
            return true;
        }
        if (this.scheduledSprintTicks > 0L) {
            finishSprint();
        }
        return false;
    }

    /** 累计本冲刺刻的耗时，对应上游 {@code endTickWork()}。 */
    public void endTickWork() {
        if (this.sprintTickStartNanos != 0L) {
            this.sprintTimeSpendNanos += System.nanoTime() - this.sprintTickStartNanos;
            this.sprintTickStartNanos = 0L;
        }
    }

    /**
     * 结束冲刺并算出实测 TPS，对应上游 {@code finishTickSprint()}。
     *
     * <p>上游公式：{@code tps = (1000 * 已跑刻数) / 耗时毫秒}。
     */
    private void finishSprint() {
        long done = this.scheduledSprintTicks - this.remainingSprintTicks;
        double elapsedMillis = Math.max(1.0D, (double) this.sprintTimeSpendNanos)
                / 1_000_000L;
        this.lastSprintTps = (double) (1_000_000_000L / 1_000_000L)
                * (double) done / elapsedMillis;
        this.lastSprintTicks = done;
        this.lastSprintMillisPerTick = done == 0L ? 0.0D : elapsedMillis / (double) done;
        this.scheduledSprintTicks = 0L;
        this.sprintTimeSpendNanos = 0L;
        this.remainingSprintTicks = 0L;
        this.sprintTickStartNanos = 0L;
    }

    /**
     * 停止冲刺并立刻出结果，对应上游 {@code stopSprinting()}。
     *
     * @return 之前在冲刺则返回 true
     */
    public boolean stopSprint() {
        if (this.remainingSprintTicks > 0L) {
            finishSprint();
            return true;
        }
        return false;
    }


    /** 刚结束的冲刺实测 TPS；&lt;= 0 表示暂无结果。 */
    public double lastSprintTps() {
        return this.lastSprintTps;
    }

    /** 是否有一份尚未消费的冲刺报告。 */
    public boolean sprintReportPending() {
        return this.lastSprintTps > 0.0D;
    }

    /**
     * 冲刺刻的收尾：先累计本刻耗时，再判断是否已跑完并结算。
     *
     * <p>顺序很重要——若先 {@code finishSprint()} 再 {@code endTickWork()}，
     * 最后一刻的耗时不会被计入，报告的每刻毫秒数会偏小。
     */
    public void finishSprintTick() {
        endTickWork();
        if (this.scheduledSprintTicks > 0L && this.remainingSprintTicks == 0L) {
            finishSprint();
        }
    }

    /**
     * 消费冲刺报告：读取后清空，保证只报一次。
     *
     * <p><b>为什么必须有这一步</b>：{@code finishSprint()} 会把
     * {@code scheduledSprintTicks} 清零，但 {@code lastSprintTps} 仍 &gt; 0，
     * 于是 {@link #sprintReportPending()} 会一直为真。主循环每轮都问一次，
     * 结果冲刺结束后每轮都发一条聊天消息——主循环每秒可达上万轮，
     * 实测把日志刷到约 600 万行 / 近 1 GB。
     */
    public void clearSprintReport() {
        this.lastSprintTps = -1.0D;
        this.lastSprintTicks = 0L;
        this.lastSprintMillisPerTick = 0.0D;
    }

    /** 刚结束的冲刺实际刻数。 */
    public long lastSprintTicks() {
        return this.lastSprintTicks;
    }

    /** 刚结束的冲刺每刻毫秒数（对应上游报告的 s 参数）。 */
    public double lastSprintMillisPerTick() {
        return this.lastSprintMillisPerTick;
    }

    // ------------------------------------------------------------------
    // 实测每刻耗时（照抄 1.21.1 MinecraftServer 的 tick 计时机制）
    //
    // 上游 tickServer 里的写法：
    //     long i = net.minecraft.util.Util.nanoTime();
    //     this.tickCount++;
    //     ... 本刻工作 ...
    //     long j = net.minecraft.util.Util.nanoTime() - i;
    //     int k = this.tickCount % 100;
    //     this.aggregatedTickTimesNanos = this.aggregatedTickTimesNanos - this.tickTimesNanos[k];
    //     this.aggregatedTickTimesNanos += j;
    //     this.tickTimesNanos[k] = j;
    //
    // 本类照抄这个「100 刻环形缓冲 + 累计和」，但在<b>游戏刻之间</b>计时：
    // 1.20.1 的等待有 1ms 的睡眠粒度，若像上游那样只测 tickServer 内部耗时，
    // 低速率时会把 1000ms 的周期测成约 999ms（误差被放大成 1.001 TPS 这类噪声）。
    // 测「上一刻到本刻的真实间隔」则天然准确，而且窗口固定为最近 100 刻，
    // 旧样本会自然滑出——不会像固定 1 秒窗口那样在冲刺后残留上万刻。
    //
    // 两次失败的尝试（记录在此避免重犯）：
    //   1. 固定 1 秒窗口 + 窗口内计数：冲刺结束后整整 1 秒内还残留冲刺期间的上万刻，
    //      HUD 持续显示 20 以上，看起来像「没加速时 TPS 间歇跳到 20+」。
    //   2. 逐刻间隔的指数平滑平均（EMA 0.1）：若首个样本取自冲刺期（每秒上万刻），
    //      衰减到正常值需约 1240 个样本（约 62 秒），实测冲刺后仍显示 182 TPS。
    // ------------------------------------------------------------------

    /** 上游用的窗口长度：{@code tickTimesNanos[100]}。 */
    private static final int TICK_TIMES_SPAN = 100;

    /** 最近 100 个游戏刻的耗时（纳秒），环形缓冲，对应上游 {@code tickTimesNanos}。 */
    private final long[] recentTickNanos = new long[TICK_TIMES_SPAN];
    /** 累计和，对应上游 {@code aggregatedTickTimesNanos}。 */
    private long aggregatedTickNanos;
    /** 已完成的游戏刻数，对应上游 {@code tickCount}。 */
    private int measuredTickCount;

    /**
     * 记录一个已完成的游戏刻。
     *
     * <p><b>只应在真正跑完一个游戏刻时调用</b>——主循环每轮不一定 tick
     * （1 TPS 目标下每秒转几万轮、只 tick 一次）。若每轮都调用，
     * 实测值会算出数百万的荒谬结果（实测踩过 600 万 TPS）。
     *
     * @param elapsedNanos 距上一个游戏刻完成的纳秒数
     */
    public void recordTickForMeasurement(long elapsedNanos) {
        if (elapsedNanos <= 0L) {
            return;
        }
        int slot = this.measuredTickCount % TICK_TIMES_SPAN;
        // 与上游同序：先减去将被覆盖的旧值，再加上新值
        this.aggregatedTickNanos -= this.recentTickNanos[slot];
        this.aggregatedTickNanos += elapsedNanos;
        this.recentTickNanos[slot] = elapsedNanos;
        this.measuredTickCount++;
    }

    /**
     * 丢弃当前测量并重新开始。
     *
     * <p>在目标速率变化、冻结/解冻、冲刺起止时调用：那些时刻前后「一刻有多长」
     * 会突变，保留旧样本只会误导读数。
     */
    public void resetMeasurement() {
        java.util.Arrays.fill(this.recentTickNanos, 0L);
        this.aggregatedTickNanos = 0L;
        this.measuredTickCount = 0;
        this.lastTickCompletionNanos = 0L;
    }

    /** 上一个游戏刻完成的时刻（纳秒），用于计算本刻间隔。 */
    private long lastTickCompletionNanos;

    /**
     * 供主循环在每刻结束时调用：算出与上一刻的间隔并记录。
     *
     * @return 本次是否产生了有效样本
     */
    public boolean recordTickCompletion() {
        long now = System.nanoTime();
        long previous = this.lastTickCompletionNanos;
        this.lastTickCompletionNanos = now;
        if (previous == 0L) {
            return false;
        }
        recordTickForMeasurement(now - previous);
        return true;
    }

    /**
     * 实测平均每刻毫秒数，照抄上游的除法：
     * {@code aggregatedTickTimesNanos / min(100, max(tickCount, 1))}。
     *
     * @param fallbackPeriodNanos 尚无样本时使用的目标周期
     */
    public double measuredMillisPerTick(long fallbackPeriodNanos) {
        int divisor = Math.min(TICK_TIMES_SPAN, Math.max(this.measuredTickCount, 1));
        if (this.measuredTickCount == 0) {
            return fallbackPeriodNanos / (double) 1_000_000L;
        }
        return this.aggregatedTickNanos / (double) divisor
                / (double) 1_000_000L;
    }

    /** 纳秒/毫秒换算是官方工具类提供的。 */
    public static long millisToNanos(long millis) {
        return millis * 1_000_000L;
    }

    /** 排空任务队列：返回是否取到了至少一个任务。 */
    @FunctionalInterface
    public interface TaskDrain {
        boolean drain();
    }

    /** 主循环是否还在运行。 */
    @FunctionalInterface
    public interface RunningFlag {
        boolean running();
    }
}