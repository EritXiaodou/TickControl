
package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.client.multiplayer.WorldClient;

/**
 * 开发环境（可读 MCP 名）变体：冻结时抑制客户端<b>环境粒子</b>。
 *
 * <p>与 {@link WorldClientMixinSrg} 完全等价，只是注解里写可读名、
 * 由 {@code TickControlMixinPlugin} 在「目标类使用可读名」时选中。
 *
 * <p>可读名 {@code doVoidFogParticles} 有个坑值得记下来：它听起来只管"虚空迷雾"，
 * 实际上是环境方块粒子的统一入口（熔炉火焰/烟、火把、岩浆、传送门都在这里生成）。
 * 我在 1.7.10 上曾按 {@code doRandomDisplayTick} 去找，结果一次都没命中。
 */
@Mixin(WorldClient.class)
public abstract class WorldClientMixinDev {

    /**
     * 冻结时直接取消本次环境粒子生成；玩家自身的粒子不经过这里。
     *
     * <p>{@code remap = false} 与其它变体一致：注解里写的就是本名字域的真实方法名，
     * 不需要 refmap。少了它，编译期注解处理器会去查 {@code doVoidFogParticles} 的
     * 混淆映射并直接报错（{@code No obfuscation mapping for @Inject target}）。
     */
    @Inject(method = "doVoidFogParticles", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$suppressAmbientParticles(CallbackInfo ci) {
        if (ServerLoop.shouldSuppressAmbientParticles()) {
            ci.cancel();
        }
    }
}
