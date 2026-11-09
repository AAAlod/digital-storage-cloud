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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Explicit DSC volume identity supplements Tom's physical stack-reference sampling. */
@Mixin(value = MultiItemHandler.class, remap = false)
public abstract class MultiItemHandlerMixin implements ForgeTomEndpoints.RawDigitalEndpoints, ForgeTomEndpoints.TopologyVersion {
    @Shadow private List<LazyOptional<IItemHandler>> handlers;
    @Shadow private int[] invSizes;
    @Shadow private boolean calling;
    @Shadow public abstract void refresh();
    @Unique private List<LazyOptional<IItemHandler>> digitalstorage$raw;
    @Unique private Set<LazyOptional<IItemHandler>> digitalstorage$sources;
    @Unique private java.util.Map<IItemHandler, Integer> digitalstorage$dynamicSizes;
    @Unique private boolean digitalstorage$refreshing;
    @Unique private long digitalstorage$stableVersion;
    @Unique private long digitalstorage$stableFingerprint = Long.MIN_VALUE;
    @Unique private boolean digitalstorage$invalidated;

    @Override public long digitalstorage$topologyVersion() { return digitalstorage$invalidated ? ~digitalstorage$stableVersion : digitalstorage$stableVersion; }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void digitalstorage$initialize(CallbackInfo callback) {
        digitalstorage$raw = new ArrayList<>();
        digitalstorage$sources = Collections.newSetFromMap(new IdentityHashMap<>());
        digitalstorage$dynamicSizes = new IdentityHashMap<>();
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void digitalstorage$deduplicate(LazyOptional<IItemHandler> candidate, CallbackInfo callback) {
        var current = candidate.orElse(null);
        if (ForgeTomEndpoints.underlyingDigital(current) != null && digitalstorage$sources.add(candidate)) {
            digitalstorage$raw.add(candidate);
        }
        var digital = ForgeTomEndpoints.digital(current);
        if (digital == null) return;
        for (var existing : handlers) {
            var previous = ForgeTomEndpoints.digital(existing.orElse(null));
            if (previous != null && ForgeTomEndpoints.sameVolume(digital, previous)) {
                callback.cancel();
                return;
            }
        }
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void digitalstorage$invalidate(CallbackInfo callback) {
        digitalstorage$invalidated = true;
    }

    @Inject(method = "clear", at = @At("TAIL"))
    private void digitalstorage$clear(CallbackInfo callback) {
        digitalstorage$raw.clear();
        digitalstorage$sources.clear();
        digitalstorage$dynamicSizes.clear();
    }

    @Inject(method = "refresh", at = @At("TAIL"))
    private void digitalstorage$rememberSizes(CallbackInfo callback) {
        digitalstorage$dynamicSizes.clear();
        for (int i = 0; i < handlers.size() && i < invSizes.length; i++) {
            var handler = handlers.get(i).orElse(null);
            if (ForgeTomEndpoints.underlyingDigital(handler) != null
                    || handler instanceof ForgeTomEndpoints.RawDigitalEndpoints) {
                digitalstorage$dynamicSizes.put(handler, invSizes[i]);
            }
        }
        // Tom rebuilds its aggregate every 20 ticks even when the topology is unchanged.
        // Bump the fast-path generation only when the observable structure actually changes.
        long fingerprint = digitalstorage$structureFingerprint();
        if (fingerprint != digitalstorage$stableFingerprint) {
            digitalstorage$stableFingerprint = fingerprint;
            digitalstorage$stableVersion++;
        }
        digitalstorage$invalidated = false;
    }

    @Unique
    private long digitalstorage$structureFingerprint() {
        long hash = 1;
        for (int i = 0; i < handlers.size(); i++) {
            hash = 31 * hash + System.identityHashCode(handlers.get(i).orElse(null));
            hash = 31 * hash + (i < invSizes.length ? invSizes[i] : -1);
        }
        return hash;
    }

    // Tom caches aggregate slot offsets. A live tier change must update those offsets,
    // including a physical inventory located after DSC, before routing any operation.
    @Inject(method = {"getSlots", "getStackInSlot", "insertItem", "extractItem", "getSlotLimit", "isItemValid"},
            at = @At("HEAD"))
    private void digitalstorage$updateSizes(CallbackInfoReturnable<?> callback) {
        if (calling || digitalstorage$refreshing || digitalstorage$dynamicSizes.isEmpty()) return;
        digitalstorage$refreshing = true;
        try {
            for (var entry : digitalstorage$dynamicSizes.entrySet()) {
                if (entry.getKey().getSlots() != entry.getValue()) {
                    refresh();
                    break;
                }
            }
        } finally {
            digitalstorage$refreshing = false;
        }
    }

    @Override public List<IItemHandler> digitalstorage$rawDigitalEndpoints() {
        var result = new ArrayList<IItemHandler>();
        for (var optional : digitalstorage$raw) {
            var handler = optional.orElse(null);
            if (ForgeTomEndpoints.underlyingDigital(handler) != null) result.add(handler);
        }
        return List.copyOf(result);
    }
}
