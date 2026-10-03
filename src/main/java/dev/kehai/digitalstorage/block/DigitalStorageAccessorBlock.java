package dev.kehai.digitalstorage.block;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class DigitalStorageAccessorBlock extends BaseEntityBlock implements EntityBlock {
    public DigitalStorageAccessorBlock(Properties settings) {
        super(settings);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return dev.kehai.digitalstorage.DigitalStorageContent.createAccessor(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        List<ItemStack> drops = super.getDrops(state, builder);
        drops.forEach(DigitalStorageAccessorBlock::removeBindingFromDrop);
        return drops;
    }

    private static void removeBindingFromDrop(ItemStack stack) {
        CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
        if (blockEntityTag == null) {
            return;
        }
        blockEntityTag.remove("ControllerId");
        blockEntityTag.remove("BoundVolumeId");
        if (blockEntityTag.isEmpty()) {
            stack.removeTagKey("BlockEntityTag");
        }
    }

    @Override
    public InteractionResult use(
            BlockState state,
            Level world,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (world.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && world.getBlockEntity(pos) instanceof DigitalStorageAccessorBlockEntity accessor) {
            serverPlayer.openMenu((MenuProvider) accessor);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
