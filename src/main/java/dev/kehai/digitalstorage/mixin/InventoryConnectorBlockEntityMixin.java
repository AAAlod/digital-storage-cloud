package dev.kehai.digitalstorage.mixin;

import com.tom.storagemod.tile.InventoryConnectorBlockEntity;
import com.tom.storagemod.util.MergedStorage;
import dev.kehai.digitalstorage.optimization.TomNetworkCache;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryConnectorBlockEntity.class)
public abstract class InventoryConnectorBlockEntityMixin {
    @Shadow(remap = false)
    private MergedStorage handlers;

    @Unique
    private long digitalstorage$topologyFingerprint;

    @Unique
    private int digitalstorage$topologySize = -1;

    @Inject(method = {"addLinked", "unLink"}, at = @At("HEAD"), remap = false)
    private void digitalstorage$invalidateTopology(
            @Coerce Object linked,
            CallbackInfo callbackInfo
    ) {
        TomNetworkCache.invalidate((BlockEntity) (Object) this);
    }

    /**
     * Tom has already rebuilt this collection on these ticks. Computing an
     * identity summary here adds O(endpoint count), but performs no extra BFS
     * and never enumerates StorageViews.
     */
    @Inject(method = "updateServer", at = @At("TAIL"), remap = false)
    private void digitalstorage$detectRebuiltTopology(CallbackInfo callbackInfo) {
        BlockEntity connector = (BlockEntity) (Object) this;
        if (connector.getWorld() == null || connector.getWorld().getTime() % 20 != 0) {
            return;
        }
        long identitySum = 0;
        int identityXor = 0;
        int size = 0;
        for (Storage<ItemVariant> storage : handlers.getStorages()) {
            int identity = System.identityHashCode(storage);
            identitySum += Integer.toUnsignedLong(identity);
            identityXor ^= identity;
            size++;
        }
        long fingerprint = identitySum * 31 + Integer.toUnsignedLong(identityXor);
        if (size != digitalstorage$topologySize || fingerprint != digitalstorage$topologyFingerprint) {
            digitalstorage$topologySize = size;
            digitalstorage$topologyFingerprint = fingerprint;
            TomNetworkCache.invalidate(connector);
        }
    }
}
