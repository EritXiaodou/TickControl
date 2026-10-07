package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * 生产环境（SRG 名）变体：冻结时抑制客户端<b>环境粒子</b>（1.20.1）。
 *
 * <p>SRG 名取自该版本的 {@code joined-1.20.1-*-srg.jar}（已用 ASM 核验）：
 *
 * <pre>
 * ClientLevel.m_233612_(IIII...;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos$MutableBlockPos;)V
 * </pre>
 *
 * <p>为什么拦这一个方法就够、以及为什么不影响玩家自己的粒子，见
 * {@link ServerLoop#shouldSuppressAmbientParticles()}。
 *
 * <p>跨版本照抄描述符会失败：1.16.5 用 {@code Random}，1.17.1 用
 * {@code MarkerParticleStatus}，1.18.2 用 {@code Block}，1.19.2 起用
 * {@code RandomSource}。{@code @Inject} 只按方法名定位，所以注解里不写描述符，
 * 但名字必须逐版本核验。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixinSrg {

    /** 冻结时取消本次环境粒子生成；只跳过粒子，不影响客户端其余 tick。 */
    @Inject(method = "m_233612_", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$suppressAmbientParticles(CallbackInfo ci) {
        if (ServerLoop.shouldSuppressAmbientParticles()) {
            ci.cancel();
        }
    }
}
