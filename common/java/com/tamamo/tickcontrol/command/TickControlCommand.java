
package com.tamamo.tickcontrol.command;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.tamamo.tickcontrol.core.TickControlAccess;
import com.tamamo.tickcontrol.core.TickState;
import com.tamamo.tickcontrol.core.TickStats;
import com.tamamo.tickcontrol.core.VersionAdapterHolder;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.CommandResultStats;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

/**
 * 1.20.3 的 {@code /tick} 命令族在 1.12.2 上的实现。
 *
 * <h2>为什么不是 Brigadier</h2>
 *
 * 1.12.2 <b>完全没有</b> Brigadier（整份 joined.tsrg 里 {@code brigadier} 零命中），
 * 所以这里按 legacy {@code ICommand} 重写：命令树变成手工的 {@code args} 分派，
 * 分支建议由 {@link #getTabCompletions} 提供，参数校验失败的报错统一用
 * {@link WrongUsageException}（它会把 {@link #getUsage} 的文案套进
 * {@code commands.generic.usage} 里）。
 *
 * <h2>与其它版本的关系</h2>
 *
 * 行为语义与 1.16.5 / 1.20.1 版本<b>逐条对齐</b>：
 * <ul>
 *   <li>根命令权限等级 3（{@link CommandBase#getRequiredPermissionLevel()}）；</li>
 *   <li>{@code rate} 取值 [1.0, 10000.0]，结果值为 {@code (int) rate}；</li>
 *   <li>{@code freeze} 结果 1、{@code unfreeze} 结果 0；</li>
 *   <li>{@code step} 无论成功与否结果都是 1；</li>
 *   <li>{@code sprint [<time>]} 总是输出「冲刺中」并返回 1；已在冲刺时先提示已停止；</li>
 *   <li>冲刺期间 {@code freeze} 失败（返回 0）；</li>
 *   <li>时间参数支持 {@code t}/{@code s}/{@code d} 后缀（1 刻 / 20 刻 / 24000 刻），
 *       默认单位为刻，最小 1 刻。</li>
 * </ul>
 *
 * <p>legacy 的 {@code ICommand#execute} 是 {@code void}，没有返回值可传，因此原版的
 * 「命令返回值」写进 {@link CommandResultStats.Type#QUERY_RESULT}——命令方块读的正是
 * 这一项，语义与其它版本的返回值一致。
 */
public final class TickControlCommand extends CommandBase {

    /**
     * 单例：命令本身无状态。
     *
     * <p>Forge 入口注册它；主循环发送冲刺报告时也要一个 {@code ICommand} 实例
     * （1.12.2 的 {@code CommandBase.notifyCommandListener} 需要它来做权限判断），
     * 两处共用同一个实例即可。
     */
    public static final TickControlCommand INSTANCE = new TickControlCommand();

    /** 与原版 {@code TickCommand.MAX_TICKRATE} 一致。 */
    private static final float MAX_TICK_RATE = TickState.MAX_TICK_RATE;
    private static final String DEFAULT_TICK_RATE = String.valueOf((int) TickState.DEFAULT_TICK_RATE);

    /** 命令方块默认权限为 2，因此权限 3 天然把命令方块挡在外面。 */
    private static final int PERMISSION_LEVEL = 3;

    private static final String[] SUBCOMMANDS = {"query", "rate", "freeze", "unfreeze", "step", "sprint"};

    /** 供没有占位符的文案复用，避免每处都新建空数组。 */
    private static final Object[] NO_ARGS = new Object[0];

    public TickControlCommand() {
    }

    // ------------------------------------------------------------------
    // ICommand / CommandBase
    // ------------------------------------------------------------------

    @Override
    public String getName() {
        return "tick";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return TickLang.USAGE;
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    /** 与原版一致：权限等级 3。 */
    @Override
    public int getRequiredPermissionLevel() {
        return PERMISSION_LEVEL;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return false;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(getUsage(sender));
        }
        TickControlAccess control = control(server, sender);
        if (control == null) {
            // 主循环的 Mixin 还没跑起来（或者这个服务器实例没被接管）
            throw new CommandException(TickLang.UNAVAILABLE);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if ("query".equals(sub)) {
            requireArgumentCount(sender, args, 1);
            setResult(sender, tickQuery(sender, control));
            return;
        }
        if ("rate".equals(sub)) {
            requireArgumentCount(sender, args, 2);
            setResult(sender, setTickingRate(sender, control, parseRate(args[1])));
            return;
        }
        if ("freeze".equals(sub)) {
            requireArgumentCount(sender, args, 1);
            setResult(sender, setFreeze(sender, control, true));
            return;
        }
        if ("unfreeze".equals(sub)) {
            requireArgumentCount(sender, args, 1);
            setResult(sender, setFreeze(sender, control, false));
            return;
        }
        if ("step".equals(sub)) {
            if (args.length == 1) {
                setResult(sender, step(sender, control, 1));
                return;
            }
            if (args.length == 2 && "stop".equalsIgnoreCase(args[1])) {
                setResult(sender, stopStepping(sender, control));
                return;
            }
            if (args.length == 2) {
                setResult(sender, step(sender, control, parseTime(args[1])));
                return;
            }
            throw new WrongUsageException(getUsage(sender));
        }
        if ("sprint".equals(sub)) {
            // 与原版 Brigadier 树一致：sprint 下面只有 stop 与 <time>，没有裸的 sprint
            if (args.length == 2 && "stop".equalsIgnoreCase(args[1])) {
                setResult(sender, stopSprinting(sender, control));
                return;
            }
            if (args.length == 2) {
                setResult(sender, sprint(sender, control, parseTime(args[1])));
                return;
            }
            throw new WrongUsageException(getUsage(sender));
        }
        throw new WrongUsageException(getUsage(sender));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("rate".equals(sub)) {
                return getListOfStringsMatchingLastWord(args, DEFAULT_TICK_RATE);
            }
            if ("step".equals(sub)) {
                return getListOfStringsMatchingLastWord(args, "1t", "1s", "stop");
            }
            if ("sprint".equals(sub)) {
                return getListOfStringsMatchingLastWord(args, "60s", "1d", "3d", "stop");
            }
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------
    // 子命令实现（与 1.20.1 / 1.16.5 版本同语义）
    // ------------------------------------------------------------------

    private static int setTickingRate(ICommandSender sender, TickControlAccess control, float rate) {
        control.setTickRate(rate);
        float effective = control.tickRate();
        VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, TickLang.RATE_SUCCESS,
                new Object[] {formatRate(effective)}, true);
        return (int) effective;
    }

    private static int tickQuery(ICommandSender sender, TickControlAccess control) {
        float rate = control.tickRate();
        TickStats stats = control.stats();
        String rateText = formatRate(rate);

        // 状态行：冻结 / 跟不上 / 正常
        if (control.isFrozen()) {
            sendStatus(sender, TickLang.STATUS_FROZEN);
        } else if (stats != null && !stats.isEmpty() && stats.isLagging(rate)) {
            sendStatus(sender, TickLang.STATUS_LAGGING);
        } else {
            sendStatus(sender, TickLang.STATUS_RUNNING);
        }

        double averageMillis = stats == null ? 0.0D : stats.averageMillis();
        double targetMillis = TickStats.targetMillisPerTick(rate);
        VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, TickLang.QUERY_RATE_RUNNING,
                new Object[] {
                        rateText,
                        TickStats.formatMillis(averageMillis),
                        TickStats.formatMillis(targetMillis)},
                false);

        if (stats != null && !stats.isEmpty()) {
            VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, TickLang.QUERY_PERCENTILES,
                    new Object[] {
                            Integer.toString(stats.size()),
                            TickStats.formatMillis(stats.percentileMillis(0.50D)),
                            TickStats.formatMillis(stats.percentileMillis(0.95D)),
                            TickStats.formatMillis(stats.percentileMillis(0.99D)),
                            TickStats.formatMillis(stats.averageMillis())},
                    false);
        }

        return (int) rate;
    }

    private static int setFreeze(ICommandSender sender, TickControlAccess control, boolean frozen) {
        // 与上游一致：冲刺期间不允许冻结（会与冲刺的临时解冻互相干扰）
        if (frozen && !control.canFreeze()) {
            VersionAdapterHolder.get().sendFailure(sender, TickLang.FREEZE_FAIL_SPRINTING, NO_ARGS);
            return 0;
        }
        control.setFrozen(frozen);
        VersionAdapterHolder.get().sendSuccess(sender, INSTANCE,
                frozen ? TickLang.STATUS_FROZEN : TickLang.STATUS_RUNNING, NO_ARGS, true);
        // 与原版一致：freeze 返回 1，unfreeze 返回 0
        return frozen ? 1 : 0;
    }

    private static int step(ICommandSender sender, TickControlAccess control, int ticks) {
        if (control.requestStep(ticks)) {
            VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, TickLang.STEP_SUCCESS,
                    new Object[] {Integer.toString(ticks)}, true);
        } else {
            VersionAdapterHolder.get().sendFailure(sender, TickLang.STEP_FAIL, NO_ARGS);
        }
        // 与原版一致：无论成功与否都返回 1
        return 1;
    }

    private static int stopStepping(ICommandSender sender, TickControlAccess control) {
        if (control.stopStepping()) {
            VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, TickLang.STEP_STOP_SUCCESS, NO_ARGS, true);
            return 1;
        }
        VersionAdapterHolder.get().sendFailure(sender, TickLang.STEP_STOP_FAIL, NO_ARGS);
        return 0;
    }

    /**
     * {@code /tick sprint <time>}。
     *
     * <p>与上游 {@code TickCommand#sprint} 一致：若之前已在冲刺，先提示已停止；
     * 然后总是输出「冲刺中」状态行，返回 1。
     */
    private static int sprint(ICommandSender sender, TickControlAccess control, int ticks) {
        // 记下发起者:冲刺结束报告要等冲刺真正跑完才由主循环发出,
        // 那时这里已经不在作用域里了。不记的话报告只能发给服务端日志,玩家看不到。
        if (control instanceof com.tamamo.tickcontrol.core.ServerTickController) {
            ((com.tamamo.tickcontrol.core.ServerTickController) control)
                    .setSprintReportTarget(sender);
        }
        boolean already = control.requestSprint(ticks);
        if (already) {
            VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, TickLang.SPRINT_STOP_SUCCESS, NO_ARGS, true);
        }
        VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, TickLang.STATUS_SPRINTING, NO_ARGS, true);
        return 1;
    }

    /** {@code /tick sprint stop}。 */
    private static int stopSprinting(ICommandSender sender, TickControlAccess control) {
        if (control.stopSprint()) {
            VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, TickLang.SPRINT_STOP_SUCCESS, NO_ARGS, true);
            return 1;
        }
        VersionAdapterHolder.get().sendFailure(sender, TickLang.SPRINT_STOP_FAIL, NO_ARGS);
        return 0;
    }

    // ------------------------------------------------------------------
    // 参数与辅助
    // ------------------------------------------------------------------

    /**
     * 取本服务器实例的控制器。
     *
     * <p>legacy 的 {@code execute} 直接把 {@code MinecraftServer} 交给我们，所以优先用它；
     * {@link TickControl#get} 只是「从命令来源反查」的等价路径，作为兜底。
     */
    private static TickControlAccess control(MinecraftServer server, ICommandSender sender) {
        TickControlAccess access = TickControl.forServer(server);
        return access != null ? access : TickControl.get(sender);
    }

    private static void requireArgumentCount(ICommandSender sender, String[] args, int expected)
            throws CommandException {
        if (args.length != expected) {
            throw new WrongUsageException(getUsageOf(sender));
        }
    }

    /** {@link #getUsage} 的静态等价物（静态辅助方法里没法调用实例方法）。 */
    private static String getUsageOf(ICommandSender sender) {
        return INSTANCE.getUsage(sender);
    }

    /**
     * legacy 命令没有返回值，把原版的「命令返回值」写进命令统计。
     *
     * <p>命令方块读的就是 {@code QueryResult}，因此行为与其它版本的返回值一致。
     */
    private static void setResult(ICommandSender sender, int result) {
        sender.setCommandStat(CommandResultStats.Type.QUERY_RESULT, result);
    }

    private static void sendStatus(ICommandSender sender, String translationKey) {
        VersionAdapterHolder.get().sendSuccess(sender, INSTANCE, translationKey, NO_ARGS, false);
    }

    /** 解析 {@code step}/{@code sprint} 的时间参数，失败时按原版风格报错。 */
    private static int parseTime(String argument) throws CommandException {
        int ticks = VersionAdapterHolder.get().parseTime(argument);
        if (ticks <= 0) {
            throw new CommandException(TickLang.TIME_INVALID, argument);
        }
        return ticks;
    }

    /** 解析 {@code rate} 的参数；取值范围 [1.0, 10000.0]，与 Brigadier 版的参数类型一致。 */
    private static float parseRate(String argument) throws CommandException {
        float rate;
        try {
            rate = Float.parseFloat(argument);
        } catch (NumberFormatException e) {
            throw new CommandException(TickLang.RATE_INVALID, argument);
        }
        if (Float.isNaN(rate) || rate < TickState.MIN_TICK_RATE || rate > MAX_TICK_RATE) {
            throw new CommandException(TickLang.RATE_INVALID, argument);
        }
        return rate;
    }

    /** 原版用 {@code %.1f} 输出速率，所以 20.0 显示为 "20.0"、30.5 显示为 "30.5"。 */
    private static String formatRate(float rate) {
        return String.format(Locale.ROOT, "%.1f", rate);
    }
}
