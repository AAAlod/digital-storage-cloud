package dev.kehai.digitalstorage.forge.mixin;

import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.hopper.HopperPolicy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Exactly one Mojmap/SRG hook must spread the original connector search clock. */
@Mixin(AbstractInventoryHopperBlockEntity.class)
public abstract class AbstractInventoryHopperScanMixin {
    @Group(name = "dscScanClock", min = 1, max = 1)
    @Redirect(method = "updateServer", remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getGameTime()J"))
    private long digitalstorage$mappedClock(Level world) { return digitalstorage$clock(world); }

    @Group(name = "dscScanClock", min = 1, max = 1)
    @Redirect(method = "updateServer", remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;m_46467_()J"))
    private long digitalstorage$runtimeClock(Level world) { return digitalstorage$clock(world); }

    @org.spongepowered.asm.mixin.Unique
    private long digitalstorage$clock(Level world) {
        var config = DigitalStorageConfig.get();
        long tick = world.getGameTime();
        return config.optimizeTomsHopper && config.staggerConnectorScans
                ? HopperPolicy.staggeredScanTime(tick, ((BlockEntity) (Object) this).getBlockPos().hashCode()) : tick;
    }
}
