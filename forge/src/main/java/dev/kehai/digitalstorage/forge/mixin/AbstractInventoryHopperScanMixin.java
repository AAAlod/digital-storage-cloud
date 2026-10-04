package dev.kehai.digitalstorage.forge.mixin;

import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.hopper.HopperPolicy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exactly one Mojmap/SRG hook must spread the original connector search clock. */
@Mixin(AbstractInventoryHopperBlockEntity.class)
public abstract class AbstractInventoryHopperScanMixin {
    @Shadow(remap = false) protected net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler> top;
    @Shadow(remap = false) protected net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler> bottom;
    @Shadow(remap = false) protected boolean topNet;
    @Shadow(remap = false) protected boolean bottomNet;
    @Unique private net.minecraft.core.Direction digitalstorage$previousFacing;

    @Inject(method = "updateServer", at = @At("HEAD"), remap = false)
    private void digitalstorage$invalidateRotatedEndpoints(CallbackInfo callback) {
        var entity = (BlockEntity) (Object) this;
        var world = entity.getLevel();
        if (world == null || world.isClientSide) return;
        var state = world.getBlockState(entity.getBlockPos());
        var property = com.tom.storagemod.block.InventoryCableConnectorBlock.FACING;
        if (!state.hasProperty(property)) return;
        var facing = state.getValue(property);
        if (digitalstorage$previousFacing != null && facing != digitalstorage$previousFacing) {
            // Tom only reacquires a physical endpoint when its old handle expires.
            // Rotation changes both endpoints even while those old handles live.
            top = null; bottom = null; topNet = false; bottomNet = false;
        }
        digitalstorage$previousFacing = facing;
    }
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
