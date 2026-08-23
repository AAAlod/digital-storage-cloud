package dev.kehai.digitalstorage.mixin;

import com.tom.storagemod.item.IItemFilter;
import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.hopper.HopperTransferOptimizer;
import dev.kehai.digitalstorage.optimization.TomScannerTelemetry;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.item.ItemStack;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BasicInventoryHopperBlockEntity.class)
public abstract class BasicInventoryHopperBlockEntityMixin {
    @Shadow(remap = false)
    private ItemStack filter;

    @Shadow(remap = false)
    private int cooldown;

    @Unique
    private int digitalstorage$consecutiveFailures;

    @Unique
    private Storage<ItemVariant> digitalstorage$lastSource;

    @Unique
    private Storage<ItemVariant> digitalstorage$lastDestination;

    /**
     * Replaces Tom's predicate scan with a direct ItemVariant operation for a
     * plain item filter. Returning 1 is intentional: Tom's 1.7.1 treats exactly
     * 1 as its success sentinel even though this method may transfer a full batch.
     */
    @Redirect(
            method = "update",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/fabricmc/fabric/api/transfer/v1/storage/StorageUtil;move(Lnet/fabricmc/fabric/api/transfer/v1/storage/Storage;Lnet/fabricmc/fabric/api/transfer/v1/storage/Storage;Ljava/util/function/Predicate;JLnet/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext;)J"
            )
    )
    private long digitalstorage$moveBatch(
            Storage<ItemVariant> source,
            Storage<ItemVariant> destination,
            Predicate<ItemVariant> predicate,
            long ignoredOriginalLimit,
            TransactionContext transaction
    ) {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        long batchLimit = HopperTransferOptimizer.batchLimit((BlockEntity) (Object) this);
        digitalstorage$lastSource = source;
        digitalstorage$lastDestination = destination;
        if (!config.optimizeTomsHopper) {
            return StorageUtil.move(source, destination, predicate, ignoredOriginalLimit, transaction);
        }

        long moved;
        if (!filter.isEmpty() && !(filter.getItem() instanceof IItemFilter)) {
            moved = HopperTransferOptimizer.moveExact(
                    source,
                    destination,
                    ItemVariant.of(filter),
                    batchLimit,
                    transaction
            );
        } else {
            moved = HopperTransferOptimizer.moveFiltered(
                    source, destination, predicate, batchLimit, transaction
            );
        }

        return moved > 0 ? 1 : 0;
    }

    @ModifyConstant(method = "update", constant = @Constant(intValue = 10), remap = false)
    private int digitalstorage$successCooldown(int original) {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        digitalstorage$consecutiveFailures = 0;
        digitalstorage$recordScannerTelemetry();
        if (!config.optimizeTomsHopper) {
            return original;
        }
        return config.hopperSuccessCooldown;
    }

    @ModifyConstant(method = "update", constant = @Constant(intValue = 5), remap = false)
    private int digitalstorage$failureCooldown(int original) {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        digitalstorage$consecutiveFailures++;
        digitalstorage$recordScannerTelemetry();
        if (!config.optimizeTomsHopper) {
            return original;
        }
        return config.failureCooldown(digitalstorage$consecutiveFailures);
    }

    @Inject(method = "setFilter", at = @At("TAIL"), remap = false)
    private void digitalstorage$wakeAfterFilterChange(ItemStack stack, CallbackInfo callbackInfo) {
        digitalstorage$consecutiveFailures = 0;
        cooldown = 0;
    }

    @Unique
    private void digitalstorage$recordScannerTelemetry() {
        if (digitalstorage$lastSource != null && digitalstorage$lastDestination != null) {
            TomScannerTelemetry.record(
                    (BlockEntity) (Object) this,
                    digitalstorage$lastSource,
                    digitalstorage$lastDestination,
                    digitalstorage$consecutiveFailures
            );
        }
    }
}
