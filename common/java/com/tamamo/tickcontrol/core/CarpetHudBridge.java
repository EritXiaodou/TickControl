
package com.tamamo.tickcontrol.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.server.MinecraftServer;

/**
 * 让 Carpet 系 HUD（Carpet 自身、BoccHUD / MiniHUD）显示<b>真实</b> TPS。
 *
 * <h2>为什么需要它</h2>
 *
 * Carpet 的 Forge 1.20.1 版<b>自己也在整个替换 {@code MinecraftServer.runServer}</b>
 * （{@code MinecraftServer_tickspeedMixin.modifiedRunLoop}），并自带一整套
 * {@code carpet.helpers.TickRateManager}。本模组同样接管 {@code runServer}，
 * 而且在 {@code HEAD} 就 {@code cancel()} 了原方法，于是 Carpet 那个位于方法中部的
 * 注入点永远到不了——它的循环从未执行。
 *
 * <p>后果是它的 {@code tickrate} 字段一直停在初始值 {@code 20.0}。而 Carpet 的 TPS
 * 显示算法是：
 * <pre>
 * double mspt = Math.max(tickRateManager.mspt(), 平均(MinecraftServer.tickTimes));
 * double tps  = 1000.0 / mspt;
 * </pre>
 * 即「真实 tickTimes」被「没更新的名义 mspt」封顶，所以 MSPT 会变、TPS 却锁在 20。
 * 而 BoccHUD（MiniHUD 的 fork）用正则
 * {@code TPS: (?<tps>[0-9]+[\.,][0-9]) MSPT: (?<mspt>[0-9]+[\.,][0-9])}
 * <b>直接解析 Carpet 发出的那条聊天消息</b>，因此两者同源。
 *
 * <h2>做法</h2>
 *
 * 每一轮主循环（对齐 Carpet 的 {@code tickCount % 20 == 0} 节奏）把实测 MSPT 写进
 * Carpet 的 tick rate manager，再调用它的 {@code HUDController.update_hud}，
 * 让那条消息带上正确数值。
 *
 * <h2>为什么用反射而不是 Mixin</h2>
 *
 * {@code @Pseudo} 方式（{@code @Mixin(targets = "carpet...")} + {@code require = 0}）
 * 理论上可行，但对一个<b>第三方模组类</b>做 Mixin 是紧耦合：Carpet 改类名/字段名就会让
 * 整个模组崩。反射 + 全路径 try/catch 则是软失败——适配不了只是 HUD 数字不准，
 * 绝不影响本体功能。
 */
public final class CarpetHudBridge {

    private static final String HUD_CONTROLLER = "carpet.logging.HUDController";
    /** 解析好的反射句柄；null 表示句柄还没解析过。 */
    private static boolean resolutionAttempted;
    /** Carpet 存在但适配失败时记一次日志，避免刷屏。 */
    private static boolean warned;
    /** Carpet 是否真的在场。 */
    private static boolean carpetPresent;

    private static Field tpsField;
    private static Field msptField;
    private static Method getTickRateManager;
    private static Method updateHud;

    private CarpetHudBridge() {
    }

    /**
     * 同步实测值到 Carpet 的 HUD。
     *
     * <p>应当在「每 20 个游戏刻」调用一次，与 Carpet 自己的显示节奏一致。
     *
     * <p>只传每刻毫秒数即可：Carpet 的显示值是 {@code 1000 / max(它的 mspt, 平均tickTimes)}，
     * 而它的 `tickrate()` 就是 {@code 1000 / mspt}，所以写入 mspt 自然把两者都带对。
     *
     * @param server       服务器实例（Carpet 的 HUD 需要它）
     * @param measuredMspt 实测平均每刻毫秒数
     */
    public static void sync(MinecraftServer server, double measuredMspt) {
        if (!resolve()) {
            return;
        }
        try {
            Object manager = getTickRateManager.invoke(server);
            if (manager != null) {
                msptField.setFloat(manager, (float) measuredMspt);
                // 保持 tickrate 与 mspt 自洽（Carpet 的 tickrate() = 1000 / mspt）
                if (measuredMspt > 0.0D) {
                    tpsField.setFloat(manager, (float) (1000.0D / measuredMspt));
                }
            }
            // 触发 Carpet 重发 TPS 消息（BoccHUD 解析的就是这条）
            updateHud.invoke(null, server, null);
        } catch (Throwable t) {
            // 软失败：只记一次，绝不影响主循环
            if (!warned) {
                warned = true;
                System.out.println("[tickcontrol] Carpet HUD sync failed (" + t
                        + "); Carpet's TPS display will show its stale value");
            }
        }
    }

    /** Carpet 是否在场（仅用于诊断输出）。 */
    public static boolean isCarpetPresent() {
        resolve();
        return carpetPresent;
    }

    /**
     * 在模组构造阶段主动探测一次。
     *
     * <p>放在这里而不是等第一次 {@link #sync} 的原因：{@code sync} 每 20 个游戏刻才跑一次，
     * 只有进入世界后才可能触发，导致诊断信息要等很久才出现——出问题时会误以为
     * 「适配代码没跑」。探测本身只依赖类加载器，不需要服务器实例，因此可以提前。
     */
    public static void probeAtStartup() {
        resolve();
    }

    private static synchronized boolean resolve() {
        if (resolutionAttempted) {
            return carpetPresent;
        }
        resolutionAttempted = true;

        // Carpet 在本模组的类加载器里通常不可见（Forge 给每个模组独立的
        // TransformingClassLoader），所以 Class.forName 一律要带上候选加载器。
        ClassLoader own = CarpetHudBridge.class.getClassLoader();
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        ClassLoader mc = MinecraftServer.class.getClassLoader();
        ClassLoader[] candidates = {own, ctx, mc};

        System.out.println("[tickcontrol] Carpet probe: own=" + own + " ctx=" + ctx + " mc=" + mc);

        Throwable lastFailure = null;
        ClassLoader chosen = null;
        for (ClassLoader loader : candidates) {
            if (loader == null) {
                continue;
            }
            try {
                Class<?> hud = Class.forName(HUD_CONTROLLER, false, loader);

                // Carpet 把 manager 暴露在 carpet.fakes.MinecraftServerInterface 上，
                // 运行时的 MinecraftServer 实例由 Carpet 的 mixin 实现该接口。
                Class<?> serverInterface =
                        Class.forName("carpet.fakes.MinecraftServerInterface", false, loader);
                getTickRateManager = serverInterface.getMethod("getTickRateManager");

                Class<?> managerClass =
                        Class.forName("carpet.helpers.ServerTickRateManager", false, loader);
                // tickrate / mspt 声明在父类 carpet.helpers.TickRateManager 上，
                // 且是 protected——Class.getField() 只返回 public 字段，所以必须
                // 沿父类链用 getDeclaredField() 找，再 setAccessible。
                tpsField = declaredField(managerClass, "tickrate");
                msptField = declaredField(managerClass, "mspt");

                updateHud = hud.getMethod("update_hud", MinecraftServer.class, java.util.List.class);

                carpetPresent = true;
                chosen = loader;
                break;
            } catch (Throwable t) {
                lastFailure = t;
                System.out.println("[tickcontrol] Carpet probe: loader " + loader
                        + " failed: " + t);
            }
        }

        if (carpetPresent) {
            System.out.println("[tickcontrol] Carpet detected via " + chosen
                    + "; TPS display will be synced with the measured tick rate");
        } else if (classVisibleAnywhere("carpet.CarpetServer")) {
            System.out.println("[tickcontrol] Carpet is present but HUD sync could not be set up: "
                    + (lastFailure == null ? "no usable class loader" : lastFailure.toString()));
        } else {
            System.out.println("[tickcontrol] Carpet not detected; HUD sync disabled");
        }
        return carpetPresent;
    }

    /**
     * 沿父类链查找字段（含非 public）。
     *
     * <p>踩过的坑：{@code Class.getField("tickrate")} 抛
     * {@code NoSuchFieldException}，因为它只返回 <b>public</b> 字段，
     * 而 Carpet 的 {@code tickrate} / {@code mspt} 是声明在父类
     * {@code carpet.helpers.TickRateManager} 上的 {@code protected} 字段。
     */
    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(name + " (searched " + type.getName() + " and its superclasses)");
    }

    /** Carpet 的类能否被任一候选加载器看到（用于区分「不在场」与「适配失败」）。 */
    private static boolean classVisibleAnywhere(String name) {
        ClassLoader[] candidates = {
                CarpetHudBridge.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                MinecraftServer.class.getClassLoader(),
        };
        for (ClassLoader loader : candidates) {
            if (loader == null) {
                continue;
            }
            try {
                Class.forName(name, false, loader);
                return true;
            } catch (Throwable ignored) {
                // 继续尝试下一个
            }
        }
        return false;
    }
}
