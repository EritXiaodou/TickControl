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

    /**
     * 每刻开头刷新门控状态。
     *
     * <p><b>handler 为什么可以不带参数</b>：{@code @Inject} 的 handler 允许
     * 不接收目标方法的任何参数。这一点很关键——handler 的参数会被搬进目标方法的
     * 局部变量表并触发 Mixin 的 {@code Transform LVT}，而 Mixin 0.8.5 自带 ASM
     * 读不了 JDK 21 的 {@code java.util.function.BooleanSupplier}：
     * <pre>
     * Error loading class: java/util/function/BooleanSupplier
     *   (java.lang.IllegalArgumentException: Unsupported class file major version 65)
     * ClassMetadataNotFoundException ... -&gt; Transform LVT
     * </pre>
     * 门控读的是 {@code ServerTickController} 的状态，本来也不需要那个谓词。
     */
    @Inject(method = "tickServer", at = @At("HEAD"), remap = false)
    private void tickcontrol$prepareTick(CallbackInfo ci) {
        ServerLoop.prepareTick((MinecraftServer) (Object) this);
    }

    /**
     * 世界门控。
     *
     * <p><b>这里的 handler 必须保留 {@code haveTime} 参数</b>：{@code @Redirect}
     * 要求 handler 接住目标调用的全部实参，少一个就会被 Mixin 判为
     * {@code InvalidInjectionException: Not enough arguments}（实测）。
     *
     * <p>但带参数就会触发 Mixin 的 LVT 变换，而 Mixin 0.8.5 自带 ASM
     * <b>读不了 JDK 21 的 class 文件</b>：
     * <pre>
     * Error loading class: java/util/function/BooleanSupplier
     *   (java.lang.IllegalArgumentException: Unsupported class file major version 65)
     * </pre>
     * 于是 <b>1.18.2 的开发环境（gradle runServer）在 JDK 21 下必然 APPLY 失败</b>——
     * 修复方向是让开发运行用 JDK 17（本工程的目标字节码版本，Gradle 的
     * foojay 插件已把 17 装进 {@code .gradle-home/jdks/}），
     * 而不是继续在 handler 签名上做文章。
     *
     * <p>生产环境不受影响：生产走 SRG 变体，且真实客户端能解析
     * {@code BooleanSupplier}，实测全程无 APPLY 错误、无 STALL。
     */
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
