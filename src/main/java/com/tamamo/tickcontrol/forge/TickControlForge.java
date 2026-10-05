package com.tamamo.tickcontrol.forge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.tamamo.tickcontrol.command.TickCommand;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * Forge 1.20.1 入口。
 *
 * <p>Minecraft 侧的改动全部在 tickcontrol.mixins.json 描述的 Mixin 里，
 * 这里只负责把 /tick 命令挂到 Brigadier 的命令树上。
 */
@Mod(TickControlForge.MOD_ID)
@EventBusSubscriber(modid = TickControlForge.MOD_ID)
public final class TickControlForge {

    public static final String MOD_ID = "tickcontrol";

    private static final Logger LOGGER = LogUtils.getLogger();

    public TickControlForge() {
        LOGGER.info("Tick Control (Forge 1.20.1) loaded; /tick backport active.");
        // 启动时就探测 Carpet：避免「适配代码没跑」与「Carpet 不在场」混淆不清。
        // 之前的探测放在 sync() 里，而 sync 每 20 刻才跑一次，必须进世界才会出现，
        // 出问题时会误以为代码没生效。
        com.tamamo.tickcontrol.core.CarpetHudBridge.probeAtStartup();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        TickCommand.register(event.getDispatcher());
    }
}