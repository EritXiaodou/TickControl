
package com.tamamo.tickcontrol.core;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.server.MinecraftServer;

/**
 * 按 {@link MinecraftServer} 实例取默认的 {@link ServerTickController}（1.7.10 版）。
 *
 * <p><b>为什么自带注册表而不复用 command 包的那个</b>：1.12.2 版本里这张表放在
 * {@code command.TickControl}，而 1.7.10 的命令层必须整体重写（legacy {@code ICommand}
 * 与 1.12.2 不同），把 core 绑到 command 上会形成无谓的编译依赖与循环风险。
 * 这里自带一张 {@link WeakHashMap}，core 层就完全自包含。
 *
 * <p>用 {@link WeakHashMap} 而不是静态单例：单人游戏退出世界再进新世界会换一个
 * {@code MinecraftServer} 实例，静态单例会把上一个实例的控制器带过去。
 */
public final class TickControlAccessHolder {

    private static final Map<MinecraftServer, ServerTickController> CONTROLLERS =
            new WeakHashMap<MinecraftServer, ServerTickController>();

    private TickControlAccessHolder() {
    }

    /** 取该服务器的控制器；没有就新建并登记，保证命令层与主循环看到同一个实例。 */
    public static ServerTickController controller(MinecraftServer server) {
        synchronized (CONTROLLERS) {
            ServerTickController existing = CONTROLLERS.get(server);
            if (existing != null) {
                return existing;
            }
            ServerTickController created = new ServerTickController();
            CONTROLLERS.put(server, created);
            return created;
        }
    }

    /** 只查不建；命令来源拿不到服务器时返回 {@code null}。 */
    public static ServerTickController getOrNull(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        synchronized (CONTROLLERS) {
            return CONTROLLERS.get(server);
        }
    }
}
