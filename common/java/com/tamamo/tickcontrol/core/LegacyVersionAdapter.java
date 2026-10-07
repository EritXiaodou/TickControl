package com.tamamo.tickcontrol.core;

import java.util.function.Supplier;

import com.mojang.brigadier.arguments.ArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * 1.17.1 的 API 适配实现。
 *
 * <p><b>为什么不能用 {@code Component.translatable/literal}</b>：那两个静态工厂是
 * <b>1.19</b> 才加入 {@code Component} 接口的。1.17.1 只有
 * {@code new TranslatableComponent(key, args)} 与 {@code new TextComponent(text)}。
 * 这正是本仓库 {@code common-1.18.2} 编译失败的原因（它照抄了 1.19+ 的写法），
 * 所以这个适配层必须按版本各写一份。
 *
 * <p>其余两处与 1.19.2 一致：
 * <ul>
 *   <li>{@code sendSuccess} 接受 {@code Component}（1.20.1 才改成 {@code Supplier}），
 *       所以要 {@code message.get()}；</li>
 *   <li>{@code TimeArgument} 只有无参的 {@code time()}，默认 1 刻。</li>
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
        // 1.17.1 的 time() 默认 1 刻；1.20.1 的 time(1) 是同一语义
        return TimeArgument.time();
    }

    @Override
    public Component translatable(String key, Object... args) {
        return new TranslatableComponent(key, args);
    }

    @Override
    public Component literal(String text) {
        return new TextComponent(text);
    }
}