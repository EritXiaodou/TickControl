package com.tamamo.tickcontrol.core;

/**
 * 平台侧（Forge / NeoForge）需要提供给纯 Java 核心的最小视图。
 *
 * <p>把「读状态」和「统计」抽成接口，命令层就可以待在 {@code common} 里，
 * 只由各平台的入口注解（{@code @Mod} / {@code @EventBusSubscriber}）负责注册。
 */
public interface TickControlAccess {

    /** 目标 tick 速率（TPS）。 */
    float tickRate();

    /** 设置目标 tick 速率，返回是否发生了变化。 */
    boolean setTickRate(float rate);

    boolean isFrozen();

    void setFrozen(boolean frozen);

    boolean isStepping();

    int stepTicks();

    /** 见 {@link TickState#runsNormally()}。 */
    boolean runsNormally();

    /**
     * 请求在冻结状态下步进指定刻数。
     *
     * @return 是否成功（未冻结时失败，与原版 {@code /tick step} 一致）
     */
    boolean requestStep(int ticks);

    /** 停止步进并重新冻结。 */
    boolean stopStepping();

    // ---- 冲刺（/tick sprint）：对应 1.21.1 ServerTickRateManager ----

    /** 是否正在冲刺。 */
    boolean isSprinting();

    /**
     * 请求冲刺指定刻数，返回之前是否已在冲刺。
     *
     * <p>与上游一致：冲刺会<b>临时解冻</b>并保存原冻结状态，结束后恢复。
     */
    boolean requestSprint(int ticks);

    /** 每轮主循环调用：本刻是否以「不等待」方式立即推进。 */
    boolean checkShouldSprintThisTick();

    /** 累计本冲刺刻耗时（对应上游 {@code endTickWork()}）。 */
    void endTickWork();

    /** 停止冲刺并立刻出结果。 */
    boolean stopSprint();

    /** 冲刺期间不允许冻结（与上游一致）。 */
    boolean canFreeze();

    /**
     * 消费一份待发送的冲刺报告。
     *
     * <p>由主循环在冲刺结束时调用，返回 true 表示应发送
     * {@code commands.tick.sprint.report} 消息（对应上游 {@code finishTickSprint}）。
     */
    boolean consumeSprintReport();

    /**
     * 清掉冲刺报告结果，保证只发一次。
     *
     * <p>调用方必须在发送报告后立刻调用本方法，否则报告会被每轮主循环重复发送
     * （实测会刷出数百万行日志）。
     */
    void clearSprintReport();

    /** 刚结束的冲刺实测 TPS；&lt;= 0 表示暂无结果。 */
    double lastSprintTps();

    /** 刚结束的冲刺实际刻数。 */
    long lastSprintTicks();

    /** 刚结束的冲刺每刻毫秒数。 */
    double lastSprintMillisPerTick();

    /**
     * 一次服务器 tick 开始时调用（对应原版 {@code TickRateManager#tick()}）。
     *
     * @return 本刻游戏内容是否应当推进
     */
    boolean prepareTick();

    /**
     * 主循环下一轮推进 {@code nextTickTime} 应使用的毫秒间隔。
     *
     * <p>1.20.1 的主循环是毫秒制，这个值替代原先硬编码的 {@code 50L}。
     */
    long nextTickIntervalMillis();

    /** 每刻耗时统计（可为空实现）。 */
    TickStats stats();
}
