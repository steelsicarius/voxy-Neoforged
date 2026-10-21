package me.cortex.voxy.client.mixin.minecraft.session;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("TAIL"))
    private void voxy$injectWorldClose(CallbackInfo ci) {
        if (ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionEnd();
        }
    }

    //MC 26.2 createDevice signature: (long window, ShaderSource, GpuDebugOptions, Runnable)
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuBackend;createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;"))
    private static GpuDevice voxy$forceOpenglDevice(GpuBackend instance, long window, ShaderSource defaultShaderSource, GpuDebugOptions gpuDebugOptions, Runnable criticalShaderLoader, Operation<GpuDevice> original) throws BackendCreationException {
        if (instance instanceof VulkanBackend) {
            throw new BackendCreationException("Voxy does not presently support vulkan", BackendCreationException.Reason.OTHER);
        }
        return original.call(instance, window, defaultShaderSource, gpuDebugOptions, criticalShaderLoader);
    }
}
