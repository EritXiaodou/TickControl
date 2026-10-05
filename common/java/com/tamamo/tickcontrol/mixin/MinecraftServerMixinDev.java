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
 * 开发环境（MCP 官方名）变体：{@code remap = false}，注解值直接用官方名。
 *
 * <p>由 {@link TickControlMixinPlugin#shouldApplyMixin} 在「目标类使用官方名」时选中；
 * 生产环境会改选 {@link MinecraftServerMixinSrg}。两份都只做委托，
 * 真正的逻辑在 {@link ServerLoop}。
 *
 * <h2>为什么要分成两个类</h2>
 * Mixin 注解里的方法名是编译期常量，无法在运行时改成 SRG 名，而本工程的 refmap
 * 在生产环境没有被应用（详见 README 第 12/13 条）。因此改为「两个变体 +
 * 配置插件选择」，把环境判断放进自己的 Java 代码里，彻底绕开 refmap。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixinDev {

    @Inject(method = "runServer", at = @At("HEAD"), cancellable = true, remap = false)
    private void tickcontrol$runServer(CallbackInfo ci) throws java.io.IOException {
        ServerLoop.runServer((MinecraftServer) (Object) this);
        ci.cancel();
    }

    @Inject(method = "tickServer", at = @At("HEAD"), remap = false)
    private void tickcontrol$prepareTick(java.util.function.BooleanSupplier haveTime, CallbackInfo ci) {
        ServerLoop.prepareTick((MinecraftServer) (Object) this);
    }

    @Redirect(
            method = "tickChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"
            ),
            remap = false
    )
    private void tickcontrol$maybeTickLevel(ServerLevel level, java.util.function.BooleanSupplier haveTime) {
        ServerLoop.tickLevel(level, haveTime);
    }
}
