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

/** Observe original attempts/results without changing Tom transfer arguments or cooldowns. */
@Mixin(BasicInventoryHopperBlockEntity.class)
public abstract class BasicInventoryHopperTelemetryMixin extends AbstractInventoryHopperBlockEntity {
    @Shadow(remap = false) private int cooldown;
    @Shadow(remap = false) private com.tom.storagemod.util.ItemPredicate filterPred;
    @Unique private boolean digitalstorage$attempt;
    @Unique private boolean digitalstorage$success;
    @Unique private int digitalstorage$failures;
    protected BasicInventoryHopperTelemetryMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    @Inject(method = "update", at = @At("HEAD"), remap = false)
    private void digitalstorage$begin(CallbackInfo callback) {
        digitalstorage$success = false;
        digitalstorage$attempt = cooldown <= 0 && (!topNet || filterPred != null)
                && getBlockState().getValue(com.tom.storagemod.block.BasicInventoryHopperBlock.ENABLED);
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
        if (!digitalstorage$attempt || !(getLevel() instanceof ServerLevel world)) return;
        digitalstorage$failures = digitalstorage$success ? 0 : Math.min(1000, digitalstorage$failures + 1);
        try {
            ForgeScannerTelemetry.get(world.getServer()).record(this, top == null ? null : top.orElse(null),
                    bottom == null ? null : bottom.orElse(null), world.getServer().getTickCount(), digitalstorage$failures);
        } catch (RuntimeException failure) {
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.debug("Forge scanner observation unavailable: {}", failure.toString());
        }
    }
}
