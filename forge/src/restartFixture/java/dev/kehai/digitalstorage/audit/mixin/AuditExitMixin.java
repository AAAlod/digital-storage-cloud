package dev.kehai.digitalstorage.audit.mixin;

import dev.kehai.digitalstorage.audit.RestartAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraftforge.server.ServerLifecycleHooks", remap = false)
public abstract class AuditExitMixin {
    @Inject(method = "handleExit", at = @At("HEAD"), cancellable = true)
    private static void keepAuditProcess(int status, CallbackInfo callback) {
        if (status == 0 && RestartAudit.keepProcess()) callback.cancel();
    }
}
