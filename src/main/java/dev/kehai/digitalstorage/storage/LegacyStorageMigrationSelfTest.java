package dev.kehai.digitalstorage.storage;

import java.nio.file.Files;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.Items;

/** Legacy migration ordering, recovery, and preservation regressions. */
final class LegacyStorageMigrationSelfTest {
    private LegacyStorageMigrationSelfTest() { }
    static void run() {
        legacyImportPreservesRecords();
        legacyMigrationIsNotRepeated();
        legacyBackupCrashWindows();
        failedMigrationSaveCanRetry();
        futureAccountsArePreserved();
    }
    private static void legacyImportPreservesRecords() {
        try {
            var root = Files.createTempDirectory("dsc-legacy-import-");
            try {
                var accounts = Files.createDirectory(root.resolve("accounts"));
                var volumes = Files.createDirectory(root.resolve("volumes"));
                var markers = Files.createDirectory(root.resolve("deleted-volumes"));
                var owner = java.util.UUID.randomUUID();
                var id = java.util.UUID.randomUUID();
                var broken = java.util.UUID.randomUUID();
                var futureId = java.util.UUID.randomUUID();
                var deleted = java.util.UUID.randomUUID();
                var account = new PlayerStorageAccount(owner);
                account.addVolume(id);
                account.addVolume(broken);
                account.addVolume(futureId);
                account.addVolume(deleted);
                NbtIo.writeCompressed(account.snapshot().writeNbt(), accounts.resolve(owner + ".dat").toFile());
                var volume = StorageVolume.create(id, owner, "Legacy fixture", () -> { });
                volume.record().storage().load(ItemKey.of(Items.STONE), 7);
                var unknown = new CompoundTag();
                unknown.putString("item", "missingmod:legacy_item");
                volume.record().storage().loadOpaque(unknown, 9);
                NbtIo.writeCompressed(volume.snapshot().writeNbt(), volumes.resolve(id + ".dat").toFile());
                Files.write(volumes.resolve(broken + ".dat"), new byte[] {1, 2, 3});
                var future = new CompoundTag(); future.putInt("SchemaVersion", 3); future.putUUID("Id", futureId);
                NbtIo.writeCompressed(future, volumes.resolve(futureId + ".dat").toFile());
                byte[] futureBytes = Files.readAllBytes(volumes.resolve(futureId + ".dat"));
                Files.write(markers.resolve(deleted + ".dat"), new byte[0]);
                var data = new DigitalStorageData();
                LegacyStorageMigrator.importFiles(root, data);
                expect(data.volumes.get(id).record().storage().totalItemCount() == 16
                        && data.volumes.get(id).record().storage().opaqueVariantCount() == 1
                        && data.legacyUnreadable.containsKey(broken)
                        && data.legacyUnreadable.containsKey(futureId)
                        && data.accounts.get(owner).volumeIds().contains(broken)
                        && data.accounts.get(owner).volumeIds().contains(futureId)
                        && data.lookup(broken).status() == DigitalStorageState.VolumeStatus.UNREADABLE
                        && data.lookup(futureId).status() == DigitalStorageState.VolumeStatus.UNREADABLE
                        && !data.accounts.get(owner).volumeIds().contains(deleted)
                        && Files.exists(volumes.resolve(broken + ".dat"))
                        && java.util.Arrays.equals(futureBytes, Files.readAllBytes(volumes.resolve(futureId + ".dat"))), "Legacy import lost records or moved files");
            } finally {
                try (var paths = Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void legacyMigrationIsNotRepeated() {
        try {
            var root = Files.createTempDirectory("dsc-legacy-migration-");
            try {
                var dataDir = Files.createDirectory(root.resolve("data"));
                var legacy = Files.createDirectory(root.resolve("digitalstorage"));
                var accounts = Files.createDirectory(legacy.resolve("accounts"));
                var volumes = Files.createDirectory(legacy.resolve("volumes"));
                var recovery = Files.createDirectories(legacy.resolve("forge-transfer/recovery"));
                Files.writeString(recovery.resolve("receipt.dat"), "receipt");
                Files.writeString(legacy.resolve("unknown"), "preserved");
                var owner = java.util.UUID.randomUUID();
                var id = java.util.UUID.randomUUID();
                var account = new PlayerStorageAccount(owner);
                account.addVolume(id);
                NbtIo.writeCompressed(account.snapshot().writeNbt(), accounts.resolve(owner + ".dat").toFile());
                var volume = StorageVolume.create(id, owner, "Migration fixture", () -> { });
                volume.record().storage().load(ItemKey.of(Items.STONE), 7);
                NbtIo.writeCompressed(volume.snapshot().writeNbt(), volumes.resolve(id + ".dat").toFile());
                var storage = new net.minecraft.world.level.storage.DimensionDataStorage(dataDir.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                var data = DigitalStorageData.open(storage, root);
                expect(!data.readOnly && !data.isDirty() && data.backupStatus.equals("completed")
                        && Files.isDirectory(root.resolve(data.legacyBackup).resolve("accounts"))
                        && Files.isDirectory(root.resolve(data.legacyBackup).resolve("volumes"))
                        && Files.readString(recovery.resolve("receipt.dat")).equals("receipt")
                        && Files.readString(legacy.resolve("unknown")).equals("preserved")
                        && data.volumes.get(id).record().storage().totalItemCount() == 7, "Legacy migration did not save before backup");
                Files.writeString(recovery.resolve("next.dat"), "writable");
                var ledger = data.volumes.get(id).record().storage();
                try (var transaction = LedgerTransaction.open()) {
                    expect(ledger.extract(ItemKey.of(Items.STONE), 2, transaction) == 2, "Migrated ledger extraction failed");
                    transaction.commit();
                }
                storage.save();
                expect(!data.isDirty(), "Migrated data did not save");
                var reopened = new net.minecraft.world.level.storage.DimensionDataStorage(dataDir.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                var restored = DigitalStorageData.open(reopened, root);
                expect(!restored.readOnly && restored.volumes.get(id).record().storage().totalItemCount() == 5
                        && Files.isDirectory(root.resolve(restored.legacyBackup)), "Second open re-imported legacy data or lost backup");
            } finally {
                try (var paths = Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void legacyBackupCrashWindows() {
        try {
            var root = Files.createTempDirectory("dsc-backup-windows-");
            try {
                var directory = Files.createDirectory(root.resolve("data"));
                var legacy = Files.createDirectory(root.resolve("digitalstorage"));
                Files.createDirectory(legacy.resolve("accounts"));
                Files.writeString(legacy.resolve("accounts/original"), "preserved");
                Files.writeString(legacy.resolve("unknown"), "untouched");
                var file = directory.resolve("digitalstorage.dat");
                var saved = new DigitalStorageData();
                saved.legacyBackup = "digitalstorage-legacy-test";
                var owner = java.util.UUID.randomUUID();
                var id = saved.createVolume(owner, "saved", 1).orElseThrow().id();
                saved.save(file.toFile());
                expect(!saved.isDirty(), "Crash-window fixture save failed");
                var backup = root.resolve(saved.legacyBackup);
                var storage = new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                var restored = DigitalStorageData.open(storage, root);
                expect(restored.volumes.containsKey(id) && Files.readString(legacy.resolve("unknown")).equals("untouched")
                        && Files.readString(backup.resolve("accounts/original")).equals("preserved"),
                        "Saved-before-rename recovery did not preserve and rename legacy directory");
                var reopened = new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                expect(DigitalStorageData.open(reopened, root).volumes.containsKey(id)
                        && Files.isDirectory(backup), "Post-rename restart lost data or backup");
                Files.createDirectory(legacy.resolve("accounts"));
                Files.writeString(legacy.resolve("accounts/original"), "conflicting legacy");
                var conflict = new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                var loaded = DigitalStorageData.open(conflict, root);
                expect(loaded.backupStatus.equals("conflict") && loaded.volumes.containsKey(id) && loaded.volumes.size() == 1
                        && Files.readString(backup.resolve("accounts/original")).equals("preserved")
                        && Files.readString(legacy.resolve("accounts/original")).equals("conflicting legacy"),
                        "Backup conflict overwrote data or re-imported legacy records");
            } finally {
                try (var paths = Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void failedMigrationSaveCanRetry() {
        try {
            var root = Files.createTempDirectory("dsc-migration-failure-");
            try {
                var dir = Files.createDirectory(root.resolve("data"));
                var legacy = Files.createDirectory(root.resolve("digitalstorage"));
                Files.writeString(legacy.resolve("keep"), "original");
                var storage = new net.minecraft.world.level.storage.DimensionDataStorage(dir.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                var attempts = new java.util.concurrent.atomic.AtomicInteger();
                var data = DigitalStorageData.open(storage, root, (tag, temp) -> {
                    if (attempts.getAndIncrement() == 0) { Files.write(temp, new byte[] {1, 2}); throw new java.io.IOException("injected migration write failure"); }
                    NbtIo.writeCompressed(tag, temp.toFile());
                });
                expect(data.isDirty() && Files.isDirectory(legacy) && Files.notExists(dir.resolve("digitalstorage.dat")),
                        "failed migration save: expected dirty data, intact legacy, and no target");
                try (var paths = Files.list(dir)) {
                    expect(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "failed migration save left temporary file");
                }
                storage.save();
                expect(!data.isDirty() && Files.isDirectory(legacy) && Files.isRegularFile(dir.resolve("digitalstorage.dat")),
                        "migration retry did not safely save before rename");
                var reopened = new net.minecraft.world.level.storage.DimensionDataStorage(dir.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                var restored = DigitalStorageData.open(reopened, root);
                expect(!restored.readOnly && Files.readString(legacy.resolve("keep")).equals("original")
                        && Files.isDirectory(root.resolve(restored.legacyBackup)),
                        "migration retry restart did not preserve and rename original");
            } finally {
                try (var paths = Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void futureAccountsArePreserved() {
        try {
            var root = Files.createTempDirectory("dsc-future-account-");
            try {
                var accounts = Files.createDirectory(root.resolve("accounts"));
                var owner = java.util.UUID.randomUUID();
                var future = new CompoundTag(); future.putInt("SchemaVersion", 2);
                var validName = accounts.resolve(owner + ".dat"); var invalidName = accounts.resolve("not-a-uuid.dat");
                NbtIo.writeCompressed(future, validName.toFile()); NbtIo.writeCompressed(future, invalidName.toFile());
                byte[] validBytes = Files.readAllBytes(validName), invalidBytes = Files.readAllBytes(invalidName);
                var data = new DigitalStorageData(); LegacyStorageMigrator.importFiles(root, data);
                expect(data.accountsIncomplete && data.legacyUnreadableAccounts.containsKey(owner)
                        && data.createVolume(owner, "denied", 0).isEmpty()
                        && data.lookup(java.util.UUID.randomUUID()).status() == DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE
                        && java.util.Arrays.equals(validBytes, Files.readAllBytes(validName))
                        && java.util.Arrays.equals(invalidBytes, Files.readAllBytes(invalidName)),
                        "future or invalid account: expected incomplete state and unchanged files");
            } finally {
                try (var paths = Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void expect(boolean value, String description) {
        if (!value) throw new IllegalStateException(description);
    }
}
