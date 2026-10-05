package com.tamamo.tickcontrol.core;

import java.util.function.Supplier;

import com.mojang.brigadier.arguments.ArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;

/**
 * 1.18.2 / 1.19.2 的 API 适配实现。
 *
 * <p>与 1.20.1 的差别只有两处（见 {@link VersionAdapter} 的类注释）：
 * <ul>
 *   <li>{@code sendSuccess} 接受 {@code Component}，所以要 {@code message.get()}；</li>
 *   <li>{@code TimeArgument} 只有无参的 {@code time()}，它默认就是 1 刻，
 *       与 1.20.1 的 {@code time(1)} 语义一致。</li>
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
    @Override
    public Component translatable(String key, Object... args) {
        return Component.translatable(key, args);
    }

    @Override
    public Component literal(String text) {
        return Component.literal(text);
    }
}