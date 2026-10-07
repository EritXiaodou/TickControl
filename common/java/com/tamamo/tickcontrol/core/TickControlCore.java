
package com.tamamo.tickcontrol.core;

import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

/**
 * 1.12.2 的 Coremod 引导类。
 *
 * <h2>为什么必须有它</h2>
 *
 * 1.12.2 的 Forge <b>不自带</b> Mixin，因此既没有 manifest 的
 * {@code MixinConfigs} 发现机制（那是 1.16.5 的路子），也没有任何东西会
 * 自动读取 {@code tickcontrol.mixins.json}。1.12.2 上正确的接线方式是：
 * <ol>
 *   <li>jar manifest 里声明 {@code FMLCorePlugin} 指向本类，并声明
 *       {@code FMLCorePluginContainsFMLMod: true} 与 {@code ForceLoadAsMod: true}；</li>
 *   <li>manifest 里声明 {@code TweakClass: org.spongepowered.asm.launch.MixinTweaker}
 *       ——FML 的 {@code CoreModManager} 会读 coremod jar 的这个属性并把它
 *       作为级联 tweaker 注入（已对着 1.12.2 Forge 的字节码核验：
 *       {@code CoreModManager} 常量池里有 {@code TweakClass}，并通过
 *       {@code FMLTweaker.injectCascadingTweak} 生效）；</li>
 *   <li>本类的构造器里 {@code MixinBootstrap.init()} + {@code Mixins.addConfiguration(...)}
 *       注册配置。</li>
 * </ol>
 *
 * <p>这两步都是幂等的（{@code MixinBootstrap.init()} 内部有 {@code initialised}
 * 判断，{@code Mixins.addConfiguration} 内部有 {@code registeredConfigs} 集合），
 * 因此与 {@code MixinTweaker} 自己那次初始化不冲突。
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("TickControlCore")
// 刻意<b>不声明</b> {@code TransformerExclusions}。
//
// 曾经这里是 {"com.tamamo.tickcontrol"}(整个模组包),启动日志里出现了:
//
//   [mixin]: Classloader restrictions [PACKAGE_TRANSFORMER_EXCLUSION] encountered
//            loading tickcontrol.mixins.json:MinecraftServerMixinDev
//   [mixin]: Classloader restrictions [PACKAGE_TRANSFORMER_EXCLUSION] encountered
//            loading tickcontrol.mixins.json:MinecraftServerMixinSrg
//
// 后果是 Mixin 拿不到自己的注入类,世界门控那半边静默失效(/tick freeze 无效,
// 而 /tick rate 与 /tick sprint 因为走 ServerLoop 主循环而不受影响)。
//
// 为什么不改成只排除 ".core":本类的构造器里要调用 MixinBootstrap.init(),
// 而 Forge 很可能是在本类<b>构造之后</b>才读取这个注解并注册排除规则的。
// 也就是说规则加得太晚,排除到谁、排除不到谁都不确定——收窄子包同样有风险。
// 干脆完全不排除:本模组没有自己的 IClassTransformer,不存在"自己的类被自己
// 转换"的递归问题;而 Mixin 对其自身包本来就有独立的排除机制,不需要我们代劳。
//
// 对照:纯 ASM 的 1.7.10 只排除 ".core",那是因为它确实有自己的 ClassTransformer
// 需要防自举递归——1.12.2 的情况不同,不能照抄。
public final class TickControlCore implements IFMLLoadingPlugin {

    /** Mixin 配置文件名（打包在 jar 根目录）。 */
    public static final String MIXIN_CONFIG = "tickcontrol.mixins.json";

    public TickControlCore() {
        MixinBootstrap.init();
        Mixins.addConfiguration(MIXIN_CONFIG);
    }

    /** 本模组不做字节码转换器，注入全部走 Mixin。 */
    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // 没有需要从 coremod 阶段传给后续阶段的数据
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
