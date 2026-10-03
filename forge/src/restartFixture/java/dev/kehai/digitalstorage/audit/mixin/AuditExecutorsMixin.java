package dev.kehai.digitalstorage.audit.mixin;

import dev.kehai.digitalstorage.audit.RestartAudit;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Util.class, remap = false)
public abstract class AuditExecutorsMixin {
    @Inject(method = {"shutdownExecutors", "m_137580_"}, at = @At("HEAD"), cancellable = true)
    private static void keepAuditExecutors(CallbackInfo callback) {
        if (RestartAudit.keepProcess()) callback.cancel();
    }
}
