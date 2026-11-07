package dev.kehai.digitalstorage.forge;

import com.tom.storagemod.block.BasicInventoryHopperBlock;
import com.tom.storagemod.item.IItemFilter;
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

public final class ForgeAdvancedInventoryHopperBlock extends BasicInventoryHopperBlock {
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ForgeAdvancedInventoryHopperBlockEntity(pos, state);
    }
    @Override public InteractionResult use(BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        var entity = world.getBlockEntity(pos);
        if (!(entity instanceof ForgeAdvancedInventoryHopperBlockEntity hopper))
            return super.use(state, world, pos, player, hand, hit);
        if (!world.isClientSide) {
            ItemStack held = player.getItemInHand(hand);
            if (!held.isEmpty()) {
                if (!hopper.getFilter().isEmpty() && hopper.getFilter().getItem() instanceof IItemFilter)
                    popResource(world, pos, hopper.getFilter());
                hopper.setFilter(held.copy());
                if (held.getItem() instanceof IItemFilter) player.setItemInHand(hand, ItemStack.EMPTY);
            } else if (player.isShiftKeyDown()) {
                if (!hopper.getFilter().isEmpty() && hopper.getFilter().getItem() instanceof IItemFilter)
                    player.setItemInHand(hand, hopper.getFilter());
                hopper.setFilter(ItemStack.EMPTY);
            }
            var filter = hopper.getFilter();
            player.displayClientMessage(Component.translatable("tooltip.toms_storage.filter_item",
                    filter.isEmpty() ? Component.translatable("tooltip.toms_storage.empty") : filter.getHoverName()), true);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public void onRemove(BlockState state, Level world, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && world.getBlockEntity(pos) instanceof ForgeAdvancedInventoryHopperBlockEntity hopper
                && !hopper.getFilter().isEmpty() && hopper.getFilter().getItem() instanceof IItemFilter)
            Containers.dropItemStack(world, pos.getX(), pos.getY(), pos.getZ(), hopper.getFilter());
        super.onRemove(state, world, pos, next, moving);
    }
}
