package dev.kehai.digitalstorage.platform.fabric.mixin;

import com.tom.storagemod.tile.InventoryConnectorBlockEntity;
import com.tom.storagemod.util.MergedStorage;
import dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkCache;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryConnectorBlockEntity.class)
public abstract class InventoryConnectorBlockEntityMixin {
    @Shadow(remap = false)
    private MergedStorage handlers;

    /**
     * Tom has already rebuilt this collection on these ticks. Computing an
     * structural summary here performs no extra BFS
     * and never enumerates StorageViews.
     * Cable connectors unLink/addLinked every second even without changes;
     * only the completed scan determines whether that cycle changed topology.
     */
    @Inject(method = "updateServer", at = @At("TAIL"), remap = false)
    private void digitalstorage$detectRebuiltTopology(CallbackInfo callbackInfo) {
        BlockEntity connector = (BlockEntity) (Object) this;
        if (connector.getLevel() == null || connector.getLevel().getGameTime() % 20 != 0) {
            return;
        }
        TomNetworkCache.rebuilt(connector, handlers);
    }
}
