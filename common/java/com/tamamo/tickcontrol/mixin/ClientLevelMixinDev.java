
package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * 开发环境（可读 MCP 名）变体：冻结时抑制客户端<b>环境粒子</b>。
 *
 * <p>与 {@link ClientLevelMixinSrg} 完全等价，由
 * {@code TickControlMixinPlugin} 在「目标类使用可读名」时选中。
 *
 * <p>名字的坑(实际踩过):1.17.1 的开发名是 {@code doAnimateTick},不是
 * {@code animateTick} —— 后者是另一个三参数方法 {@code animateTick(int,int,int)}。
 * 写成 {@code animateTick} 会静默注入到错误的方法上。
 * 对应的 SRG 名是 {@code m_171634_}。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixinDev {

    /** 冻结时取消本次环境粒子生成；玩家自身的粒子不经过这里。 */
    @Inject(method = "doAnimateTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$suppressAmbientParticles(CallbackInfo ci) {
        if (ServerLoop.shouldSuppressAmbientParticles()) {
            ci.cancel();
        }
    }
}
