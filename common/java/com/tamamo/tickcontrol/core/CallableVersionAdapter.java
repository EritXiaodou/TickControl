package com.tamamo.tickcontrol.core;

import java.util.function.Supplier;

import com.mojang.brigadier.arguments.ArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;

/**
 * 1.20.1 的 API 适配实现。
 *
 * <p>与 1.18.2 / 1.19.2 的差别只有两处（见 {@link VersionAdapter} 的类注释）：
 * <ul>
 *   <li>{@code sendSuccess} 接受 {@code Supplier<Component>}，直接透传即可；</li>
 *   <li>{@code TimeArgument.time(int)} 可以指定最小刻数，用 {@code time(1)}
 *       与原版 1.20.3 的 {@code TickCommand} 保持一致。</li>
 * </ul>
 */
public final class CallableVersionAdapter implements VersionAdapter {

    @Override
    public void sendSuccess(CommandSourceStack source, Supplier<Component> message, boolean broadcast) {
        source.sendSuccess(message, broadcast);
    }

    @Override
    public void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }

    @Override
    public ArgumentType<Integer> timeArgument() {
        // 与原版 1.20.3 TickCommand 一致：最小 1 刻
        return TimeArgument.time(1);
    }
    @Override
    public Component translatable(String key, Object... args) {
        return Component.translatable(key, args);
    }

    @Override
    public Component literal(String text) {
        return Component.literal(text);
    }
}