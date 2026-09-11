package dev.kehai.digitalstorage.mixin;

import dev.kehai.digitalstorage.optimization.TomStorageIdentity;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.fabricmc.fabric.impl.transfer.item.SidedInventoryStorageImpl", remap = false)
public abstract class SidedInventoryStorageMixin implements TomStorageIdentity.Sided {
    @Unique private Object digitalstorage$identity;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void digitalstorage$captureIdentity(@Coerce Object backing, Direction direction, CallbackInfo ci) {
        digitalstorage$identity = TomStorageIdentity.sided(backing, (InventoryStorage) this, direction);
    }

    @Override public Object digitalstorage$identity() { return digitalstorage$identity; }
}
