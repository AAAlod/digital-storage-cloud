package dev.kehai.digitalstorage.storage;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/** Immutable item identity. Counts belong to the ledger, never to a key. */
public final class ItemKey {
    private static final ItemKey BLANK = new ItemKey(Items.AIR, null);
    private static final java.util.concurrent.ConcurrentMap<Item, ItemKey> TAGLESS_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Item item;
    private final CompoundTag tag;
    private final int hashCode;
    private final int tagBytes;

    private ItemKey(Item item, CompoundTag tag) {
        this.item = item;
        this.tag = tag == null ? null : tag.copy();
        this.hashCode = Objects.hash(item, this.tag);
        this.tagBytes = this.tag == null ? 0 : this.tag.sizeInBytes();
    }

    public static ItemKey blank() {
        return BLANK;
    }

    public static ItemKey of(ItemLike item) {
        return of(item, null);
    }

    public static ItemKey of(ItemLike item, CompoundTag tag) {
        Item resolved = Objects.requireNonNull(item, "item").asItem();
        if (resolved == Items.AIR) {
            return BLANK;
        }
        return tag == null ? TAGLESS_KEYS.computeIfAbsent(resolved, key -> new ItemKey(key, null))
                : new ItemKey(resolved, tag);
    }

    public static ItemKey of(ItemStack stack) {
        return of(stack.getItem(), stack.getTag());
    }

    public Item item() {
        return item;
    }

    public boolean isBlank() {
        return item == Items.AIR;
    }

    /** Never exposes the compound held by a map key. Null and empty are distinct. */
    public CompoundTag copyTag() {
        return tag == null ? null : tag.copy();
    }

    public int tagBytes() {
        return tagBytes;
    }

    public ItemStack toStack(int count) {
        if (isBlank()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item, count);
        stack.setTag(copyTag());
        return stack;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ItemKey key
                && hashCode == key.hashCode && item == key.item && Objects.equals(tag, key.tag);
    }
}
