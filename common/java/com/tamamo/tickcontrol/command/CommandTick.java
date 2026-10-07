
package com.tamamo.tickcontrol.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tamamo.tickcontrol.core.ServerTickController;
import com.tamamo.tickcontrol.core.TickControlAccessHolder;
import com.tamamo.tickcontrol.core.TickControlRuntime;
import com.tamamo.tickcontrol.core.TickState;
import com.tamamo.tickcontrol.core.TickStats;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

/**
 * 1.20.3 的 {@code /tick} 命令在 <b>1.7.10</b> 上的实现。
 *
 * <h2>为什么这个类与其它三个版本都不一样</h2>
 *
 * <p>1.7.10 完全没有 Brigadier(已核验:FML 1.7.10 的 joined.srg 里 {@code brigadier}
 * 零命中),命令只能写在 legacy 的 {@code ICommand} / {@code CommandBase} 上。
 *
 * <p>而且 <b>1.7.10 的 {@code ICommand} 与 1.12.2 的也不同</b>——这是最容易踩的坑:
 *
 * <table border="1">
 *   <caption>1.7.10 与 1.12.2 的 legacy 命令接口差异</caption>
 *   <tr><th>用途</th><th>1.7.10</th><th>1.12.2</th></tr>
 *   <tr><td>命令名</td><td>{@code getCommandName()}</td><td>{@code getName()}</td></tr>
 *   <tr><td>执行</td><td>{@code processCommand(ICommandSender, String[])}
 *       —— <b>void,且不接 MinecraftServer</b></td>
 *       <td>{@code execute(MinecraftServer, ICommandSender, String[])}</td></tr>
 *   <tr><td>权限</td><td>{@code canCommandSenderUseCommand(ICommandSender)}</td>
 *       <td>{@code checkPermission(MinecraftServer, ICommandSender)}</td></tr>
 *   <tr><td>反馈</td><td>{@code ICommandSender#addChatMessage(IChatComponent)}</td>
 *       <td>{@code CommandBase#notifyCommandListener(...)}</td></tr>
 * </table>
 *
 * <p>所以 1.12.2 的命令层**不能直接搬过来**。本类是按行为语义重写的,与其它版本
 * 逐条对齐(权限 3;rate 取值 [1,10000];freeze 返 1 / unfreeze 返 0;
 * sprint 总报「冲刺中」;冲刺期间 freeze 失败)。
 *
 * <p>服务器实例通过静态的 {@code MinecraftServer.getServer()} 取——1.7.10 的
 * {@code processCommand} 不提供它。
 *
 * <p><b>名字域</b>:1.7.10 的 RFG 编译类路径用的是 <b>MCP 名</b>(已核验:
 * recompiled_minecraft-1.7.10.jar 里存在 {@code CommandBase} / {@code ICommand} /
 * {@code ChatComponentText} / {@code MinecraftServer.getServer()},且成员名不是
 * {@code func_*} 形式)。
 */
public class CommandTick extends CommandBase {

    /** 与原版 {@code TickCommand.MAX_TICKRATE} 一致。 */
    private static final float MAX_TICK_RATE = TickState.MAX_TICK_RATE;

    /** 命令方块默认权限 2,所以 3 天然把它挡在外面。 */
    private static final int PERMISSION_LEVEL = 3;

    /**
     * 本版本的 <b>freeze / step / sprint 是否已经实现</b>。
     *
     * <p>1.7.10 走纯 ASM coremod,而冻结与步进需要在<b>指令级</b>对
     * {@code MinecraftServer.tick()} 的调用做门控,不是替换一个常量就能做到的;
     * 那部分尚未实现。把它做成显式开关,是为了让命令<b>如实回复「未实现」</b>,
     * 而不是像之前那样返回成功——「报成功但什么都没做」是最难查的一类问题
     * (本项目在 1.16.5 上就吃过 {@code require = 0} 静默失效的亏)。
     *
     * <p>实现门控后把这里改成 {@code true} 即可,其余逻辑已经就位。
     */
    private static final boolean GATING_IMPLEMENTED = true;

    /** 未实现时的统一回复,措辞明确到"不是没生效,是没做"。 */
    private static final String NOT_IMPLEMENTED =
            "TickControl: this subcommand is not implemented on 1.7.10 yet"
                    + " (only /tick query and /tick rate work)";

    @Override
    public String getCommandName() {
        return "tick";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/tick <query|rate <rate>|freeze|unfreeze|step [time|stop]|sprint [time|stop]>";
    }

    @Override
    public List getCommandAliases() {
        List<String> aliases = new ArrayList<String>();
        aliases.add("tickcontrol");
        return aliases;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return PERMISSION_LEVEL;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        // 1.7.10 的 processCommand 不提供 MinecraftServer,只能走静态入口。
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            // 理论上不会发生(命令只在服务端执行),但绝不能因此抛 NPE。
            msg(sender, TickLang.NO_SERVER);
            return;
        }
        ServerTickController control = TickControlAccessHolder.controller(server);

        if (args.length == 0) {
            msg(sender, TickLang.USAGE);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("query".equals(sub)) {
            query(sender, control);
        } else if ("rate".equals(sub)) {
            // 现在真的能改速率了。
            //
            // 之前这里硬编码返回 RATE_UNSUPPORTED,理由是"控速必须改 run() 里那套自校正计时
            // 算术"。那个理由只说对了一半:需要改的只是<b>睡眠值</b>,而它现在由
            // TickControlRuntime.waitForNextTick() 返回(max(0, 目标周期 - 上一轮耗时))。
            // 而 i -= 50L 那段追赶递减<b>仍然一个字没动</b> —— 那才是两次事故的根源。
            if (args.length < 2) {
                msg(sender, TickLang.RATE_USAGE);
                return;
            }
            float rate;
            try {
                rate = Float.parseFloat(args[1]);
            } catch (NumberFormatException e) {
                msg(sender, TickLang.RATE_NOT_A_NUMBER, args[1]);
                return;
            }
            if (Float.isNaN(rate) || rate < TickState.MIN_TICK_RATE || rate > TickState.MAX_TICK_RATE) {
                msg(sender, TickLang.RATE_RANGE,
                        formatRate(TickState.MIN_TICK_RATE), formatRate(TickState.MAX_TICK_RATE));
                return;
            }
            control.setTickRate(rate);
            msg(sender, TickLang.RATE_SUCCESS, formatRate(control.tickRate()));
        } else if ("freeze".equals(sub)) {
            if (!GATING_IMPLEMENTED) {
                msg(sender, NOT_IMPLEMENTED);
                return;
            }
            // 与上游一致:冲刺期间不允许冻结,否则与冲刺的临时解冻互相干扰。
            if (!control.canFreeze()) {
                msg(sender, TickLang.FREEZE_FAIL_SPRINTING);
                return;
            }
            control.setFrozen(true);
            msg(sender, TickLang.FREEZE_SUCCESS);
        } else if ("unfreeze".equals(sub)) {
            if (!GATING_IMPLEMENTED) {
                msg(sender, NOT_IMPLEMENTED);
                return;
            }
            control.setFrozen(false);
            msg(sender, TickLang.UNFREEZE_SUCCESS);
        } else if ("step".equals(sub)) {
            if (!GATING_IMPLEMENTED) {
                msg(sender, NOT_IMPLEMENTED);
                return;
            }
            if (args.length >= 2 && "stop".equalsIgnoreCase(args[1])) {
                if (control.stopStepping()) {
                    msg(sender, TickLang.STEP_STOP_SUCCESS);
                } else {
                    msg(sender, TickLang.STEP_STOP_FAIL);
                }
                return;
            }
            int ticks = 1;
            if (args.length >= 2) {
                Integer parsed = parseTime(args[1]);
                if (parsed == null) {
                    msg(sender, TickLang.TIME_INVALID, args[1]);
                    return;
                }
                ticks = parsed.intValue();
            }
            if (control.requestStep(ticks)) {
                msg(sender, TickLang.STEP_SUCCESS, Integer.valueOf(ticks));
            } else {
                msg(sender, TickLang.STEP_FAIL);
            }
        } else if ("sprint".equals(sub)) {
            if (!GATING_IMPLEMENTED) {
                msg(sender, NOT_IMPLEMENTED);
                return;
            }
            if (args.length >= 2 && "stop".equalsIgnoreCase(args[1])) {
                if (control.stopSprint()) {
                    msg(sender, TickLang.SPRINT_STOP_SUCCESS);
                } else {
                    msg(sender, TickLang.SPRINT_STOP_FAIL);
                }
                return;
            }
            int ticks = 1;
            if (args.length >= 2) {
                Integer parsed = parseTime(args[1]);
                if (parsed == null) {
                    msg(sender, TickLang.TIME_INVALID, args[1]);
                    return;
                }
                ticks = parsed.intValue();
            }
            if (control.requestSprint(ticks)) {
                msg(sender, TickLang.SPRINT_STOP_SUCCESS);
            }
            // 记下发起者的名字。冲刺报告不是命令的即时回执——它要等冲刺真正跑完,
            // 由主循环里的 TickControlRuntime 发出,那时这里的 sender 早已不在作用域。
            //
            // 只存名字而不是 ICommandSender 本身:1.7.10 的命令对象是单例
            // (INSTANCE),持有一个 sender 引用会让已离线的玩家对象无法回收。
            // 投递时再按名字查在线玩家,查不到就安静跳过。
            TickControlRuntime.setSprintReportTarget(sender.getCommandSenderName());
            msg(sender, TickLang.SPRINT_SUCCESS);
        } else {
            msg(sender, TickLang.USAGE);
        }
    }

    // ------------------------------------------------------------------
    // 子命令
    // ------------------------------------------------------------------

    private void query(ICommandSender sender, ServerTickController control) {
        TickStats stats = control.stats();
        float rate = control.tickRate();

        if (control.isFrozen()) {
            msg(sender, TickLang.STATUS_FROZEN);
        } else if (control.isSprinting()) {
            msg(sender, TickLang.STATUS_SPRINTING);
        } else if (stats != null && !stats.isEmpty() && stats.isLagging(rate)) {
            msg(sender, TickLang.STATUS_LAGGING);
        } else {
            msg(sender, TickLang.STATUS_RUNNING);
        }

        msg(sender, TickLang.QUERY_RATE, formatRate(rate));

        if (stats != null && !stats.isEmpty()) {
            msg(sender, TickLang.QUERY_PERCENTILES,
                    TickStats.formatMillis(stats.percentileMillis(0.50D)),
                    TickStats.formatMillis(stats.percentileMillis(0.95D)),
                    TickStats.formatMillis(stats.percentileMillis(0.99D)),
                    TickStats.formatMillis(stats.averageMillis()));
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 1.7.10 没有 Brigadier 的 {@code TimeArgument},所以手工解析。
     *
     * <p>支持 {@code t}/{@code s}/{@code d} 后缀(1 刻 / 20 刻 / 24000 刻),
     * 默认单位是刻,最小 1 刻。与 1.20.3 的语义对齐。
     *
     * @return 刻数;无法解析时返回 {@code null}
     */
    private static Integer parseTime(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        int mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last < '0' || last > '9') {
            s = s.substring(0, s.length() - 1);
            if (last == 't') {
                mult = 1;
            } else if (last == 's') {
                mult = 20;
            } else if (last == 'd') {
                mult = 24000;
            } else {
                return null;
            }
        }
        try {
            long value = Long.parseLong(s) * (long) mult;
            if (value < 1L || value > Integer.MAX_VALUE) {
                return null;
            }
            return Integer.valueOf((int) value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 原版用 {@code %.1f} 输出速率,20.0 显示为 "20.0"。 */
    private static String formatRate(float rate) {
        return String.format(Locale.ROOT, "%.1f", Float.valueOf(rate));
    }

    /**
     * 发一条<b>可翻译</b>消息给命令发起者。
     *
     * <p>1.7.10 只能往 sender 上直接发组件,没有 {@code notifyCommandListener}。
     * 用 {@link ChatComponentTranslation} 而不是 {@code ChatComponentText}:前者会把
     * 键与参数发给客户端,由客户端按自己的语言查
     * {@code assets/tickcontrol/lang/<语言>.lang},于是中文客户端显示中文。
     *
     * <p>键与参数都集中在这里,字面量不再散落在业务逻辑里。
     */
    private static void msg(ICommandSender sender, String translationKey, Object... args) {
        IChatComponent component = new ChatComponentTranslation(translationKey, args);
        sender.addChatMessage(component);
    }
}
