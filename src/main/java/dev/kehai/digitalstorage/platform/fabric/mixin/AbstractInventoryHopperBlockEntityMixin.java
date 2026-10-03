package dev.kehai.digitalstorage.platform.fabric.mixin;

import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractInventoryHopperBlockEntity.class)
public abstract class AbstractInventoryHopperBlockEntityMixin {
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
