package dev.kehai.digitalstorage.mixin;

import com.tom.storagemod.util.MergedStorage;
import dev.kehai.digitalstorage.optimization.TomNetworkIntrospection;
import dev.kehai.digitalstorage.optimization.TomDigitalEndpointTracker;
import dev.kehai.digitalstorage.hopper.HopperTransferOptimizer;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent distinct Tom proxy objects from exposing the same Volume twice. */
@Mixin(MergedStorage.class)
public abstract class MergedStorageMixin implements TomDigitalEndpointTracker {
    @Unique
    private final Set<DigitalItemStorage> digitalstorage$digitalEndpoints =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private final Set<UUID> digitalstorage$volumeIds = new java.util.HashSet<>();

    @Unique
    private final Set<Storage<ItemVariant>> digitalstorage$rawEndpointSources =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private final List<DigitalItemStorage> digitalstorage$rawDigitalEndpoints = new ArrayList<>();

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void digitalstorage$deduplicateDigitalEndpoint(
            Storage<ItemVariant> storage,
            CallbackInfo callbackInfo
    ) {
        DigitalItemStorage canonical = TomNetworkIntrospection.canonicalDigitalEndpoint(storage);
        if (canonical != null) {
            if (digitalstorage$rawEndpointSources.add(storage)) {
                digitalstorage$rawDigitalEndpoints.add(canonical);
            }
            boolean accepted = canonical.volumeId()
                    .map(digitalstorage$volumeIds::add)
                    .orElseGet(() -> digitalstorage$digitalEndpoints.add(canonical));
            if (!accepted) {
                callbackInfo.cancel();
            }
        }
    }

    @Inject(method = "add", at = @At("TAIL"), remap = false)
    private void digitalstorage$invalidateFilteredCursorAfterAdd(
            Storage<ItemVariant> storage,
            CallbackInfo callbackInfo
    ) {
        HopperTransferOptimizer.invalidateFilteredCursor((Storage<ItemVariant>) (Object) this);
    }

    @Inject(method = "clear", at = @At("TAIL"), remap = false)
    private void digitalstorage$clearDigitalEndpoints(CallbackInfo callbackInfo) {
        digitalstorage$digitalEndpoints.clear();
        digitalstorage$volumeIds.clear();
        digitalstorage$rawEndpointSources.clear();
        digitalstorage$rawDigitalEndpoints.clear();
        HopperTransferOptimizer.invalidateFilteredCursor((Storage<ItemVariant>) (Object) this);
    }

    @Override
    public List<DigitalItemStorage> digitalstorage$rawDigitalEndpoints() {
        return List.copyOf(digitalstorage$rawDigitalEndpoints);
    }
}
