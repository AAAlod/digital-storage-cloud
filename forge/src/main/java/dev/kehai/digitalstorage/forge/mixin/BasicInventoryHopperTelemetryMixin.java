package dev.kehai.digitalstorage.forge.mixin;

import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.forge.tom.ForgeScannerTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve Tom's disabled path; optimized attempts use the device-owned safe transfer. */
@Mixin(value = BasicInventoryHopperBlockEntity.class, priority = 900)
public abstract class BasicInventoryHopperTelemetryMixin extends AbstractInventoryHopperBlockEntity {
    @Shadow(remap = false) private int cooldown;
    @Shadow(remap = false) private com.tom.storagemod.util.ItemPredicate filterPred;
    @Shadow(remap = false) private ItemStack filter;
    @Shadow(remap = false) private int lastItemSlot;
    @Shadow(remap = false) public abstract void setFilter(ItemStack stack);
    @Unique private boolean digitalstorage$attempt;
    @Unique private boolean digitalstorage$success;
    @Unique private int digitalstorage$failures;
    protected BasicInventoryHopperTelemetryMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    @Inject(method = "update", at = @At("HEAD"), cancellable = true, remap = false)
    private void digitalstorage$begin(CallbackInfo callback) {
        digitalstorage$success = false;
        var config = dev.kehai.digitalstorage.config.DigitalStorageConfig.get();
        if (config.optimizeTomsHopper) {
            callback.cancel();
            digitalstorage$attempt = false;
            var engine = ((dev.kehai.digitalstorage.forge.ForgeHopperState) this).digitalstorage$transferState();
            if (engine.blocked()) return;
            if (!filter.isEmpty() && filterPred == null) setFilter(filter);
            if (topNet && filterPred == null) return;
            if (cooldown > 0) { cooldown--; return; }
            if (!getBlockState().getValue(com.tom.storagemod.block.BasicInventoryHopperBlock.ENABLED)) return;
            try {
                IItemHandler source = top == null ? null : top.orElse(null);
                IItemHandler destination = bottom == null ? null : bottom.orElse(null);
                if (source != null && destination != null) {
                    int slots = source.getSlots();
                    if (lastItemSlot < 0 || lastItemSlot >= slots
                            || !digitalstorage$matches(source.getStackInSlot(lastItemSlot))) lastItemSlot = -1;
                    if (lastItemSlot == -1) {
                        for (int slot = 0; slot < slots; slot++) {
                            if (digitalstorage$matches(source.getStackInSlot(slot))
                                    && !source.extractItem(slot, 1, true).isEmpty()) { lastItemSlot = slot; break; }
                        }
                    }
                    int remaining = (int) dev.kehai.digitalstorage.hopper.HopperPolicy.batchLimit(
                            getBlockState().getBlock() instanceof dev.kehai.digitalstorage.forge.ForgeAdvancedInventoryHopperBlock, config);
                    while (lastItemSlot >= 0 && remaining > 0 && !engine.blocked()
                            && digitalstorage$matches(source.getStackInSlot(lastItemSlot))) {
                        var result = engine.move(source, lastItemSlot, destination, remaining);
                        if (result.moved() > 0) { digitalstorage$success = true; remaining -= result.moved(); }
                        if (result.moved() == 0 || result.stopped()) break;
                    }
                }
            } catch (RuntimeException failure) {
                engine.halt("hopper selection failed: " + failure.getClass().getSimpleName());
            }
            digitalstorage$failures = digitalstorage$success ? 0 : Math.min(1000, digitalstorage$failures + 1);
            cooldown = digitalstorage$success ? config.hopperSuccessCooldown : config.failureCooldown(digitalstorage$failures);
            if (digitalstorage$success || engine.blocked()) setChanged();
            if (engine.blocked()) dev.kehai.digitalstorage.forge.ForgeHopperLifecycle.checkpoint(this);
            digitalstorage$record();
            return;
        }
        digitalstorage$attempt = cooldown <= 0 && (!topNet || filterPred != null)
                && getBlockState().getValue(com.tom.storagemod.block.BasicInventoryHopperBlock.ENABLED);
    }
    @Unique private boolean digitalstorage$matches(ItemStack stack) {
        return !stack.isEmpty() && (filterPred == null || filterPred.test(stack));
    }
    @Inject(method = "setFilter", at = @At("TAIL"), remap = false)
    private void digitalstorage$wake(ItemStack stack, CallbackInfo callback) {
        digitalstorage$failures = 0;
        cooldown = 0;
        lastItemSlot = -1;
    }
    @Redirect(method = "update", remap = false, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/items/ItemHandlerHelper;insertItemStacked(Lnet/minecraftforge/items/IItemHandler;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack digitalstorage$observe(IItemHandler destination, ItemStack stack, boolean simulate) {
        boolean hadItems = !stack.isEmpty();
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(destination, stack, simulate);
        if (!simulate && hadItems && remainder.isEmpty()) digitalstorage$success = true;
        return remainder;
    }
    @Inject(method = "update", at = @At("RETURN"), remap = false)
    private void digitalstorage$finish(CallbackInfo callback) {
        if (!digitalstorage$attempt) return;
        digitalstorage$failures = digitalstorage$success ? 0 : Math.min(1000, digitalstorage$failures + 1);
        digitalstorage$record();
    }
    @Unique private void digitalstorage$record() {
        if (!(getLevel() instanceof ServerLevel world)) return;
        try {
            ForgeScannerTelemetry.get(world.getServer()).record(this, top == null ? null : top.orElse(null),
                    bottom == null ? null : bottom.orElse(null), world.getServer().getTickCount(), digitalstorage$failures);
        } catch (RuntimeException failure) {
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.debug("Forge scanner observation unavailable: {}", failure.toString());
        }
    }
}
