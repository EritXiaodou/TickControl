package com.tamamo.tickcontrol.core;

import java.util.function.Supplier;

import com.mojang.brigadier.arguments.ArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * 1.18.2 的 API 适配实现。
 *
 * <p>与 1.19.2 / 1.20.1 的三处差别（见 {@link VersionAdapter} 的类注释）：
 * <ul>
 *   <li>{@code sendSuccess} 接受 {@code Component}，所以要 {@code message.get()}；</li>
 *   <li>{@code TimeArgument} 只有无参 {@code time()}（默认 1 刻）；</li>
 *   <li>没有 {@code Component.translatable} / {@code Component.literal} 静态工厂
 *       （1.19 才加入），必须用 {@code TranslatableComponent} /
 *       {@code TextComponent} 构造器。</li>
 * </ul>
 */
public final class LegacyVersionAdapter implements VersionAdapter {

    @Override
    public void sendSuccess(CommandSourceStack source, Supplier<Component> message, boolean broadcast) {
        source.sendSuccess(message.get(), broadcast);
    }

    @Override
    public void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }

    @Override
    public ArgumentType<Integer> timeArgument() {
        // 1.18.2 / 1.19.2 的 time() 默认 1 刻；1.20.1 的 time(1) 是同一语义
        return TimeArgument.time();
    }

    /**
     * 构造可翻译组件，并<b>保证内容非空</b>。
     *
     * <p>背景（实测踩过）：1.18.2 原版语言文件里没有 {@code commands.tick.*}
     * （{@code /tick} 是 1.20.3 才加入的命令），只有本模组
     * {@code assets/tickcontrol/lang/*.json} 里有。一旦服务端的语言表没有加载到
     * 模组的语言文件，{@code Component.translatable(key)} 解析结果就是<b>原始键名</b>——
     * 在聊天/控制台里看起来就是「命令执行了但零输出 / 一串英文键」。
     *
     * <p>这里先用语言表试解析：解析得到真文本就用它；解析不到（返回键名本身）
     * 就退回到一个可读的兜底字符串，确保玩家永远能看到反馈，而不是空白。
     */
    @Override
    public Component translatable(String key, Object... args) {
        TranslatableComponent tc = new TranslatableComponent(key, args);
        try {
            String resolved = tc.getString();
            // 原版解析失败时 getString() 返回的就是 key 本身
            if (resolved != null && !resolved.isEmpty() && !resolved.equals(key)) {
                return new TextComponent(resolved);
            }
        } catch (Throwable ignored) {
            // 解析抛异常也走兜底
        }
        return new TextComponent(fallbackFor(key, args));
    }

    /** 解析不到翻译时的可读兜底文本（英文，便于跨语言排查）。 */
    private static String fallbackFor(String key, Object... args) {
        switch (key) {
            case "tickcontrol.commands.tick.status.frozen":    return "Ticking is frozen";
            case "tickcontrol.commands.tick.status.running":   return "Ticking is running";
            case "tickcontrol.commands.tick.status.lagging":   return "Ticking is running behind";
            case "tickcontrol.commands.tick.status.sprinting": return "Ticking is sprinting";
            case "tickcontrol.commands.tick.freeze.success":   return "Froze ticking";
            case "tickcontrol.commands.tick.freeze.fail":
            case "tickcontrol.commands.tick.freeze.fail.sprinting":
                return "Cannot freeze: sprint in progress";
            case "tickcontrol.commands.tick.unfreeze.success": return "Unfroze ticking";
            case "tickcontrol.commands.tick.step.success":     return "Stepped " + argAt(args, 0) + " ticks";
            case "tickcontrol.commands.tick.step.fail":        return "Not stepping";
            case "tickcontrol.commands.tick.step.stop.success":return "Stopped stepping";
            case "tickcontrol.commands.tick.step.stop.fail":   return "Not stepping";
            case "tickcontrol.commands.tick.sprint.stop.success": return "Stopped sprinting";
            case "tickcontrol.commands.tick.sprint.stop.fail":    return "Not sprinting";
            case "tickcontrol.commands.tick.query.rate.running":
                return "Target tick rate: " + argAt(args, 0) + " TPS";
            case "tickcontrol.commands.tick.query.rate.sprinting":
                return "Sprinting (rate not applicable)";
            case "tickcontrol.commands.tick.query.percentiles":
                return "Tick times (ms): " + argAt(args, 0);
            case "tickcontrol.commands.tick.rate.success":
                return "Set target tick rate to " + argAt(args, 0) + " TPS";
            case "tickcontrol.commands.tick.sprint.report":
                return "Sprint completed: " + argAt(args, 0) + " ticks per second, "
                        + argAt(args, 1) + " ms/tick";
            // 兜底也要认「漏了 tickcontrol. 前缀」的老键名：万一以后又有人写错，
            // 玩家看到的是可读英文，而不是 [commands.tick.sprint.report]。
            case "commands.tick.sprint.report":
                return "Sprint completed: " + argAt(args, 0) + " ticks per second, "
                        + argAt(args, 1) + " ms/tick";
            default:
                return "[" + key + "] " + java.util.Arrays.toString(args);
        }
    }

    private static String argAt(Object[] args, int i) {
        return (args != null && args.length > i && args[i] != null) ? String.valueOf(args[i]) : "?";
    }

    @Override
    public Component literal(String text) {
        return new TextComponent(text);
    }
}