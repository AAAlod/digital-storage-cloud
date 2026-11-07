package dev.kehai.digitalstorage.platform.fabric.mixin;

import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractInventoryHopperBlockEntity.class)
public abstract class AbstractInventoryHopperBlockEntityMixin {
    @Shadow(remap = false) protected boolean topNet;
    @Shadow(remap = false) protected boolean bottomNet;
    @Shadow(remap = false) protected Storage<ItemVariant> top;
    @Shadow(remap = false) protected Storage<ItemVariant> bottom;
    @Shadow(remap = false) protected abstract void update();

    @Inject(method = "updateServer", at = @At("RETURN"), remap = false)
    private void digitalstorage$updateDirectInventories(CallbackInfo ci) {
        BlockEntity hopper = (BlockEntity) (Object) this;
        if (hopper.getType() != DigitalStorageMod.ADVANCED_HOPPER_BLOCK_ENTITY
                && hopper.getBlockState().is(DigitalStorageMod.ADVANCED_INVENTORY_HOPPER)
                && top != null && bottom != null && !topNet && !bottomNet) {
            update();
        }
    }

    /** Spread Tom's once-per-second connector searches across 20 server ticks. */
    @Redirect(
            method = "updateServer",
            remap = false,
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getGameTime()J",
                    remap = true
            )
    )
    private long digitalstorage$staggerConnectorScan(Level world) {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        if (!config.optimizeTomsHopper || !config.staggerConnectorScans) {
            return world.getGameTime();
        }

        BlockPos pos = ((BlockEntity) (Object) this).getBlockPos();
        return dev.kehai.digitalstorage.hopper.HopperPolicy.staggeredScanTime(world.getGameTime(), pos.hashCode());
    }
}
