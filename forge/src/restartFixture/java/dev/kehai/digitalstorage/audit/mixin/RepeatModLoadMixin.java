package dev.kehai.digitalstorage.audit.mixin;

import dev.kehai.digitalstorage.audit.RestartAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraftforge.server.loading.ServerModLoader", remap = false)
public abstract class RepeatModLoadMixin {
    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private static void skipRepeatRegistration(CallbackInfo callback) {
        if (RestartAudit.secondLaunch()) callback.cancel();
    }
}
