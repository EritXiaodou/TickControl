
package com.tamamo.tickcontrol.core;

/**
 * 与版本/加载器无关的 tick 状态机。
 *
 * <p>对应原版 1.20.3 的 {@code net.minecraft.world.TickRateManager}：承载目标 tick 速率、
 * 冻结开关与步进剩余刻数。这里刻意不依赖任何 Minecraft / 加载器类型，
 * 以便 Forge、NeoForge（以及后续 1.19.2 / 1.18.2 / 1.16.5 / 1.12.2）共用同一份实现。
 *
 * <p>注意：原版把「冻结」与「步进」都建模在同一个 {@code stepTicks} 字段上——
 * 冻结时把 {@code stepTicks} 设为 0，{@code /tick step N} 把它设为 N，
 * 每完成一次 tick 就递减，减到 0 后重新变为冻结。
 */
public final class TickState {

    /** 原版 {@code TickRateManager.MIN_TICK_RATE}。 */
    public static final float MIN_TICK_RATE = 1.0F;
    /** 原版 rate 参数上限。 */
    public static final float MAX_TICK_RATE = 10000.0F;
    /** 默认 20 TPS。 */
    public static final float DEFAULT_TICK_RATE = 20.0F;



    /** 一游戏刻的毫秒数（1.20.1 主循环是毫秒制，所以这里用毫秒而不是纳秒）。 */
    public static final double MILLIS_PER_TICK = 50.0D;

    private float tickRate = DEFAULT_TICK_RATE;
    private boolean frozen = false;
    private int stepTicks = 0;

    /**
     * 目标 tick 速率（TPS）。1.20.1 的主循环以毫秒为单位推进
     * {@code nextTickTime}，因此速率是 20 TPS 的倍率，见 {@link #nextTickIntervalMillis(float)}。
     */
    public float getTickRate() {
        return this.tickRate;
    }

    public void setTickRate(float rate) {
        this.tickRate = clampRate(rate);
    }

    /** 钳到原版取值范围 [1.0, 10000.0]（与 1.20.3 的 TickRateManager 一致）。 */
    public static float clampRate(float rate) {
        return Math.max(MIN_TICK_RATE, Math.min(MAX_TICK_RATE, rate));
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    // ------------------------------------------------------------------
    // 冲刺（/tick sprint）：1.21.1 的 ServerTickRateManager 逻辑
    // ------------------------------------------------------------------

    /** 冲刺前是否处于冻结，结束后恢复（对应上游 previousIsFrozen）。 */
    private boolean sprintPreviousFrozen;
    /** 是否已经为一次冲刺保存过冻结状态，避免重复覆盖。 */
    private boolean sprintActive;

    /** 是否正在冲刺（本类只记录状态；刻数进度由 {@link LoopPacer} 管）。 */
    public boolean isSprinting() {
        return this.sprintActive;
    }

    /**
     * 冲刺开始：<b>临时解冻</b>并保存原冻结状态。
     *
     * <p>注意不能用 {@link #setFrozen(boolean)}——那会顺手清空 {@code stepTicks}，
     * 而冲刺不应该丢掉待步进的刻数。上游此时只动 {@code isFrozen}。
     */
    public void beginSprint() {
        if (!this.sprintActive) {
            this.sprintPreviousFrozen = this.frozen;
            this.sprintActive = true;
        }
        this.frozen = false;
    }

    /** 冲刺结束：恢复冲刺前的冻结状态（不清空 {@code stepTicks}）。 */
    public void endSprint() {
        if (this.sprintActive) {
            this.frozen = this.sprintPreviousFrozen;
            this.sprintActive = false;
        }
    }

    /**
     * 是否可以进入冻结状态。
     *
     * <p>与上游一致：冲刺期间不允许冻结（{@code /tick freeze} 会失败），
     * 因为冻结会与冲刺的「临时解冻」互相干扰。
     */
    public boolean canFreeze() {
        return !this.sprintActive;
    }

    public boolean isStepping() {
        return this.frozen && this.stepTicks > 0;
    }

    public int getStepTicks() {
        return this.stepTicks;
    }

    /**
     * 游戏内容是否可以正常推进。
     *
     * <p>等价于原版 1.20.3+ 的 {@code TickRateManager#runsNormally()}：
     * 未冻结、或正处在步进过程中（本次 tick 是被 step 放行的一次）。
     */
    public boolean runsNormally() {
        return !this.frozen || this.stepTicks > 0;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
        if (!frozen) {
            this.stepTicks = 0;
        }
    }

    /**
     * 请求冻结状态下步进指定刻数。
     *
     * @return 是否成功（未冻结时与原版一致，执行失败）
     */
    public boolean requestStep(int ticks) {
        if (!this.frozen || ticks <= 0) {
            return false;
        }
        this.stepTicks = ticks;
        return true;
    }

    /** 停止步进并重新冻结。 */
    public boolean stopStepping() {
        boolean wasStepping = isStepping();
        this.stepTicks = 0;
        this.frozen = true;
        return wasStepping;
    }

    /**
     * 一次服务器 tick 开始时调用，等价于原版 {@code TickRateManager#tick()}：
     * 先决定本刻游戏内容是否推进，再递减步进计数。
     *
     * <p>把「判断」与「递减」放在同一个方法里并保持这个顺序，是为了精确复刻原版语义——
     * {@code /tick step N} 会让接下来正好 N 个游戏刻推进，第 N 刻之后重新冻结。
     *
     * @return 本刻游戏内容是否应当推进
     */
    public boolean prepareTick() {
        boolean run = !this.frozen || this.stepTicks > 0;
        if (this.stepTicks > 0) {
            this.stepTicks--;
        }
        return run;
    }

    /** 便于命令输出：当前状态下目标速率对应的每刻毫秒数。 */
    public double millisPerTick() {
        return MILLIS_PER_TICK * DEFAULT_TICK_RATE / this.tickRate;
    }

    /**
     * 主循环下一次推进 {@code nextTickTime} 应使用的毫秒间隔。
     *
     * <p>1.20.3 用 {@code nanosecondsPerTick}；1.20.1 的主循环是毫秒制，
     * 对应的是 {@code nextTickTime} 每次应当推进多少毫秒。
     */
    public long nextTickIntervalMillis(float rate) {
        double interval = MILLIS_PER_TICK * DEFAULT_TICK_RATE / clampRate(rate);
        // 至少 1ms：Thread.sleep(0) 会变成忙等，且 1.20.1 的循环本身有 1ms 睡眠下限
        return Math.max(1L, Math.round(interval));
    }
}
