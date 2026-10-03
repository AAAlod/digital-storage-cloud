package dev.kehai.digitalstorage.storage;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/** Immutable item identity. Counts belong to the ledger, never to a key. */
public final class ItemKey {
    private static final ItemKey BLANK = new ItemKey(Items.AIR, null, null);
    private static final java.util.concurrent.ConcurrentMap<Item, ItemKey> TAGLESS_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Item item;
    private final CompoundTag tag;
    private final CompoundTag attachments;
    private final int hashCode;
    private final int tagBytes;
    private static volatile StackDataAdapter stackDataAdapter = new StackDataAdapter() {
        public CompoundTag capture(ItemStack stack) { return null; }
        public ItemStack create(ItemKey key, int count) {
            if (key.attachments != null) throw new IllegalStateException("No adapter for this item's platform stack data");
            ItemStack stack = new ItemStack(key.item, count);
            stack.setTag(key.copyTag());
            return stack;
        }
    };

    private ItemKey(Item item, CompoundTag tag, CompoundTag attachments) {
        this.item = item;
        this.tag = tag == null ? null : tag.copy();
        this.attachments = attachments == null ? null : attachments.copy();
        this.hashCode = this.attachments == null ? Objects.hash(item, this.tag) : Objects.hash(item, this.tag, this.attachments);
        this.tagBytes = Math.addExact(this.tag == null ? 0 : this.tag.sizeInBytes(),
                this.attachments == null ? 0 : this.attachments.sizeInBytes());
    }

    public static ItemKey blank() {
        return BLANK;
    }

    public static ItemKey of(ItemLike item) {
        return of(item, null);
    }

    public static ItemKey of(ItemLike item, CompoundTag tag) {
        return of(item, tag, null);
    }

    /** Optional loader-owned stack data is opaque to shared business and participates in identity. */
    public static ItemKey of(ItemLike item, CompoundTag tag, CompoundTag attachments) {
        Item resolved = Objects.requireNonNull(item, "item").asItem();
        if (resolved == Items.AIR) {
            return BLANK;
        }
        return tag == null && attachments == null
                ? TAGLESS_KEYS.computeIfAbsent(resolved, key -> new ItemKey(key, null, null))
                : new ItemKey(resolved, tag, attachments);
    }

    public static ItemKey of(ItemStack stack) {
        return of(stack.getItem(), stack.getTag(), stackDataAdapter.capture(stack));
    }

    /** Install before loading storage or constructing menus; this contract contains no loader types. */
    public static void installStackDataAdapter(StackDataAdapter adapter) {
        stackDataAdapter = Objects.requireNonNull(adapter);
    }

    public interface StackDataAdapter {
        CompoundTag capture(ItemStack stack);
        ItemStack create(ItemKey key, int count);
        default int maximumStackSize(ItemKey key) { return key.item.getMaxStackSize(); }
    }

    public Item item() {
        return item;
    }

    public int maximumStackSize() { return stackDataAdapter.maximumStackSize(this); }

    public boolean isBlank() {
        return item == Items.AIR;
    }

    /** Never exposes the compound held by a map key. Null and empty are distinct. */
    public CompoundTag copyTag() {
        return tag == null ? null : tag.copy();
    }

    public int tagBytes() {
        // Both ordinary item NBT and platform payload must obey the same safety cap.
        return tagBytes;
    }

    public CompoundTag copyAttachments() { return attachments == null ? null : attachments.copy(); }
    public boolean hasAttachments() { return attachments != null; }

    public ItemStack toStack(int count) {
        if (isBlank()) {
            return ItemStack.EMPTY;
        }
        return stackDataAdapter.create(this, count);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ItemKey key
                && hashCode == key.hashCode && item == key.item && Objects.equals(tag, key.tag)
                && Objects.equals(attachments, key.attachments);
    }
}
