package com.tamamo.tickcontrol.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tamamo.tickcontrol.core.ServerLoop;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 生产环境（SRG 名）变体：{@code remap = false}，注解值直接写 SRG 名。
 *
 * <p>SRG 名取自 ForgeGradle 缓存里的官方映射，并已用 {@code javap} 对着
 * 生产用的 {@code forge-1.20.1-47.4.26-client.jar} 逐个验证存在：
 *
 * <pre>
 * runServer          -> m_130011_
 * tickServer         -> m_5705_
 * tickChildren       -> m_5703_
 * ServerLevel.tick   -> m_8793_
 * </pre>
 *
 * <p>因为名字已经手动改成生产名，{@code remap = false} 让 Mixin 原样使用，
 * 不再依赖 refmap。由 {@link TickControlMixinPlugin#shouldApplyMixin}
 * 在「目标类使用 SRG 名」时选中。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixinSrg {

    @Inject(method = "m_130011_", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$runServer(CallbackInfo ci) throws java.io.IOException {
        ServerLoop.runServer((MinecraftServer) (Object) this);
        ci.cancel();
    }

    @Inject(method = "m_5705_", at = @At("HEAD"), remap = false)
    private void tickcontrol$prepareTick(java.util.function.BooleanSupplier haveTime, CallbackInfo ci) {
        ServerLoop.prepareTick((MinecraftServer) (Object) this);
    }

    @Redirect(
            method = "m_5703_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;m_8793_(Ljava/util/function/BooleanSupplier;)V"
            ),
            remap = false
    )
    private void tickcontrol$maybeTickLevel(ServerLevel level, java.util.function.BooleanSupplier haveTime) {
        ServerLoop.tickLevel(level, haveTime);
    }
}
