package dev.kehai.digitalstorage.block;

import com.tom.storagemod.block.BasicInventoryHopperBlock;
import com.tom.storagemod.item.IItemFilter;
import dev.kehai.digitalstorage.platform.fabric.hopper.FabricAdvancedInventoryHopperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A separately registered Tom inventory hopper endpoint. Its transfer tier is
 * selected by the shared hopper optimizer from the cached block state.
 */
public final class AdvancedInventoryHopperBlock extends BasicInventoryHopperBlock {
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FabricAdvancedInventoryHopperBlockEntity(pos, state);
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof FabricAdvancedInventoryHopperBlockEntity hopper)) {
            return super.use(state, level, pos, player, hand, hit);
        }
        if (!level.isClientSide) {
            ItemStack held = player.getItemInHand(hand);
            if (!held.isEmpty()) {
                ItemStack previous = hopper.getFilter();
                if (!previous.isEmpty() && previous.getItem() instanceof IItemFilter) popResource(level, pos, previous);
                hopper.setFilter(held.copy());
                if (held.getItem() instanceof IItemFilter) player.setItemInHand(hand, ItemStack.EMPTY);
                player.displayClientMessage(Component.translatable("tooltip.toms_storage.filter_item",
                        hopper.getFilter().getHoverName()), true);
            } else if (player.isShiftKeyDown()) {
                ItemStack previous = hopper.getFilter();
                if (!previous.isEmpty() && previous.getItem() instanceof IItemFilter) player.setItemInHand(hand, previous);
                hopper.setFilter(ItemStack.EMPTY);
                player.displayClientMessage(Component.translatable("tooltip.toms_storage.filter_item",
                        Component.translatable("tooltip.toms_storage.empty")), true);
            } else {
                ItemStack filter = hopper.getFilter();
                player.displayClientMessage(Component.translatable("tooltip.toms_storage.filter_item",
                        filter.isEmpty() ? Component.translatable("tooltip.toms_storage.empty") : filter.getHoverName()), true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof FabricAdvancedInventoryHopperBlockEntity hopper) {
            ItemStack filter = hopper.getFilter();
            if (!filter.isEmpty() && filter.getItem() instanceof IItemFilter) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), filter);
            }
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
