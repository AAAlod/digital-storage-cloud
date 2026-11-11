package dev.kehai.digitalstorage.platform.fabric.mixin;

import dev.kehai.digitalstorage.platform.fabric.hopper.BlockStorageGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelBlockStorageInvalidationMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void digitalstorage$invalidateBlockStorage(BlockPos pos, BlockState state, int flags, int depth,
                                                        CallbackInfoReturnable<Boolean> callback) {
        Level world = (Level) (Object) this;
        if (!world.isClientSide && world.hasChunkAt(pos)) BlockStorageGuard.blockChanging(world, pos, world.getBlockState(pos).getBlock());
    }
}
