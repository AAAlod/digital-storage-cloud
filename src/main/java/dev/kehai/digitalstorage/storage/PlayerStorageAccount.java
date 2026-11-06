package dev.kehai.digitalstorage.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class PlayerStorageAccount {
    private static final String OWNER_KEY = "Owner";
    private static final String VOLUMES_KEY = "Volumes";

    private final UUID ownerId;
    private final List<UUID> volumeIds = new ArrayList<>();

    PlayerStorageAccount(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public List<UUID> volumeIds() {
        return List.copyOf(volumeIds);
    }

    boolean addVolume(UUID volumeId) {
        if (volumeIds.contains(volumeId)) {
            return false;
        }
        volumeIds.add(volumeId);
        return true;
    }

    boolean removeVolume(UUID volumeId) {
        return volumeIds.remove(volumeId);
    }

    Snapshot snapshot() {
        return new Snapshot(ownerId, volumeIds);
    }

    record Snapshot(UUID ownerId, List<UUID> volumeIds) {
        Snapshot {
            volumeIds = List.copyOf(volumeIds);
        }

        CompoundTag writeNbt() {
            CompoundTag nbt = new CompoundTag();
            nbt.putUUID(OWNER_KEY, ownerId);
            ListTag volumes = new ListTag();
            for (UUID volumeId : volumeIds) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("Id", volumeId);
                volumes.add(entry);
            }
            nbt.put(VOLUMES_KEY, volumes);
            return nbt;
        }
    }

    static PlayerStorageAccount fromNbt(CompoundTag nbt) {
        if (!nbt.hasUUID(OWNER_KEY)) {
            throw new IllegalArgumentException("Storage account has no valid owner UUID");
        }
        if (!(nbt.get(VOLUMES_KEY) instanceof ListTag)) {
            throw new IllegalArgumentException("Storage account volumes is not a list");
        }
        PlayerStorageAccount account = new PlayerStorageAccount(nbt.getUUID(OWNER_KEY));
        ListTag volumes = (ListTag) nbt.get(VOLUMES_KEY);
        if (!volumes.isEmpty() && volumes.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Storage account volumes contains non-compound entries");
        }
        for (int index = 0; index < volumes.size(); index++) {
            CompoundTag entry = volumes.getCompound(index);
            if (!entry.hasUUID("Id")) throw new IllegalArgumentException("Storage account volume has no valid UUID");
            account.addVolume(entry.getUUID("Id"));
        }
        return account;
    }
}
