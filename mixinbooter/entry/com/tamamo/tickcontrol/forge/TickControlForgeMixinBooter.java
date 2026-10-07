package com.tamamo.tickcontrol.forge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tamamo.tickcontrol.command.TickControlCommand;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/**
 * Forge 1.12.2 入口 —— <b>MixinBooter 变体专用</b>。
 *
 * <h2>为什么类名与内置版不同({@code TickControlForgeMixinBooter})</h2>
 *
 * <p>两个 1.12.2 交付物都必须有自己的 {@code @Mod} 入口类,因为它们的 modid 必须不同
 * (同 modid 会让 FML 抛 {@code DuplicateModsFoundException},客户端启动直接崩溃 ——
 * 已由客户端日志实测)。
 *
 * <p>但仅有"两份源码"还不够:<b>类名也必须不同</b>。第一版两份都叫
 * {@code com.tamamo.tickcontrol.forge.TickControlForge},结果同一个类的定义被两个 mod
 * 容器共用 —— 实测症状是
 *
 * <pre>
 * [Server thread/INFO] [tickcontrol]: Tick Control (built-in Mixin) yielding to the
 *     MixinBooter edition: MixinBooter is present ...
 * </pre>
 *
 * <p>即<b>内置版让位了,而 MixinBooter 版那句 "MixinBooter edition loaded" 从未出现</b>
 * —— 两个 mod 的 {@code serverStarting} 都跑到了内置版那份实现上,于是两次都让位,
 * {@code /tick} 谁也没注册,表现为"命令干脆没了"。
 *
 * <p>本类因此独立命名并且<b>不做让位判断</b>:它永远是主动注册方。让位逻辑只留在内置版
 * ({@code TickControlForge})里。
 *
 * <h2>为什么放在 {@code entry/} 而不是 {@code src/main/java}</h2>
 *
 * <p>避免与 {@code ../src/main/java} 的同路径源码冲突:{@code java.exclude} 按路径匹配
 * 且作用于所有 srcDir,用它排除共享那份会连本变体的一起排掉(产物没有 {@code @Mod} 类),
 * 而两份同名类同时编译又会报 {@code duplicate class}。用独立源目录 +
 * {@code compileJava.source} 显式指定,两个问题一起避开。
 *
 * <p>这是本仓库唯一一处刻意的源码重复:约 40 行接线,且<b>必须</b>不同,不存在漂移风险。
 * 真正的逻辑仍在 {@code common-1.12.2}。
 */
@Mod(
        modid = TickControlForgeMixinBooter.MOD_ID,
        name = "Tick Control (MixinBooter)",
        version = TickControlForgeMixinBooter.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*")
public final class TickControlForgeMixinBooter {

    /** 与已通过实测的构建保持一致,刻意不改。内置版用 {@code tickcontrolbuiltin}。 */
    public static final String MOD_ID = "tickcontrol";
    public static final String VERSION = "1.0.0-mixinbooter";

    private static final Logger LOGGER = LogManager.getLogger("tickcontrol");

    /**
     * 无条件注册 {@code /tick}。
     *
     * <p>刻意<b>不</b>判断 MixinBooter 是否在场 —— 让位是内置版的职责。若这里也让位,
     * 两个 mod 会互相谦让,命令就没人注册了(这正是第一版的故障)。
     */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(TickControlCommand.INSTANCE);
        LOGGER.info("Tick Control (Forge 1.12.2, MixinBooter edition) loaded;"
                + " /tick backport active.");
    }
}
