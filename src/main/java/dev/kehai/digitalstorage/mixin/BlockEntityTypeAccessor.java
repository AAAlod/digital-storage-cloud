package dev.kehai.digitalstorage.mixin;

import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockEntityType.class)
public interface BlockEntityTypeAccessor {
    @Accessor("blocks")
    Set<Block> digitalstorage$getBlocks();

    @Mutable
    @Accessor("blocks")
    void digitalstorage$setBlocks(Set<Block> blocks);
}
