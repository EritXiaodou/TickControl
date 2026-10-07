
package com.tamamo.tickcontrol.core;

import java.util.function.Supplier;

import com.mojang.brigadier.arguments.ArgumentType;

import net.minecraft.command.CommandSource;
import net.minecraft.command.arguments.TimeArgument;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * 1.18.2 / 1.19.2 的 API 适配实现。
 *
 * <p>与 1.20.1 的差别只有两处（见 {@link VersionAdapter} 的类注释）：
 * <ul>
 *   <li>{@code sendSuccess} 接受 {@code ITextComponent}，所以要 {@code message.get()}；</li>
 *   <li>{@code TimeArgument} 只有无参的 {@code time()}，它默认就是 1 刻，
 *       与 1.20.1 的 {@code time(1)} 语义一致。</li>
 * </ul>
 */
public final class LegacyVersionAdapter implements VersionAdapter {

    @Override
    public void sendSuccess(CommandSource source, Supplier<ITextComponent> message, boolean broadcast) {
        source.sendFeedback(message.get(), broadcast);
    }

    @Override
    public void sendFailure(CommandSource source, ITextComponent message) {
        source.sendErrorMessage(message);
    }

    @Override
    public ArgumentType<Integer> timeArgument() {
        // 1.18.2 / 1.19.2 的 time() 默认 1 刻；1.20.1 的 time(1) 是同一语义
        // 1.16.5 没有静态 time()，只有无参构造器（默认 1 刻）
        return new TimeArgument();
    }
    @Override
    public ITextComponent translatable(String key, Object... args) {
        // 1.19 才有 ITextComponent.translatable 静态工厂；1.16.5 用构造器
        return new TranslationTextComponent(key, args);
    }

    @Override
    public ITextComponent literal(String text) {
        // 同上，1.16.5 用 StringTextComponent 构造器
        return new StringTextComponent(text);
    }
}