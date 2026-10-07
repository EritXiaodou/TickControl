
package com.tamamo.tickcontrol.core;

import com.tamamo.tickcontrol.command.TickControl;

import net.minecraft.server.MinecraftServer;

/**
 * 按 {@link MinecraftServer} 实例取默认的 {@link ServerTickController}。
 *
 * <p>两个名字域的薄 Mixin（见 {@code MinecraftServerMixinDev} /
 * {@code MinecraftServerMixinSrg}）都把逻辑委托给 {@link ServerLoop}，
 * 而逻辑需要一个稳定的控制器实例，因此统一从这里取：
 *
 * <ul>
 *   <li>平台入口（{@code TickControlForge}）已经登记过就直接复用；</li>
 *   <li>没有则创建并登记，保证命令层与主循环看到的是同一个实例。</li>
 * </ul>
 *
 * <p>只在注入方法体内调用（不在 Mixin 的字段初始化器里），
 * 避免在目标类构造阶段过早执行。
 */
public final class TickControlAccessHolder {

    private TickControlAccessHolder() {
    }

    public static ServerTickController controller(MinecraftServer server) {
        TickControlAccess existing = TickControl.forServer(server);
        // Classic instanceof + cast, not Java 16 pattern matching: 1.16.5 is a
        // Java 8 target (see ServerLoop's identical note).
        if (existing instanceof ServerTickController) {
            return (ServerTickController) existing;
        }
        ServerTickController created = new ServerTickController();
        TickControl.register(server, created);
        return created;
    }
}
