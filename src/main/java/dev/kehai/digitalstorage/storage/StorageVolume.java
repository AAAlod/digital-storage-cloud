package dev.kehai.digitalstorage.storage;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;

public final class StorageVolume {
    public static final int MAX_NAME_LENGTH = 48;

    private static final String ID_KEY = "Id";
    private static final String OWNER_KEY = "Owner";
    private static final String NAME_KEY = "Name";

    private final UUID id;
    private final UUID ownerId;
    private String name;
    private final DigitalStorageRecord record;
    private final Runnable dirtyCallback;

    private StorageVolume(
            UUID id,
            UUID ownerId,
            String name,
            DigitalStorageRecord record,
            Runnable dirtyCallback
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = normalizeName(name);
        this.record = record;
        this.dirtyCallback = dirtyCallback;
    }

    static StorageVolume create(UUID id, UUID ownerId, String name, Runnable dirtyCallback) {
        return new StorageVolume(id, ownerId, name, DigitalStorageRecord.createNew(id, dirtyCallback), dirtyCallback);
    }

    static StorageVolume fromNbt(NbtCompound nbt, Runnable dirtyCallback) {
        if (!nbt.containsUuid(ID_KEY) || !nbt.containsUuid(OWNER_KEY)) {
            throw new IllegalArgumentException("Storage volume has no valid ID or owner UUID");
        }
        String name = nbt.contains(NAME_KEY) ? nbt.getString(NAME_KEY) : "Storage";
        return new StorageVolume(
                nbt.getUuid(ID_KEY),
                nbt.getUuid(OWNER_KEY),
                name,
                DigitalStorageRecord.fromNbt(nbt.getUuid(ID_KEY), nbt, dirtyCallback),
                dirtyCallback
        );
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String name() {
        return name;
    }

    public DigitalStorageRecord record() {
        return record;
    }

    public boolean rename(String requestedName) {
        String normalized = normalizeName(requestedName);
        if (name.equals(normalized)) {
            return false;
        }
        name = normalized;
        dirtyCallback.run();
        return true;
    }

    Snapshot snapshot() {
        return snapshot(record.storage().snapshotEntries());
    }

    Snapshot snapshot(List<DigitalItemStorage.StoredEntrySnapshot> items) {
        return new Snapshot(id, ownerId, name, record.snapshot(items));
    }

    record Snapshot(UUID id, UUID ownerId, String name, DigitalStorageRecord.Snapshot record) {
        NbtCompound writeNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putUuid(ID_KEY, id);
            nbt.putUuid(OWNER_KEY, ownerId);
            nbt.putString(NAME_KEY, name);
            record.writeNbt(nbt);
            return nbt;
        }
    }

    static String normalizeName(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            normalized = "Storage";
        }
        return normalized.substring(0, Math.min(MAX_NAME_LENGTH, normalized.length()));
    }
}
