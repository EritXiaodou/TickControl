
package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.client.multiplayer.WorldClient;

/**
 * 生产环境（SRG 名）变体：冻结时抑制客户端<b>环境粒子</b>。
 *
 * <p>SRG 名取自 {@code forge-1.12.2-14.23.5.2860-srg.jar}（已核验该类与方法存在）：
 *
 * <pre>
 * WorldClient.doVoidFogParticles -> func_184153_a
 *   描述符 (IIIILjava/util/Random;ZLnet/minecraft/util/math/BlockPos$MutableBlockPos;)V
 * </pre>
 *
 * <h2>为什么要单独处理客户端</h2>
 *
 * <p>服务端门控（{@link MinecraftServerMixinSrg}）刻意不碰客户端 —— 冻结客户端会让
 * 玩家自己的挖掘/放置失去反馈，比粒子问题更糟。但环境粒子是客户端<b>按帧自己生成</b>的，
 * 与服务器刻无关，于是出现"世界已冻结、熔炉却仍在冒烟火"的矛盾观感。
 * 用户先是在 1.12.2 上注意到这一点。
 *
 * <h2>为什么拦这一个方法就够</h2>
 *
 * <p>已用 ASM 遍历整个 srg jar 核验：{@code Block.func_180655_c}
 * （即 {@code randomDisplayTick}）的调用者中，<b>客户端路径只有本方法一处</b>
 * （{@code WorldClient.func_184153_a}）；另外三处是 {@code BlockStairs} /
 * {@code BlockMycelium} / {@code BlockEnchantmentTable} 调用自己的 {@code super}，
 * 不是独立的粒子入口。所以在这里 cancel 一次即可覆盖全部环境方块粒子。
 *
 * <p><b>玩家自己的粒子不受影响</b>：那类粒子走 {@code World.func_175688_a}
 * （{@code spawnParticle}），不经过这里。
 */
@Mixin(WorldClient.class)
public abstract class WorldClientMixinSrg {

    /**
     * 冻结时直接取消本次环境粒子生成。
     *
     * <p>{@code cancellable = true} + {@code ci.cancel()} 是刻意的：只跳过粒子，
     * 不影响客户端的其余 tick（实体插值、渲染、玩家自身反馈都照常）。
     */
    @Inject(method = "func_184153_a", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$suppressAmbientParticles(CallbackInfo ci) {
        if (ServerLoop.shouldSuppressAmbientParticles()) {
            ci.cancel();
        }
    }
}
