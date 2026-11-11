package dev.kehai.digitalstorage.platform.fabric.mixin;

import com.tom.storagemod.tile.InventoryConnectorBlockEntity;
import dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkCache;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import dev.kehai.digitalstorage.platform.fabric.hopper.BlockStorageGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryConnectorBlockEntity.class)
public abstract class InventoryConnectorBlockEntityMixin {
    @Redirect(method = "updateServer", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/fabricmc/fabric/api/lookup/v1/block/BlockApiLookup;find(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object digitalstorage$guardNetworkStorage(BlockApiLookup<Storage<ItemVariant>, Direction> lookup,
            Level level, BlockPos pos, BlockState state, BlockEntity entity, Object side) {
        Storage<ItemVariant> storage = lookup.find(level, pos, state, entity, (Direction) side);
        return BlockStorageGuard.wrap(level, pos, entity, storage);
    }
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
        TomNetworkCache.rebuilt(connector, ((InventoryConnectorBlockEntity) (Object) this).getInventory());
    }
}
