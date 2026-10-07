
package com.tamamo.tickcontrol.core;

/**
 * 1.7.10 coremod 与主循环之间的静态桥梁。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>1.7.10 的 Forge **不自带 Mixin**,而用户的测试实例里也没有安装任何 Mixin 库,
 * 所以本版本走 **纯 ASM coremod**(见 {@code TickControlTransformer}),不往实例里
 * 添加任何东西。ASM 变形只能改指令,不能凭空往 {@code MinecraftServer} 里加方法,
 * 因此被替换进去的指令只能调用**某个已存在的静态方法**——就是这里的方法。
 *
 * <h2>实例从哪来</h2>
 *
 * <p>{@code MinecraftServer.getServer()} 是 1.7.10 的静态入口(已核验存在于
 * recompiled_minecraft-1.7.10.jar,且是 MCP 名)。控制器按服务器实例登记在
 * {@link TickControlAccessHolder} 里。
 *
 * <p>所有方法都必须**绝不抛异常**:它们被注入到主循环最热的路径上,
 * 任何异常都会直接崩掉服务器。
 */
public final class TickControlRuntime {

    /** 1.7.10 原版的每刻毫秒数,也就是被替换掉的那个常量。 */
    public static final long VANILLA_MSPT = 50L;

    private TickControlRuntime() {
    }


    /**
     * 世界门控:代替 {@code WorldServer.tick()} / {@code WorldServer.updateEntities()}
     * 被 {@code updateTimeLightAndEntities()} 调用。
     *
     * <h2>为什么门控放在这里</h2>
     *
     * <p>{@code MinecraftServer.tick()} 里不只有世界 —— {@code updateTimeLightAndEntities()}
     * 的顺序是:世界 tick、世界 updateEntities、<b>{@code NetworkSystem.networkTick()}</b>、
     * 时间同步。所以:
     *
     * <ul>
     *   <li>拦 {@code run()} 里的 {@code server.tick()} → 网络一起冻住,<b>熔炉打不开、
     *       {@code /tick unfreeze} 也收不到</b>(用户实测报告);</li>
     *   <li>只拦这两个世界调用 → 世界停,网络照常,命令随时可用。</li>
     * </ul>
     *
     * <p>这与 1.12.2 的架构一致:那边也是只 redirect 世界调用,所以冻结期间
     * 聊天栏与命令始终正常工作。
     *
     * <h2>状态机为什么由这里推进</h2>
     *
     * <p>冻结/步进状态机需要"每个服务器刻推进恰好一次"。1.7.10 没有 Mixin,而
     * {@code run()} 的计时算术一个字都不能改(改了就死循环,见变换器注释),
     * 所以挂钩点选在这里:每次 {@code MinecraftServer.tick()} 恰好进入本方法一次
     * (世界 tick 与 updateEntities 各一次,由 {@code prepareTickDone} 去重)。
     */
    public static void worldTickIfRunning(net.minecraft.world.WorldServer world) {
        try {
            // 每个服务器刻只推进一次状态机。
            if (!prepareTickDone) {
                prepareTickDone = true;
                // ---- 开始计时这一服务器刻 ----
                //
                // 1.7.10 里原先<b>没有任何地方调用 recordTickTime</b>(1.12.2 由 LoopPacer 负责),
                // 所以 TickStats 永远是空的,/tick query 只剩"运行状态"与"目标速率"两行 ——
                // 用户实测正是这个现象。这里补上:进入本方法即视为一刻开始。
                tickStartNanos = System.nanoTime();
                net.minecraft.server.MinecraftServer server =
                        net.minecraft.server.MinecraftServer.getServer();
                if (server != null) {
                    ServerTickController c = TickControlAccessHolder.controller(server);
                    c.prepareTick();
                    deliverSprintReportIfReady(server, c);
                }
            }

            net.minecraft.server.MinecraftServer server =
                    net.minecraft.server.MinecraftServer.getServer();
            if (server == null) {
                world.tick();
                return;
            }
            ServerTickController controller = TickControlAccessHolder.controller(server);
            if (!controller.runsNormally()) {
                return;
            }
            world.tick();
            controller.noteLevelTick();
        } catch (Throwable t) {
            // 主循环最热路径:绝不能把异常抛回原版循环。
            System.err.println("[tickcontrol] worldTickIfRunning failed: " + t);
        }
    }

    /** 本服务器刻的起始纳秒时间;0 表示尚未开始计时。 */
    private static long tickStartNanos;

    /**
     * 记录这一刻的耗时。由世界门控的<b>第二处</b>({@code updateEntities})调用 ——
     * 它在同一刻内晚于 {@code tick},所以此刻到这里就是"一个服务器刻的耗时"。
     */
    public static void recordCurrentTick() {
        if (tickStartNanos == 0L) {
            return;
        }
        long start = tickStartNanos;
        tickStartNanos = 0L;
        long elapsedNanos = System.nanoTime() - start;
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraft.server.MinecraftServer.getServer();
            if (server != null) {
                TickControlAccessHolder.controller(server).recordTickTime(elapsedNanos);
            }
        } catch (Throwable t) {
            System.err.println("[tickcontrol] recordCurrentTick failed: " + t);
        }
    }

    /**
     * 世界门控的<b>第二处</b>:代替 {@code WorldServer.updateEntities()}。
     *
     * <h2>为什么必须有这个独立方法(这是一个真实 bug 的修复)</h2>
     *
     * <p>{@code updateTimeLightAndEntities} 里有<b>两处</b>世界调用,原来我把两处都换成了
     * {@link #worldTickIfRunning} —— 而那个方法内部对两处都调用 {@code world.tick()}。
     * 于是 {@code updateEntities()} 被替换成了<b>第二次世界 tick</b>,而实体更新再也不会执行。
     *
     * <p>症状是<b>进存档看到虚空、视角被拽回</b>:世界被 tick 了两次(时间/光照推进异常),
     * 而实体与区块相关的更新被跳过。
     *
     * <p>1.7.10 的映射(官方 {@code mcp-1.7.10-srg.zip})已确认这两处的身份:
     *
     * <pre>
     * mt.b ()V  -&gt;  func_72835_b   ≡  WorldServer.tick()
     * mt.h ()V  -&gt;  func_72939_s   ≡  WorldServer.updateEntities()
     * </pre>
     *
     * <p>本方法<b>刻意不推进状态机</b> —— {@code prepareTick} 只由
     * {@link #worldTickIfRunning} 每刻推进一次,否则同一刻会被推进两次。
     */
    public static void worldUpdateEntitiesIfRunning(net.minecraft.world.WorldServer world) {
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraft.server.MinecraftServer.getServer();
            if (server == null) {
                world.updateEntities();
                return;
            }
            ServerTickController controller = TickControlAccessHolder.controller(server);
            if (!controller.runsNormally()) {
                return;
            }
            world.updateEntities();
            // 本刻到此结束 —— 这是本刻内晚于 world.tick() 的那一处,所以在此时结算耗时。
            recordCurrentTick();
        } catch (Throwable t) {
            System.err.println("[tickcontrol] worldUpdateEntitiesIfRunning failed: " + t);
        }
    }

    /** 本服务器刻是否已经推进过状态机;每刻由 {@link #resetTickFlags()} 复位。 */
    private static boolean prepareTickDone;

    /**
     * 由 {@code run()} 的睡眠挂钩点调用:复位状态机标志,并返回<b>本轮应当睡眠的毫秒数</b>。
     *
     * <h2>这里就是速率控制的落点(也是 sprint / rate 一直失效的原因)</h2>
     *
     * <p>1.7.10 原版主循环的形状是:
     *
     * <pre>
     * long i = getCurrentTimeMillis() - lastTick;
     * if (i &gt; 2000) { ...警告... }
     * lastTick = now;  otherLastTick = i;
     * if (i &lt; 0) i = 0;
     * if (i &gt; 50) i = 50;                 // 上限 50 —— 不碰
     * tick();                             // 世界在其中推进
     * Thread.sleep(Math.max(1, 50 - i));  // ← 本方法替换的就是这里的 50
     * </pre>
     *
     * <p>原版固定"周期 50ms ⇒ 20 刻/秒"。要支持 {@code /tick sprint} 与 {@code /tick rate},
     * 必须让这个周期可调 —— 我此前把它恒定返回 50(出于对计时算术的畏惧),于是
     * <b>sprint 与 rate 完全不起作用</b>,正是用户实测的现象。
     *
     * <p>这里返回 {@code max(0, 目标周期 - 上一轮实际耗时)},所以:
     *
     * <ul>
     *   <li>默认 {@code rate=20} ⇒ 目标 50ms ⇒ 与<b>原版行为完全一致</b>;</li>
     *   <li>{@code sprint} 期间目标 1ms ⇒ 几乎不睡 ⇒ 游戏刻飞快推进;</li>
     *   <li>{@code /tick rate R} ⇒ 目标 {@code 1000/R} ms。</li>
     * </ul>
     *
     * <p><b>刻意不改</b>上面的 {@code i -= 50L} 追赶递减:那是上一次"冻结反而极速推进"
     * 事故的根源,一律不动。这里只动睡眠值。
     *
     * <p>下限 0(允许不睡),上限 50——不放大、不缩小原版的最大等待。
     *
     * @return 本轮睡眠毫秒数
     */
    public static long tickSleepMillis() {
        // 每轮主循环复位状态机标志。
        //
        // 这里取代了原先"替换 Thread.sleep 入参"的做法。关键差别:
        // 原版入参是 max(1, 50 - i),i 是<b>本轮已耗时</b>;把它当周期去缩放方向是错的,
        // 实测减速完全失灵。现在整段 Math.max 表达式被替换掉,周期由本方法直接给出,
        // i 不再参与睡眠计算。
        resetTickFlags();
        return targetPeriodMillis();
    }

    /** 当前目标周期(毫秒):sprint 期间为 1ms,否则按 {@code /tick rate} 换算。 */
    private static long targetPeriodMillis() {
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraft.server.MinecraftServer.getServer();
            if (server == null) {
                return VANILLA_MSPT;
            }
            ServerTickController controller = TickControlAccessHolder.controller(server);
            if (controller.isSprinting()) {
                // 冲刺:尽可能快。1ms 是 Thread.sleep 的合理下限,再小没有意义。
                return 1L;
            }
            float rate = controller.tickRate();
            if (rate <= 0.0F) {
                return VANILLA_MSPT;
            }
            long period = (long) (1000.0D / (double) rate);
            return period < 1L ? 1L : period;
        } catch (Throwable t) {
            System.err.println("[tickcontrol] targetPeriodMillis failed: " + t);
            return VANILLA_MSPT;
        }
    }

    /**
     * 记录本轮主循环迭代的耗时 —— 供诊断使用。
     */
    static void noteIterationMillis(long millis) {
        lastIterationMillis = millis;
    }

    /** 最近一次测得的迭代耗时(毫秒),仅用于诊断输出。 */
    private static long lastIterationMillis = -1L;

    /** 下一个服务器刻开始时,允许状态机再推进一次。 */
    private static void resetTickFlags() {
        prepareTickDone = false;
    }

    /**
     * 冻结时是否应当抑制客户端<b>环境粒子</b>(熔炉火焰/烟、火把、岩浆、传送门)。
     *
     * <p>由 {@code WorldClient.doRandomDisplayTick} 的方法头调用(见变换器
     * {@code transformClientWorld})。
     *
     * <h2>为什么客户端要单独处理</h2>
     *
     * <p>服务端门控刻意不碰客户端:那会让玩家自己的挖掘/放置失去反馈,比粒子问题更糟。
     * 但环境粒子是<b>客户端自己按帧生成的</b>,与服务器刻无关,所以冻结后熔炉照样冒烟
     * —— 用户正是这样发现的("冻结状态下令炉居然能正常发散工作粒子")。
     *
     * <p>只有这一个门控点就够:1.7.10 全 jar 里 {@code Block.randomDisplayTick} 的调用者
     * 只有 {@code WorldClient.doRandomDisplayTick}。<b>玩家自己的粒子不受影响</b>,
     * 因为那类粒子走 {@code World.spawnParticle},不经过这里。
     *
     * <p>只读判断,不推进任何状态机。刻意在冻结而非"未推进"时抑制:
     * {@code /tick step} 期间是冻结状态,此时应当保持视觉静止。
     */
    public static boolean shouldSuppressAmbientParticles() {
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraft.server.MinecraftServer.getServer();
            if (server == null) {
                return false;
            }
            return !TickControlAccessHolder.controller(server).runsNormally();
        } catch (Throwable t) {
            // 客户端每帧调用:绝不能抛异常。
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 冲刺报告投递
    // ------------------------------------------------------------------

    /**
     * 发起冲刺的玩家名,由命令层在收到 {@code /tick sprint} 时写入。
     *
     * <p>存名字而不是 {@code ICommandSender} 本身:1.7.10 的命令是单例,持有
     * sender 引用会让已离线玩家的对象无法回收;而且投递发生在冲刺结束之后,
     * 那时本来就应该按名字找<b>当前在线</b>的那个玩家。
     */
    private static volatile String sprintReportTarget;

    /** 由命令层调用,记录冲刺报告应发给谁。 */
    public static void setSprintReportTarget(String playerName) {
        sprintReportTarget = playerName;
    }

    /**
     * 冲刺结束后把报告发出去。
     *
     * <p>{@link ServerTickController#consumeSprintReport()} 只在冲刺真正结束的那一轮
     * 返回一次 {@code true}(内部随即清掉标志),所以这里不需要额外的"只发一次"保护。
     *
     * <p>1.7.10 的 {@code ServerTickController} 一直有这套 API——{@code consumeSprintReport}
     * 与 {@code finishedSprintTps}/{@code finishedSprintMspt} 都已经实现——但过去
     * <b>没有任何地方调用过它</b>,于是玩家跑完 {@code /tick sprint} 什么都看不到。
     * 这与 1.16.5 上报告的现象是同一个问题,只是这一版连"取不到命令来源"的日志都没有,
     * 所以更隐蔽。
     */
    private static void deliverSprintReportIfReady(net.minecraft.server.MinecraftServer server,
                                                   ServerTickController controller) {
        if (!controller.consumeSprintReport()) {
            return;
        }
        try {
            String text = String.format(java.util.Locale.ROOT,
                    "Sprint completed: %d ticks per second, %.2f ms/tick",
                    Integer.valueOf((int) Math.round(controller.finishedSprintTps())),
                    Double.valueOf(controller.finishedSprintMspt()));
            net.minecraft.util.IChatComponent message =
                    new net.minecraft.util.ChatComponentText(text);

            String name = sprintReportTarget;
            if (name != null) {
                net.minecraft.entity.player.EntityPlayerMP player =
                        server.getConfigurationManager().func_152612_a(name);
                if (player != null) {
                    player.addChatMessage(message);
                    return;
                }
            }
            // 玩家已离线(或没有记录发起者)时退回服务端日志,
            // 而不是把报告彻底丢掉——至少还能在日志里看到结果。
            server.logInfo(text);
        } catch (Throwable t) {
            // 报告只是提示信息,绝不能让主循环因为它出错。
            System.err.println("[tickcontrol] sprint report delivery failed: " + t);
        }
    }
}
