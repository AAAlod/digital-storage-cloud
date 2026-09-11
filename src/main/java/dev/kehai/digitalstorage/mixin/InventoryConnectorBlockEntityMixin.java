package dev.kehai.digitalstorage.mixin;

import com.tom.storagemod.tile.InventoryConnectorBlockEntity;
import com.tom.storagemod.util.MergedStorage;
import dev.kehai.digitalstorage.optimization.TomNetworkCache;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryConnectorBlockEntity.class)
public abstract class InventoryConnectorBlockEntityMixin {
    @Shadow(remap = false)
    private MergedStorage handlers;

    @Inject(method = {"addLinked", "unLink"}, at = @At("HEAD"), remap = false)
    private void digitalstorage$invalidateTopology(
            @Coerce Object linked,
            CallbackInfo callbackInfo
    ) {
        TomNetworkCache.invalidate((BlockEntity) (Object) this);
    }

    /**
     * Tom has already rebuilt this collection on these ticks. Computing an
     * structural summary here performs no extra BFS
     * and never enumerates StorageViews.
     */
    @Inject(method = "updateServer", at = @At("TAIL"), remap = false)
    private void digitalstorage$detectRebuiltTopology(CallbackInfo callbackInfo) {
        BlockEntity connector = (BlockEntity) (Object) this;
        if (connector.getWorld() == null || connector.getWorld().getTime() % 20 != 0) {
            return;
        }
        TomNetworkCache.rebuilt(connector, handlers);
    }
}
