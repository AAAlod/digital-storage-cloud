package dev.kehai.digitalstorage.forge.mixin;

import com.tom.storagemod.util.MultiItemHandler;
import dev.kehai.digitalstorage.forge.tom.ForgeTomEndpoints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explicit DSC volume identity supplements Tom's physical stack-reference sampling. */
@Mixin(value = MultiItemHandler.class, remap = false)
public abstract class MultiItemHandlerMixin implements ForgeTomEndpoints.RawDigitalEndpoints {
    @Shadow private List<LazyOptional<IItemHandler>> handlers;
    @Unique private List<LazyOptional<IItemHandler>> digitalstorage$raw;
    @Unique private Set<LazyOptional<IItemHandler>> digitalstorage$sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void digitalstorage$initialize(CallbackInfo callback) {
        digitalstorage$raw = new ArrayList<>();
        digitalstorage$sources = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void digitalstorage$deduplicate(LazyOptional<IItemHandler> candidate, CallbackInfo callback) {
        var current = candidate.orElse(null);
        var digital = ForgeTomEndpoints.digital(current);
        if (digital == null) return;
        if (digitalstorage$sources.add(candidate)) digitalstorage$raw.add(candidate);
        for (var existing : handlers) {
            var previous = ForgeTomEndpoints.digital(existing.orElse(null));
            if (previous != null && ForgeTomEndpoints.sameVolume(digital, previous)) {
                callback.cancel();
                return;
            }
        }
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void digitalstorage$clear(CallbackInfo callback) {
        digitalstorage$raw.clear();
        digitalstorage$sources.clear();
    }

    @Override public List<IItemHandler> digitalstorage$rawDigitalEndpoints() {
        var result = new ArrayList<IItemHandler>();
        for (var optional : digitalstorage$raw) {
            var handler = optional.orElse(null);
            if (ForgeTomEndpoints.digital(handler) != null) result.add(handler);
        }
        return List.copyOf(result);
    }
}
