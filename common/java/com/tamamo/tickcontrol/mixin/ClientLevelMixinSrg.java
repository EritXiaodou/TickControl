
package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * 生产环境（SRG 名）变体：冻结时抑制客户端<b>环境粒子</b>。
 *
 * <p>SRG 名取自 {@code joined-1.17.1-*-srg.jar}（已用 ASM 核验）：
 *
 * <pre>
 * ClientLevel.m_171634_(IIIILjava/util/Random;Lnet/minecraft/client/multiplayer/ClientLevel$MarkerParticleStatus;Lnet/minecraft/core/BlockPos$MutableBlockPos;)V
 * </pre>
 *
 * <p>为什么拦这一个方法就够、以及为什么不影响玩家自己的粒子，见
 * {@link ServerLoop#shouldSuppressAmbientParticles()}。
 *
 * <p>注意描述符里第 6 个参数是 {@code MarkerParticleStatus} —— 1.18.2 换成了
 * {@code Block}、1.19.2 起又换成 {@code RandomSource}。@Inject 只按方法名定位，
 * 所以描述符不必写进注解；但跨版本照抄名字一定会失败，因此每个版本各自核验过。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixinSrg {

    /**
     * 冻结时取消本次环境粒子生成。
     *
     * <p>{@code HEAD} + {@code cancellable} 是刻意的：只跳过粒子，不影响客户端其余
     * tick（渲染、实体插值、玩家自身反馈都照常）。
     */
    @Inject(method = "m_171634_", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$suppressAmbientParticles(CallbackInfo ci) {
        if (ServerLoop.shouldSuppressAmbientParticles()) {
            ci.cancel();
        }
    }
}
