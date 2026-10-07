
package com.tamamo.tickcontrol.command;

/**
 * 1.7.10 命令层用到的翻译键。
 *
 * <h2>为什么要集中在一处</h2>
 *
 * <p>1.7.10 的 {@code CommandTick} 最初把消息<b>硬编码成英文字面量</b>,所以无论客户端
 * 语言是什么都只显示英文。1.12.2 用的是 {@code ChatComponentTranslation} + 语言文件,
 * 这里对齐同样的做法,键名也保持一致,便于两条产线对照。
 *
 * <p>语言文件在 {@code assets/tickcontrol/lang/{en_us,zh_cn}.lang}。
 * <b>1.7.10 与 1.12.2 一样只读 {@code .lang},不读 {@code .json}</b>
 * —— 这一点在 1.12.2 上踩过坑:JSON 文件明明在 jar 里、内容也对,但加载器根本不看它。
 */
public final class TickLang {

    /** 所有键的公共前缀,与 1.12.2 一致。 */
    private static final String P = "tickcontrol.commands.tick.";

    /** 无法取到服务器实例(理论上不会发生)。 */
    public static final String NO_SERVER = P + "no.server";

    public static final String USAGE = P + "usage";

    // ---- rate ----
    public static final String RATE_USAGE = P + "rate.usage";
    public static final String RATE_NOT_A_NUMBER = P + "rate.notanumber";
    public static final String RATE_RANGE = P + "rate.range";
    public static final String RATE_SUCCESS = P + "rate.success";
    public static final String RATE_UNSUPPORTED = P + "rate.unsupported";

    // ---- freeze / unfreeze ----
    public static final String FREEZE_FAIL_SPRINTING = P + "freeze.fail.sprinting";
    public static final String FREEZE_SUCCESS = P + "freeze.success";
    public static final String UNFREEZE_SUCCESS = P + "unfreeze.success";

    // ---- step ----
    public static final String STEP_SUCCESS = P + "step.success";
    public static final String STEP_FAIL = P + "step.fail";
    public static final String STEP_STOP_SUCCESS = P + "step.stop.success";
    public static final String STEP_STOP_FAIL = P + "step.stop.fail";
    public static final String TIME_INVALID = P + "time.invalid";

    // ---- sprint ----
    public static final String SPRINT_SUCCESS = P + "sprint.success";
    public static final String SPRINT_STOP_SUCCESS = P + "sprint.stop.success";
    public static final String SPRINT_STOP_FAIL = P + "sprint.stop.fail";
    public static final String SPRINT_REPORT = P + "sprint.report";

    // ---- query ----
    public static final String STATUS_FROZEN = P + "status.frozen";
    public static final String STATUS_SPRINTING = P + "status.sprinting";
    public static final String STATUS_LAGGING = P + "status.lagging";
    public static final String STATUS_RUNNING = P + "status.running";
    public static final String QUERY_RATE = P + "query.rate";
    public static final String QUERY_PERCENTILES = P + "query.percentiles";

    private TickLang() {
    }
}
