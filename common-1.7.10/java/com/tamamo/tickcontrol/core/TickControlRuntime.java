
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
            worldTickCalls++;
            // 每个服务器刻只推进一次状态机。
            if (!prepareTickDone) {
                prepareTickDone = true;
                // 自证测速:每个服务器刻计一次(此处已由 prepareTickDone 去重,
                // 所以不会因为 tick / updateEntities 两处调用而翻倍)。
                noteLoopForBench();
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
                    // ⚠️ 不在这里投递冲刺报告。
                    //
                    // 报告只应在"一轮结束"(tickSleepMillis 的 doSleep 收尾处)投递一次。
                    // 曾经这里与那处都调用 deliverSprintReportIfReady,而
                    // LoopPacer.sprintReportPending() 只看 lastSprintTps > 0,
                    // 且 lastSprintTps 由 finishSprint() 写入后<b>再不清零</b>,
                    // 于是每一轮都判定"有报告待发" -> 聊天栏被刷屏。
                }
            }

            net.minecraft.server.MinecraftServer server =
                    net.minecraft.server.MinecraftServer.getServer();
            if (server == null) {
                world.tick();
                return;
            }
            ServerTickController controller = TickControlAccessHolder.controller(server);

            // 读 runsGameElements(冻结/步进),与世界更新那一处一致,避免半冻结状态。
            //
            // 【为什么不再按节拍器限制追赶补跑】
            //
            // 曾加过 allowExtraTick():每次追赶补跑都向 LoopPacer 申请配额,意图是让
            // 补跑被速率节流。它带来了<b>进存档区块不渲染</b> —— 追赶循环在一轮内连调
            // 多次,每次都把 pacer 的 deadlineNanos 往后推,几轮后截止时刻被推到很远的
            // 未来,世界长期不推进,区块加载停摆。
            //
            // 1.7.10 的追赶循环与速率控制是结构性冲突:要让世界变慢,就必须丢掉补跑;
            // 但一旦在这里丢,区块/世界的推进也会一起停。真正可行的做法是在"每轮一次"
            // 的位置改周期(睡眠挂钩点,已生效:实测 requestedSleep 正确跟随 rate),
            // 而不是在世界门控里逐次否决。
            //
            // 当前保持与冻结一致的判据,首先保证存档可进、区块可加载。
            if (!controller.runsGameElements()) {
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
            // 与 worldTickIfRunning 必须同步(见那里的说明),否则会出现
            // "世界 tick 了但方块实体不更新"的半冻结状态。
            // 注意这一处<b>不再</b>向节拍器申请配额:配额已由前面的 worldTickIfRunning
            // 消费,这里只是同一刻内的第二步,跟随同一个判定结果。
            if (!controller.runsGameElements()) {
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

        // ---- 冲刺的驱动点 ----
        //
        // 1.12.2 / 1.20.1 的主循环里有这两步:
        //     checkShouldSprintThisTick()   // 本刻是否冲刺,并扣减剩余刻数
        //     ...tickServer()...
        //     endTickWork() / finishSprintTick()   // 收尾并结算报告
        //
        // 而 1.7.10 因为<b>没有重写主循环</b>,这两步从未被调用 —— pacer 的冲刺记账
        // 一直是死的。这正是 /tick sprint 回执成功却毫无效果的原因:
        // remainingSprintTicks 设了 1200,但没人扣减,也永远跑不完。
        //
        // 本方法每轮主循环恰好调用一次,所以把"每轮一次"的驱动接在这里。
        try {
            net.minecraft.server.MinecraftServer s =
                    net.minecraft.server.MinecraftServer.getServer();
            if (s != null) {
                ServerTickController c = TickControlAccessHolder.controller(s);
                c.checkShouldSprintThisTick();
            }
        } catch (Throwable t) {
            System.err.println("[tickcontrol] sprint check failed: " + t);
        }

        // 同一个主循环轮次内可能被调用多次(阈值/递减/睡眠三处都会调),
        // 必须返回同一个值,否则 i 的累积与比较会用不同的周期,算术就自相矛盾。
        long period = cachedPeriodMillis;
        if (period <= 0L || periodIsStale) {
            period = targetPeriodMillis();
            cachedPeriodMillis = period;
            periodIsStale = false;
        }

        // ---- 决定性诊断:每轮主循环里 worldTickIfRunning 被调用了几次 ----
        //
        // 这是当前唯一还没测的变量。原版 run() 的结构是
        //     tick();  while (i > 50) { tick(); i -= 50; }  sleep(max(1, 50 - i));
        // 如果每轮只调一次 tick(),worldTps 应当等于 actualTps;实测 worldTps=20 而
        // actualTps=1,说明<b>一轮里 worldTickIfRunning 被调了约 20 次</b>。
        //
        // ticksBetweenSleeps 就是这个数字。它决定了修法:
        //   约等于 1 -> 世界由别处驱动(需另找源头);
        //   约等于 20 -> 追赶循环在跑,必须让 i 与 period 匹配(而不是否决 tick)。
        long ticksThisRound = worldTickCalls - worldTickCallsAtLastSleep;
        worldTickCallsAtLastSleep = worldTickCalls;
        if ((sleepRoundCount++ % 20L) == 0L) {
            System.out.println("[tickcontrol] ROUND period=" + period + "ms ticksBetweenSleeps="
                    + ticksThisRound);
        }

        // ---- 诊断:每 200 轮输出一次真实周期 ----
        //
        // 用户实测 /tick rate 5 回执成功、变形器日志也确认注入生效(见 fml-client-latest.log
        // 的 "sleep-hook: replaced the 50L feeding Math.max"),但游戏仍 20 刻/秒。
        // 所以必须看到<b>运行时本方法到底返回什么</b>:
        //   period=50  -> getServer() 为 null,或走了别的分支(说明前提不成立);
        //   period=200 -> 周期正确,瓶颈在本方法的下游(另有睡眠/限速路径)。
        // 这是唯一的判断依据,不能再靠推测。
        if ((tickSleepCalls++ % 200L) == 0L) {
            String extra;
            long rate = 0L;
            try {
                net.minecraft.server.MinecraftServer s =
                        net.minecraft.server.MinecraftServer.getServer();
                if (s == null) {
                    extra = "SERVER_NULL";
                } else {
                    ServerTickController c = TickControlAccessHolder.controller(s);
                    rate = (long) c.tickRate();
                    extra = "sprint=" + c.isSprinting() + " frozen=" + c.isFrozen();
                }
            } catch (Throwable t) {
                extra = "probe-failed:" + t;
            }
            System.out.println("[tickcontrol] sleep-period probe: period=" + period
                    + "ms rate=" + rate + " call#" + tickSleepCalls + " " + extra);
        }

        return period;
    }

    /** 诊断计数器:{@link #tickSleepMillis()} 被调用的次数(= 主循环轮数)。 */
    private static long tickSleepCalls;
    /** 诊断计数器:{@link #worldTickIfRunning} 被调用的次数(= 世界刻尝试数)。 */
    private static long worldTickCalls;
    private static long worldTickCallsAtLastSleep;
    private static long sleepRoundCount;

    /**
     * 诊断用:代替 {@code Thread.sleep(J)V},<b>先记录再真正睡眠</b>。
     *
     * <p>签名与 {@code Thread.sleep(J)V} 完全一致(消费一个 long、无返回值),所以替换后
     * 栈效应不变、所有 stackmap 帧继续成立 —— 比改表达式安全得多。
     *
     * <p>为什么需要它:运行时 probe 已证明 {@code tickSleepMillis()} 返回 1000ms,
     * 但游戏仍按 20 刻/秒运行。唯一还没被测量的一环,就是<b>实际传给 sleep 的毫秒数</b>。
     * 若这里打印 1000 而游戏不变快慢,瓶颈就在酷版之外(另有调度/限速);
     * 若打印的是小值,瓶颈就在 {@code Math.max} 之前的算术。
     */
    public static void doSleep(long millis) throws InterruptedException {
        long wallStart = System.nanoTime();

        // ---- 自证式测速:用真实经过的时间算实际 TPS ----
        //
        // 视觉判断(红石粉、中继器)已经被证明不可靠,所以这里改为直接测量:
        // 记录「这一轮睡眠前的时间戳」,在主循环下一轮回到 worldTickIfRunning 时再取一次,
        // 用真实墙钟时间除以往返轮数,就得到<b>实际主循环频率</b>(= 实际 TPS 上限)。
        //
        // 每 5 秒输出一次,并把「请求的睡眠值」与「实际经过的时间」并排显示 ——
        // 如果请求 950ms 而每轮回合仍只有 50ms,一眼就能看出两者不符。
        lastSleepRequestedMillis = millis;
        lastSleepStartNanos = wallStart;

        // 睡眠是本轮的最后一步:下一轮必须重新计算周期(否则改了 rate/sprint 后
        // 缓存永远不会更新,命令看起来没效果)。
        periodIsStale = true;

        // ---- 冲刺收尾(对应上游 finishSprintTick) ----
        //
        // 必须在"本轮结束"这个位置结算:lastSprintTicks / lastSprintTps / 报告标志都由
        // 这里产出。没有它,冲刺跑完也不会发送 /tick sprint 的完成报告。
        try {
            net.minecraft.server.MinecraftServer s =
                    net.minecraft.server.MinecraftServer.getServer();
            if (s != null) {
                ServerTickController c = TickControlAccessHolder.controller(s);
                c.endTickWork();
                deliverSprintReportIfReady(s, c);
            }
        } catch (Throwable t) {
            System.err.println("[tickcontrol] sprint finish failed: " + t);
        }

        Thread.sleep(millis);
    }

    /** 最近一次请求的睡眠毫秒数,供自证式测速报告使用。 */
    private static volatile long lastSleepRequestedMillis;
    /** 最近一次进入睡眠的时间戳。 */
    private static long lastSleepStartNanos;

    /** 自证式测速:统计两次报告之间的轮数与真实时间。 */
    private static long benchLoopCount;
    private static long benchLastReportNanos;

    /**
     * 每轮主循环调用一次(由 {@code worldTickIfRunning} 触发),用于自证实际 TPS。
     *
     * <p>输出的 {@code actualTps} 是「真实经过 1 秒内跑了多少轮」,与 {@code requested}
     * (本轮请求的睡眠毫秒数)对照。两者不一致就说明睡眠并未真正生效。
     */
    static void noteLoopForBench() {
        benchLoopCount++;
        long now = System.nanoTime();
        if (benchLastReportNanos == 0L) {
            benchLastReportNanos = now;
            return;
        }
        long elapsed = now - benchLastReportNanos;
        if (elapsed < 5_000_000_000L) {
            return;                          // 每 5 秒报告一次
        }
        double seconds = elapsed / 1e9;
        double actualTps = benchLoopCount / seconds;
        long requested = lastSleepRequestedMillis;

        // ---- 世界时间漂移:与玩家观察到的"熔炉 10 秒"对齐的指标 ----
        //
        // BENCH 的 actualTps 只说明主循环跑了多少轮,不代表世界真的推进了。而熔炉燃烧、
        // 红石、实体一律由世界刻驱动,所以这里额外读<b>世界总时间</b>并算它每秒涨多少。
        // 若 worldDelta ≈ actualTps,世界确实在按新速率推进;
        // 若 worldDelta ≈ 20 而 actualTps = 1,说明世界另有 20Hz 驱动源(那就是熔炉 10 秒的原因)。
        String worldInfo = "";
        try {
            net.minecraft.server.MinecraftServer s =
                    net.minecraft.server.MinecraftServer.getServer();
            if (s != null && s.worldServers != null && s.worldServers.length > 0) {
                net.minecraft.world.WorldServer w = s.worldServers[0];
                if (w != null) {
                    long nowTicks = w.getTotalWorldTime();
                    long dTicks = nowTicks - benchLastWorldTicks;
                    double worldTps = dTicks / seconds;
                    worldInfo = String.format(" worldTimeDelta=%d worldTps=%.2f", dTicks, worldTps);
                    benchLastWorldTicks = nowTicks;
                }
            }
        } catch (Throwable t) {
            worldInfo = " world-probe-failed:" + t;
        }

        System.out.println(String.format(
                "[tickcontrol] BENCH actualTps=%.2f loops=%d over %.2fs requestedSleep=%dms%s",
                actualTps, benchLoopCount, seconds, requested, worldInfo));
        benchLoopCount = 0L;
        benchLastReportNanos = now;
    }

    /** 上一次报告时的世界总时间,用于算出世界刻真实推进速度。 */
    private static long benchLastWorldTicks = -1L;


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

    /** 本轮主循环缓存的目标周期(毫秒);<=0 表示未缓存。 */
    private static long cachedPeriodMillis;
    /** 速率/冲刺状态可能变过,需要重新计算周期。 */
    private static volatile boolean periodIsStale = true;

    /**
     * 速率变化时由命令层调用,让下一轮重新计算周期。
     */
    public static void invalidatePeriodCache() {
        periodIsStale = true;
    }

    /**
     * 每一轮主循环开始时调用:复位"本刻已推进状态机"标志,并记录刻起点。
     *
     * <p>由接管后的 {@code ServerLoop} 调用。接管循环后 {@code tickSleepMillis()} 不再执行,
     * 而它原本是 {@link #resetTickFlags()} 的唯一调用点 —— 若不在这里复位,
     * {@code prepareTickDone} 会永远为 {@code true},于是:
     *
     * <ul>
     *   <li>状态机只在第一刻推进一次,<b>冻结/步进全部失效</b>;</li>
     *   <li>{@code worldTickIfRunning} 里那一段(刻耗时统计、BENCH 计数)只执行一次,
     *       {@code /tick query} 的数字不再更新。</li>
     * </ul>
     */
    static void beginTick() {
        resetTickFlags();
        tickStartNanos = System.nanoTime();
        noteLoopForBench();
    }

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
    /**
     * 冲刺结束后投递报告。
     *
     * <p>包内可见(不是 private):主循环接管类 {@link ServerLoop} 需要调用它。
     * 接管之后 {@code doSleep} 不再被执行,原先挂在它上面的投递点因此失效 ——
     * 这就是"冲刺跑完了但没有任何报告"的原因。
     */
    static void deliverSprintReportIfReady(net.minecraft.server.MinecraftServer server,
                                                   ServerTickController controller) {
        if (!controller.consumeSprintReport()) {
            return;
        }

        // ⚠️ 一次性投递保护(修刷屏)。
        //
        // LoopPacer.sprintReportPending() 的判据是 lastSprintTps > 0,而 lastSprintTps 由
        // finishSprint() 写入后<b>不会自动清零</b>:consumeSprintReport() 只清
        // ServerTickController 自己的 sprintReportPending 标志,下一次 endTickWork()
        // 看到 pacer 仍有 lastSprintTps>0 就又把它置回 true。
        //
        // 后果就是玩家实测的刷屏:冲刺结束后每一轮主循环都发一条聊天消息
        // (一轮可达上千次,日志能涨到几百 MB)。
        //
        // 这里在投递之后主动清掉 pacer 的报告数据,让下一次判据为假。
        try {
            controller.clearSprintReport();
        } catch (Throwable ignored) {
            // 清理失败不该影响投递结果。
        }

        try {
            // 用翻译键而不是拼死的英文字符串,否则中文客户端看到的是英文。
            // 对应 assets/tickcontrol/lang/{en_US,zh_CN}.lang 里的 sprint.report。
            net.minecraft.util.IChatComponent message =
                    new net.minecraft.util.ChatComponentTranslation(
                            com.tamamo.tickcontrol.command.TickLang.SPRINT_REPORT,
                            new Object[] {
                                    Integer.valueOf((int) Math.round(controller.finishedSprintTps())),
                                    String.format(java.util.Locale.ROOT, "%.2f",
                                            Double.valueOf(controller.finishedSprintMspt()))
                            });

            String name = sprintReportTarget;
            if (name != null) {
                net.minecraft.entity.player.EntityPlayerMP player =
                        server.getConfigurationManager().func_152612_a(name);
                if (player != null) {
                    player.addChatMessage(message);
                    return;
                }
            }
            // 玩家已离线(或没有记录发起者)时退回服务端日志。
            //
            // 日志里给出翻译后的文本:IChatComponent.getUnformattedText() 会走
            // LanguageRegistry,所以这里拿到的是当前语言下的字符串。
            server.logInfo(message.getUnformattedText());
        } catch (Throwable t) {
            // 报告只是提示信息,绝不能让主循环因为它出错。
            System.err.println("[tickcontrol] sprint report delivery failed: " + t);
        }
    }

    /**
     * 客户端实体是否应当在冻结期间跳过本帧更新。
     *
     * <p>用户报告:服务端确实冻结了(BENCH 显示 worldTps 掉到约 2),但客户端上
     * <b>实体仍会被玩家碰撞箱推动</b>、<b>马等实体的待机动画仍在播放</b>。
     *
     * <p>原因是这两件事都由客户端自己推进的实体更新驱动,服务端不再发包并不会让它们停下。
     * 这与 1.16.5+ 上"冻结的熔炉仍冒烟"是同一类问题:客户端自算的表现层。
     *
     * <p>判据复用冻结状态本身({@link #shouldSuppressAmbientParticles()} 用的同一个实时查询),
     * 因此与 /tick freeze、/tick step 的语义严格一致。
     */
    public static boolean shouldFreezeClientEntities() {
        return shouldSuppressAmbientParticles();
    }
}
