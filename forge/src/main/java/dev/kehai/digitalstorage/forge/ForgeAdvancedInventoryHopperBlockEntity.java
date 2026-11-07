package dev.kehai.digitalstorage.forge;

import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import com.tom.storagemod.block.BasicInventoryHopperBlock;
import com.tom.storagemod.block.InventoryCableConnectorBlock;
import com.tom.storagemod.item.IItemFilter;
import com.tom.storagemod.util.BlockFace;
import com.tom.storagemod.util.ItemPredicate;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.forge.tom.ForgeScannerTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;

/** DSC-owned hopper; legacy Tom entities remain under their original mixins. */
public class ForgeAdvancedInventoryHopperBlockEntity extends AbstractInventoryHopperBlockEntity implements ForgeHopperState {
    private ItemStack filter = ItemStack.EMPTY;
    private ItemPredicate filterPred;
    private int cooldown, failures, lastItemSlot = -1;
    public ForgeAdvancedInventoryHopperBlockEntity(BlockPos pos, BlockState state) {
        super(ForgeDigitalStorage.ADVANCED_HOPPER_TYPE.get(), pos, state);
    }
    @Override public void updateServer() {
        super.updateServer();
        if (level != null && !level.isClientSide && top != null && top.isPresent()
                && bottom != null && bottom.isPresent() && !topNet && !bottomNet) update();
    }
    @Override protected void update() {
        if (!digitalstorage$resolved || digitalstorage$transfer.exported()
                || (digitalstorage$transfer.blocked() && !digitalstorage$journaled)) ForgeHopperLifecycle.checkpoint(this);
        var engine = digitalstorage$transfer;
        if (engine.blocked()) return;
        if (!filter.isEmpty() && filterPred == null) setFilter(filter);
        if (topNet && filterPred == null) return;
        if (cooldown > 0) { cooldown--; return; }
        if (!getBlockState().getValue(BasicInventoryHopperBlock.ENABLED)) return;
        var config = DigitalStorageConfig.get();
        boolean success = false;
        try {
            IItemHandler source = top == null ? null : top.orElse(null);
            IItemHandler destination = bottom == null ? null : bottom.orElse(null);
            if (source != null && destination != null) {
                int slots = source.getSlots();
                if (lastItemSlot < 0 || lastItemSlot >= slots || !matches(source.getStackInSlot(lastItemSlot))) lastItemSlot = -1;
                if (lastItemSlot == -1) for (int slot = 0; slot < slots; slot++) {
                    if (matches(source.getStackInSlot(slot)) && !source.extractItem(slot, 1, true).isEmpty()) { lastItemSlot = slot; break; }
                }
                int remaining = config.optimizeTomsHopper ? (int) dev.kehai.digitalstorage.hopper.HopperPolicy.batchLimit(true, config) : 1;
                while (lastItemSlot >= 0 && remaining > 0 && !engine.blocked() && matches(source.getStackInSlot(lastItemSlot))) {
                    var result = engine.move(source, lastItemSlot, destination, remaining);
                    if (result.moved() > 0) { success = true; remaining -= result.moved(); }
                    if (result.moved() == 0 || result.stopped()) break;
                }
            }
        } catch (RuntimeException failure) { engine.halt("hopper selection failed: " + failure.getClass().getSimpleName()); }
        failures = success ? 0 : Math.min(1000, failures + 1);
        cooldown = config.optimizeTomsHopper ? (success ? config.hopperSuccessCooldown : config.failureCooldown(failures)) : 10;
        if (success || engine.blocked()) setChanged();
        if (engine.blocked()) ForgeHopperLifecycle.checkpoint(this);
        if (getLevel() instanceof ServerLevel world) try {
            ForgeScannerTelemetry.get(world.getServer()).record(this, top == null ? null : top.orElse(null),
                    bottom == null ? null : bottom.orElse(null), world.getServer().getTickCount(), failures);
        } catch (RuntimeException failure) {
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.debug("Forge scanner observation unavailable: {}", failure.toString());
        }
    }
    private boolean matches(ItemStack stack) { return !stack.isEmpty() && (filterPred == null || filterPred.test(stack)); }
    public ItemStack getFilter() { return filter; }
    public void setFilter(ItemStack stack) {
        filter = stack;
        if (stack.isEmpty()) filterPred = null;
        else if (stack.getItem() instanceof IItemFilter item) filterPred = item.createFilter(
                BlockFace.touching(level, worldPosition, getBlockState().getValue(InventoryCableConnectorBlock.FACING)), stack);
        else filterPred = candidate -> ItemStack.isSameItemSameTags(stack, candidate);
        cooldown = failures = 0; lastItemSlot = -1; setChanged();
    }
    private ForgeHopperTransfer digitalstorage$transfer = new ForgeHopperTransfer();
    private Tag digitalstorage$unreadableTag;
    private java.util.UUID digitalstorage$id = java.util.UUID.randomUUID();
    private Tag digitalstorage$unreadableIdentity;
    private boolean digitalstorage$missingIdentity;
    private boolean digitalstorage$resolved;
    private boolean digitalstorage$journaled;

    @Override public ForgeHopperTransfer digitalstorage$transferState() { return digitalstorage$transfer; }
    private Tag digitalstorage$snapshot() {
        return digitalstorage$unreadableTag == null ? digitalstorage$transfer.saveState() : digitalstorage$unreadableTag.copy();
    }
    private Tag digitalstorage$identityEvidence() {
        var evidence = new CompoundTag();
        evidence.putBoolean("PresentAtLoad", !digitalstorage$missingIdentity);
        if (digitalstorage$unreadableIdentity != null) evidence.put("RawIdentity", digitalstorage$unreadableIdentity.copy());
        else if (!digitalstorage$missingIdentity) evidence.putUUID("RawIdentity", digitalstorage$id);
        return evidence;
    }
    @Override public void digitalstorage$reconcile(ForgeHopperCustody custody, String dimension, net.minecraft.core.BlockPos position) {
        var evidence = digitalstorage$identityEvidence();
        var binding = custody.bind(digitalstorage$id, dimension, position, digitalstorage$transfer,
                this::digitalstorage$snapshot, evidence::copy);
        digitalstorage$id = binding.id();
        digitalstorage$transfer = binding.engine();
        if (binding.state() != null && !digitalstorage$transfer.blocked()) {
            // The journal retains the original invalid identity evidence.
            // A settled lineage rotates the device's new operational identity.
            digitalstorage$unreadableIdentity = null;
            digitalstorage$missingIdentity = false;
        }
        if (binding.state() != null) digitalstorage$unreadableTag = binding.state() instanceof CompoundTag ? null : binding.state().copy();
        digitalstorage$resolved = true;
        digitalstorage$journaled = digitalstorage$transfer.blocked();
    }
    @Override public void digitalstorage$retain(ForgeHopperCustody custody, String dimension, net.minecraft.core.BlockPos position) {
        var evidence = digitalstorage$identityEvidence();
        custody.retain(digitalstorage$id, dimension, position, digitalstorage$transfer,
                this::digitalstorage$snapshot, evidence::copy);
    }

    @Override public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Filter", filter.save(new CompoundTag()));
        ForgeHopperLifecycle.checkpoint(this);
        if (digitalstorage$unreadableIdentity != null) tag.put(ID_KEY, digitalstorage$unreadableIdentity.copy());
        else tag.putUUID(ID_KEY, digitalstorage$id);
        tag.putUUID(CUSTODY_ID_KEY, digitalstorage$id);
        tag.put(NBT_KEY, digitalstorage$snapshot());
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        filter = ItemStack.of(tag.getCompound("Filter"));
        filterPred = null;
        // Reload/data updates must not replace a live instance holding ownership
        // or an uncertain callback. Only a fresh/ready device reads incoming state.
        if (digitalstorage$transfer.blocked()) return;
        digitalstorage$resolved = false;
        digitalstorage$journaled = false;
        digitalstorage$unreadableIdentity = null;
        digitalstorage$missingIdentity = !tag.contains(ID_KEY);
        if (tag.hasUUID(ID_KEY)) digitalstorage$id = tag.getUUID(ID_KEY);
        else if (tag.contains(ID_KEY)) digitalstorage$unreadableIdentity = tag.get(ID_KEY).copy();
        if (tag.hasUUID(CUSTODY_ID_KEY)) digitalstorage$id = tag.getUUID(CUSTODY_ID_KEY);
        digitalstorage$unreadableTag = null;
        if (!tag.contains(NBT_KEY)) {
            digitalstorage$transfer = new ForgeHopperTransfer();
        } else if (tag.contains(NBT_KEY, Tag.TAG_COMPOUND)) {
            digitalstorage$transfer = ForgeHopperTransfer.restore(tag.getCompound(NBT_KEY));
        } else {
            digitalstorage$unreadableTag = tag.get(NBT_KEY).copy();
            digitalstorage$transfer = ForgeHopperTransfer.restore(new CompoundTag());
        }
        if (digitalstorage$unreadableIdentity != null) digitalstorage$transfer.markUncertain("invalid hopper custody identity");
        else if (!tag.hasUUID(ID_KEY) && digitalstorage$transfer.blocked()) {
            digitalstorage$transfer.markUncertain("stopped legacy hopper has no stable custody identity");
        }
    }

}
