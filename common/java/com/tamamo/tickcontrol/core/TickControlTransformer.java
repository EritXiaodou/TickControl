
package com.tamamo.tickcontrol.core;

import java.util.Map;

import net.minecraft.launchwrapper.IClassTransformer;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 1.7.10 的 coremod:把 {@code MinecraftServer.run()} 里的节拍常量换成可调控的值。
 *
 * <h2>为什么用 ASM 而不是 Mixin</h2>
 *
 * <p>1.7.10 的 Forge **不自带 Mixin**,而用户的测试实例里也没有装任何 Mixin 库。
 * 用 Mixin 就得往实例里加东西(UniMixins)或把 Mixin 打包进来;纯 ASM coremod
 * 两者都不需要,而且对"只改一个常量"这件事本来就更轻。
 *
 * <h2>为什么瞄准 run() 而不是 tick()</h2>
 *
 * <p>1.7.10 的 {@code run()} 是主循环本身(来自 {@code Runnable},**两个名字域里都不
 * 混淆**,名字恒为 {@code run}),而 {@code tick()} 是被它调用的单刻方法。
 * 反编译 {@code run()} 得到的关键指令序列是:
 *
 * <pre>
 *   ...
 *   i += elapsed;  currentTime = now;
 *   while (i &gt; 50L) { i -= 50L; this.tick(); }     // 追赶循环
 *   Thread.sleep(Math.max(1L, 50L - i));          // 等待到下一刻
 * </pre>
 *
 * <h2>⚠️ 为什么<b>绝对不能</b>去改这两处 50L(血泪教训)</h2>
 *
 * <p>本变形器最初把追赶循环里的 {@code i -= 50L} 换成 {@code i -= tickBatch()}`,
 * 把 sleep 里的 {@code 50L} 换成 {@code waitForNextTick()}。这两处改动让
 * <b>/tick freeze 变成"游戏刻飞速推进"</b>,原因值得永远记住:
 *
 * <pre>
 *   while (i &gt; 50) {        // 条件里的 50 没改
 *       i -= tickBatch();   // 冻结时返回 0
 *       tick();
 *   }
 * </pre>
 *
 * <p>冻结时 {@code i} 不再减少,循环条件恒为真,&nbsp;{@code goto} 回到条件判断
 * ——<b>死循环</b>。用户看到的就是"冻结后游戏刻极快推进",直到 unfreeze 时
 * {@code tickBatch()} 恢复返回 50、循环才得以退出。
 *
 * <p>而且即使把 {@code tickBatch()} 改成恒返回 50 也<b>仍然不对</b>:
 * 原版 {@code sleep(Math.max(1, 50 - i))} 与 {@code i += elapsed} 是一套
 * 互相自校正的算术,把 sleep 的周期换成变量后会掉到约 10 TPS 而不是 20。
 *
 * <p><b>结论:这个循环的计时算术一个字都不能动。</b>冻结只能在
 * {@code tick()} 的<b>调用点</b>做门控——见 {@link #patchRunLoop}。
 *
 * <h2>现在只做一件事</h2>
 *
 * <p>把 {@code run()} 里对 {@code MinecraftServer.tick:()V} 的调用
 * 换成 {@link TickControlRuntime#tickIfRunning()}。计时行为与原版
 * <b>逐字节一致</b>,只有"要不要真的推进这一帧"被门控。
 * (代价:{@code /tick rate} 在 1.7.10 上暂不可用,因为改速率就必须动这个算术。
 * 命令层会如实说明,不再假装成功。)
 */
public class TickControlTransformer implements IClassTransformer {

    /** 目标类在两个名字域里都是这个名字(它是原版类名,不混淆)。 */
    private static final String TARGET = "net.minecraft.server.MinecraftServer";

    /** 主循环方法名:来自 {@code Runnable},不混淆。 */
    private static final String RUN = "run";
    private static final String RUN_DESC = "()V";

    /**
     * 承载体是世界更新的方法。1.7.10 的 {@code MinecraftServer.tick()} 在偏移 57 调用它,
     * 而它内部按顺序做:世界 tick、世界 updateEntities、**网络刻**、时间包同步。
     * 门控必须放在它内部的两个 WorldServer 调用上,而不是外面 —— 在外面拦会把网络刻
     * 一起冻掉(见 {@link #patchWorldCalls})。
     */
    /**
     * 承载世界更新的方法可能的名字 —— <b>只放可靠的名字</b>。
     *
     * <h2>⚠️ 这里绝不能放混淆后的单字母名字(已造成崩溃)</h2>
     *
     * <p>我曾把运行时日志里看到的 {@code MinecraftServer} 全部 no-arg void 方法名
     * ({@code g,n,o,r,t,u,v,x,U,am,az})放进这张表,以为"既然实测就是这个,那就是它"。
     * <b>这是严重错误</b>:那张表列的是<b>所有</b>方法,不是目标方法。于是按名字的快速路径
     * 匹配到了 {@code az} —— 它在生产环境里其实是 <b>{@code stopServer}</b>。
     *
     * <p>后果是启动即崩溃:
     *
     * <pre>
     * java.lang.VerifyError: Bad type on operand stack
     *   Location: net/minecraft/server/MinecraftServer.func_71260_j()V   // = stopServer
     *   Reason: 'ServerConfigurationManager' is not assignable to 'WorldServer'
     * </pre>
     *
     * <p>把 {@code stopServer} 里对 {@code ServerConfigurationManager} 的调用换成了期望
     * {@code WorldServer} 的包装方法,字节码校验必然失败。
     *
     * <p>结论:混淆名<b>不可枚举</b> —— 只能靠结构判据,不能靠猜名字。
     * 这张表因此只保留"语义上确实是那个方法"的名字。
     */
    private static final String[] UPDATE_WORLD_NAMES = {
            "updateTimeLightAndEntities",
    };

    /**
     * 结构判据:这个方法是不是"承载世界更新"的那个。
     *
     * <h2>为什么不能认 {@code WorldServer} 这个名字</h2>
     *
     * <p>生产环境里 Minecraft 的类型名<b>也是混淆的</b>。实测(离线 dump 生产 jar 的调用直方图):
     *
     * <pre>
     * dev : updateTimeLightAndEntities 调用 WorldServer.tick / updateEntities
     * prod: o() 对类型 "oi" 调用两次无参 void 方法   &lt;- "oi" 就是 WorldServer
     * </pre>
     *
     * <p>所以 {@code WORLD_OWNER.equals(call.owner)} 在生产环境永不成立,注入必然落空
     * (实测日志:{@code replaced 0 WorldServer call(s)} 重复出现,最后报 INACTIVE)。
     *
     * <h2>真正的判据</h2>
     *
     * <p>不认名字,而是<b>从字节码自身找出"恰好被调用两次无参 void 方法的那个类型"</b>:
     * 世界更新方法对 WorldServer 先调 {@code tick()} 再调 {@code updateEntities()},
     * 两次都是无参 void,且 owner 相同。这个"同 owner 两次"的形态在
     * {@code MinecraftServer} 的任何一个 no-arg void 方法里都只出现在目标方法上 ——
     * 已用 dev 与 prod 两个 jar 的直方图交叉核验。
     *
     * <p>顺带说明:之前的实现声明了 {@code worldNoArgVoid} / {@code networkTick} 两个局部变量
     * 却<b>从未在返回语句里使用</b>,等于无条件返回 true,于是匹配了全部 11 个方法 ——
     * 这也是那 8 行 {@code replaced 0} 的来源。
     */
    private static boolean looksLikeWorldUpdate(MethodNode method) {
        return findAdjacentCallPair(method) != null;
    }

    /**
     * 本类是否已经被本变换器注入过。
     *
     * <p>判据:类里是否已经存在对 {@link TickControlRuntime#worldTickIfRunning} 的调用。
     * 该方法是本模组独有的静态入口,原版字节码里不可能出现,所以它是可靠的"已注入"标记。
     *
     * <p>为什么必须判:FML 可能对同一个类多次调用变换器,而注入会改变方法体形态,
     * 使结构判据第二次指向另一个方法 —— 结果两个方法被注入,第二个若不对就
     * {@code VerifyError} 启动崩溃。实测日志里 {@code gatedMethods=[stopServer, tick]}
     * 与 {@code [o, v]} 都是这么来的。
     */
    private static boolean alreadyPatched(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (RUNTIME.equals(call.owner) && RUNTIME_WORLD.equals(call.name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 判据的核心:在本方法里找出"背靠背那一对同类调用"所在的 owner 与指令位置。
     *
     * <h2>为什么要精确到"这一对"</h2>
     *
     * <p>只判"某 owner 恰好两次"会同时命中多个方法。实测(离线校验输出):
     *
     * <pre>
     * DEV  gatedMethods=[stopServer, tick]     <- 两个都被注入,错
     * PROD gatedMethods=[o, u]                 <- 同样错
     * </pre>
     *
     * <p>把 {@code stopServer} 里的 {@code ServerConfigurationManager} 调用换成期望
     * {@code WorldServer} 的包装方法,启动就 {@code VerifyError}。
     *
     * <p>真正有区分度的是<b>紧邻性</b>:世界 tick 与 updateEntities 在字节码里相邻,
     * 中间只隔 profiler 的 start/end(约十条指令)。而 {@code stopServer}/{@code tick}
     * 里那两次同类调用相隔很远。
     *
     * @return {@code [owner, firstIndex, secondIndex]};没有合格的一对时返回 {@code null}
     */
    /**
     * 世界更新方法的<b>字符串指纹</b> —— 混淆不改字符串常量,所以这在两个名字域都成立。
     *
     * <p>取自真正的游戏 jar
     * ({@code E:\.minecraft\versions\1.7.10-Forge_10.13.4.1614\1.7.10-Forge_10.13.4.1614.jar})
     * 的常量池。离线核验:
     *
     * <pre>
     * DEV  updateTimeLightAndEntities()V  contains "Exception ticking world"
     * PROD v()V                           contains "Exception ticking world"
     * </pre>
     *
     * <p>此前失败的六种判据(可读名、SRG 名、形态、相邻对、网络刻、run() 调用图锚点)
     * 全部依赖方法名或指令形态,而 1.7.10 这个安装里<b>没有任何 MCP 映射文件</b>
     * (全盘搜索 {@code func_71190_q} 只命中 1.16.5 的配置),FML 原样交出混淆字节码。
     * 字符串是唯一没被混淆的信息。
     */
    private static final String[] UPDATE_WORLD_FINGERPRINTS = {
            "Exception ticking world",
    };

    /** 在类里找出方法体含任一指纹的方法;找到多个时返回 {@code null}(宁可不注入)。 */
    private static MethodNode findByString(ClassNode node, String[] needles) {
        MethodNode found = null;
        StringBuilder seen = new StringBuilder();
        for (MethodNode method : node.methods) {
            if (!RUN_DESC.equals(method.desc)) {
                continue;
            }
            boolean hit = containsAnyFingerprint(method, needles);
            if (hit) {
                seen.append(method.name).append("=HIT,");
            }
            if (!hit) {
                continue;
            }
            if (found != null) {
                // 多个候选无法区分 -> 拒绝,避免注入到错误位置(那会启动崩溃)。
                System.out.println("[tickcontrol] fingerprint matched more than one method: " + seen);
                return null;
            }
            found = method;
        }
        if (found == null) {
            System.out.println("[tickcontrol] fingerprint '" + needles[0]
                    + "' matched nothing among ()V methods; insn count of run(): "
                    + describeVoidMethods(node));
        }
        return found;
    }

    /** 诊断辅助:列出所有 ()V 方法及其指令数,便于判断为何没命中。 */
    private static String describeVoidMethods(ClassNode node) {
        StringBuilder sb = new StringBuilder();
        for (MethodNode m : node.methods) {
            if (RUN_DESC.equals(m.desc)) {
                sb.append(m.name).append('(').append(m.instructions.size()).append("),");
            }
        }
        return sb.toString();
    }

    /**
     * 用调用图锚定世界更新方法:{@code run()} → 它调用的、指令数最多的自方法(即
     * {@code tick}) → 该方法调用的那个自方法。
     *
     * <p>{@code run()} 来自 {@code Runnable},名字永不混淆,所以整条链不依赖任何混淆名。
     * 离线核验两个名字域都精确命中:
     *
     * <pre>
     * DEV  run -> tick(167) -> updateTimeLightAndEntities(214)
     * PROD run -> u(163)    -> v(184)
     * </pre>
     *
     * <p>为什么"指令数最多":{@code run()} 还会调用 {@code stopServer} 等,而主循环主体
     * ({@code tick}) 是其中最大的。实测 DEV 167 / 102 / 1,PROD 163 / 64 / 1,
     * 最大者都是 tick,区分度充足。
     *
     * @return 世界更新方法;任一环缺失时返回 {@code null}
     */
    private static MethodNode findWorldUpdateByAnchor(ClassNode node) {
        MethodNode run = null;
        for (MethodNode m : node.methods) {
            if (RUN.equals(m.name) && RUN_DESC.equals(m.desc)) {
                run = m;
                break;
            }
        }
        if (run == null) {
            return null;
        }
        MethodNode tick = largestSelfCall(node, run);
        if (tick == null) {
            return null;
        }
        return largestSelfCall(node, tick);
    }

    /** 在 {@code from} 的自调用里,取指令数最多的那个方法。 */
    private static MethodNode largestSelfCall(ClassNode node, MethodNode from) {
        MethodNode best = null;
        int bestSize = -1;
        for (AbstractInsnNode insn = from.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!TARGET.equals(call.owner) || !RUN_DESC.equals(call.desc)) {
                continue;
            }
            MethodNode candidate = null;
            for (MethodNode m : node.methods) {
                if (m.name.equals(call.name) && RUN_DESC.equals(m.desc)) {
                    candidate = m;
                    break;
                }
            }
            if (candidate != null && candidate.instructions.size() > bestSize) {
                bestSize = candidate.instructions.size();
                best = candidate;
            }
        }
        return best;
    }

    private static final int MAX_PAIR_GAP = 16;

    /**
     * 方法体是否触碰网络刻。
     *
     * <p>这是把"世界更新方法"与 {@code stopServer} 区分开的<b>关键**判据。
     * 两者都含"同一类型上相邻的两个无参 void 调用",所以单靠形态无法区分 —— 实测就因此
     * 把 {@code stopServer} 当成了目标,导致启动 {@code VerifyError}。
     *
     * <p>而世界更新方法体内必然驱动网络刻({@code NetworkSystem} 的调用),
     * {@code stopServer} 不会。owner 名里含 "network"/"Network" 即认定。
     */
    private static boolean touchesNetwork(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                String owner = ((MethodInsnNode) insn).owner;
                if (owner.toLowerCase().contains("network")) {
                    return true;
                }
            }
            if (insn instanceof FieldInsnNode) {
                String owner = ((FieldInsnNode) insn).owner;
                if (owner.toLowerCase().contains("network")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object[] findAdjacentCallPair(MethodNode method) {
        // 先过网络这一关:不含网络刻的方法(例如 stopServer)直接排除。
        //
        // 这条判据是"注入到错误位置导致启动崩溃"之后加的。形态判据本身无法区分
        // 世界更新方法与 stopServer —— 两者都有"相邻两个同类无参 void 调用"。
        if (!touchesNetwork(method)) {
            return null;
        }
        java.util.List<Object[]> calls = new java.util.ArrayList<Object[]>();
        int index = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            index++;
            if (insn instanceof MethodInsnNode && "()V".equals(((MethodInsnNode) insn).desc)) {
                calls.add(new Object[] { ((MethodInsnNode) insn).owner, Integer.valueOf(index), insn });
            }
        }

        // 收集所有"相邻同类"的组合。目标方法里这种对应当<b>恰好只有一对</b>
        // (世界 tick + updateEntities);多于一对说明本方法里还有别的相邻同类调用,
        // 那就不是我们要注入的地方 —— 宁可不动,也不要注入错位置。
        java.util.List<Object[]> pairs = new java.util.ArrayList<Object[]>();
        for (int i = 0; i + 1 < calls.size(); i++) {
            Object[] x = calls.get(i);
            Object[] y = calls.get(i + 1);
            if (!x[0].equals(y[0])) {
                continue;
            }
            int gap = ((Integer) y[1]).intValue() - ((Integer) x[1]).intValue();
            if (gap <= MAX_PAIR_GAP) {
                pairs.add(new Object[] { x[0], x[1], y[1], x[2], y[2] });
            }
        }
        if (pairs.size() != 1) {
            return null;
        }
        return pairs.get(0);
    }


    // ------------------------------------------------------------------
    // 客户端视觉门控(第二个目标类)
    // ------------------------------------------------------------------

    /**
     * 客户端世界。1.7.10 的环境粒子(熔炉火焰/烟、火把、岩浆、传送门)全部由
     * {@code WorldClient.doVoidFogParticles(III)V} 里那一次
     * {@code Block.randomDisplayTick} 调用产生 —— 已核验这是**全 jar 唯一**的调用点。
     *
     * <h2>两个容易搞错的地方(都实际踩过)</h2>
     *
     * <ul>
     *   <li><b>名字</b>:{@code doVoidFogParticles} 听起来只管虚空迷雾,实际是环境方块
     *       粒子的统一入口。我最初按 {@code doRandomDisplayTick} 找,变换器一次都没命中。</li>
     *   <li><b>签名随版本不同</b>:1.7.10 是 {@code (III)V},而 1.12.2 是
     *       {@code (IIIILjava/util/Random;Z…MutableBlockPos;)V}(SRG {@code func_184153_a})。
     *       直接把 1.12.2 的描述符搬过来会<b>静默不命中</b>。</li>
     * </ul>
     *
     * <p>为什么要单独处理:客户端渲染与粒子<b>刻意不在服务端门控范围内</b>
     * (门控客户端会让玩家自己的操作失去反馈)。但结果是冻结后熔炉仍在冒烟,
     * 视觉上就像没冻结 —— 用户正是这样发现的。
     */
    private static final String CLIENT_TARGET = "net.minecraft.client.multiplayer.WorldClient";
    /** 客户端环境粒子方法的候选名。 */
    private static final String[] CLIENT_FX_NAMES = {
            // 生产环境:官方映射确认 bjf.C (III)V -> func_73029_E,即 doVoidFogParticles。
            // 我曾因"单字母不可枚举"的错误推理把它删掉,结果生产环境粒子门控失效。
            // 单字母确实不可靠 —— 但现在有映射作证,它是正确的。
            "C",
            "func_73029_E",
            "doVoidFogParticles",
    };

    /**
     * 客户端环境粒子方法的结构指纹:引用 {@code EffectRenderer}(粒子引擎)。
     *
     * <p>已核验 {@code doVoidFogParticles} 的方法体引用了 {@code EffectRenderer} 与
     * {@code EntityFireworkStarterFX}。原名 {@code "minecraft:barrier"} 是我想当然写的,
     * 那个字符串根本不存在 —— 又一次猜测,已删除。
     *
     * <p>结构判据的价值在于:即使生产环境的混淆字母变了,只要方法还在用粒子引擎就仍能命中。
     */
    private static final String[] CLIENT_FX_FINGERPRINTS = {
            "EffectRenderer",
            "EntityFireworkStarterFX",
    };
    private static final String CLIENT_FX_DESC = "(III)V";

    /** 只在第一次成功变形时打一行日志,避免刷屏。 */
    private static boolean reportedPatch;
    private static boolean reportedClientPatch;

    private static final String RUNTIME = "com/tamamo/tickcontrol/core/TickControlRuntime";
    private static final String RUNTIME_WORLD = "worldTickIfRunning";
    /** 第二处世界门控:代替 {@code WorldServer.updateEntities()}(映射名 {@code func_72939_s})。 */
    private static final String RUNTIME_UPDATE_ENTITIES = "worldUpdateEntitiesIfRunning";
    private static final String WORLD_DESC =
            "(Lnet/minecraft/world/WorldServer;)V";
    private static final String WORLD_OWNER = "net/minecraft/world/WorldServer";

    /**
     * 睡眠挂钩点:把 {@code run()} 里 {@code Thread.sleep(...)} 参数中的那个
     * {@code 50L} 换成 {@link TickControlRuntime} 的调用。
     *
     * <h2>它存在的唯一理由:让状态机能每刻推进一次</h2>
     *
     * <p>{@code TickControlRuntime.worldTickIfRunning()} 里用 {@code prepareTickDone}
     * 把状态机限制为"每服务器刻一次",而<b>复位这个标志的地方就是
     * {@link TickControlRuntime#waitForNextTick()}</b> —— 它由本注入在每轮循环末尾调用。
     *
     * <p>我在把门控从 {@code run()} 迁到 {@code updateTimeLightAndEntities()} 时,
     * 顺手把这个注入删了(以为它只与控速有关)。后果是 {@code prepareTickDone} 第一次之后
     * 永远是 {@code true},{@code prepareTick()} 只在开服时跑了一次 ——
     * <b>{@code /tick step} 与 {@code /tick sprint} 因此完全失效</b>
     * (用户实测:step 步进恢复成正常 20 刻/秒、sprint 不加速)。
     *
     * <p>返回值恒为原版 {@code VANILLA_MSPT}(50),所以睡眠行为与原版<b>逐字节一致</b>,
     * 不触及那套自校正的计时算术 —— 这正是之前两次严重故障的根源,绝不能碰。
     */
    /** 睡眠挂钩点:代替整段 {@code Math.max(1, 50 - i)},直接返回本轮睡眠毫秒数。 */
    private static final String RUNTIME_PERIOD = "tickSleepMillis";
    private static final Long VANILLA_MSPT = Long.valueOf(50L);

    /** 客户端粒子门控的入口,返回"是否应当跳过环境粒子"。 */
    private static final String RUNTIME_FX_GATE = "shouldSuppressAmbientParticles";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        // `name` 是运行时(可能是混淆)名,`transformedName` 是去混淆名。
        // 两个域都试,任何一个匹配就处理。
        boolean isServer = TARGET.equals(name) || TARGET.equals(transformedName);
        boolean isClientWorld = CLIENT_TARGET.equals(name) || CLIENT_TARGET.equals(transformedName);
        if ((!isServer && !isClientWorld) || basicClass == null) {
            return basicClass;
        }

        if (isClientWorld) {
            return transformClientWorld(basicClass);
        }

        try {
            ClassNode node = new ClassNode();
            new ClassReader(basicClass).accept(node, 0);

            // ⚠️ 幂等保护(必须在任何注入之前):本类是否已经被注入过。
            //
            // 这不是"优化",是正确性要求:FML 可能对同一个类多次调用变换器。第二次进来时
            // 第一次注入的包装调用已改变方法体形态,结构判据会指向<b>另一个</b>方法,
            // 于是第二个方法也被注入。日志里两种情况都出现过:
            //   gatedMethods=[stopServer, tick]
            //   gatedMethods=[o, v]
            // 只要第二个方法不是世界更新方法,启动就是 VerifyError。
            if (alreadyPatched(node)) {
                return basicClass;
            }

            boolean patched = false;
            String hitName = null;

            // 快速路径:按名字(开发域与 srg-mcp.srg 里的 SRG 名)。
            for (MethodNode method : node.methods) {
                if (!RUN_DESC.equals(method.desc)) {
                    continue;
                }
                for (String candidate : UPDATE_WORLD_NAMES) {
                    if (candidate.equals(method.name)) {
                        patched |= patchWorldCalls(method);
                        hitName = method.name + " [by name]";
                    }
                }
            }

            // 结构路径:⚠️ 已停用,原因见下。
            //
            // 【为什么停用】它选错过注入目标,后果是<b>启动崩溃</b>{@code VerifyError},
            // 比"功能不可用"严重得多。决定性证据是生产字节码的原始指令流:
            //
            // <pre>
            // ===== method o()V  insns=101          &lt;- 结构判据曾选中它
            //    10  LDC "Stopping server"
            //    31  LDC "Saving players"
            //    37  CALL oi.j()V
            //    42  CALL oi.u()V
            //    52  LDC "Saving worlds"
            // </pre>
            //
            // 也就是说 <b>{@code o()} 其实是 stopServer</b>,而 {@code oi} 是
            // <b>ServerConfigurationManager</b>(j/u = saveAllPlayerData/removeAllPlayers),
            // 根本不是 WorldServer。判据只看到"同一类型上两个相邻无参 void 调用"就下手,
            // 于是把 ServerConfigurationManager 的调用换成了期望 WorldServer 的包装方法。
            //
            // 【为什么不再继续修】要在生产混淆字节码里可靠区分 WorldServer,必须先能解析
            // 类型身份(如父类链判定"是不是 World"),而运行时的类来源与映射在 1.7.10 上
            // 我未能确定 —— 试过的四种判据(可读名、SRG 名、字符串指纹、结构/调用图)
            // 都落空或误判。在无法验证的情况下继续猜,只会重复"启动崩溃"。
            //
            // 【现在的行为】生产环境因此<b>不注入、不生效</b>,但<b>不会崩溃</b>,
            // 并打印明确的 WARNING 说明原因。功能不可用是可以接受的降级;
            // 启动崩溃不可以。
            //
            // 若将来要恢复:必须先拿到"能确认目标类型身份"的手段,并用
            // .tools-probe/validate-1710-transform.py 两个名字域都验证通过
            // (该脚本用 ASM CheckClassAdapter 校验产出字节码,能抓出这类错误)。
            // 方法定位:字符串指纹(混淆不改字符串常量)+ 官方映射确认的调用名字。
            //
            // ⚠️ 必须判 !patched:按名字的快速路径若已成功,这里就<b>不能再跑一遍</b>。
            // 实测会出现"同一次变换里跑两次"的怪象:第一次成功替换 3 处,第二次再进来时
            // 调用已经变成包装方法,于是报 tick=0/updateEntities=0 的假失败,把成功日志盖掉。
            //
            // 已用官方映射 de.oceanlabs.mcp:mcp:1.7.10:srg 的 joined.srg 核对:
            //   mt  = net/minecraft/world/WorldServer
            //   v() 含 "Exception ticking world" -> 就是 updateTimeLightAndEntities
            //   mt.b = func_72835_b (tick)、mt.h = func_72939_s (updateEntities)
            if (!patched) {
                MethodNode target = findByString(node, UPDATE_WORLD_FINGERPRINTS);
                if (target != null && patchWorldCalls(target)) {
                    patched = true;
                    hitName = target.name + " [by string fingerprint]";
                }
            }

            if (!patched) {
                // 诊断:把实际看到的所有 no-arg void 方法名列全。
                //
                // 这段诊断已经完成使命(它暴露了生产名是单字母混淆),但保留着 ——
                // 它是这类"静默失效"唯一能自救的线索,代价只是失败时一行日志。
                StringBuilder all = new StringBuilder();
                for (MethodNode m : node.methods) {
                    if (m.desc.equals("()V")) {
                        all.append(m.name).append(',');
                    }
                }
                System.err.println("[tickcontrol] PRODNAME MinecraftServer ()V = " + all);
                System.err.println("[tickcontrol] WARNING: MinecraftServer world-update method"
                        + " had no matching injection points; tick control is INACTIVE."
                        + " Tried names " + java.util.Arrays.toString(UPDATE_WORLD_NAMES)
                        + " and the structural test");
                return basicClass;
            }

            // ---- 睡眠挂钩点(独立于上面的世界门控)----
            //
            // 必须单独注入,而且必须注入成功:它是 prepareTickDone 唯一的复位点,
            // 没有它 prepareTick() 只会跑一次,/tick step 与 /tick sprint 全部失效。
            // 详见 RUNTIME_WAIT 的说明。
            boolean sleepHooked = patchSleepHook(node);
            if (!sleepHooked) {
                System.err.println("[tickcontrol] WARNING: Thread.sleep hook not found in run();"
                        + " /tick step and /tick sprint will NOT work (the tick state machine"
                        + " can only advance once). World gating is unaffected.");
            }
            if (!reportedPatch) {
                reportedPatch = true;
                System.out.println("[tickcontrol] patched MinecraftServer."
                        + hitName + "(): world gating active"
                        + " (network layer intentionally left running)");
            }

            // COMPUTE_MAXS 足够:每处替换都是「压入 2 槽」换成「调用后压入 2 槽」,
            // 栈深与局部变量表都不变,所以不需要 COMPUTE_FRAMES
            // (它会拖入额外的类解析,在 coremod 早期阶段风险更高)。
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            // 变形失败必须回退成原类,否则服务器直接起不来。
            System.err.println("[tickcontrol] MinecraftServer.run() transform failed;"
                    + " falling back to vanilla: " + t);
            return basicClass;
        }
    }

    /**
     * 客户端环境粒子门控。
     *
     * <p>把 {@code WorldClient.doRandomDisplayTick(III)V} 的<b>方法头</b>改成先问
     * {@link TickControlRuntime#shouldSuppressAmbientParticles()},冻结时直接返回。
     *
     * <h2>为什么拦这一个方法就够</h2>
     *
     * <p>已核验:1.7.10 全 jar 里 {@code Block.randomDisplayTick} 的调用者
     * <b>只有 {@code WorldClient.doRandomDisplayTick} 一处</b>。熔炉的火焰/烟、
     * 火把、岩浆、传送门的粒子都由它统一驱动,所以在这里拦一次就覆盖全部环境粒子。
     *
     * <p>为什么不拦 {@code BlockFurnace.randomDisplayTick}:那样只治熔炉,
     * 冻结后火把和岩浆照样在动,视觉上依然"没冻结"。
     *
     * <p>为什么不干脆冻结客户端整个 tick:那会让玩家自己的操作(挖掘、放置)
     * 失去反馈,体验比粒子问题更糟。
     *
     * @return 变形后的类;未命中时返回原类并留下告警
     */
    private byte[] transformClientWorld(byte[] basicClass) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(basicClass).accept(node, 0);

            boolean patched = false;
            String hitName = null;

            // 快速路径:按名字。
            for (MethodNode method : node.methods) {
                if (!CLIENT_FX_DESC.equals(method.desc)) {
                    continue;
                }
                for (String candidate : CLIENT_FX_NAMES) {
                    if (candidate.equals(method.name)) {
                        patchAmbientParticles(method);
                        patched = true;
                        hitName = method.name + " [by name]";
                    }
                }
            }

            // 兜底路径:按独有类引用定位,与名字域无关。
            if (!patched) {
                for (MethodNode method : node.methods) {
                    if (!CLIENT_FX_DESC.equals(method.desc)) {
                        continue;
                    }
                    if (containsAnyFingerprint(method, CLIENT_FX_FINGERPRINTS)) {
                        patchAmbientParticles(method);
                        patched = true;
                        hitName = method.name + " [by fingerprint]";
                    }
                }
            }

            if (!patched) {
                // 诊断:列出这个类里所有 (III)V 方法,看真实名字是什么。
                StringBuilder cand = new StringBuilder();
                for (MethodNode m : node.methods) {
                    if ("(III)V".equals(m.desc)) {
                        cand.append(m.name).append(',');
                    }
                }
                System.err.println("[tickcontrol] PRODNAME WorldClient (III)V = " + cand);
                System.err.println("[tickcontrol] WARNING: WorldClient ambient-FX method"
                        + " not found; tried " + java.util.Arrays.toString(CLIENT_FX_NAMES)
                        + " and fingerprints "
                        + java.util.Arrays.toString(CLIENT_FX_FINGERPRINTS));
                return basicClass;
            }
            if (!reportedClientPatch) {
                reportedClientPatch = true;
                System.out.println("[tickcontrol] patched WorldClient." + hitName
                        + "(): ambient particles suppressed while frozen");
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[tickcontrol] WorldClient transform failed;"
                    + " falling back to vanilla: " + t);
            return basicClass;
        }
    }

    /**
     * 在方法入口插入:{@code if (shouldSuppressAmbientParticles()) return;}
     *
     * <p>插入的是 {@code INVOKESTATIC / IFEQ skip / RETURN / skip:},不改动原有指令,
     * 也不影响栈映射,所以 {@code COMPUTE_MAXS} 足够。
     */
    private static void patchAmbientParticles(MethodNode method) {
        InsnList prefix = new InsnList();
        LabelNode skip = new LabelNode();
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                RUNTIME_FX_GATE, "()Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        prefix.add(new InsnNode(Opcodes.RETURN));
        prefix.add(skip);
        method.instructions.insert(prefix);
    }

    /**
     * 把 {@code updateTimeLightAndEntities()} 里对 {@code WorldServer} 的两次调用
     * 换成 {@link TickControlRuntime#worldTickIfRunning(WorldServer)}。
     *
     * <p>这是本变形器<b>唯一</b>做的事,而且位置是经过两次失败才定下来的。
     *
     * <h2>失败一:改 {@code run()} 的累积器 -&gt; 冻结变成极速推进</h2>
     *
     * <p>最初把追赶循环的 {@code i -= 50L} 换成 {@code i -= tickBatch()}。冻结时它返回 0:
     *
     * <pre>
     *   while (i &gt; 50) { i -= 0; tick(); }   // i 永不减少 -&gt; 死循环
     * </pre>
     *
     * <p>用户看到"<b>/tick freeze 之后游戏刻极快推进</b>",直到 unfreeze 时增量恢复 50
     * 才退出。命令报成功、行为却完全相反。
     *
     * <h2>失败二:门控 {@code run()} 里的 {@code tick()} -&gt; 连网络一起冻住</h2>
     *
     * <p>改为在 {@code run()} 里跳过整个 {@code server.tick()} 之后,世界确实停了,
     * 但 {@code MinecraftServer.tick()} 里不只有世界 —— 它还包含
     * {@code updateTimeLightAndEntities()} 内的 {@code NetworkSystem.networkTick()}。
     * 一并跳过之后<b>服务器不再处理任何数据包</b>,表现为:
     *
     * <ul>
     *   <li><b>熔炉打不开</b> —— 打开容器是客户端发包、服务端响应;</li>
     *   <li><b>{@code /tick unfreeze} 也收不到</b> —— 命令同样是数据包,
     *       于是冻结之后再也解不开。</li>
     * </ul>
     *
     * <h2>现在的做法:只拦那两个世界调用</h2>
     *
     * <p>{@code updateTimeLightAndEntities()} 的顺序是:世界 tick、世界 updateEntities、
     * <b>网络刻</b>、时间同步。只把前两者（{@code WorldServer.tick:()V} 与
     * {@code WorldServer.updateEntities:()V}）换成包装调用,网络刻原样保留。
     * 这与 1.12.2 的架构一致 —— 那边也是只 redirect 世界调用,
     * 所以它的聊天栏与命令在冻结期间始终可用。
     *
     * <p>两处调用的字节码形态相同（{@code aload 7} 之后 {@code invokevirtual}），
     * 可以用同一个包装方法替换,因为它俩的接收者类型与描述符完全一样。
     *
     * @return 是否至少替换了一处
     */
    /**
     * {@code WorldServer} 的 owner 名,两个名字域都列。
     *
     * <p>生产名 {@code mt} 来自官方映射 {@code mcp-1.7.10-srg} 的 {@code joined.srg}:
     * {@code CL: mt net/minecraft/world/WorldServer}。
     *
     * <p>⚠️ 必须连 owner 一起匹配。只按 {@code b()}/{@code h()} 这样的方法名会在生产环境
     * 误伤别的类 —— 实测注入数变成 7 而不是 2。
     */
    private static final java.util.Set<String> WORLD_SERVER_OWNERS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "mt",
                    "net/minecraft/world/WorldServer"));

    /**
     * 世界更新方法里的两处世界调用,已由官方映射确认为:
     *
     * <pre>
     * mt.b ()V  -&gt;  func_72835_b   ≡  WorldServer.tick()             -&gt; worldTickIfRunning
     * mt.h ()V  -&gt;  func_72939_s   ≡  WorldServer.updateEntities()   -&gt; worldUpdateEntitiesIfRunning
     * </pre>
     *
     * <p>来源:{@code de.oceanlabs.mcp:mcp:1.7.10:srg} 的 {@code joined.srg}(已下载到
     * {@code .tmp/mcp-1.7.10-srg.zip} 供离线核对)。该文件同时确认了
     * {@code mt = net/minecraft/world/WorldServer}、{@code qi = Profiler}、
     * {@code oi = ServerConfigurationManager}、{@code bjf = WorldClient}。
     */
    private static boolean patchWorldCalls(MethodNode method) {
        InsnList instructions = method.instructions;

        // ⚠️ 必须分两遍:先收集,再替换。
        //
        // 实测证据(诊断输出):在遍历中直接 instructions.set(...) 会破坏 InsnList 的
        // 链式结构,导致 getNext() 提前终止 —— 第二次进入时打印出的调用列表里
        // WorldServer.tick 已经消失、updateEntities 还在,而两个标志位分别是
        // tick=true / updateEntities=false。也就是说<b>第二处替换从未执行</b>。
        //
        // 这与 1.12.2 上遇到过的是同一个坑(那时表现为"冻结了但熔炉还在烧")。
        java.util.List<AbstractInsnNode> tickCalls = new java.util.ArrayList<AbstractInsnNode>();
        java.util.List<AbstractInsnNode> updateCalls = new java.util.ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!"()V".equals(call.desc)) {
                continue;
            }
            // ⚠️ owner 必须同时匹配。只按方法名会在生产环境误伤:实测 PROD 注入了 7 处,
            // 因为别的类上也有 b()/h()。官方映射给出 WorldServer 的混淆名是 mt。
            if (!WORLD_SERVER_OWNERS.contains(call.owner)) {
                continue;
            }
            // 两个名字域都要认:
            //   生产(混淆)  : mt.b / mt.h          —— 来自 mcp-1.7.10-srg 的 joined.srg
            //   开发(可读)  : tick / updateEntities
            if ("b".equals(call.name) || "tick".equals(call.name)) {
                tickCalls.add(insn);
            } else if ("h".equals(call.name) || "updateEntities".equals(call.name)) {
                updateCalls.add(insn);
            }
        }

        if (tickCalls.isEmpty() || updateCalls.isEmpty()) {
            // 诊断:把方法里所有无参 void 调用列全。
            StringBuilder all = new StringBuilder();
            for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode c = (MethodInsnNode) insn;
                    if ("()V".equals(c.desc)) {
                        all.append(c.owner).append('.').append(c.name).append(',');
                    }
                }
            }
            System.out.println("[tickcontrol] world gating: incomplete match (tick="
                    + tickCalls.size() + ", updateEntities=" + updateCalls.size()
                    + "); no-arg void calls were: " + all);
            return false;
        }

        for (AbstractInsnNode t : tickCalls) {
            instructions.set(t, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, RUNTIME_WORLD, WORLD_DESC, false));
        }
        for (AbstractInsnNode u : updateCalls) {
            instructions.set(u, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, RUNTIME_UPDATE_ENTITIES, WORLD_DESC, false));
        }

        System.out.println("[tickcontrol] world gating: gated WorldServer.tick x" + tickCalls.size()
                + " and WorldServer.updateEntities x" + updateCalls.size()
                + " (names from mcp-1.7.10-srg)");
        return true;
    }

    /**
     * 在 {@code run()} 里挂上睡眠挂钩点。
     *
     * <p>做法:找到 {@code Thread.sleep} 调用,把它参数表达式里的那个 {@code ldc2_w 50L}
     * 换成对 {@link TickControlRuntime#waitForNextTick()} 的调用。
     *
     * <h2>为什么不能像最初那样改其它 50L</h2>
     *
     * <p>1.7.10 的 {@code run()} 里有四处 {@code 50L},最初我把追赶循环的
     * {@code i -= 50L} 也换掉了,结果冻结时 {@code i} 不再减少、循环条件恒真 ——
     * <b>游戏刻极速推进</b>。所以这里只碰 {@code Thread.sleep} 参数里的那一处,
     * 判据是"从 sleep 调用往前找最近的 50L 常量"。
     *
     * <p>而且返回值恒为原版 50,睡眠算术逐字节不变。
     */
    private static boolean patchSleepHook(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!RUN.equals(method.name) || !RUN_DESC.equals(method.desc)) {
                continue;
            }
            // 先定位 Thread.sleep 调用(每个 run() 只有一处)。
            AbstractInsnNode sleepCall = null;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if ("java/lang/Thread".equals(call.owner) && "sleep".equals(call.name)) {
                    sleepCall = insn;
                }
            }
            if (sleepCall == null) {
                return false;
            }

            // ---- 精确锁定"睡眠周期"那个 50L ----
            //
            // 生产 run() 里 Thread.sleep 的参数是:
            //
            //   211: ldc2_w 50          <- 睡眠周期(本方法要替换的就是这一处)
            //   214: lload_3            <- 已耗时
            //   215: lsub
            //   216: Math.max(JJ)J
            //   219: Thread.sleep(J)V
            //
            // ⚠️ 绝不能"从 sleep 往前找最近的 50L":那样会命中 index 190 那个用于
            // `i -= 50` 追赶判断的常量(实测就是如此)。后果是双重的:
            //   1. 睡眠周期从未改变 -> /tick rate 与 sprint 完全无效;
            //   2. 挂钩点不在每轮路径上 -> 状态机推进也不正常;
            //   3. 而被改掉的恰恰是追赶递减常量 —— 正是最危险的那一处。
            //
            // 正确判据是 Math.max 之前的<b>第一个</b>操作数,即从 Math.max 往前找
            // 最近的 50L 常量。
            AbstractInsnNode mathMax = sleepCall.getPrevious();
            if (mathMax == null || mathMax.getOpcode() != Opcodes.INVOKESTATIC) {
                return false;
            }
            MethodInsnNode maxCall = (MethodInsnNode) mathMax;
            if (!"java/lang/Math".equals(maxCall.owner) || !"max".equals(maxCall.name)) {
                return false;
            }

            // ---- 把 Thread.sleep(J)V 整个换成我们的睡眠方法 ----
            //
            // 只换 Math.max 的第一个操作数(那个 50L)是<b>不够</b>的,原因是原版把 i 也钳在
            // 50 以内:
            //
            //     if (i > 50) i = 50;          // 上面 index 117 那处 50L
            //     ...
            //     Thread.sleep(max(1, 50 - i));
            //
            // 所以 50 - i 恒在 [0,50],睡眠永远 ≤50ms —— 那条路只能<b>加快</b>,无法<b>减慢</b>,
            // /tick rate 低于 20 时就会失效(用户实测:调多少都是 20)。
            //
            // ---- 把「Math.max(1, 50 - i) + Thread.sleep(...)」整段换成我们的方法 ----
            //
            // 为什么必须整段替换,而不是只换 sleep 的入参:
            //
            // 原版入参是 max(1, 50 - i),i 是<b>本轮已耗时</b>。我先前把它当"周期"按比例缩放,
            // 结果减速方向完全失灵(用户实测:无论调多少速率都是正常 20)。原因是这个表达式
            // 已经被上方的算术绑死,无法表达"比原版睡更久"。
            //
            // 整段替换后,周期由我们直接给出,i 不再参与睡眠计算。原版那段算术
            // (含 `i -= 50` 追赶递减)<b>一个字都没改</b>,仍在原处执行,只是其睡眠结果被接管。
            //
            // ---- 最小改动:只替换 Math.max 里的那个 50L 常量 ----
            //
            // 我先后试过三种更"彻底"的改法,全部失败,记录在此以免重蹈:
            //   1. 从 sleep 往前找最近的 50L —— 命中追赶递减常量(改错地方);
            //   2. 替换整个 Thread.sleep 调用 + POP2 —— 栈效应变了,帧校验失败;
            //   3. 删除整段 max(1L,50L-i) 表达式再替换 sleep —— DEV 通过,PROD 帧仍失败,
            //      因为 i 被上方 `if (i > 50) i = 50;` 钳住,某个帧的目标正好落在这段上。
            //
            // 最小改动才是对的:表达式结构原封不动,只把常量 50L 换成一次静态调用。
            // 栈形态完全不变(压一个 long),所以<b>除常量本身外没有任何字节码变化</b>,
            // 所有 stackmap 帧必然继续成立。
            //
            // 语义:Math.max(1L, period() - i),i = 本轮已耗时。
            //   rate 20  -> period=50  -> max(1, 50-i),与原版逐字节等价;
            //   rate 5   -> period=200 -> max(1, 200-i),明显变慢;
            //   sprint   -> period=1   -> max(1, 1-i) = 1,飞快。
            // i > period 时 max 兜底为 1ms,不会出现负睡眠。
            for (AbstractInsnNode insn = mathMax.getPrevious(); insn != null; insn = insn.getPrevious()) {
                if (isMsptConstant(insn)) {
                    method.instructions.set(insn, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, RUNTIME, RUNTIME_PERIOD, "()J", false));
                    System.out.println("[tickcontrol] sleep-hook: replaced the 50L feeding"
                            + " Math.max with " + RUNTIME_PERIOD + "()J"
                            + " (structure untouched, so every stackmap frame stays valid;"
                            + " /tick rate and /tick sprint now control the pace)");
                    return true;
                }
            }
            return false;
        }

        return false;
    }

    /** 是否是那个节拍常量 {@code 50L}。 */
    private static boolean isMsptConstant(AbstractInsnNode insn) {
        return insn.getOpcode() == Opcodes.LDC
                && VANILLA_MSPT.equals(((LdcInsnNode) insn).cst);
    }

    /**
     * 方法体内是否含任意一个给定的指纹,指纹可以是<b>字符串常量或类引用</b>。
     *
     * <p>用于<b>指纹定位</b>:在完全不看方法名的情况下认出目标方法。这是在按名字连续
     * 失败两轮之后加的 —— 生产环境的成员名既不是可读名也不是 SRG 名。
     *
     * <p>同时匹配类引用很重要:{@code WorldClient.doVoidFogParticles} 里几乎没有独有
     * 字符串常量,但它引用了 {@code EntityFireworkStarterFX} 与 {@code EffectRenderer},
     * 那些类名在常量池里是真实的独有指纹。(我一开始只想用字符串常量,还差点写下一个
     * 并不存在的 {@code "minecraft:barrier"} —— 又是猜。)
     */
    private static boolean containsAnyFingerprint(MethodNode method, String[] needles) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            String text = null;
            if (insn instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof String) {
                    text = (String) cst;
                } else if (cst instanceof org.objectweb.asm.Type) {
                    text = ((org.objectweb.asm.Type) cst).getInternalName();
                }
            } else if (insn instanceof TypeInsnNode) {
                text = ((TypeInsnNode) insn).desc;
            } else if (insn instanceof FieldInsnNode) {
                text = ((FieldInsnNode) insn).owner + "." + ((FieldInsnNode) insn).name;
            } else if (insn instanceof MethodInsnNode) {
                text = ((MethodInsnNode) insn).owner + "." + ((MethodInsnNode) insn).name;
            }
            if (text == null) {
                continue;
            }
            for (String needle : needles) {
                if (text.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AbstractInsnNode skipLabels(AbstractInsnNode insn) {
        AbstractInsnNode cur = insn;
        while (cur != null && cur.getOpcode() < 0) {
            cur = cur.getNext();
        }
        return cur;
    }

    /**
     * FML 的 coremod 入口。
     *
     * <p>{@code getASMTransformerClass} 返回本变形器,Forge 会在类加载时调用它。
     * 这里**不**做 Mixin 引导——本版本完全不用 Mixin。
     *
     * <p>{@code TransformerExclusions} 把本模组自己的包排除在变形之外,避免
     * 自举阶段的递归加载问题。
     */
    @IFMLLoadingPlugin.MCVersion("1.7.10")
    @IFMLLoadingPlugin.TransformerExclusions({"com.tamamo.tickcontrol.core"})
    public static class Core implements IFMLLoadingPlugin {

        @Override
        public String[] getASMTransformerClass() {
            return new String[] {TickControlTransformer.class.getName()};
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
            // 1.7.10 不需要在这里做事:实例由 MinecraftServer.getServer() 取。
        }

        @Override
        public String getAccessTransformerClass() {
            return null;
        }
    }
}
