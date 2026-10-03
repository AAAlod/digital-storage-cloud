package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** Reads the existing Variant compound (item string, optional tag), without loader APIs. */
public final class ItemKeyCodec {
    private ItemKeyCodec() {
    }

    public static ItemKey read(CompoundTag serialized) {
        try {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(serialized.getString("item")));
            return ItemKey.of(item, serialized.contains("tag") ? serialized.getCompound("tag") : null);
        } catch (RuntimeException exception) {
            DigitalStorage.LOGGER.debug("Tried to load an invalid item key from NBT: {}", serialized, exception);
            return ItemKey.blank();
        }
    }

    public static CompoundTag write(ItemKey key) {
        CompoundTag serialized = new CompoundTag();
        serialized.putString("item", BuiltInRegistries.ITEM.getKey(key.item()).toString());
        CompoundTag tag = key.copyTag();
        if (tag != null) {
            serialized.put("tag", tag);
        }
        return serialized;
    }
}
