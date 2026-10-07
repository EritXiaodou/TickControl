
package com.tamamo.tickcontrol.core;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.ITextComponent;

/**
 * 跨 Minecraft 版本的 API 适配层（1.12.2 版）。
 *
 * <h2>为什么需要它</h2>
 *
 * 本模组为 1.12.2 / 1.16.5 / 1.17.1 / 1.18.2 / 1.19.2 / 1.20.1 各出一份产物，
 * 这些版本的命令与文本 API 有直接冲突，<b>无法用同一份源码编译</b>。
 * 因此每个版本各有一份源码树（{@code common-<version>/java}），
 * <b>只有本接口的实现不同</b>；命令层只依赖本接口。
 *
 * <h2>1.12.2 与其它版本的差异（均已对着 1.12.2 的 jar 核验）</h2>
 *
 * <table border="1">
 *   <caption>差异</caption>
 *   <tr><th>用途</th><th>1.16.5+</th><th>1.12.2</th></tr>
 *   <tr><td>命令来源</td><td>{@code CommandSource}</td>
 *       <td>{@link ICommandSender}（1.12.2 没有 {@code CommandSource}）</td></tr>
 *   <tr><td>反馈</td>
 *       <td>{@code sendSuccess(Supplier&lt;ITextComponent&gt;, boolean)}</td>
 *       <td>{@code CommandBase.notifyCommandListener(sender, command, key, args)}
 *           或 {@code ICommandSender#sendMessage}</td></tr>
 *   <tr><td>时间参数</td><td>Brigadier {@code ArgumentType<Integer>}</td>
 *       <td><b>没有 Brigadier</b>，改为手工解析字符串</td></tr>
 * </table>
 *
 * <p>因为 1.12.2 的反馈走「翻译键 + 参数」（{@code notifyCommandListener} 只接受
 * 翻译键，不接受已构造好的组件），本接口的反馈方法收的是
 * {@code translationKey + args}，而不是 1.16.5 那种 {@code Supplier<ITextComponent>}。
 */
public interface VersionAdapter {

    /**
     * 向命令来源发送成功消息。
     *
     * @param source         命令来源
     * @param command        触发这条消息的命令；{@code broadcast} 为真时
     *                       1.12.2 的实现需要它来做权限判断，可以为 {@code null}
     * @param translationKey 翻译键
     * @param args           翻译键的格式化参数
     * @param broadcast      是否广播给其他有权限的管理员
     */
    void sendSuccess(ICommandSender source, ICommand command, String translationKey,
                     Object[] args, boolean broadcast);

    /** 向命令来源发送失败消息（只回给发起者，不广播）。 */
    void sendFailure(ICommandSender source, String translationKey, Object[] args);

    /**
     * 解析 {@code step} / {@code sprint} 的时间参数。
     *
     * <p>1.12.2 没有 Brigadier 的 {@code TimeArgument}，所以这里手工解析：
     * 支持可选的 {@code t}（刻，默认）/ {@code s}（秒 = 20 刻）/ {@code d}（天 = 24000 刻）
     * 后缀，语义与 1.20.3 的 {@code TimeArgument#time(1)} 一致（最小 1 刻）。
     *
     * @return 解析出的刻数（&gt;= 1）；<b>无法解析时返回 0</b>，
     *         由调用方决定用什么文案报错（翻译键因此仍然集中在命令包里）
     */
    int parseTime(String argument);

    /** 构造可翻译文本组件。 */
    ITextComponent translatable(String translationKey, Object... args);

    /** 构造字面文本组件。 */
    ITextComponent literal(String text);
}
