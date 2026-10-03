package dev.kehai.digitalstorage.forge.mixin;

import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.forge.ForgeHopperState;
import dev.kehai.digitalstorage.forge.ForgeHopperTransfer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mojmap development and SRG runtime aliases target Tom's own vanilla overrides. */
@Mixin(BasicInventoryHopperBlockEntity.class)
public abstract class BasicInventoryHopperStateMixin implements ForgeHopperState {
    @Unique private ForgeHopperTransfer digitalstorage$transfer = new ForgeHopperTransfer();
    @Unique private Tag digitalstorage$unreadableTag;

    @Override public ForgeHopperTransfer digitalstorage$transferState() { return digitalstorage$transfer; }

    @Inject(method = {"saveAdditional", "m_183515_"}, at = @At("RETURN"), remap = false)
    private void digitalstorage$save(CompoundTag tag, CallbackInfo callback) {
        tag.put(NBT_KEY, digitalstorage$unreadableTag == null
                ? digitalstorage$transfer.saveState() : digitalstorage$unreadableTag.copy());
    }

    @Inject(method = {"load", "m_142466_"}, at = @At("RETURN"), remap = false)
    private void digitalstorage$load(CompoundTag tag, CallbackInfo callback) {
        // Reload/data updates must not replace a live instance holding ownership
        // or an uncertain callback. Only a fresh/ready device reads incoming state.
        if (digitalstorage$transfer.blocked()) return;
        digitalstorage$unreadableTag = null;
        if (!tag.contains(NBT_KEY)) {
            digitalstorage$transfer = new ForgeHopperTransfer();
        } else if (tag.contains(NBT_KEY, Tag.TAG_COMPOUND)) {
            digitalstorage$transfer = ForgeHopperTransfer.restore(tag.getCompound(NBT_KEY));
        } else {
            digitalstorage$unreadableTag = tag.get(NBT_KEY).copy();
            digitalstorage$transfer = ForgeHopperTransfer.restore(new CompoundTag());
        }
    }

    @Inject(method = "update", at = @At("HEAD"), cancellable = true, remap = false)
    private void digitalstorage$guard(CallbackInfo callback) {
        if (digitalstorage$transfer.blocked()) callback.cancel();
    }
}
