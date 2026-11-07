package dev.kehai.digitalstorage.platform.fabric.hopper;

import com.tom.storagemod.block.BasicInventoryHopperBlock;
import com.tom.storagemod.block.InventoryCableConnectorBlock;
import com.tom.storagemod.item.IItemFilter;
import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import com.tom.storagemod.util.BlockFace;
import com.tom.storagemod.util.ItemPredicate;
import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.platform.fabric.tom.TomScannerTelemetry;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** DSC-owned advanced hopper. Tom's endpoint scanner is reused through inheritance. */
public final class FabricAdvancedInventoryHopperBlockEntity extends AbstractInventoryHopperBlockEntity {
    private ItemStack filter = ItemStack.EMPTY;
    private ItemPredicate filterPredicate;
    private int cooldown;
    private int failures;

    public FabricAdvancedInventoryHopperBlockEntity(BlockPos pos, BlockState state) {
        super(DigitalStorageMod.ADVANCED_HOPPER_BLOCK_ENTITY, pos, state);
    }

    @Override public void updateServer() {
        super.updateServer();
        if (level != null && !level.isClientSide && top != null && bottom != null && !topNet && !bottomNet) update();
    }

    @Override protected void update() {
        if (!filter.isEmpty() && filterPredicate == null) setFilter(filter);
        if (topNet && filterPredicate == null) return;
        if (cooldown > 0) { cooldown--; return; }
        if (!getBlockState().getValue(BasicInventoryHopperBlock.ENABLED)) return;
        var config = DigitalStorageConfig.get();
        long moved;
        try (var transaction = Transaction.openOuter()) {
            if (!config.optimizeTomsHopper) {
                moved = StorageUtil.move(top, bottom, filterPredicate == null ? variant -> true : filterPredicate, 1, transaction);
            } else if (!filter.isEmpty() && !(filter.getItem() instanceof IItemFilter)) {
                moved = HopperTransferOptimizer.moveExact(top, bottom, ItemVariant.of(filter),
                        HopperTransferOptimizer.batchLimit(this), transaction);
            } else {
                moved = HopperTransferOptimizer.moveFiltered(top, bottom,
                        filterPredicate == null ? variant -> true : filterPredicate,
                        HopperTransferOptimizer.batchLimit(this), transaction);
            }
            if (moved > 0) transaction.commit();
        }
        failures = moved > 0 ? 0 : failures + 1;
        cooldown = !config.optimizeTomsHopper ? (moved > 0 ? 10 : 5)
                : moved > 0 ? config.hopperSuccessCooldown : config.failureCooldown(failures);
        TomScannerTelemetry.record(this, top, bottom, failures);
    }

    public ItemStack getFilter() { return filter; }

    public void setFilter(ItemStack stack) {
        filter = stack;
        if (stack.isEmpty()) filterPredicate = null;
        else if (stack.getItem() instanceof IItemFilter item) {
            filterPredicate = item.createFilter(BlockFace.touching(level, worldPosition,
                    getBlockState().getValue(InventoryCableConnectorBlock.FACING)), stack);
        } else {
            ItemVariant variant = ItemVariant.of(stack);
            filterPredicate = variant::equals;
        }
        cooldown = 0;
        failures = 0;
        setChanged();
    }

    @Override public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Filter", filter.save(new CompoundTag()));
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        filter = ItemStack.of(tag.getCompound("Filter"));
        filterPredicate = null;
    }
}
