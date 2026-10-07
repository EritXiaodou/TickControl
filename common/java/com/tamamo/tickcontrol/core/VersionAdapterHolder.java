
package com.tamamo.tickcontrol.core;

/**
 * 版本适配器的持有者（每个版本的源码树各有一份）。
 *
 * <p>命令层通过本类取得适配器，从而完全不直接依赖版本差异化的 API。
 *
 * <p>实现是<b>延迟创建</b>的：静态字段持有实现类实例会让本类的类初始化
 * 顺带加载实现类，而实现类的签名里引用了 Minecraft 类型
 * （{@code ICommandSender} / {@code ITextComponent}）。虽然本类只会在
 * 游戏跑起来之后被命令层与主循环用到，但延迟到第一次 {@link #get()} 才 new，
 * 可以把「类加载时机」完全交给调用点，避免任何过早加载的可能。
 */
public final class VersionAdapterHolder {

    private static volatile VersionAdapter instance;

    private VersionAdapterHolder() {
    }

    public static VersionAdapter get() {
        VersionAdapter current = instance;
        if (current == null) {
            current = new LegacyVersionAdapter();
            instance = current;
        }
        return current;
    }

    /** 平台侧可以在更早的时机替换实现（当前没有其它实现，保留给后续版本）。 */
    public static void set(VersionAdapter adapter) {
        instance = adapter;
    }
}
