package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * 开发环境（可读 MCP 名）变体：冻结时抑制客户端<b>环境粒子</b>（1.19.2）。
 *
 * <p>与 {@link ClientLevelMixinSrg} 完全等价，由 {@code TickControlMixinPlugin}
 * 在「目标类使用可读名」时选中。
 *
 * <p>名字的坑（三个版本都实际核验过）：开发名是 {@code doAnimateTick}，<b>不是</b>
 * {@code animateTick} —— 后者是本版本里另一个方法 {@code animateTick(int,int,int)}。
 * 写成 {@code animateTick} 会注入到错误的方法上而且不报错。
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
