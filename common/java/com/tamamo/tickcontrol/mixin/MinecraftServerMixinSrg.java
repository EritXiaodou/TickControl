
package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * 生产环境（SRG 名）变体：{@code remap = false}，注解值直接写 SRG 名。
 *
 * <p>SRG 名取自 ForgeGradle 缓存里的 {@code forge-1.12.2-14.23.5.2860-srg.jar}，
 * 并已用 {@code javap} 对着它逐个核验存在：
 *
 * <pre>
 * run                      -> run            （来自 Runnable，不混淆）
 * tick                     -> func_71217_p
 * updateTimeLightAndEntities -> func_71190_q
 * WorldServer.tick()       -> func_72835_b
 * </pre>
 *
 * <p>因为名字已经手动改成生产名，{@code remap = false} 让 Mixin 原样使用，
 * 不再依赖 refmap。由 {@link TickControlMixinPlugin#shouldApplyMixin}
 * 在「目标类使用 SRG 名」时选中。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixinSrg {

    /**
     * 整个替换 {@code run()}：注入到 HEAD 后 {@code cancel()}，
     * 因此原版 while 循环永远不会执行，生命周期收尾由
     * {@link ServerLoop#runServer} 自己复刻。
     */
    @Inject(method = "run", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$runServer(CallbackInfo ci) throws java.io.IOException {
        ServerLoop.runServer((MinecraftServer) (Object) this);
        ci.cancel();
    }

    /** 每刻开头刷新「本刻游戏内容是否推进」并采集耗时样本。 */
    @Inject(method = "func_71217_p", at = @At("HEAD"), remap = false)
    private void tickcontrol$prepareTick(CallbackInfo ci) {
        // 这一行是刻意的"活性探针"。
        //
        // 曾经 Mixin 因为 TransformerExclusions 排除了整个模组包而拿不到注入类,
        // 表现是 /tick rate 与 /tick sprint 正常,唯独 /tick freeze 无效——
        // 因为前者走 ServerLoop 主循环,后者只依赖本方法的注入。当时日志里唯一
        // 的线索是一句非常难懂的 "Classloader restrictions
        // [PACKAGE_TRANSFORMER_EXCLUSION] encountered"。
        //
        // 只打一次,开销可忽略,但能让"注入是否生效"变成一眼可见的事实。
        if (!ServerLoop.PREPARE_TICK_PROBE_LOGGED) {
            ServerLoop.PREPARE_TICK_PROBE_LOGGED = true;
            System.out.println("[tickcontrol] mixin active: MinecraftServer"
                    + ".func_71217_p() injection reached -- world gating is live");
        }
        ServerLoop.prepareTick((MinecraftServer) (Object) this);
    }

    /**
     * 世界门控：把 {@code updateTimeLightAndEntities()} 里的
     * {@code worldserver.tick()} 换成 {@link ServerLoop#tickLevel}。
     *
     * <p>1.12.2 的 {@code WorldServer.tick()} <b>没有参数</b>
     * （1.16.5 才是 {@code tick(BooleanSupplier)}），所以 target 描述符是 {@code ()V}。
     */
    @Redirect(
            method = "func_71190_q",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/WorldServer;func_72835_b()V"
            ),
            remap = false
    )
    private void tickcontrol$maybeTickLevel(WorldServer level) {
        ServerLoop.tickLevel(level);
    }

    /**
     * <b>第二个</b>世界门控点:实体与方块实体的更新。
     *
     * <h2>为什么只拦 {@code func_72835_b} 不够</h2>
     *
     * <p>1.12.2 的 {@code func_71190_q()} 对每个世界<b>分别调用两个方法</b>:
     *
     * <pre>
     *   235: invokevirtual WorldServer.func_72835_b:()V    // 世界 tick
     *   273: invokevirtual WorldServer.func_72939_s:()V    // 更新实体/方块实体
     * </pre>
     *
     * <p>之前只 redirect 了前者,于是世界 tick 确实停了,但
     * {@code func_72939_s} 仍在每刻被直接调用 —— <b>方块实体照常更新</b>,
     * 表现为"已经冻结了,但熔炉里的物品还在烧"。用户就是靠这个现象发现的。
     *
     * <p>注:{@code WorldServer.func_72939_s()} 内部会 {@code invokespecial}
     * {@code World.func_72939_s()},那是同一次调用的父类实现,不是第二个入口,
     * 不需要也不应该再拦一次。
     */
    @Redirect(
            method = "func_71190_q",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/WorldServer;func_72939_s()V"
            ),
            remap = false
    )
    private void tickcontrol$maybeTickLevelEntities(WorldServer level) {
        ServerLoop.tickLevelEntities(level);
    }
}
