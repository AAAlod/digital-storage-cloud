package dev.kehai.digitalstorage.forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** World-level evidence and ownership retention, independent of a hopper's chunk.
 * A live source is retained before encoding. Disk snapshots never authorize replay
 * or delivery; the device lifecycle must reconcile its mirrored NBT with this store.
 */
public final class ForgeHopperCustody {
    private final Path root;
    private final Map<UUID, Record> records = new LinkedHashMap<>();
    private int unreadable;

    public ForgeHopperCustody(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
            try (var files = Files.list(root)) {
                for (var file : files.filter(path -> path.getFileName().toString().endsWith(".dat")
                        || path.getFileName().toString().endsWith(".tmp")).toList()) {
                    if (file.getFileName().toString().endsWith(".tmp")) { unreadable++; continue; }
                    try {
                        var saved = NbtIo.readCompressed(file.toFile());
                        if (saved == null || !saved.contains("Version", Tag.TAG_INT) || saved.getInt("Version") != 1
                                || !saved.hasUUID("Id") || !saved.contains("Dimension", Tag.TAG_STRING)
                                || saved.getString("Dimension").isBlank() || !saved.contains("Position", Tag.TAG_LONG)
                                || !saved.contains("State")) throw new IllegalArgumentException("Invalid custody record");
                        UUID id = saved.getUUID("Id");
                        if (!file.getFileName().toString().equals(id + ".dat") || records.containsKey(id)) {
                            throw new IllegalArgumentException("Custody identity mismatch");
                        }
                        var record = new Record(id, saved.getString("Dimension"),
                                BlockPos.of(saved.getLong("Position")), null, saved.get("State").copy(), true);
                        if (saved.contains("ConflictsWith")) {
                            if (!saved.hasUUID("ConflictsWith")) throw new IllegalArgumentException("Invalid conflict identity");
                            record.conflictsWith = saved.getUUID("ConflictsWith");
                        }
                        records.put(id, record);
                    } catch (IOException | RuntimeException failure) { unreadable++; }
                }
            }
        } catch (IOException failure) { throw new IllegalStateException("Cannot open hopper custody", failure); }
    }

    /** The identity token must be the actual retained device/engine, not a transient snapshot. */
    public boolean retain(UUID id, String dimension, BlockPos position, Object identity, Supplier<Tag> snapshot) {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(identity);
        java.util.Objects.requireNonNull(snapshot);
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Missing dimension");
        Record previous = records.get(id);
        if (previous != null && (previous.identity != identity || !previous.dimension.equals(dimension)
                || !previous.position.equals(position))) {
            // A loaded snapshot/other device cannot overwrite ownership merely by
            // presenting the same UUID. Retain conflicting evidence under a fresh
            // identity; neither branch is eligible for automatic delivery.
            for (var record : records.values()) {
                if (id.equals(record.conflictsWith) && record.identity == identity
                        && record.dimension.equals(dimension) && record.position.equals(position)) {
                    record.snapshot = snapshot;
                    record.durable = false;
                    persist(record);
                    return false;
                }
            }
            UUID conflict = UUID.randomUUID();
            Record record = new Record(conflict, dimension, position.immutable(), identity, null, false);
            record.snapshot = snapshot;
            record.conflictsWith = id;
            records.put(conflict, record);
            persist(record);
            return false;
        }
        Record record = previous;
        if (record == null) {
            record = new Record(id, dimension, position.immutable(), identity, null, false);
            records.put(id, record);
        }
        record.snapshot = snapshot;
        record.durable = false;
        return persist(record);
    }

    public boolean flush() {
        boolean successful = true;
        for (var record : records.values()) if (!record.durable) successful &= persist(record);
        return successful;
    }
    public int pendingCount() { return records.size(); }
    public int unsavedCount() { return (int) records.values().stream().filter(record -> !record.durable).count(); }
    public int unreadableFiles() { return unreadable; }
    public boolean available() { return records.isEmpty() && unreadable == 0; }
    public String lastFailure(UUID id) {
        var record = records.get(id); return record == null ? "" : record.failure;
    }
    public Tag state(UUID id) {
        var record = records.get(id);
        return record == null || record.encoded == null ? null : record.encoded.copy();
    }
    boolean retainsIdentity(UUID id, Object identity) {
        var record = records.get(id); return record != null && record.identity == identity;
    }

    private boolean persist(Record record) {
        try {
            Tag encoded = java.util.Objects.requireNonNull(record.snapshot.get(), "Missing hopper state").copy();
            var saved = new CompoundTag();
            saved.putInt("Version", 1);
            saved.putUUID("Id", record.id);
            saved.putString("Dimension", record.dimension);
            saved.putLong("Position", record.position.asLong());
            saved.put("State", encoded);
            if (record.conflictsWith != null) saved.putUUID("ConflictsWith", record.conflictsWith);
            ForgeTransferFiles.write(root.resolve(record.id + ".dat"), saved);
            record.encoded = encoded;
            record.durable = true;
            record.failure = "";
            return true;
        } catch (RuntimeException failure) {
            // Keep the supplier and its original live identity even when ItemStack
            // capability encoding or the filesystem fails. Retry needs that object.
            record.durable = false;
            record.failure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            return false;
        }
    }

    private static final class Record {
        private final UUID id;
        private final String dimension;
        private final BlockPos position;
        private final Object identity;
        private Supplier<Tag> snapshot;
        private Tag encoded;
        private boolean durable;
        private UUID conflictsWith;
        private String failure = "";
        private Record(UUID id, String dimension, BlockPos position, Object identity, Tag encoded, boolean durable) {
            this.id = id; this.dimension = dimension; this.position = position;
            this.identity = identity; this.encoded = encoded; this.durable = durable;
        }
    }
}
