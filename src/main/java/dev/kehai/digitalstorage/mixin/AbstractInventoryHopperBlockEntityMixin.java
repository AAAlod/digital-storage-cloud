package dev.kehai.digitalstorage.mixin;

import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractInventoryHopperBlockEntity.class)
public abstract class AbstractInventoryHopperBlockEntityMixin {
    /** Spread Tom's once-per-second connector searches across 20 server ticks. */
    @Redirect(
            method = "updateServer",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getTime()J"
            )
    )
    private long digitalstorage$staggerConnectorScan(World world) {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        if (!config.optimizeTomsHopper || !config.staggerConnectorScans) {
            return world.getTime();
        }

        BlockPos pos = ((BlockEntity) (Object) this).getPos();
        return world.getTime() + Math.floorMod(pos.hashCode(), 20);
    }
}
