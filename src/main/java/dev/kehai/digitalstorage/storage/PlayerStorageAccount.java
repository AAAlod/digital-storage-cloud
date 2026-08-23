package dev.kehai.digitalstorage.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

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

        NbtCompound writeNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putUuid(OWNER_KEY, ownerId);
            NbtList volumes = new NbtList();
            for (UUID volumeId : volumeIds) {
                NbtCompound entry = new NbtCompound();
                entry.putUuid("Id", volumeId);
                volumes.add(entry);
            }
            nbt.put(VOLUMES_KEY, volumes);
            return nbt;
        }
    }

    static PlayerStorageAccount fromNbt(NbtCompound nbt) {
        if (!nbt.containsUuid(OWNER_KEY)) {
            throw new IllegalArgumentException("Storage account has no valid owner UUID");
        }
        PlayerStorageAccount account = new PlayerStorageAccount(nbt.getUuid(OWNER_KEY));
        NbtList volumes = nbt.getList(VOLUMES_KEY, NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < volumes.size(); index++) {
            NbtCompound entry = volumes.getCompound(index);
            if (entry.containsUuid("Id")) {
                account.addVolume(entry.getUuid("Id"));
            }
        }
        return account;
    }
}
