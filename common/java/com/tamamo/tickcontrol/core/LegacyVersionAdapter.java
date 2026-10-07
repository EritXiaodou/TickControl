
package com.tamamo.tickcontrol.core;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;

/**
 * 1.12.2 的 API 适配实现。
 *
 * <h2>反馈为什么要用 notifyCommandListener</h2>
 *
 * 1.16.5 的 {@code CommandSource#sendFeedback(component, true)} 做两件事：
 * 把消息回给发起者，并把消息以 {@code chat.type.admin} 包装广播给其他有权限的管理员。
 * 1.12.2 没有这个方法，等价物是
 * {@code CommandBase.notifyCommandListener(sender, command, key, args)}
 * ——已对着 1.12.2 的字节码核验过 {@code ServerCommandManager#notifyListener}：
 * <ul>
 *   <li>它把<b>原始组件</b>{@code sender.sendMessage(...)} 回给发起者
 *       （控制台来源恒发；玩家来源受 {@code gamerule sendCommandFeedback} 约束）；</li>
 *   <li>并以 {@code chat.type.admin} 包装后广播给「能收命令反馈且对该命令有权限」的
 *       在线玩家，与 1.16.5 的 admin broadcast 语义一致。</li>
 * </ul>
 * 它只接受「翻译键 + 参数」，这正是本适配层接口收 key/args 而不是
 * {@code Supplier<ITextComponent>} 的原因。
 *
 * <h2>时间参数为什么要手工解析</h2>
 *
 * 1.12.2 完全没有 Brigadier（整份 joined.tsrg 里 {@code brigadier} 零命中），
 * 没有 {@code TimeArgument}，因此这里手工解析 {@code t}/{@code s}/{@code d} 后缀。
 */
public final class LegacyVersionAdapter implements VersionAdapter {

    /** 1 秒 = 20 刻（与原版 {@code TimeArgument} 的换算一致）。 */
    private static final long TICKS_PER_SECOND = 20L;
    /** 1 天 = 24000 刻。 */
    private static final long TICKS_PER_DAY = 24_000L;

    /**
     * 回一条成功消息。
     *
     * <h2>为什么这里<b>必须</b>直接 sendMessage,不能用 notifyCommandListener</h2>
     *
     * <p>1.12.2 的 {@code CommandBase.notifyCommandListener} 只有两个重载,
     * <b>没有任何一个接受 {@code boolean}</b>(那是 1.13+ 才有的参数)。
     * 反编译它的字节码可以看到 4 参重载做的事是:
     *
     * <pre>
     *   notifyCommandListener(source, command, false, key, args);   // iconst_0
     * </pre>
     *
     * <p>而 5 参重载的全部逻辑是:
     *
     * <pre>
     *   if (commandListener != null) commandListener.notifyListener(...);
     * </pre>
     *
     * <p>{@code commandListener} 是一个<b>静态字段,只在专用服务器上由命令方块日志器
     * 安装</b>,单人游戏里恒为 {@code null}。也就是说 {@code notifyCommandListener}
     * 在单人下<b>什么都不做</b>——消息根本发不出去。
     *
     * <p>这正是聊天栏一直显示原始翻译键的原因:玩家看到的不是我们发的组件,
     * 而是服务端回显里那条未经翻译的键名。之前那个 {@code if (broadcast ...)}
     * 分支是照搬 1.13+ 的写法,在 1.12.2 上不成立。
     *
     * <p>现在统一直接发给发起者。命令方块/控制台的广播由原版自己的广播路径负责,
     * 不需要我们代劳,而"玩家看不到任何反馈"是不可接受的。
     */
    @Override
    public void sendSuccess(ICommandSender source, ICommand command, String translationKey,
                            Object[] args, boolean broadcast) {
        diagnoseOnce(translationKey);
        source.sendMessage(new TextComponentTranslation(translationKey, args));
    }

    /** 只打印一次,避免刷屏。 */
    private static boolean diagnosed;

    /**
     * 一次性诊断:把「游戏自己能否解析这个键」写进日志。
     *
     * <p>加它的原因:聊天栏一直显示原始翻译键,而我已经逐一确认过——语言文件在 jar 里、
     * 21 个键齐全、无 BOM、JSON 有效、资源包被 FML 构造、客户端与服务端用的是同一个 jar
     * (SHA256 一致)。所有"看起来对"的地方都对,却仍然显示原文。
     *
     * <p>继续靠推断没有意义,所以让游戏自己回答。用 {@code I18n} 而不是
     * {@code LanguageMap}:后者是客户端专用类,且 {@code getInstance()} 是包级私有;
     * {@code I18n.canTranslate/translateToLocal} 是公开静态,而且查的正是
     * <b>客户端语言表</b>——也就是决定聊天栏显示原文还是译文的那张表。
     *
     * <p>两种结果指向完全不同的方向:
     * <ul>
     *   <li>{@code canTranslate=false} → 客户端语言表里没有这个键,问题在资源加载;</li>
     *   <li>{@code canTranslate=true} → 键是能翻译的,问题在消息组件的构造或投递方式。</li>
     * </ul>
     */
    private static void diagnoseOnce(String key) {
        if (diagnosed) {
            return;
        }
        diagnosed = true;
        try {
            boolean known = net.minecraft.util.text.translation.I18n.canTranslate(key);
            String value = net.minecraft.util.text.translation.I18n.translateToLocal(key);
            System.out.println("[tickcontrol] lang diagnostic: key=" + key
                    + " clientCanTranslate=" + known
                    + " clientValue=" + value);
        } catch (Throwable t) {
            System.out.println("[tickcontrol] lang diagnostic failed: " + t);
        }
    }

    @Override
    public void sendFailure(ICommandSender source, String translationKey, Object[] args) {
        // 1.12.2 的 ICommandSender 没有 sendErrorMessage（那是 1.13+），
        // 失败信息同样用一条普通文本回给发起者。
        source.sendMessage(new TextComponentTranslation(translationKey, args));
    }

    @Override
    public int parseTime(String argument) {
        String value = argument == null ? "" : argument.trim();
        if (value.isEmpty()) {
            return 0;
        }

        long multiplier = 1L;
        String digits = value;
        char last = value.charAt(value.length() - 1);
        if (last == 't' || last == 'T') {
            digits = value.substring(0, value.length() - 1);
        } else if (last == 's' || last == 'S') {
            digits = value.substring(0, value.length() - 1);
            multiplier = TICKS_PER_SECOND;
        } else if (last == 'd' || last == 'D') {
            digits = value.substring(0, value.length() - 1);
            multiplier = TICKS_PER_DAY;
        } else if (last < '0' || last > '9') {
            return 0;
        }
        if (digits.isEmpty()) {
            return 0;
        }

        long parsed;
        try {
            parsed = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
        if (parsed < 1L) {
            return 0;
        }
        // 溢出保护：先乘法再判断，用 long 承接，避免 int 溢出成负数
        long ticks = parsed * multiplier;
        if (ticks < 1L || ticks > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) ticks;
    }

    @Override
    public ITextComponent translatable(String translationKey, Object... args) {
        // 1.19 才有 ITextComponent.translatable 静态工厂；1.12.2 用构造器
        return new TextComponentTranslation(translationKey, args);
    }

    @Override
    public ITextComponent literal(String text) {
        return new TextComponentString(text);
    }
}
