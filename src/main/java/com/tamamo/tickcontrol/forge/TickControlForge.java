package com.tamamo.tickcontrol.forge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.tamamo.tickcontrol.command.TickControlCommand;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/**
 * Forge 1.12.2 入口。
 *
 * <p>Minecraft 侧的改动全部在 {@code tickcontrol.mixins.json} 描述的 Mixin 里
 * （由 {@code com.tamamo.tickcontrol.core.TickControlCore} 这个 coremod 注册），
 * 这里只负责把 {@code /tick} 命令挂到 1.12.2 的 legacy 命令管理器上。
 *
 * <h2>与其它版本入口的差异</h2>
 *
 * <ul>
 *   <li>1.12.2 没有 {@code RegisterCommandsEvent}（那是 1.13+），
 *       命令要在 {@link FMLServerStartingEvent} 里用
 *       {@code event.registerServerCommand(new ...)} 注册；</li>
 *   <li>事件注解是 {@code @Mod.EventHandler}（1.7.10 的 {@code @EventHandler} 早已不存在）；</li>
 *   <li>命令类实现的是 legacy {@code ICommand}（1.12.2 没有 Brigadier），
 *       由 common 侧的 {@code TickControlCommand} 承担。</li>
 * </ul>
 *
 * <h2>两个 1.12.2 变体如何共存(用户要求)</h2>
 *
 * <p>本仓库有<b>两个</b> 1.12.2 交付物:{@code tickcontrol-forge-1.12.2} (内置 Mixin)
 * 与 {@code tickcontrol-forge-1.12.2-mixinbooter} (依赖 MixinBooter)。它们共用本文件,
 * 因此 {@code @Mod} 里的 modid 只能是同一个 —— 若两个 jar 同时放进 {@code mods/},
 * FML 会报 <b>{@code Found a duplicate mod tickcontrol at ...}</b> 并弃掉其中一个。</p>
 *
 * <p>处理方式分两层:</p>
 *
 * <ol>
 *   <li><b>内置版改用独立 modid</b>({@value #MOD_ID})。MixinBooter 版仍是
 *       {@code tickcontrol}。两者 modid 不同,FML 就不会再报重复。</li>
 *   <li><b>MixinBooter 在场时,内置版不再注册命令</b> —— 见
 *       {@link #serverStarting}。因为此时 MixinBooter 版已提供同样的 {@code /tick},
 *       两边都注册会得到两条同名命令。这样内置版变成"被静默弃用"而不是报错。</li>
 * </ol>
 *
 * <p>注意 {@code MOD_ID} 只影响 modid,不影响本地化:语言文件的键
 * ({@code tickcontrol.commands.tick.*}) 与资源域 ({@code assets/tickcontrol/lang/})
 * 都与 modid 无关,这也是刻意保留 {@code tickcontrol} 作为资源域的原因。
 *
 * <p>另:本条改动的必要性来自用户实测 —— 两个 jar 同时安装时报"安装相同 mod"。
 */
@Mod(
        modid = TickControlForge.MOD_ID,
        name = "Tick Control (built-in Mixin)",
        version = TickControlForge.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*")
public final class TickControlForge {

    /**
     * <b>与 MixinBooter 版不同</b>的 modid —— 这是硬性要求。
     *
     * <h2>为什么必须不同(客户端日志实测)</h2>
     *
     * <p>一度让两个 jar 都用 {@code tickcontrol},依据是 FML 源码里
     * {@code Loader.identifyDuplicates} 那句 "Found a duplicate mod" 只是一条 <b>WARN</b>。
     * 那个判断是<b>错的</b>:该方法的警告之后紧跟着抛出
     * {@code DuplicateModsFoundException},客户端在启动时直接崩溃。实测日志:
     *
     * <pre>
     * [Client thread/FATAL] [FML]: Found a duplicate mod tickcontrol at ...
     * net.minecraftforge.fml.common.DuplicateModsFoundException:
     * Duplicate Mods:
     *   tickcontrol : ...tickcontrol-forge-1.12.2-mixinbooter-1.0.0-mixinbooter.jar
     *   tickcontrol : ...tickcontrol-forge-1.12.2-1.0.0.jar
     * </pre>
     *
     * <p>教训:我是<b>只读了方法的前半段</b>就下结论的。判读字节码时看到 WARN 就收手,
     * 没有继续往下看到 {@code athrow}。
     *
     * <h2>为什么是 {@code tickcontrolbuiltin} 这个名字</h2>
     *
     * <p>MixinBooter 版保留 {@code tickcontrol}(已通过实测,不动)。本变体加后缀以区分,
     * 两者因此可以同时放在 {@code mods/} 里。
     *
     * <p>{@link #serverStarting} 里还有一层让位逻辑:MixinBooter 在场时本变体不注册
     * {@code /tick},避免同名命令注册两次。
     */
    public static final String MOD_ID = "tickcontrolbuiltin";
    public static final String VERSION = "1.0.0";

    /** MixinBooter 的 modid;它决定本变体是否让位。 */
    private static final String MIXINBOOTER_MOD_ID = "mixinbooter";

    private static final Logger LOGGER = LogManager.getLogger("tickcontrol");

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // MixinBooter 在场时让位:MixinBooter 版已经提供 /tick,
        // 本变体若再注册一次会得到两条同名命令。
        //
        // 判据用 modid "mixinbooter" 而不是类名:MixinBooter 的 @Mod 声明的就是它,
        // 而且 isModLoaded 不会触发类加载。放在事件处理器里也安全 ——
        // Loader 在 FMLServerStartingEvent 阶段早已就绪。
        if (Loader.isModLoaded(MIXINBOOTER_MOD_ID)) {
            LOGGER.info("Tick Control (built-in Mixin) yielding to the MixinBooter edition:"
                    + " MixinBooter is present, so /tick is registered by"
                    + " tickcontrol-forge-1.12.2-mixinbooter. This jar is inert.");
            return;
        }
        event.registerServerCommand(TickControlCommand.INSTANCE);
        LOGGER.info("Tick Control (Forge 1.12.2, built-in Mixin) loaded;"
                + " /tick backport active.");
    }
}
