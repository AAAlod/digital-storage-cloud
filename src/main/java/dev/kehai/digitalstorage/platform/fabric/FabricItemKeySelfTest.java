package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.ItemKeyCodec;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;

public final class FabricItemKeySelfTest {
    private FabricItemKeySelfTest() {
    }

    public static void run() {
        CompoundTag nested = new CompoundTag();
        nested.putInt("Damage", 23);
        nested.putString("Unknown", "preserve 中文");
        ItemVariant[] variants = {
                ItemVariant.blank(), ItemVariant.of(Items.PAPER), ItemVariant.of(Items.PAPER, new CompoundTag()),
                ItemVariant.of(Items.PAPER, nested), ItemVariant.of(Items.IRON_PICKAXE, nested)
        };
        for (ItemVariant legacy : variants) {
            ItemKey key = FabricItemKeys.fromVariant(legacy);
            if (!legacy.equals(FabricItemKeys.toVariant(key)) || !legacy.toNbt().equals(ItemKeyCodec.write(key))
                    || !key.equals(ItemKeyCodec.read(legacy.toNbt()))) {
                throw new IllegalStateException("ItemKey diverged from the Fabric identity or legacy NBT codec");
            }
        }
        for (ItemVariant first : variants) {
            for (ItemVariant second : variants) {
                if (first.equals(second) != FabricItemKeys.fromVariant(first).equals(FabricItemKeys.fromVariant(second))) {
                    throw new IllegalStateException("Fabric item identity equivalence changed");
                }
            }
        }
        ItemKey key = FabricItemKeys.fromVariant(variants[3]);
        FabricItemKeys.toVariant(key).getNbt().putString("Unknown", "mutated platform data");
        if (!key.equals(ItemKeyCodec.read(variants[3].toNbt()))) {
            throw new IllegalStateException("Fabric adapter exposed the item key compound");
        }
        boolean rejected = false;
        try {
            FabricItemKeys.toVariant(ItemKey.of(Items.PAPER, null, nested));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) throw new IllegalStateException("Fabric silently discarded unsupported platform attachments");
    }
}
