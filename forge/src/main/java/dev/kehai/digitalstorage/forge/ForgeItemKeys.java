package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.ItemKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Preserve serialized ForgeCaps outside the ordinary item tag, without putting Forge types in common. */
public final class ForgeItemKeys implements ItemKey.StackDataAdapter {
    @Override
    public int maximumStackSize(ItemKey key) {
        // Forge items may compute this from ordinary NBT or restored capabilities.
        return key.toStack(1).getMaxStackSize();
    }

    @Override
    public CompoundTag capture(ItemStack stack) {
        CompoundTag serialized = stack.serializeNBT();
        serialized.remove("id");
        serialized.remove("Count");
        serialized.remove("tag");
        return serialized.isEmpty() ? null : serialized;
    }

    @Override
    public ItemStack create(ItemKey key, int count) {
        CompoundTag serialized = key.copyAttachments();
        if (serialized == null) serialized = new CompoundTag();
        serialized.putString("id", BuiltInRegistries.ITEM.getKey(key.item()).toString());
        serialized.putByte("Count", (byte) 1);
        CompoundTag tag = key.copyTag();
        if (tag != null) serialized.put("tag", tag);
        ItemStack stack = ItemStack.of(serialized);
        stack.setCount(count);
        return stack;
    }
}
