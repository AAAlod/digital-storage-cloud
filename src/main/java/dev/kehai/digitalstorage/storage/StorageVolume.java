package dev.kehai.digitalstorage.storage;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

public final class StorageVolume {
    public static final int MAX_NAME_LENGTH = 48;
    public static final List<ResourceLocation> PRESET_ICONS = List.of("chest", "diamond_sword", "redstone_block",
            "iron_ingot", "wheat", "cobblestone").stream()
            .map(path -> new ResourceLocation("minecraft", path)).toList();

    private static final String ID_KEY = "Id";
    private static final String OWNER_KEY = "Owner";
    private static final String NAME_KEY = "Name";

    private final UUID id;
    private final UUID ownerId;
    private String name;
    public static final ResourceLocation DEFAULT_ICON = new ResourceLocation("minecraft", "chest");
    private ResourceLocation icon = DEFAULT_ICON;
    private long metadataVersion;
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
        record.attachOwner(this);
    }

    static StorageVolume create(UUID id, UUID ownerId, String name, Runnable dirtyCallback) {
        return new StorageVolume(id, ownerId, name, DigitalStorageRecord.createNew(id, dirtyCallback), dirtyCallback);
    }

    static StorageVolume fromNbt(CompoundTag nbt, Runnable dirtyCallback) {
        if (!nbt.hasUUID(ID_KEY) || !nbt.hasUUID(OWNER_KEY)) {
            throw new IllegalArgumentException("Storage volume has no valid ID or owner UUID");
        }
        String name = nbt.contains(NAME_KEY) ? nbt.getString(NAME_KEY) : "Storage";
        StorageVolume volume = new StorageVolume(
                nbt.getUUID(ID_KEY),
                nbt.getUUID(OWNER_KEY),
                name,
                DigitalStorageRecord.fromNbt(nbt.getUUID(ID_KEY), nbt, dirtyCallback),
                dirtyCallback
        );
        String iconId = nbt.getString("Icon");
        ResourceLocation storedIcon = iconId.isBlank() ? null : ResourceLocation.tryParse(iconId);
        if (storedIcon != null && !storedIcon.equals(new ResourceLocation("minecraft", "air"))) volume.icon = storedIcon;
        return volume;
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

    public ResourceLocation icon() { return icon; }
    public long metadataVersion() { return metadataVersion; }

    public boolean setIcon(ResourceLocation requestedIcon) {
        if (icon.equals(requestedIcon)) return false;
        icon = java.util.Objects.requireNonNull(requestedIcon);
        metadataVersion++;
        dirtyCallback.run();
        return true;
    }

    public DigitalStorageRecord record() {
        return record;
    }

    public boolean acceptsUnstackableItems() {
        return record.acceptsUnstackableItems();
    }

    public boolean setAcceptUnstackableItems(boolean value) {
        return record.setAcceptUnstackableItems(value);
    }

    public boolean rename(String requestedName) {
        String normalized = normalizeName(requestedName);
        if (name.equals(normalized)) {
            return false;
        }
        name = normalized;
        metadataVersion++;
        dirtyCallback.run();
        return true;
    }

    Snapshot snapshot() {
        return snapshot(record.storage().snapshotEntries());
    }

    Snapshot snapshot(List<VolumeLedger.StoredEntrySnapshot> items) {
        return new Snapshot(id, ownerId, name, icon, record.snapshot(items));
    }

    record Snapshot(UUID id, UUID ownerId, String name, ResourceLocation icon, DigitalStorageRecord.Snapshot record) {
        CompoundTag writeNbt() {
            CompoundTag nbt = new CompoundTag();
            nbt.putUUID(ID_KEY, id);
            nbt.putUUID(OWNER_KEY, ownerId);
            nbt.putString(NAME_KEY, name);
            nbt.putString("Icon", icon.toString());
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
