package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.storage.ItemKey;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;

/** Conversion only; no Fabric type appears in the key or its persistence codec. */
public final class FabricItemKeys {
    // ItemVariant is immutable by contract. Weak keys avoid retaining transient
    // incoming variants; values own copied tags and never reference their keys.
    private static final java.util.Map<ItemVariant, ItemKey> TAGGED_KEYS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private FabricItemKeys() {
    }

    public static ItemKey fromVariant(ItemVariant variant) {
        return variant.getNbt() == null ? ItemKey.of(variant.getItem())
                : TAGGED_KEYS.computeIfAbsent(variant, key -> ItemKey.of(key.getItem(), key.getNbt()));
    }

    public static ItemVariant toVariant(ItemKey key) {
        if (key.hasAttachments()) throw new IllegalArgumentException("Fabric ItemVariant cannot represent platform stack attachments");
        return ItemVariant.of(key.item(), key.copyTag());
    }
}
