package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** Legacy item/tag Variant encoding with optional opaque stack attachments; no loader APIs. */
public final class ItemKeyCodec {
    private ItemKeyCodec() {
    }

    public static ItemKey read(CompoundTag serialized) {
        ItemKey key = tryRead(serialized);
        if (key != null) return key;
        DigitalStorage.LOGGER.debug("Tried to load an invalid item key from NBT: {}", serialized);
        return ItemKey.blank();
    }

    public static ItemKey tryRead(CompoundTag serialized) {
        if (serialized == null || !serialized.contains("item", Tag.TAG_STRING)) return null;
        ResourceLocation id = ResourceLocation.tryParse(serialized.getString("item"));
        if (id == null) return null;
        if (!BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == net.minecraft.world.item.Items.AIR) return ItemKey.blank();
        return ItemKey.of(item, serialized.contains("tag") ? serialized.getCompound("tag") : null,
                serialized.contains("attachments") ? serialized.getCompound("attachments") : null);
    }

    public static CompoundTag write(ItemKey key) {
        CompoundTag serialized = new CompoundTag();
        serialized.putString("item", BuiltInRegistries.ITEM.getKey(key.item()).toString());
        CompoundTag tag = key.copyTag();
        if (tag != null) {
            serialized.put("tag", tag);
        }
        CompoundTag attachments = key.copyAttachments();
        if (attachments != null) serialized.put("attachments", attachments);
        return serialized;
    }
}
