package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Reads legacy files without modifying them; the caller saves before renaming the root. */
final class LegacyStorageMigrator {
    private LegacyStorageMigrator() { }

    static void importFiles(Path root, DigitalStorageData data) throws IOException {
        Set<UUID> markers = new HashSet<>();
        for (Path file : files(root.resolve("deleted-volumes"))) {
            try { markers.add(id(file)); }
            catch (IllegalArgumentException invalid) { DigitalStorage.LOGGER.warn("Ignoring invalid legacy deletion marker {}", file); }
        }
        for (Path file : files(root.resolve("accounts"))) {
            try {
                UUID id = id(file);
                CompoundTag tag = read(file, 1);
                var account = PlayerStorageAccount.fromNbt(tag);
                if (!id.equals(account.ownerId()) || data.accounts.putIfAbsent(id, account) != null) throw new IllegalArgumentException("Account ID mismatch or duplicate");
            } catch (IOException | RuntimeException failure) {
                data.accountsIncomplete = true;
                try { data.legacyUnreadableAccounts.put(id(file), failure.toString()); }
                catch (IllegalArgumentException invalid) { /* Filename does not identify an owner. */ }
                DigitalStorage.LOGGER.error("Legacy account {} could not be read; original preserved", file, failure);
            }
        }
        for (Path file : files(root.resolve("volumes"))) {
            UUID volumeId;
            try { volumeId = id(file); }
            catch (IllegalArgumentException invalid) { DigitalStorage.LOGGER.error("Invalid legacy volume filename {}; original preserved", file); continue; }
            if (markers.contains(volumeId)) {
                if (data.accountsIncomplete) data.legacyUnreadable.put(volumeId, "Legacy deletion marker deferred because accounts are incomplete");
                continue;
            }
            try {
                CompoundTag tag = read(file, 2);
                if (!tag.hasUUID("Id") || !volumeId.equals(tag.getUUID("Id"))) throw new IllegalArgumentException("Volume ID mismatch");
                var volume = StorageVolume.fromNbt(tag, data::setDirty);
                if (data.volumes.putIfAbsent(volumeId, volume) != null) throw new IllegalArgumentException("Duplicate volume");
                volume.record().storage().setActivePredicate(() -> !data.readOnly && data.volumes.get(volumeId) == volume);
                if (!data.legacyUnreadableAccounts.containsKey(volume.ownerId())) {
                    for (var account : data.accounts.values()) {
                        if (!account.ownerId().equals(volume.ownerId())) account.removeVolume(volumeId);
                    }
                    data.accounts.computeIfAbsent(volume.ownerId(), PlayerStorageAccount::new).addVolume(volumeId);
                }
            } catch (IOException | RuntimeException failure) {
                data.legacyUnreadable.put(volumeId, failure.toString());
                DigitalStorage.LOGGER.error("Legacy volume {} could not be read; original preserved", file, failure);
            }
        }
        if (!data.accountsIncomplete) {
            for (UUID id : markers) for (var account : data.accounts.values()) account.removeVolume(id);
        }
        data.setDirty();
    }

    private static CompoundTag read(Path file, int maxSchema) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(file.toFile());
        if (tag.contains("SchemaVersion", Tag.TAG_INT) && tag.getInt("SchemaVersion") > maxSchema) {
            throw new IllegalArgumentException("Newer legacy schema " + tag.getInt("SchemaVersion"));
        }
        return tag;
    }

    private static java.util.List<Path> files(Path directory) throws IOException {
        if (Files.notExists(directory)) return java.util.List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".dat")).sorted().toList();
        }
    }

    private static UUID id(Path path) {
        String name = path.getFileName().toString();
        return UUID.fromString(name.substring(0, name.length() - 4));
    }
}
