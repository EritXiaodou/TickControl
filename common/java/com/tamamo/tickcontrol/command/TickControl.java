
package com.tamamo.tickcontrol.command;

import java.util.Map;
import java.util.WeakHashMap;

import com.tamamo.tickcontrol.core.TickControlAccess;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

/**
 * 把平台侧的 tick 控制器按 {@link MinecraftServer} 实例登记，供命令层取用。
 *
 * <p>这样命令实现可以完全待在 {@code common} 里，只由各平台的入口类负责
 * 在服务器启动/停止时 {@link #register}/{@link #unregister}。
 *
 * <p>用 {@link WeakHashMap} 而不是直接存单个静态引用，是为了在单人游戏
 * 「退出世界再进新世界」时不会残留上一个 {@code MinecraftServer} 的控制器。
 *
 * <h2>1.12.2 的差异</h2>
 *
 * 1.16.5 的对应物是 {@code CommandSource}，1.12.2 没有这个类，
 * 命令来源是 {@link ICommandSender}；它上面的 {@code getServer()}
 * 与 1.16.5 的 {@code CommandSource#getServer()} 语义相同
 * （1.12.2 的 {@code MinecraftServer} 自己也实现 {@code ICommandSender}）。
 */
public final class TickControl {

    private static final Map<MinecraftServer, TickControlAccess> CONTROLLERS = new WeakHashMap<>();

    private TickControl() {
    }

    public static void register(MinecraftServer server, TickControlAccess access) {
        synchronized (CONTROLLERS) {
            CONTROLLERS.put(server, access);
        }
    }

    public static void unregister(MinecraftServer server) {
        synchronized (CONTROLLERS) {
            CONTROLLERS.remove(server);
        }
    }

    /** 供平台侧在服务器线程外读取当前控制器（例如同步网络包）。 */
    public static TickControlAccess forServer(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        synchronized (CONTROLLERS) {
            return CONTROLLERS.get(server);
        }
    }

    /**
     * 从命令来源解析控制器；解析不到时返回 {@code null}，调用方按「执行失败」处理。
     *
     * <p>legacy 的 {@code ICommand#execute} 另外会直接给出 {@code MinecraftServer}，
     * 命令层优先用那个，这里是兜底路径。
     */
    public static TickControlAccess get(ICommandSender source) {
        if (source == null) {
            return null;
        }
        return forServer(source.getServer());
    }
}
