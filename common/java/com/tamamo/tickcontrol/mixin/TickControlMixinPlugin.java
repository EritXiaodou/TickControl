package com.tamamo.tickcontrol.mixin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * 按当前名字域在两个 Mixin 变体之间二选一。
 *
 * <h2>背景</h2>
 *
 * Mixin 注解里的方法名（{@code method = "runServer"}）是编译期常量，无法在运行时
 * 改成生产环境需要的 SRG 名（{@code m_130011_}）。常规做法由 refmap 完成这一步，
 * 但本工程的 refmap 在生产环境<b>完全没有被应用</b>：
 *
 * <pre>
 * Critical injection failure: @Inject annotation on tickcontrol$runServer could not
 * find any targets matching 'runServer' in net.minecraft.server.MinecraftServer.
 * Using refmap tickcontrol.refmap.json
 * </pre>
 *
 * 已逐项排除：refmap 映射值错误、未打包、config 未加载、{@code data} 缺少上下文键、
 * {@code -Dmixin.env.remapRefMap} 相关开关，以及 manifest / {@code mods.toml}
 * 两种注册方式（详见 README 第 12/13 条）。
 *
 * <h2>做法</h2>
 *
 * 两个变体的注解值分别写官方名与 SRG 名（都带 {@code remap = false}，
 * 因此都不需要 refmap），由本插件按环境选择其一。
 *
 * <h2>环境探测踩过的两个坑</h2>
 *
 * <ol>
 *   <li>{@code Class.forName("net.minecraft.server.MinecraftServer")} 会让目标类
 *       <b>提前加载</b>，Mixin 直接拒绝注入（{@code MixinTargetAlreadyLoadedException:
 *       target ... was loaded too early.}）；</li>
 *   <li>{@code MixinEnvironment.getObfuscationContext()} 在生产环境的 {@code onLoad}
 *       阶段返回 {@code null}，依赖它会在生产环境选错变体。</li>
 * </ol>
 *
 * 因此改为<b>读目标类的字节码</b>（{@code MixinService.getClassBytes} + 常量池字符串扫描）：
 * 不加载类、不受上下文初始化时机影响。判据是 {@code MinecraftServer} 的
 * {@code running} 字段名 {@code f_129764_} 是否出现在常量池中。
 */
public final class TickControlMixinPlugin implements IMixinConfigPlugin {

    private static final String DEV_VARIANT = "com.tamamo.tickcontrol.mixin.MinecraftServerMixinDev";
    private static final String SRG_VARIANT = "com.tamamo.tickcontrol.mixin.MinecraftServerMixinSrg";

    /**
     * 客户端环境粒子的两个变体。
     *
     * <p>它们与主循环变体<b>必须一起选择</b>：名字域是全局事实，不存在
     * "服务端用 SRG 而客户端用 MCP"的情况。漏掉这里会让两个客户端变体同时生效，
     * 其中一个必然因为名字对不上而让 {@code require = 1} 抛错。
     */
    private static final String CLIENT_DEV_VARIANT =
            "com.tamamo.tickcontrol.mixin.ClientLevelMixinDev";
    private static final String CLIENT_SRG_VARIANT =
            "com.tamamo.tickcontrol.mixin.ClientLevelMixinSrg";


    private static final String TARGET = "net.minecraft.server.MinecraftServer";
    /** {@code MinecraftServer.running} 的 SRG 名；出现即生产环境。 */
    private static final String SRG_PROBE = "f_129764_";
    private static final String SRG_PROBE2 = "m_130011_";

    private static Boolean srgEnvironment;

    @Override
    public void onLoad(String mixinPackage) {
        System.out.println("[tickcontrol] mixin plugin loaded; target is "
                + (isSrgEnvironment() ? "SRG-named (production)" : "MCP-named (development)"));
    }

    @Override
    public String getRefMapperConfig() {
        // 两个变体都是 remap = false，不需要 refmap
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean srg = isSrgEnvironment();
        if (DEV_VARIANT.equals(mixinClassName)) {
            return !srg;
        }
        if (SRG_VARIANT.equals(mixinClassName)) {
            return srg;
        }
        // 客户端粒子变体（判据相同：名字域是全局事实）
        if (CLIENT_DEV_VARIANT.equals(mixinClassName)) {
            return !srg;
        }
        if (CLIENT_SRG_VARIANT.equals(mixinClassName)) {
            return srg;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static synchronized boolean isSrgEnvironment() {
        if (srgEnvironment != null) {
            return srgEnvironment;
        }

        // 1) 若能拿到混淆上下文就直接用
        try {
            String ctx = MixinEnvironment.getCurrentEnvironment().getObfuscationContext();
            if ("searge".equalsIgnoreCase(ctx) || "srg".equalsIgnoreCase(ctx)) {
                srgEnvironment = Boolean.TRUE;
                return true;
            }
        } catch (Throwable ignored) {
            // 拿不到就继续用字节码探测
        }

        // 2) 扫目标类字节码常量池里的成员名——不加载类，因此不会触发 loaded-too-early
        boolean srg = false;
        String[] candidates = {
                TARGET + ".class",
                TARGET.replace('.', '/') + ".class",
        };
        for (String res : candidates) {
            try (InputStream in = MixinService.getService().getResourceAsStream(res)) {
                if (in == null) {
                    System.out.println("[tickcontrol] probe: null resource for " + res);
                    continue;
                }
                byte[] bytes = in.readAllBytes();
                boolean dev = containsAscii(bytes, "runServer");
                boolean s = containsAscii(bytes, SRG_PROBE);
                boolean s2 = containsAscii(bytes, SRG_PROBE2);
                System.out.println("[tickcontrol] probe: " + res + " -> " + bytes.length + " bytes; runServer="
                        + dev + " " + SRG_PROBE + "=" + s + " " + SRG_PROBE2 + "=" + s2);
                if (s || s2) {
                    srg = true;
                    break;
                }
                if (dev) {
                    srg = false;
                    break;
                }
            } catch (Throwable t) {
                System.out.println("[tickcontrol] probe failed for " + res + ": " + t);
            }
        }

        srgEnvironment = srg;
        return srg;
    }

    /** 在字节数组里查找 ASCII 串（常量池中的名字就是裸 UTF-8 字节）。 */
    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
