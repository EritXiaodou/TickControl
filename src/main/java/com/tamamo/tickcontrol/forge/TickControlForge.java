package com.tamamo.tickcontrol.forge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.tamamo.tickcontrol.command.TickCommand;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;


/**
 * Forge 1.16.5 入口。
 *
 * <p>Minecraft 侧的改动全部在 {@code tickcontrol.mixins.json} 描述的 Mixin 里，
 * 这里只负责把 {@code /tick} 命令挂到 Brigadier 的命令树上。
 *
 * <p><b>1.16.5 与 1.17.1 的差别</b>：1.16.5 已经有 {@code com.mojang.logging.LogUtils}
 * （1.17.1 还没有，那边只能用 Log4j），所以这里直接用 {@code LogUtils.getLogger()}。
 */
@Mod(TickControlForge.MOD_ID)
@EventBusSubscriber(modid = TickControlForge.MOD_ID)
public final class TickControlForge {

    public static final String MOD_ID = "tickcontrol";

    private static final Logger LOGGER = LogManager.getLogger("tickcontrol");

    public TickControlForge() {
        // 版本号从加载器元数据读，不硬编码：排查时日志里的版本号如果不对，
        // 很容易被误导成「加载了错误的 jar」。
        LOGGER.info("Tick Control (Forge {}) loaded; /tick backport active.", modVersion());
        // 启动时就探测 Carpet：把「适配代码没跑」与「Carpet 不在场」分开，
        // 否则出问题时在日志里完全区分不了。
        com.tamamo.tickcontrol.core.CarpetHudBridge.probeAtStartup();
    }

    /** 本模组的实际版本，来自 mods.toml 展开出的加载器元数据。 */
    private static String modVersion() {
        return ModList.get()
                .getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        TickCommand.register(event.getDispatcher());
    }
}
