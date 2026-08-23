package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.tier.DigitalStorageTier;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;

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
    private final Runnable dirtyCallback;
    private final DigitalItemStorage storage;
    private Identifier tierId;
    private int lastKnownVariantCapacity;
    private final long createdTime;
    private long lastAccessTime;

    private DigitalStorageRecord(
            UUID volumeId,
            Identifier tierId,
            int lastKnownVariantCapacity,
            Runnable dirtyCallback,
            long createdTime,
            long lastAccessTime
    ) {
        this.dirtyCallback = dirtyCallback;
        this.tierId = tierId;
        this.lastKnownVariantCapacity = Math.max(1, lastKnownVariantCapacity);
        this.createdTime = createdTime;
        this.lastAccessTime = lastAccessTime;
        this.storage = new DigitalItemStorage(
                volumeId,
                this::onStorageMutationCommitted,
                this::variantCapacity,
                () -> DigitalStorageConfig.get().maxVolumeVariantNbtBytes
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
                dirtyCallback,
                now,
                now
        );
    }

    static DigitalStorageRecord fromNbt(NbtCompound nbt, Runnable dirtyCallback) {
        return fromNbt(null, nbt, dirtyCallback);
    }

    static DigitalStorageRecord fromNbt(UUID volumeId, NbtCompound nbt, Runnable dirtyCallback) {
        DigitalStorageTierRegistry tiers = DigitalStorageTierRegistry.INSTANCE;
        NbtList items = nbt.getList(ITEMS_KEY, NbtElement.COMPOUND_TYPE);
        boolean hasStoredTier = nbt.contains(TIER_KEY, NbtElement.STRING_TYPE);
        Identifier storedTierId = hasStoredTier
                ? Identifier.tryParse(nbt.getString(TIER_KEY))
                : null;
        boolean migrated = storedTierId == null || tiers.find(storedTierId).isEmpty();
        int preservedCapacity = nbt.contains(LAST_KNOWN_VARIANT_CAPACITY_KEY, NbtElement.INT_TYPE)
                ? Math.max(1, nbt.getInt(LAST_KNOWN_VARIANT_CAPACITY_KEY))
                : migratedCapacity(items);

        if (migrated) {
            if (hasStoredTier) {
                DigitalStorageTier fallback = tiers.tierAtLeastCapacity(preservedCapacity);
                DigitalStorageMod.LOGGER.warn(
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
        boolean missingMetadata = !nbt.contains(CREATED_TIME_KEY, NbtElement.LONG_TYPE)
                || !nbt.contains(LAST_ACCESS_TIME_KEY, NbtElement.LONG_TYPE);
        long createdTime = nbt.contains(CREATED_TIME_KEY, NbtElement.LONG_TYPE)
                ? nbt.getLong(CREATED_TIME_KEY)
                : now;
        long lastAccessTime = nbt.contains(LAST_ACCESS_TIME_KEY, NbtElement.LONG_TYPE)
                ? nbt.getLong(LAST_ACCESS_TIME_KEY)
                : createdTime;
        DigitalStorageRecord record = new DigitalStorageRecord(
                volumeId,
                storedTierId,
                lastKnownVariantCapacity,
                dirtyCallback,
                createdTime,
                Math.max(createdTime, lastAccessTime)
        );
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            NbtCompound itemNbt = items.getCompound(itemIndex);
            NbtCompound serializedVariant = itemNbt.getCompound(VARIANT_KEY);
            ItemVariant variant = ItemVariant.fromNbt(serializedVariant);
            long amount = itemNbt.getLong(AMOUNT_KEY);
            record.storage.load(variant, amount, serializedVariant);
        }

        if (migrated || missingMetadata) {
            dirtyCallback.run();
        }
        return record;
    }

    public DigitalItemStorage storage() {
        return storage;
    }

    public Identifier tierId() {
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
        DigitalStorageMod.LOGGER.warn(
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

    public boolean setTier(Identifier requestedTierId) {
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

    public boolean advanceTier(Identifier expectedCurrentTierId, Identifier requestedTierId) {
        if (!tierId().equals(expectedCurrentTierId)) {
            return false;
        }
        return setTier(requestedTierId);
    }

    void writeNbt(NbtCompound nbt) {
        snapshot().writeNbt(nbt);
    }

    Snapshot snapshot() {
        return snapshot(storage.snapshotEntries());
    }

    Snapshot snapshot(List<DigitalItemStorage.StoredEntrySnapshot> items) {
        return new Snapshot(
                tierId(),
                lastKnownVariantCapacity,
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
            Identifier tierId,
            int lastKnownVariantCapacity,
            long createdTime,
            long lastAccessTime,
            List<DigitalItemStorage.StoredEntrySnapshot> items
    ) {
        Snapshot {
            items = List.copyOf(items);
        }

        void writeNbt(NbtCompound nbt) {
            nbt.putString(TIER_KEY, tierId.toString());
            nbt.putInt(LAST_KNOWN_VARIANT_CAPACITY_KEY, lastKnownVariantCapacity);
            nbt.putLong(CREATED_TIME_KEY, createdTime);
            nbt.putLong(LAST_ACCESS_TIME_KEY, lastAccessTime);

            NbtList items = new NbtList();
            for (DigitalItemStorage.StoredEntrySnapshot entry : this.items) {
                NbtCompound itemNbt = new NbtCompound();
                itemNbt.put(VARIANT_KEY, entry.serializedVariant());
                itemNbt.putLong(AMOUNT_KEY, entry.amount());
                items.add(itemNbt);
            }
            nbt.put(ITEMS_KEY, items);
        }
    }

    private static int migratedCapacity(NbtList items) {
        Set<ItemVariant> variants = new HashSet<>();
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            NbtCompound itemNbt = items.getCompound(itemIndex);
            ItemVariant variant = ItemVariant.fromNbt(itemNbt.getCompound(VARIANT_KEY));
            if (!variant.isBlank() && itemNbt.getLong(AMOUNT_KEY) > 0) {
                variants.add(variant);
            }
        }
        return Math.max(DEFAULT_VARIANT_CAPACITY, variants.size());
    }
}
