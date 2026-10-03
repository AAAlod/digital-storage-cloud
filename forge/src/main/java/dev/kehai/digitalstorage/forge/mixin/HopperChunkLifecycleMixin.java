package dev.kehai.digitalstorage.forge.mixin;

import dev.kehai.digitalstorage.forge.ForgeHopperLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact Mojmap/SRG aliases from the fixed 1.20.1 local mapping baseline. */
@Mixin(LevelChunk.class)
public abstract class HopperChunkLifecycleMixin {
    @Inject(method = {"setBlockEntity", "m_142169_"}, at = @At("HEAD"), remap = false)
    private void digitalstorage$replace(BlockEntity incoming, CallbackInfo callback) {
        var chunk = (LevelChunk) (Object) this;
        var previous = chunk.getBlockEntities().get(incoming.getBlockPos());
        if (previous != null && previous != incoming) ForgeHopperLifecycle.removing(chunk, previous);
    }
    @Inject(method = {"setBlockEntity", "m_142169_"}, at = @At("RETURN"), remap = false)
    private void digitalstorage$attach(BlockEntity incoming, CallbackInfo callback) {
        var chunk = (LevelChunk) (Object) this;
        if (chunk.getBlockEntities().get(incoming.getBlockPos()) == incoming) ForgeHopperLifecycle.attached(chunk, incoming);
    }
    @Inject(method = {"removeBlockEntity", "m_8114_"}, at = @At("HEAD"), remap = false)
    private void digitalstorage$remove(BlockPos position, CallbackInfo callback) {
        var chunk = (LevelChunk) (Object) this;
        ForgeHopperLifecycle.removing(chunk, chunk.getBlockEntities().get(position));
    }
    @Inject(method = {"clearAllBlockEntities", "m_187957_"}, at = @At("HEAD"), remap = false)
    private void digitalstorage$unload(CallbackInfo callback) {
        var chunk = (LevelChunk) (Object) this;
        for (var entity : chunk.getBlockEntities().values()) ForgeHopperLifecycle.removing(chunk, entity);
    }
}
