
package com.tamamo.tickcontrol.core;

import java.util.function.Supplier;

import com.mojang.brigadier.arguments.ArgumentType;

import net.minecraft.command.CommandSource;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;

/**
 * 跨 Minecraft 版本的 API 适配层。
 *
 * <h2>为什么需要它</h2>
 *
 * 本模组为 1.18.2 / 1.19.2 / 1.20.1 各出一份产物，这三个版本的命令与文本 API
 * 有直接冲突，<b>无法用同一份源码编译</b>（实测 1.19.2 与 1.20.1 的差异会让共用
 * 源码产生 15 个编译错误，1.18.2 又是另一组）：
 *
 * <table border="1">
 *   <caption>版本差异</caption>
 *   <tr><th>API</th><th>1.18.2</th><th>1.19.2</th><th>1.20.1</th></tr>
 *   <tr><td>sendSuccess</td>
 *       <td>ITextComponent</td><td>ITextComponent</td><td>Supplier&lt;ITextComponent&gt;</td></tr>
 *   <tr><td>TimeArgument</td>
 *       <td>time()</td><td>time()</td><td>time() + time(int)</td></tr>
 *   <tr><td>文本组件</td>
 *       <td>new TranslationStringTextComponent(...)</td>
 *       <td>ITextComponent.translatable(...)</td>
 *       <td>ITextComponent.translatable(...)</td></tr>
 * </table>
 *
 * <p>因此每个版本各有一份源码树（common-&lt;version&gt;/java），
 * <b>只有本接口的实现不同</b>。命令层只依赖本接口，所以除实现类以外，
 * 各版本的 TickCommand / ServerLoop 源码完全相同。
 */
public interface VersionAdapter {

    /**
     * 向命令来源发送成功消息。
     *
     * @param source    命令来源
     * @param message   消息内容（延迟构造；1.19.2 及以下会立即求值）
     * @param broadcast 是否广播给其他管理员
     */
    void sendSuccess(CommandSource source, Supplier<ITextComponent> message, boolean broadcast);

    /** 向命令来源发送失败消息。 */
    void sendFailure(CommandSource source, ITextComponent message);

    /**
     * step / sprint 的 time 参数类型。
     *
     * @return 最小值为 1 刻的时间参数类型
     */
    ArgumentType<Integer> timeArgument();

    /**
     * 构造可翻译文本组件。
     *
     * <p>1.18.2 没有 {@code ITextComponent.translatable} 静态工厂（1.19 才加入），
     * 只能用 {@code new TranslationStringTextComponent(key, args)}。
     *
     * @param key  翻译键
     * @param args 格式化参数
     */
    ITextComponent translatable(String key, Object... args);

    /** 构造字面文本组件（1.18.2 用 {@code new StringTextComponent(...)}）。 */
    ITextComponent literal(String text);
}