package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorage;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Reads the existing Variant compound (item string, optional tag), without loader APIs. */
public final class ItemKeyCodec {
    private ItemKeyCodec() {
    }

    public static ItemKey read(NbtCompound serialized) {
        try {
            Item item = Registries.ITEM.get(new Identifier(serialized.getString("item")));
            return ItemKey.of(item, serialized.contains("tag") ? serialized.getCompound("tag") : null);
        } catch (RuntimeException exception) {
            DigitalStorage.LOGGER.debug("Tried to load an invalid item key from NBT: {}", serialized, exception);
            return ItemKey.blank();
        }
    }

    public static NbtCompound write(ItemKey key) {
        NbtCompound serialized = new NbtCompound();
        serialized.putString("item", Registries.ITEM.getId(key.item()).toString());
        NbtCompound tag = key.copyTag();
        if (tag != null) {
            serialized.put("tag", tag);
        }
        return serialized;
    }
}
