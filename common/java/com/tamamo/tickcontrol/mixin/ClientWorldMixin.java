
package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.client.world.ClientWorld;

/**
 * 冻结时抑制客户端<b>环境粒子</b>(熔炉火焰/烟、火把、岩浆、传送门)。
 *
 * <h2>问题</h2>
 *
 * <p>服务端门控刻意不碰客户端 —— 冻住客户端会让玩家自己的挖掘/放置失去反馈,
 * 比粒子问题更糟。但环境粒子是<b>客户端自己按帧生成的</b>,与服务器刻无关,
 * 于是"世界已冻结、熔炉仍在冒烟火"。用户先在 1.12.2 上发现,
 * 随后确认 1.16.5~1.20.1 同样存在。
 *
 * <h2>为什么拦这一个方法就够</h2>
 *
 * <p>已用 ASM 按<b>方法形状</b>而非名字扫过 1.16.5 的 srg jar:每个方块的环境粒子钩子
 * {@code Block.func_180655_c(BlockState, World, BlockPos, Random)V} 在客户端侧
 * <b>只有本方法一处调用者</b>;其余调用者是 {@code StairsBlock} 调用自己的
 * {@code super},以及两个<b>服务端</b>的 {@code func_2255xx_} 变体(不是客户端路径)。
 *
 * <p><b>玩家自己的粒子不受影响</b>:那类粒子走 {@code World.addParticle},不经过这里。
 *
 * <h2>命名</h2>
 *
 * <p>1.16.5 的 ForgeGradle 把开发与生产都映射到 SRG 名,所以这里只有一个变体、
 * 注解直接写 SRG 名并配 {@code remap = false}(与 {@link MinecraftServerMixin} 一致),
 * 不需要 refmap。
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    /**
     * 冻结时取消本次环境粒子生成。
     *
     * <p>{@code HEAD} + {@code cancellable} 是刻意的:只跳过粒子,
     * 不影响客户端其余 tick(渲染、实体插值、玩家自身反馈都照常)。
     */
    @Inject(method = "func_184153_a", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$suppressAmbientParticles(CallbackInfo ci) {
        if (ServerLoop.shouldSuppressAmbientParticles()) {
            ci.cancel();
        }
    }
}
