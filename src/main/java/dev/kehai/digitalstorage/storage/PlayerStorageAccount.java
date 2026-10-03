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
        PlayerStorageAccount account = new PlayerStorageAccount(nbt.getUUID(OWNER_KEY));
        ListTag volumes = nbt.getList(VOLUMES_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < volumes.size(); index++) {
            CompoundTag entry = volumes.getCompound(index);
            if (entry.hasUUID("Id")) {
                account.addVolume(entry.getUUID("Id"));
            }
        }
        return account;
    }
}
