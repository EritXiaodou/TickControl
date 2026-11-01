package com.tamamo.tickcontrol.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.server.MinecraftServer;

/**
 * 反射桥接:访问原版 {@code MinecraftServer} 里主循环依赖的成员。
 *
 * <h2>为什么必须用反射</h2>
 *
 * <p>这些成员在<b>开发环境</b>与<b>生产(混淆)环境</b>里名字不同,而 Java 源码只能写一个名字:
 *
 * <pre>
 *   循环条件:  dev serverIsRunning()      prod v : Z       (字段)
 *   tick:      dev tick()                prod u()V
 *   时钟:      prod ar()J                 dev 无此方法
 * </pre>
 *
 * <p>直接写名字编译不过(dev 侧没有 ar),写死生产名则客户端(混淆侧)才对、开发侧编译失败。
 * 反射两边都试,且<b>解析失败不退化成异常</b>:主循环绝不能因为找不到成员而拒绝启动。
 *
 * <p>时间统一用 {@code System.currentTimeMillis()}:1.7.10 的
 * {@code MinecraftServer.ar()} 本身就是它的 4 条指令包装(已核实),行为等价。
 */
public final class LoopAccess {

    private LoopAccess() {
    }

    // ---- 候选名(全部来自官方映射,不再猜) ----
    //
    // 生产名(混淆) : MinecraftServer 自己的字段是单字母 A..z
    //                  v:Z -> field_71317_u  运行标志   (run() 里 GET=1,正是 while 判据)
    //                  P:J -> field_71299_R  时钟       (GET=1 PUT=1)
    //                  O:Z -> field_71296_Q  每轮写入   (PUT=1)
    // 开发名        : serverIsRunning / time / serverIsReady
    //
    // ⚠️ 绝不能再用 mt/J、mt/O、mt/P、mt.e() 之类:那些属于 net/minecraft/world/WorldServer
    //    (CL: mt net/minecraft/world/WorldServer),其中 mt.e()Z = areAllPlayersAsleep,
    //    是"是否所有玩家都睡了"的世界查询,与运行标志毫无关系。
    //    之前的候选表把它们混在了一起,会解析到错误成员。
    private static final String[] RUNNING_FIELD = {"v", "serverIsRunning", "field_71317_u"};
    private static final String[] RUNNING_METHOD = {"isServerRunning", "func_71278_l"};
    private static final String[] TICK_METHOD = {"tick", "u", "func_71217_p"};
    private static final String[] CLOCK_FIELD = {"P", "time", "field_71299_R"};
    private static final String[] READY_FIELD = {"O", "serverIsReady", "field_71296_Q"};

    private static boolean resolved;
    private static Field runningField;
    private static Method runningMethod;
    private static Method tickMethod;
    private static Field clockField;
    private static Field readyField;

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        runningField = field(RUNNING_FIELD, boolean.class);
        if (runningField == null) {
            runningMethod = method(RUNNING_METHOD, boolean.class);
        }
        tickMethod = method(TICK_METHOD, void.class);
        clockField = field(CLOCK_FIELD, long.class);
        readyField = field(READY_FIELD, boolean.class);
    }

    private static Field field(String[] names, Class<?> type) {
        for (String n : names) {
            try {
                Field f = MinecraftServer.class.getDeclaredField(n);
                if (f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignored) {
                // next candidate
            }
        }
        return null;
    }

    private static Method method(String[] names, Class<?> returnType) {
        for (String n : names) {
            try {
                Method m = MinecraftServer.class.getDeclaredMethod(n);
                if (m.getReturnType() == returnType) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
                // next candidate
            }
        }
        return null;
    }

    /** 服务器是否仍在运行;解析失败时返回 {@code true}(绝不能误判为停止而结束主循环)。 */
    public static boolean isRunning(MinecraftServer server) {
        resolve();
        try {
            if (runningField != null) {
                return runningField.getBoolean(server);
            }
            if (runningMethod != null) {
                return ((Boolean) runningMethod.invoke(server)).booleanValue();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 执行一个服务器刻。解析失败时返回 {@code false},由调用方记录一次告警。 */
    public static boolean tick(MinecraftServer server) {
        resolve();
        if (tickMethod == null) {
            return false;
        }
        try {
            tickMethod.invoke(server);
            return true;
        } catch (Throwable t) {
            System.err.println("[tickcontrol] LoopAccess.tick failed: " + t);
            return false;
        }
    }

    public static void setCurrentTime(MinecraftServer server, long value) {
        resolve();
        if (clockField == null) {
            return;
        }
        try {
            clockField.setLong(server, value);
        } catch (Throwable ignored) {
        }
    }

    public static void markReady(MinecraftServer server) {
        resolve();
        if (readyField == null) {
            return;
        }
        try {
            readyField.setBoolean(server, true);
        } catch (Throwable ignored) {
        }
    }
}
