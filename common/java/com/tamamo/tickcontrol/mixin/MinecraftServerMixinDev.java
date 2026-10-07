
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
 * 开发环境（MCP stable_39 名）变体：{@code remap = false}，注解值直接用官方名。
 *
 * <p>由 {@link TickControlMixinPlugin#shouldApplyMixin} 在「目标类使用官方名」时选中；
 * 生产环境会改选 {@link MinecraftServerMixinSrg}。两份都只做委托，
 * 真正的逻辑在 {@link ServerLoop}。
 *
 * <h2>为什么 1.12.2 也要分两个类</h2>
 *
 * Mixin 注解里的方法名是编译期常量，无法在运行时改成 SRG 名。1.12.2 的两个名字域
 * 确实不同：{@code run} 在两边同名（来自 {@code Runnable}，不混淆），
 * 但 {@code tick} 在 MCP 里是 {@code tick}、在生产里是 {@code func_71217_p}；
 * {@code WorldServer.tick()} 对应 {@code func_72835_b}。
 * 因此沿用「两个变体 + 配置插件按名字域选择」的做法，把环境判断放进自己的 Java 代码里。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixinDev {

    @Inject(method = "run", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$runServer(CallbackInfo ci) throws java.io.IOException {
        ServerLoop.runServer((MinecraftServer) (Object) this);
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void tickcontrol$prepareTick(CallbackInfo ci) {
        ServerLoop.prepareTick((MinecraftServer) (Object) this);
    }

    @Redirect(
            method = "updateTimeLightAndEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/WorldServer;tick()V"
            ),
            remap = false
    )
    private void tickcontrol$maybeTickLevel(WorldServer level) {
        ServerLoop.tickLevel(level);
    }

    /**
     * 第二个世界门控点:实体与方块实体的更新(开发名 {@code updateEntities}）。
     *
     * <p>与 SRG 变体同理:1.12.2 的服务器刻对每个世界分别调用「世界 tick」和
     * 「更新实体」两个方法,只拦前者会出现"冻结了但熔炉还在烧"。
     */
    @Redirect(
            method = "updateTimeLightAndEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/WorldServer;updateEntities()V"
            ),
            remap = false
    )
    private void tickcontrol$maybeTickLevelEntities(WorldServer level) {
        ServerLoop.tickLevelEntities(level);
    }
}
