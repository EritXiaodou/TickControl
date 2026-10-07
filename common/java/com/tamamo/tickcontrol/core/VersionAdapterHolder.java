
package com.tamamo.tickcontrol.core;

/**
 * 版本适配器的持有者（每个版本的源码树各有一份）。
 *
 * <p>命令层通过本类取得适配器，从而完全不直接依赖版本差异化的 API。
 */
public final class VersionAdapterHolder {

    private static final VersionAdapter INSTANCE = new LegacyVersionAdapter();

    private VersionAdapterHolder() {
    }

    public static VersionAdapter get() {
        return INSTANCE;
    }
}