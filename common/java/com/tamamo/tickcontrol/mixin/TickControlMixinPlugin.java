
package com.tamamo.tickcontrol.mixin;

import java.io.ByteArrayOutputStream;
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
 * Mixin 注解里的方法名是编译期常量，无法在运行时改成生产环境需要的 SRG 名。
 * 1.12.2 的两个名字域确实不同：
 *
 * <pre>
 * MinecraftServer.tick                      MCP: tick                SRG: func_71217_p
 * MinecraftServer.updateTimeLightAndEntities MCP: (原名)              SRG: func_71190_q
 * WorldServer.tick()                        MCP: tick                SRG: func_72835_b
 * MinecraftServer.run                       MCP: run                 SRG: run（来自 Runnable）
 * </pre>
 *
 * 因此两个变体分别写官方名与 SRG 名（都带 {@code remap = false}，都不需要 refmap），
 * 由本插件按环境选择其一。
 *
 * <h2>判据为什么是「扫目标类字节码」而不是别的</h2>
 *
 * 踩过的两个坑（1.16.5 上实测）：
 * <ol>
 *   <li>{@code Class.forName("net.minecraft.server.MinecraftServer")} 会让目标类
 *       <b>提前加载</b>，Mixin 直接拒绝注入
 *       （{@code MixinTargetAlreadyLoadedException: target ... was loaded too early.}）；</li>
 *   <li>{@code MixinEnvironment.getObfuscationContext()} 在生产环境的 {@code onLoad}
 *       阶段可能返回 {@code null}，依赖它会选错变体。</li>
 * </ol>
 *
 * 所以主判据是<b>读目标类的字节码</b>
 * （{@code MixinService.getClassBytes} 之外用资源流 + 常量池字符串扫描）：
 * 不加载类、不受上下文初始化时机影响。1.12.2 的 SRG 前缀是 {@code field_}/{@code func_}
 * （不是 1.16.5 的 {@code f_}/{@code m_}），所以探针串必须换成 1.12.2 的。
 *
 * <p>三个探针的组合已在真实 jar 上验证可区分：SRG 探针
 * （{@code field_71317_u} / {@code field_71305_c} / {@code func_71217_p}）
 * 只出现在 SRG jar 里，MCP 探针（{@code serverRunning} / {@code tickTimeArray}）
 * 只出现在 mapped（MCP）jar 里——已用脚本读两个 jar 里的
 * {@code MinecraftServer.class} 原始字节逐个验证过（见下面两个常量表的注释）。
 *
 * <p>另外提供 {@code -Dtickcontrol.naming=dev|srg} 显式覆盖：名字域判断一旦出错，
 * Mixin 的 {@code require = 1} 会直接抛错（这是刻意的——宁可响亮地失败，
 * 也不要 {@code require = 0} 那种「模组加载了但完全没生效」的静默失败），
 * 出问题时用户可以用这个开关在不重新构建的前提下确定性地指定变体。
 */
public final class TickControlMixinPlugin implements IMixinConfigPlugin {

    private static final String DEV_VARIANT = "com.tamamo.tickcontrol.mixin.MinecraftServerMixinDev";
    private static final String SRG_VARIANT = "com.tamamo.tickcontrol.mixin.MinecraftServerMixinSrg";

    /**
     * 客户端环境粒子的两个变体。
     *
     * <p>它们与主循环变体<b>必须一起选择</b>:名字域是全局事实,不存在
     * "服务端用 SRG 而客户端用 MCP"的情况。漏掉这里会让两个客户端变体同时生效,
     * 其中一个必然因为名字对不上而让 {@code require = 1} 抛错 —— 这正是
     * 本项目反复吃过的那类"只改了一半"的故障。
     */
    private static final String CLIENT_DEV_VARIANT =
            "com.tamamo.tickcontrol.mixin.WorldClientMixinDev";
    private static final String CLIENT_SRG_VARIANT =
            "com.tamamo.tickcontrol.mixin.WorldClientMixinSrg";

    private static final String TARGET = "net.minecraft.server.MinecraftServer";

    /** 显式覆盖用的系统属性。 */
    public static final String NAMING_PROPERTY = "tickcontrol.naming";

    /**
     * 生产域（SRG）探针：{@code MinecraftServer} 里出现任意一个即判定为生产环境。
     * 三者都来自 1.12.2 的 srg jar（javap 核验）：{@code serverRunning} /
     * {@code worlds} / {@code tick} 的 SRG 名。
     */
    private static final String[] SRG_PROBES = {
            "field_71317_u", "field_71305_c", "func_71217_p",
    };

    /**
     * 开发域（MCP stable_39）探针。
     *
     * <p>刻意不用 {@code run}／{@code tick} 这类两边同名的成员做探针，
     * 也<b>不能</b>用 {@code updateTimeLightAndEntities}：实测 SRG 版的
     * {@code MinecraftServer.class} 里含有合成 lambda 方法名
     * {@code lambda$updateTimeLightAndEntities$0}（Forge 生成 srg jar 时 lambda 名
     * 沿用了 MCP 名），所以那个串在两个域里都存在。
     * {@code serverRunning} 与 {@code tickTimeArray} 已实测只出现在 MCP 版里。
     */
    private static final String[] MCP_PROBES = {
            "serverRunning", "tickTimeArray",
    };

    private static Boolean srgEnvironment;
    private static boolean overrideLogged;

    @Override
    public void onLoad(String mixinPackage) {
        System.out.println("[tickcontrol] mixin plugin loaded; naming domain = "
                + (isSrgEnvironment() ? "SRG (production)" : "MCP (development)"));
    }

    @Override
    public String getRefMapperConfig() {
        // 两个变体都是 remap = false，不需要 refmap
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean srg = isSrgEnvironment();
        // 服务端主循环变体
        if (DEV_VARIANT.equals(mixinClassName)) {
            return !srg;
        }
        if (SRG_VARIANT.equals(mixinClassName)) {
            return srg;
        }
        // 客户端粒子变体(判据相同:名字域是全局事实)
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
        // 两个变体都写在 tickcontrol.mixins.json 的 mixins 里，由 shouldApplyMixin 选择
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

        // 0) 显式覆盖优先——这是唯一完全确定的判据
        String override = System.getProperty(NAMING_PROPERTY);
        if (override != null && !override.isEmpty()) {
            boolean srg = !"dev".equalsIgnoreCase(override) && !"mcp".equalsIgnoreCase(override);
            if (!overrideLogged) {
                overrideLogged = true;
                System.out.println("[tickcontrol] naming domain forced by -D" + NAMING_PROPERTY
                        + "=" + override + " -> " + (srg ? "SRG" : "MCP"));
            }
            srgEnvironment = srg;
            return srg;
        }

        // 1) 主判据：扫目标类字节码常量池里的成员名——不加载类，不会 loaded-too-early
        Boolean probed = probeTargetClass();
        if (probed != null) {
            srgEnvironment = probed;
            return probed;
        }

        // 2) 拿不到字节码时退回混淆上下文
        try {
            String ctx = MixinEnvironment.getCurrentEnvironment().getObfuscationContext();
            System.out.println("[tickcontrol] bytecode probe unavailable; obfuscation context = " + ctx);
            if ("searge".equalsIgnoreCase(ctx) || "srg".equalsIgnoreCase(ctx)) {
                srgEnvironment = Boolean.TRUE;
                return true;
            }
            if ("notch".equalsIgnoreCase(ctx) || "mcp".equalsIgnoreCase(ctx)) {
                srgEnvironment = Boolean.FALSE;
                return false;
            }
        } catch (Throwable ignored) {
            // 拿不到就继续走默认值
        }

        // 3) 都判断不出来：默认生产（正式 jar 是主要运行方式）
        System.out.println("[tickcontrol] naming domain could not be detected;"
                + " assuming SRG (production). Use -D" + NAMING_PROPERTY + "=dev to force development.");
        srgEnvironment = Boolean.TRUE;
        return true;
    }

    /**
     * 读目标类字节码并扫描探针串。
     *
     * @return {@code TRUE} 生产域 / {@code FALSE} 开发域 / {@code null} 拿不到字节码
     */
    private static Boolean probeTargetClass() {
        // 注意：资源名必须是斜杠形式（点号形式返回 null）
        String[] candidates = {
                TARGET.replace('.', '/') + ".class",
                TARGET + ".class",
        };
        for (String resource : candidates) {
            try (InputStream in = MixinService.getService().getResourceAsStream(resource)) {
                if (in == null) {
                    System.out.println("[tickcontrol] naming probe: no resource for " + resource);
                    continue;
                }
                byte[] bytes = readFully(in);
                boolean srg = containsAny(bytes, SRG_PROBES);
                boolean mcp = containsAny(bytes, MCP_PROBES);
                System.out.println("[tickcontrol] naming probe: " + resource + " -> "
                        + bytes.length + " bytes; srg=" + srg + " mcp=" + mcp);
                if (srg) {
                    return Boolean.TRUE;
                }
                if (mcp) {
                    return Boolean.FALSE;
                }
            } catch (Throwable t) {
                System.out.println("[tickcontrol] naming probe failed for " + resource + ": " + t);
            }
        }
        return null;
    }

    /** {@code InputStream.readAllBytes()} 是 Java 9 API，本工程目标是 Java 8，手写循环。 */
    private static byte[] readFully(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static boolean containsAny(byte[] haystack, String[] needles) {
        for (String needle : needles) {
            if (containsAscii(haystack, needle)) {
                return true;
            }
        }
        return false;
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
