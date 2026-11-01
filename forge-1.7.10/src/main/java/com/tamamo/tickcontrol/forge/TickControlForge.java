package com.tamamo.tickcontrol.forge;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * Forge 1.7.10 入口。
 *
 * <p>1.7.10 的加载器是 {@code cpw.mods.fml}（FML），不是 1.13+ 的
 * {@code net.minecraftforge.fml}，注解也少得多：只有 {@code @Mod}，没有
 * {@code @EventBusSubscriber}。命令通过 {@link FMLServerStartingEvent} 注册，
 * 这是 1.7.10 的标准做法（比自己去拿 {@code ICommandManager} 更稳）。
 *
 * <p>1.7.10 **完全没有 Brigadier**（已核验：FML 1.7.10 的 joined.srg 里
 * {@code brigadier} 零命中），所以 {@code /tick} 必须写在 legacy 的
 * {@code ICommand} / {@code CommandBase} 体系上。
 */
@Mod(modid = TickControlForge.MOD_ID, name = "Tick Control", version = "1.0.0",
        acceptedMinecraftVersions = "[1.7.10]")
public class TickControlForge {

    public static final String MOD_ID = "tickcontrol";

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        // 1.7.10 的命令注册方式与 1.12.2 相同(都是 FMLServerStartingEvent),
        // 但命令类本身是按 1.7.10 的 ICommand 接口单独写的——两代的接口不同,
        // 详见 CommandTick 的类注释。
        event.registerServerCommand(new com.tamamo.tickcontrol.command.CommandTick());
    }
}
