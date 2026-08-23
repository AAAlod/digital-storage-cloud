package dev.kehai.digitalstorage.tier;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

public record UpgradeIngredient(Kind kind, Identifier id, int count) {
    public UpgradeIngredient {
        if (count <= 0) {
            throw new IllegalArgumentException("Upgrade ingredient count must be positive");
        }
    }

    public static UpgradeIngredient item(Identifier id, int count) {
        return new UpgradeIngredient(Kind.ITEM, id, count);
    }

    public static UpgradeIngredient tag(Identifier id, int count) {
        return new UpgradeIngredient(Kind.TAG, id, count);
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return switch (kind) {
            case ITEM -> stack.isOf(Registries.ITEM.get(id));
            case TAG -> stack.isIn(TagKey.of(RegistryKeys.ITEM, id));
        };
    }

    public String displayName() {
        return (kind == Kind.TAG ? "#" : "") + id + " x" + count;
    }

    public enum Kind {
        ITEM,
        TAG
    }
}
