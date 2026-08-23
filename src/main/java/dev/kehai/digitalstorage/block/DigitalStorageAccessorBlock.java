package dev.kehai.digitalstorage.block;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import java.util.List;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public final class DigitalStorageAccessorBlock extends BlockWithEntity implements BlockEntityProvider {
    public DigitalStorageAccessorBlock(Settings settings) {
        super(settings);
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new DigitalStorageAccessorBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public List<ItemStack> getDroppedStacks(BlockState state, LootContextParameterSet.Builder builder) {
        List<ItemStack> drops = super.getDroppedStacks(state, builder);
        drops.forEach(DigitalStorageAccessorBlock::removeBindingFromDrop);
        return drops;
    }

    private static void removeBindingFromDrop(ItemStack stack) {
        NbtCompound blockEntityTag = stack.getSubNbt("BlockEntityTag");
        if (blockEntityTag == null) {
            return;
        }
        blockEntityTag.remove("ControllerId");
        blockEntityTag.remove("BoundVolumeId");
        if (blockEntityTag.isEmpty()) {
            stack.removeSubNbt("BlockEntityTag");
        }
    }

    @Override
    public ActionResult onUse(
            BlockState state,
            World world,
            BlockPos pos,
            PlayerEntity player,
            Hand hand,
            BlockHitResult hit
    ) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity serverPlayer
                && world.getBlockEntity(pos) instanceof DigitalStorageAccessorBlockEntity accessor) {
            serverPlayer.openHandledScreen((NamedScreenHandlerFactory) accessor);
            return ActionResult.CONSUME;
        }
        return ActionResult.PASS;
    }
}
