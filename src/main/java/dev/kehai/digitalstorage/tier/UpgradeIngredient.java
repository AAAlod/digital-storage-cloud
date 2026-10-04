package dev.kehai.digitalstorage.tier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

public record UpgradeIngredient(Kind kind, ResourceLocation id, int count) {
    public UpgradeIngredient {
        if (count <= 0) {
            throw new IllegalArgumentException("Upgrade ingredient count must be positive");
        }
    }

    public static UpgradeIngredient item(ResourceLocation id, int count) {
        return new UpgradeIngredient(Kind.ITEM, id, count);
    }

    public static UpgradeIngredient tag(ResourceLocation id, int count) {
        return new UpgradeIngredient(Kind.TAG, id, count);
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return switch (kind) {
            case ITEM -> stack.is(BuiltInRegistries.ITEM.get(id));
            case TAG -> stack.is(TagKey.create(Registries.ITEM, id));
        };
    }

    public String displayName() {
        return (kind == Kind.TAG ? "#" : "") + id + " x" + count;
    }

    public Component displayComponent() {
        Component name = kind == Kind.ITEM
                ? BuiltInRegistries.ITEM.getOptional(id).<Component>map(item -> item.getDescription())
                        .orElse(Component.literal(id.toString()))
                : Component.literal("#" + id);
        return Component.translatable("screen.digitalstorage.cost_entry", name, count);
    }

    public enum Kind {
        ITEM,
        TAG
    }
}
