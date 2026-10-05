package com.tamamo.tickcontrol.command;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.tamamo.tickcontrol.core.TickControlAccess;
import com.tamamo.tickcontrol.core.VersionAdapterHolder;
import com.tamamo.tickcontrol.core.TickStats;
import com.tamamo.tickcontrol.core.TickState;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;

/**
 * 1.20.3 {@code /tick} 命令在 1.20.1 上的回移实现。
 *
 * <p>行为与文案严格对照原版 {@code net.minecraft.server.commands.TickCommand}：
 * <ul>
 *   <li>根节点权限等级 3（因此命令方块与函数默认无法执行，与 MC-266134 的判定一致）；</li>
 *   <li>{@code rate} 取值 [1.0, 10000.0]，返回值为 {@code (int) rate}；</li>
 *   <li>{@code step [<time>]} 使用 {@link TimeArgument#time(int)}，最小 1 刻，支持
 *       {@code d}/{@code s}/{@code t} 单位，默认 1 刻；</li>
 *   <li>{@code query} 输出状态、目标/平均每刻耗时，以及 P50/P95/P99 百分位。</li>
 * </ul>
 *
 * <p>第二期未实现的部分：{@code sprint} / {@code sprint stop}（需要额外处理主循环
 * 冲刺、看门狗与客户端同步）。本版刻意不注册这两个子命令，避免出现「能输入但行为
 * 不完整」的半成品。
 */
public final class TickCommand {

    /** 与原版 {@code TickCommand.MAX_TICKRATE} 一致。 */
    private static final float MAX_TICK_RATE = TickState.MAX_TICK_RATE;
    private static final String DEFAULT_TICK_RATE = String.valueOf((int) TickState.DEFAULT_TICK_RATE);

    /** 命令方块默认权限为 2，因此权限 3 天然把命令方块挡在外面。 */
    private static final int PERMISSION_LEVEL = 3;

    private TickCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tick")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL));

        root.then(Commands.literal("query")
                .executes(context -> tickQuery(context.getSource())));

        root.then(Commands.literal("rate")
                .then(Commands.argument("rate",
                                FloatArgumentType.floatArg(TickState.MIN_TICK_RATE, MAX_TICK_RATE))
                        .suggests((context, builder) ->
                                SharedSuggestionProvider.suggest(new String[] {DEFAULT_TICK_RATE}, builder))
                        .executes(context -> setTickingRate(
                                context.getSource(),
                                FloatArgumentType.getFloat(context, "rate")))));

        root.then(Commands.literal("freeze")
                .executes(context -> setFreeze(context.getSource(), true)));

        root.then(Commands.literal("unfreeze")
                .executes(context -> setFreeze(context.getSource(), false)));

        root.then(Commands.literal("step")
                .executes(context -> step(context.getSource(), 1))
                .then(Commands.literal("stop")
                        .executes(context -> stopStepping(context.getSource())))
                .then(Commands.argument("time", VersionAdapterHolder.get().timeArgument())
                        .suggests((context, builder) ->
                                SharedSuggestionProvider.suggest(new String[] {"1t", "1s"}, builder))
                        .executes(context -> step(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "time")))));

        // /tick sprint [<time>] 与 /tick sprint stop
        // 与 1.21.1 的 TickCommand 结构一致；建议值也照抄（60s / 1d / 3d）。
        root.then(Commands.literal("sprint")
                .then(Commands.literal("stop")
                        .executes(context -> stopSprinting(context.getSource())))
                .then(Commands.argument("time", VersionAdapterHolder.get().timeArgument())
                        .suggests((context, builder) ->
                                SharedSuggestionProvider.suggest(new String[] {"60s", "1d", "3d"}, builder))
                        .executes(context -> sprint(
                                context.getSource(),
                                IntegerArgumentType.getInteger(context, "time")))));

        dispatcher.register(root);
    }

    // ------------------------------------------------------------------
    // 子命令实现
    // ------------------------------------------------------------------

    private static int setTickingRate(CommandSourceStack source, float rate) {
        TickControlAccess control = TickControl.get(source);
        if (control == null) {
            return 0;
        }
        control.setTickRate(rate);
        float effective = control.tickRate();
        VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.RATE_SUCCESS,
                formatRate(effective)), true);
        return (int) effective;
    }

    private static int tickQuery(CommandSourceStack source) {
        TickControlAccess control = TickControl.get(source);
        if (control == null) {
            return 0;
        }
        float rate = control.tickRate();
        TickStats stats = control.stats();
        String rateText = formatRate(rate);

        // 状态行：冻结 / 跟不上 / 正常
        if (control.isFrozen()) {
            VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.STATUS_FROZEN), false);
        } else if (stats != null && !stats.isEmpty() && stats.isLagging(rate)) {
            VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.STATUS_LAGGING), false);
        } else {
            VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.STATUS_RUNNING), false);
        }

        double averageMillis = stats == null ? 0.0D : stats.averageMillis();
        double targetMillis = TickStats.targetMillisPerTick(rate);
        VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.QUERY_RATE_RUNNING,
                rateText,
                TickStats.formatMillis(averageMillis),
                TickStats.formatMillis(targetMillis)), false);

        if (stats != null && !stats.isEmpty()) {
            VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.QUERY_PERCENTILES,
                    Integer.toString(stats.size()),
                    TickStats.formatMillis(stats.percentileMillis(0.50D)),
                    TickStats.formatMillis(stats.percentileMillis(0.95D)),
                    TickStats.formatMillis(stats.percentileMillis(0.99D)),
                    TickStats.formatMillis(stats.averageMillis())), false);
        }

        return (int) rate;
    }

    private static int setFreeze(CommandSourceStack source, boolean frozen) {
        TickControlAccess control = TickControl.get(source);
        if (control == null) {
            return 0;
        }
        // 与上游一致：冲刺期间不允许冻结（会与冲刺的临时解冻互相干扰）
        if (frozen && !control.canFreeze()) {
            VersionAdapterHolder.get().sendFailure(source, VersionAdapterHolder.get().translatable(TickLang.FREEZE_FAIL_SPRINTING));
            return 0;
        }
        control.setFrozen(frozen);
        VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(
                frozen ? TickLang.STATUS_FROZEN : TickLang.STATUS_RUNNING), true);
        // 与原版一致：freeze 返回 1，unfreeze 返回 0
        return frozen ? 1 : 0;
    }

    private static int step(CommandSourceStack source, int ticks) {
        TickControlAccess control = TickControl.get(source);
        if (control == null) {
            return 0;
        }
        if (control.requestStep(ticks)) {
            VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.STEP_SUCCESS,
                    Integer.toString(ticks)), true);
        } else {
            VersionAdapterHolder.get().sendFailure(source, VersionAdapterHolder.get().translatable(TickLang.STEP_FAIL));
        }
        // 与原版一致：无论成功与否都返回 1
        return 1;
    }

    private static int stopStepping(CommandSourceStack source) {
        TickControlAccess control = TickControl.get(source);
        if (control == null) {
            return 0;
        }
        if (control.stopStepping()) {
            VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.STEP_STOP_SUCCESS), true);
            return 1;
        }
        VersionAdapterHolder.get().sendFailure(source, VersionAdapterHolder.get().translatable(TickLang.STEP_STOP_FAIL));
        return 0;
    }

    /**
     * {@code /tick sprint <time>}。
     *
     * <p>与上游 {@code TickCommand#sprint} 一致：若之前已在冲刺，先提示已停止；
     * 然后总是输出「冲刺中」状态行，返回 1。
     */
    private static int sprint(CommandSourceStack source, int ticks) {
        TickControlAccess control = TickControl.get(source);
        if (control == null) {
            return 0;
        }
        boolean already = control.requestSprint(ticks);
        if (already) {
            VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.SPRINT_STOP_SUCCESS), true);
        }
        VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.STATUS_SPRINTING), true);
        return 1;
    }

    /** {@code /tick sprint stop}。 */
    private static int stopSprinting(CommandSourceStack source) {
        TickControlAccess control = TickControl.get(source);
        if (control == null) {
            return 0;
        }
        if (control.stopSprint()) {
            VersionAdapterHolder.get().sendSuccess(source, () -> VersionAdapterHolder.get().translatable(TickLang.SPRINT_STOP_SUCCESS), true);
            return 1;
        }
        VersionAdapterHolder.get().sendFailure(source, VersionAdapterHolder.get().translatable(TickLang.SPRINT_STOP_FAIL));
        return 0;
    }

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    /** 原版用 {@code %.1f} 输出速率，所以 20.0 显示为 "20.0"、30.5 显示为 "30.5"。 */
    private static String formatRate(float rate) {
        return String.format(Locale.ROOT, "%.1f", rate);
    }
}
