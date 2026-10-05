package com.tamamo.tickcontrol.neoforge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.tamamo.tickcontrol.command.TickCommand;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * NeoForge 1.20.1 入口。
 *
 * <p><b>重要</b>：1.20.1 的 NeoForge（坐标 {@code net.neoforged:forge:1.20.1-47.1.x}）
 * 仍然使用 {@code net.minecraftforge.*} 包名——它只是换了 groupId/artifactId 的
 * Forge 分支；{@code net.neoforged.*} 命名空间要到 20.2 才引入。元数据文件同样是
 * {@code META-INF/mods.toml}，Mixin 也仍由 manifest 的 {@code MixinConfigs} 声明。
 *
 * <p>因此本类与 Forge 侧的 {@code TickControlForge} 基本一致，只是编译进面向不同
 * 加载器坐标的 jar；核心逻辑与 Mixin 由两个工程共用 {@code common/} 的那一份。
 */
@Mod(TickControlNeoForge.MOD_ID)
public final class TickControlNeoForge {

    public static final String MOD_ID = "tickcontrol";

    private static final Logger LOGGER = LogUtils.getLogger();

    static {
        // RegisterCommandsEvent 发在 game bus 上，这里显式注册，避免依赖
        // @EventBusSubscriber 的自动订阅（两种加载器下行为更可预期）。
        MinecraftForge.EVENT_BUS.register(TickControlNeoForge.class);
    }

    public TickControlNeoForge() {
        LOGGER.info("Tick Control (NeoForge 1.20.1) loaded; /tick backport active.");
        // 与 Forge 侧一致：启动时就探测 Carpet，避免诊断信息要等进世界才出现
        com.tamamo.tickcontrol.core.CarpetHudBridge.probeAtStartup();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        TickCommand.register(event.getDispatcher());
    }
}
