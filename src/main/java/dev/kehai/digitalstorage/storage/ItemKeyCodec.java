package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** Legacy item/tag Variant encoding with optional opaque stack attachments; no loader APIs. */
public final class ItemKeyCodec {
    private ItemKeyCodec() {
    }

    public static ItemKey read(CompoundTag serialized) {
        try {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(serialized.getString("item")));
            return ItemKey.of(item, serialized.contains("tag") ? serialized.getCompound("tag") : null,
                    serialized.contains("attachments") ? serialized.getCompound("attachments") : null);
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
        CompoundTag attachments = key.copyAttachments();
        if (attachments != null) serialized.put("attachments", attachments);
        return serialized;
    }
}
