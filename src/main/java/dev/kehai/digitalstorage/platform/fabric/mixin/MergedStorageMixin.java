package dev.kehai.digitalstorage.platform.fabric.mixin;

import com.tom.storagemod.util.MergedStorage;
import dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkIntrospection;
import dev.kehai.digitalstorage.platform.fabric.tom.TomDigitalEndpointTracker;
import dev.kehai.digitalstorage.platform.fabric.hopper.HopperTransferOptimizer;
import dev.kehai.digitalstorage.platform.fabric.FabricDigitalItemStorage;
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
    private Set<FabricDigitalItemStorage> digitalstorage$digitalEndpoints =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private Set<UUID> digitalstorage$volumeIds = new java.util.HashSet<>();

    @Unique
    private Set<Storage<ItemVariant>> digitalstorage$rawEndpointSources =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void digitalstorage$deduplicateDigitalEndpoint(
            Storage<ItemVariant> storage,
            CallbackInfo callbackInfo
    ) {
        digitalstorage$ensureSets();
        FabricDigitalItemStorage canonical = TomNetworkIntrospection.canonicalDigitalEndpoint(storage);
        if (canonical != null) {
            digitalstorage$rawEndpointSources.add(storage);
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
        digitalstorage$ensureSets();
        digitalstorage$digitalEndpoints.clear();
        digitalstorage$volumeIds.clear();
        digitalstorage$rawEndpointSources.clear();
        HopperTransferOptimizer.invalidateFilteredCursor((Storage<ItemVariant>) (Object) this);
    }

    @Override
    public List<FabricDigitalItemStorage> digitalstorage$rawDigitalEndpoints() {
        digitalstorage$ensureSets();
        return digitalstorage$rawEndpointSources.stream().map(TomNetworkIntrospection::canonicalDigitalEndpoint)
                .filter(java.util.Objects::nonNull).toList();
    }
    @Unique private void digitalstorage$ensureSets() {
        if (digitalstorage$digitalEndpoints == null) digitalstorage$digitalEndpoints = Collections.newSetFromMap(new IdentityHashMap<>());
        if (digitalstorage$volumeIds == null) digitalstorage$volumeIds = new java.util.HashSet<>();
        if (digitalstorage$rawEndpointSources == null) digitalstorage$rawEndpointSources = Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
