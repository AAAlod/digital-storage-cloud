package dev.kehai.digitalstorage.forge.mixin;

import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.forge.ForgeHopperState;
import dev.kehai.digitalstorage.forge.ForgeHopperTransfer;
import dev.kehai.digitalstorage.forge.ForgeHopperCustody;
import dev.kehai.digitalstorage.forge.ForgeHopperLifecycle;
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
    @Unique private java.util.UUID digitalstorage$id = java.util.UUID.randomUUID();
    @Unique private Tag digitalstorage$unreadableIdentity;
    @Unique private boolean digitalstorage$resolved;
    @Unique private boolean digitalstorage$journaled;

    @Override public ForgeHopperTransfer digitalstorage$transferState() { return digitalstorage$transfer; }
    @Unique private Tag digitalstorage$snapshot() {
        return digitalstorage$unreadableTag == null ? digitalstorage$transfer.saveState() : digitalstorage$unreadableTag.copy();
    }
    @Override public void digitalstorage$reconcile(ForgeHopperCustody custody, String dimension, net.minecraft.core.BlockPos position) {
        var binding = custody.bind(digitalstorage$id, dimension, position, digitalstorage$transfer, this::digitalstorage$snapshot);
        digitalstorage$id = binding.id();
        digitalstorage$transfer = binding.engine();
        if (binding.state() != null) digitalstorage$unreadableTag = binding.state() instanceof CompoundTag ? null : binding.state().copy();
        digitalstorage$resolved = true;
        digitalstorage$journaled = digitalstorage$transfer.blocked();
    }
    @Override public void digitalstorage$retain(ForgeHopperCustody custody, String dimension, net.minecraft.core.BlockPos position) {
        custody.retain(digitalstorage$id, dimension, position, digitalstorage$transfer, this::digitalstorage$snapshot);
    }

    @Inject(method = {"saveAdditional", "m_183515_"}, at = @At("RETURN"), remap = false)
    private void digitalstorage$save(CompoundTag tag, CallbackInfo callback) {
        ForgeHopperLifecycle.checkpoint((net.minecraft.world.level.block.entity.BlockEntity) (Object) this);
        if (digitalstorage$unreadableIdentity != null) tag.put(ID_KEY, digitalstorage$unreadableIdentity.copy());
        else tag.putUUID(ID_KEY, digitalstorage$id);
        tag.put(NBT_KEY, digitalstorage$snapshot());
    }

    @Inject(method = {"load", "m_142466_"}, at = @At("RETURN"), remap = false)
    private void digitalstorage$load(CompoundTag tag, CallbackInfo callback) {
        // Reload/data updates must not replace a live instance holding ownership
        // or an uncertain callback. Only a fresh/ready device reads incoming state.
        if (digitalstorage$transfer.blocked()) return;
        digitalstorage$resolved = false;
        digitalstorage$journaled = false;
        digitalstorage$unreadableIdentity = null;
        if (tag.hasUUID(ID_KEY)) digitalstorage$id = tag.getUUID(ID_KEY);
        else if (tag.contains(ID_KEY)) digitalstorage$unreadableIdentity = tag.get(ID_KEY).copy();
        digitalstorage$unreadableTag = null;
        if (!tag.contains(NBT_KEY)) {
            digitalstorage$transfer = new ForgeHopperTransfer();
        } else if (tag.contains(NBT_KEY, Tag.TAG_COMPOUND)) {
            digitalstorage$transfer = ForgeHopperTransfer.restore(tag.getCompound(NBT_KEY));
        } else {
            digitalstorage$unreadableTag = tag.get(NBT_KEY).copy();
            digitalstorage$transfer = ForgeHopperTransfer.restore(new CompoundTag());
        }
        if (digitalstorage$unreadableIdentity != null) digitalstorage$transfer.halt("invalid hopper custody identity");
    }

    @Inject(method = "update", at = @At("HEAD"), cancellable = true, remap = false)
    private void digitalstorage$guard(CallbackInfo callback) {
        if (!digitalstorage$resolved || (digitalstorage$transfer.blocked() && !digitalstorage$journaled)) {
            ForgeHopperLifecycle.checkpoint((net.minecraft.world.level.block.entity.BlockEntity) (Object) this);
        }
        if (digitalstorage$transfer.blocked()) callback.cancel();
    }
}
