
package com.tamamo.tickcontrol.command;

/**
 * 翻译键常量。
 *
 * <p>键名沿用原版 {@code /tick} 命令的 {@code commands.tick.*} 结构，只加了
 * {@code tickcontrol.} 前缀，避免与其他模组/资源包抢同名键。这样整合包作者
 * 可以用资源包按原版习惯覆盖文案。
 */
public final class TickLang {

    private TickLang() {
    }

    private static final String P = "tickcontrol.commands.tick.";

    // --- 用法与参数错误（1.12.2 没有 Brigadier，校验失败要自己给文案）---
    /** 用法说明，同时用于 {@code /help tick}。 */
    public static final String USAGE = P + "usage";
    /** 这个服务器实例没有被 Tick Control 接管（主循环 Mixin 没跑起来）。 */
    public static final String UNAVAILABLE = P + "unavailable";
    /** 时间参数非法（参数：原始输入）。 */
    public static final String TIME_INVALID = P + "time.invalid";
    /** 速率参数非法（参数：原始输入）。 */
    public static final String RATE_INVALID = P + "rate.invalid";

    // --- 状态（原版 net.minecraft.server.commands.TickCommand 的 commands.tick.status.*）---
    public static final String STATUS_FROZEN = P + "status.frozen";
    public static final String STATUS_RUNNING = P + "status.running";
    public static final String STATUS_LAGGING = P + "status.lagging";
    public static final String STATUS_SPRINTING = P + "status.sprinting";

    // --- /tick query ---
    public static final String QUERY_RATE_RUNNING = P + "query.rate.running";
    public static final String QUERY_RATE_SPRINTING = P + "query.rate.sprinting";
    public static final String QUERY_PERCENTILES = P + "query.percentiles";

    // --- /tick rate ---
    public static final String RATE_SUCCESS = P + "rate.success";
    /** 请求值超过可达上限时的提示（参数：请求值、实际生效值）。 */
    public static final String RATE_CAPPED = P + "rate.capped";

    // --- /tick step ---
    public static final String STEP_SUCCESS = P + "step.success";
    public static final String STEP_FAIL = P + "step.fail";
    public static final String STEP_STOP_SUCCESS = P + "step.stop.success";
    public static final String STEP_STOP_FAIL = P + "step.stop.fail";

    // --- /tick sprint（对应 1.21.1 TickCommand / ServerTickRateManager）---
    /** 冲刺被手动停止（{@code /tick sprint stop}，或冲刺中再次发起）。 */
    public static final String SPRINT_STOP_SUCCESS = P + "sprint.stop.success";
    /** 未在冲刺时执行 {@code /tick sprint stop}。 */
    public static final String SPRINT_STOP_FAIL = P + "sprint.stop.fail";
    /** 冲刺期间不允许冻结（与上游一致）。 */
    public static final String FREEZE_FAIL_SPRINTING = P + "freeze.fail.sprinting";
    /** 冲刺结束报告（参数：实测 TPS、每刻毫秒数）。 */
    public static final String SPRINT_REPORT = P + "sprint.report";
}
