package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import dev.kehai.digitalstorage.tier.DigitalStorageTier;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public final class DigitalStorageRecord {
    private static final long ACCESS_TIME_UPDATE_INTERVAL_MILLIS = 1_000;
    public static final int DEFAULT_VARIANT_CAPACITY = 64;
    public static final int ABSOLUTE_MAX_VARIANTS = 1024;

    private static final String TIER_KEY = "Tier";
    private static final String LAST_KNOWN_VARIANT_CAPACITY_KEY = "LastKnownVariantCapacity";
    private static final String ITEMS_KEY = "Items";
    private static final String VARIANT_KEY = "Variant";
    private static final String AMOUNT_KEY = "Amount";
    private static final String CREATED_TIME_KEY = "CreatedTime";
    private static final String LAST_ACCESS_TIME_KEY = "LastAccessTime";
    private static final String ACCEPT_UNSTACKABLE_ITEMS_KEY = "AcceptUnstackableItems";
    private final Runnable dirtyCallback;
    private final VolumeLedger storage;
    private ResourceLocation tierId;
    private int lastKnownVariantCapacity;
    private boolean acceptUnstackableItems;
    private long policyVersion;
    private final long createdTime;
    private long lastAccessTime;

    private DigitalStorageRecord(
            UUID volumeId,
            ResourceLocation tierId,
            int lastKnownVariantCapacity,
            boolean acceptUnstackableItems,
            Runnable dirtyCallback,
            long createdTime,
            long lastAccessTime
    ) {
        this.dirtyCallback = dirtyCallback;
        this.tierId = tierId;
        this.lastKnownVariantCapacity = Math.max(1, lastKnownVariantCapacity);
        this.acceptUnstackableItems = acceptUnstackableItems;
        this.createdTime = createdTime;
        this.lastAccessTime = lastAccessTime;
        this.storage = new VolumeLedger(
                volumeId,
                this::onStorageMutationCommitted,
                this::variantCapacity,
                () -> DigitalStorageConfig.get().maxVolumeVariantNbtBytes,
                variant -> ItemSecurityPolicy.allowsInsert(variant, acceptsUnstackableItems()),
                ItemSecurityPolicy::allowsNewVariant
        );
    }

    public static DigitalStorageRecord createNew(Runnable dirtyCallback) {
        return createNew(null, dirtyCallback);
    }

    static DigitalStorageRecord createNew(UUID volumeId, Runnable dirtyCallback) {
        long now = System.currentTimeMillis();
        DigitalStorageTier firstTier = DigitalStorageTierRegistry.INSTANCE.first();
        return new DigitalStorageRecord(
                volumeId,
                firstTier.id(),
                firstTier.variantCapacity(),
                false,
                dirtyCallback,
                now,
                now
        );
    }

    static DigitalStorageRecord fromNbt(CompoundTag nbt, Runnable dirtyCallback) {
        return fromNbt(null, nbt, dirtyCallback);
    }

    static DigitalStorageRecord fromNbt(UUID volumeId, CompoundTag nbt, Runnable dirtyCallback) {
        DigitalStorageTierRegistry tiers = DigitalStorageTierRegistry.INSTANCE;
        ListTag items = nbt.getList(ITEMS_KEY, Tag.TAG_COMPOUND);
        boolean hasStoredTier = nbt.contains(TIER_KEY, Tag.TAG_STRING);
        ResourceLocation storedTierId = hasStoredTier
                ? ResourceLocation.tryParse(nbt.getString(TIER_KEY))
                : null;
        boolean migrated = storedTierId == null || tiers.find(storedTierId).isEmpty();
        int preservedCapacity = nbt.contains(LAST_KNOWN_VARIANT_CAPACITY_KEY, Tag.TAG_INT)
                ? Math.max(1, nbt.getInt(LAST_KNOWN_VARIANT_CAPACITY_KEY))
                : migratedCapacity(items);

        if (migrated) {
            if (hasStoredTier) {
                DigitalStorageTier fallback = tiers.tierAtLeastCapacity(preservedCapacity);
                DigitalStorage.LOGGER.warn(
                        "Stored tier {} is unavailable; migrating to {} to preserve at least {} variants",
                        nbt.getString(TIER_KEY),
                        fallback.id(),
                        preservedCapacity
                );
                storedTierId = fallback.id();
            } else {
                storedTierId = tiers.tierAtLeastCapacity(preservedCapacity).id();
            }
        }

        DigitalStorageTier resolvedTier = tiers.find(storedTierId).orElseThrow();
        int lastKnownVariantCapacity = Math.max(preservedCapacity, resolvedTier.variantCapacity());

        long now = System.currentTimeMillis();
        boolean missingMetadata = !nbt.contains(CREATED_TIME_KEY, Tag.TAG_LONG)
                || !nbt.contains(LAST_ACCESS_TIME_KEY, Tag.TAG_LONG);
        long createdTime = nbt.contains(CREATED_TIME_KEY, Tag.TAG_LONG)
                ? nbt.getLong(CREATED_TIME_KEY)
                : now;
        long lastAccessTime = nbt.contains(LAST_ACCESS_TIME_KEY, Tag.TAG_LONG)
                ? nbt.getLong(LAST_ACCESS_TIME_KEY)
                : createdTime;
        boolean missingUnstackablePolicy = !nbt.contains(ACCEPT_UNSTACKABLE_ITEMS_KEY, Tag.TAG_BYTE);
        boolean acceptUnstackableItems = missingUnstackablePolicy
                ? DigitalStorageConfig.get().allowUnstackableItems
                : nbt.getBoolean(ACCEPT_UNSTACKABLE_ITEMS_KEY);
        DigitalStorageRecord record = new DigitalStorageRecord(
                volumeId,
                storedTierId,
                lastKnownVariantCapacity,
                acceptUnstackableItems,
                dirtyCallback,
                createdTime,
                Math.max(createdTime, lastAccessTime)
        );
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            CompoundTag itemNbt = items.getCompound(itemIndex);
            CompoundTag serializedVariant = itemNbt.getCompound(VARIANT_KEY);
            ItemKey variant = ItemKeyCodec.read(serializedVariant);
            long amount = itemNbt.getLong(AMOUNT_KEY);
            record.storage.load(variant, amount, serializedVariant);
        }

        if (migrated || missingMetadata || missingUnstackablePolicy) {
            dirtyCallback.run();
        }
        return record;
    }

    public VolumeLedger storage() {
        return storage;
    }

    public ResourceLocation tierId() {
        return tier().id();
    }

    public DigitalStorageTier tier() {
        DigitalStorageTierRegistry tiers = DigitalStorageTierRegistry.INSTANCE;
        DigitalStorageTier resolved = tiers.find(tierId).orElse(null);
        if (resolved != null) {
            return resolved;
        }

        DigitalStorageTier fallback = tiers.tierAtLeastCapacity(
                Math.max(lastKnownVariantCapacity, storage.variantCount())
        );
        DigitalStorage.LOGGER.warn(
                "Storage tier {} no longer exists; falling back to {} to preserve at least {} variants",
                tierId,
                fallback.id(),
                lastKnownVariantCapacity
        );
        tierId = fallback.id();
        lastKnownVariantCapacity = Math.max(lastKnownVariantCapacity, fallback.variantCapacity());
        dirtyCallback.run();
        return fallback;
    }

    public int variantCapacity() {
        return tier().variantCapacity();
    }

    public long createdTime() {
        return createdTime;
    }

    public long lastAccessTime() {
        return lastAccessTime;
    }

    public boolean acceptsUnstackableItems() {
        return acceptUnstackableItems;
    }

    public boolean setAcceptUnstackableItems(boolean value) {
        if (acceptUnstackableItems == value) {
            return false;
        }
        acceptUnstackableItems = value;
        policyVersion++;
        dirtyCallback.run();
        return true;
    }

    public long policyVersion() {
        return policyVersion;
    }

    public boolean canInsert(ItemKey variant) {
        return ItemSecurityPolicy.canInsert(variant, acceptUnstackableItems)
                && (storage.amountOf(variant) > 0 || ItemSecurityPolicy.canCreateVariant(variant));
    }

    public boolean setTier(ResourceLocation requestedTierId) {
        if (DigitalStorageTierRegistry.INSTANCE.find(requestedTierId).isEmpty() || tierId.equals(requestedTierId)) {
            return false;
        }

        tierId = requestedTierId;
        lastKnownVariantCapacity = DigitalStorageTierRegistry.INSTANCE.find(requestedTierId)
                .orElseThrow()
                .variantCapacity();
        dirtyCallback.run();
        return true;
    }

    public boolean advanceTier(ResourceLocation expectedCurrentTierId, ResourceLocation requestedTierId) {
        if (!tierId().equals(expectedCurrentTierId)) {
            return false;
        }
        return setTier(requestedTierId);
    }

    void writeNbt(CompoundTag nbt) {
        snapshot().writeNbt(nbt);
    }

    Snapshot snapshot() {
        return snapshot(storage.snapshotEntries());
    }

    Snapshot snapshot(List<VolumeLedger.StoredEntrySnapshot> items) {
        return new Snapshot(
                tierId(),
                lastKnownVariantCapacity,
                acceptUnstackableItems,
                createdTime,
                lastAccessTime,
                items
        );
    }

    private void onStorageMutationCommitted() {
        long now = System.currentTimeMillis();
        if (now - lastAccessTime >= ACCESS_TIME_UPDATE_INTERVAL_MILLIS) {
            lastAccessTime = now;
        }
        dirtyCallback.run();
    }

    record Snapshot(
            ResourceLocation tierId,
            int lastKnownVariantCapacity,
            boolean acceptUnstackableItems,
            long createdTime,
            long lastAccessTime,
            List<VolumeLedger.StoredEntrySnapshot> items
    ) {
        Snapshot {
            items = List.copyOf(items);
        }

        void writeNbt(CompoundTag nbt) {
            nbt.putString(TIER_KEY, tierId.toString());
            nbt.putInt(LAST_KNOWN_VARIANT_CAPACITY_KEY, lastKnownVariantCapacity);
            nbt.putBoolean(ACCEPT_UNSTACKABLE_ITEMS_KEY, acceptUnstackableItems);
            nbt.putLong(CREATED_TIME_KEY, createdTime);
            nbt.putLong(LAST_ACCESS_TIME_KEY, lastAccessTime);

            ListTag items = new ListTag();
            for (VolumeLedger.StoredEntrySnapshot entry : this.items) {
                CompoundTag itemNbt = new CompoundTag();
                itemNbt.put(VARIANT_KEY, entry.serializedVariant());
                itemNbt.putLong(AMOUNT_KEY, entry.amount());
                items.add(itemNbt);
            }
            nbt.put(ITEMS_KEY, items);
        }
    }

    private static int migratedCapacity(ListTag items) {
        Set<ItemKey> variants = new HashSet<>();
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            CompoundTag itemNbt = items.getCompound(itemIndex);
            ItemKey variant = ItemKeyCodec.read(itemNbt.getCompound(VARIANT_KEY));
            if (!variant.isBlank() && itemNbt.getLong(AMOUNT_KEY) > 0) {
                variants.add(variant);
            }
        }
        return Math.max(DEFAULT_VARIANT_CAPACITY, variants.size());
    }
}
