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
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.ItemKeyCodec;

/** World-level evidence and ownership retention, independent of a hopper's chunk.
 * A live source is retained before encoding. Disk snapshots never authorize replay
 * or delivery; the device lifecycle must reconcile its mirrored NBT with this store.
 */
public final class ForgeHopperCustody {
    private final Path root;
    private final Map<UUID, Record> records = new LinkedHashMap<>();
    private int unreadable;
    private boolean opened;
    private String openingFailure = "";

    public ForgeHopperCustody(Path root) {
        this.root = root;
        open();
    }
    private boolean open() {
        if (opened) return true;
        try {
            Files.createDirectories(root);
            var loaded = new LinkedHashMap<UUID, Record>();
            int damaged = 0;
            try (var files = Files.list(root)) {
                for (var file : files.filter(path -> path.getFileName().toString().endsWith(".dat")
                        || path.getFileName().toString().endsWith(".tmp")).toList()) {
                    if (file.getFileName().toString().endsWith(".tmp")) { damaged++; continue; }
                    try {
                        var saved = NbtIo.readCompressed(file.toFile());
                        if (saved == null || !saved.contains("Version", Tag.TAG_INT)
                                || (saved.getInt("Version") != 1 && saved.getInt("Version") != 2)
                                || !saved.hasUUID("Id") || !saved.contains("Dimension", Tag.TAG_STRING)
                                || saved.getString("Dimension").isBlank() || !saved.contains("Position", Tag.TAG_LONG)
                                || !saved.contains("State")) throw new IllegalArgumentException("Invalid custody record");
                        UUID id = saved.getUUID("Id");
                        if (!file.getFileName().toString().equals(id + ".dat") || loaded.containsKey(id)) {
                            throw new IllegalArgumentException("Custody identity mismatch");
                        }
                        var record = new Record(id, saved.getString("Dimension"),
                                BlockPos.of(saved.getLong("Position")), null, saved.get("State").copy(), true);
                        record.diskEvidence = saved.copy();
                        if (saved.contains("IdentityEvidence")) record.identityEvidence = saved.get("IdentityEvidence").copy();
                        if (saved.getInt("Version") == 2) {
                            if (!saved.contains("Phase", Tag.TAG_STRING)) throw new IllegalArgumentException("Missing custody phase");
                            record.phase = Phase.valueOf(saved.getString("Phase"));
                            if (record.phase != Phase.PENDING) {
                                if (!saved.hasUUID("Owner") || !saved.hasUUID("Volume") || !saved.hasUUID("Administrator")
                                        || !saved.contains("ExportKey", Tag.TAG_COMPOUND) || !saved.contains("ExportAmount", Tag.TAG_LONG)
                                        || saved.getLong("ExportAmount") <= 0 || !saved.contains("Explanation", Tag.TAG_STRING)
                                        || saved.getString("Explanation").isBlank() || saved.getString("Explanation").length() > 512) {
                                    throw new IllegalArgumentException("Invalid hopper handoff receipt");
                                }
                                record.owner = saved.getUUID("Owner"); record.volume = saved.getUUID("Volume");
                                record.administrator = saved.getUUID("Administrator");
                                record.exportKey = ItemKeyCodec.read(saved.getCompound("ExportKey"));
                                record.exportAmount = saved.getLong("ExportAmount");
                                record.explanation = saved.getString("Explanation");
                            }
                        }
                        if (saved.contains("ConflictsWith")) {
                            if (!saved.hasUUID("ConflictsWith")) throw new IllegalArgumentException("Invalid conflict identity");
                            record.conflictsWith = saved.getUUID("ConflictsWith");
                        }
                        loaded.put(id, record);
                    } catch (IOException | RuntimeException failure) { damaged++; }
                }
            }
            // A device retained while the directory was inaccessible cannot
            // authorize replacement of a record discovered only after repair.
            for (var id : loaded.keySet()) if (records.containsKey(id)) {
                throw new IOException("Late disk identity conflicts with retained live hopper " + id);
            }
            records.putAll(loaded);
            unreadable = damaged;
            opened = true;
            openingFailure = "";
            return true;
        } catch (IOException | RuntimeException failure) {
            openingFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            return false;
        }
    }
    public boolean opened() { return opened; }
    public String openingFailure() { return openingFailure; }

    /** The identity token must be the actual retained device/engine, not a transient snapshot. */
    public boolean retain(UUID id, String dimension, BlockPos position, Object identity, Supplier<Tag> snapshot) {
        return retain(id, dimension, position, identity, snapshot, null);
    }
    public boolean retain(UUID id, String dimension, BlockPos position, Object identity, Supplier<Tag> snapshot, Supplier<Tag> evidence) {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(identity);
        java.util.Objects.requireNonNull(snapshot);
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Missing dimension");
        open();
        Record previous = records.get(id);
        if (previous != null && previous.phase != Phase.PENDING) {
            // HANDOFF/EXPORTED are authoritative receipts, not snapshots that a
            // stale device is allowed to replace with its old held stack.
            return previous.phase == Phase.EXPORTED && previous.durable;
        }
        if (previous != null && (previous.identity != identity || !previous.dimension.equals(dimension)
                || !previous.position.equals(position))) {
            // A loaded snapshot/other device cannot overwrite ownership merely by
            // presenting the same UUID. Retain conflicting evidence under a fresh
            // identity; neither branch is eligible for automatic delivery.
            for (var record : records.values()) {
                if (id.equals(record.conflictsWith) && record.identity == identity
                        && record.dimension.equals(dimension) && record.position.equals(position)) {
                    record.snapshot = snapshot;
                    if (record.identityEvidence == null && record.evidenceSnapshot == null) record.evidenceSnapshot = evidence;
                    record.durable = false;
                    persist(record);
                    return false;
                }
            }
            UUID conflict = UUID.randomUUID();
            Record record = new Record(conflict, dimension, position.immutable(), identity, null, false);
            record.snapshot = snapshot;
            record.evidenceSnapshot = evidence;
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
        if (record.identityEvidence == null && record.evidenceSnapshot == null) record.evidenceSnapshot = evidence;
        record.durable = false;
        return persist(record);
    }

    public boolean flush() {
        if (!open()) return false;
        boolean successful = true;
        for (var record : records.values()) if (!record.durable) successful &= persist(record);
        return successful;
    }
    /** A matching chunk snapshot is a mirror of the record, not another item source. */
    public Binding bind(UUID id, String dimension, BlockPos position, ForgeHopperTransfer incoming, Supplier<Tag> snapshot) {
        return bind(id, dimension, position, incoming, snapshot, null);
    }
    public Binding bind(UUID id, String dimension, BlockPos position, ForgeHopperTransfer incoming,
                        Supplier<Tag> snapshot, Supplier<Tag> evidence) {
        open();
        Record record = records.get(id);
        if (record == null) {
            if (incoming.blocked()) retain(id, dimension, position, incoming, snapshot, evidence);
            return new Binding(id, incoming, null);
        }
        if (record.phase == Phase.EXPORTED && record.durable) {
            var ready = new ForgeHopperTransfer();
            return new Binding(UUID.randomUUID(), ready, ready.saveState());
        }
        if (record.phase != Phase.PENDING) {
            var canonical = record.identity instanceof ForgeHopperTransfer live ? live
                    : record.encoded instanceof CompoundTag compound ? ForgeHopperTransfer.restore(compound) : incoming;
            canonical.halt("hopper custody handoff requires completion");
            record.identity = canonical;
            return new Binding(id, canonical, record.encoded == null ? null : record.encoded.copy());
        }
        if (record.identity == incoming && record.dimension.equals(dimension) && record.position.equals(position)) {
            return new Binding(id, incoming, null);
        }
        boolean matches = record.dimension.equals(dimension) && record.position.equals(position);
        Tag canonical = record.encoded;
        if (matches && !incoming.blocked() && record.identity instanceof ForgeHopperTransfer existing) {
            // The old chunk can be empty while the actual returned stack cannot
            // be encoded. Its live canonical owner wins without serialization.
            return new Binding(id, existing, canonical == null ? null : canonical.copy());
        }
        try {
            if (record.snapshot != null) canonical = record.snapshot.get();
            // A ready, empty chunk mirror may predate the external ownership
            // record. The journal wins; it cannot create an additional stack.
            matches &= !incoming.blocked() || (canonical != null && canonical.equals(snapshot.get()));
        } catch (RuntimeException failure) { matches = false; }
        if (matches && canonical != null) {
            ForgeHopperTransfer engine;
            if (record.identity instanceof ForgeHopperTransfer existing) engine = existing;
            else {
                engine = canonical instanceof CompoundTag compound
                        ? ForgeHopperTransfer.restore(compound) : ForgeHopperTransfer.restore(new CompoundTag());
                record.identity = engine;
                Tag preserved = canonical.copy();
                record.snapshot = canonical instanceof CompoundTag ? engine::saveState : () -> preserved.copy();
            }
            return new Binding(id, engine, canonical.copy());
        }
        incoming.halt("hopper chunk mirror conflicts with custody record");
        retain(id, dimension, position, incoming, snapshot, evidence);
        for (var conflict : records.values()) {
            if (id.equals(conflict.conflictsWith) && conflict.identity == incoming
                    && conflict.dimension.equals(dimension) && conflict.position.equals(position)) {
                return new Binding(conflict.id, incoming, null);
            }
        }
        throw new IllegalStateException("Custody conflict was not retained");
    }
    public record Binding(UUID id, ForgeHopperTransfer engine, Tag state) { }
    public int pendingCount() { return (int) records.values().stream().filter(record -> record.phase != Phase.EXPORTED || !record.durable).count(); }
    public int unsavedCount() { return (int) records.values().stream().filter(record -> !record.durable).count(); }
    public int unreadableFiles() { return unreadable; }
    public boolean available() { return opened && pendingCount() == 0 && unreadable == 0; }
    public java.util.List<Summary> entries() {
        var conflicts = new java.util.HashSet<UUID>();
        for (var record : records.values()) if (record.conflictsWith != null) conflicts.add(record.conflictsWith);
        return records.values().stream().map(record -> {
            var engine = record.identity instanceof ForgeHopperTransfer live ? live
                    : record.encoded instanceof CompoundTag compound ? ForgeHopperTransfer.restore(compound) : null;
            boolean observedKnown = record.phase != Phase.PENDING || (engine != null && engine.heldCount() > 0);
            boolean confirmed = record.phase != Phase.PENDING || (observedKnown && !engine.uncertain()
                    && record.conflictsWith == null && !conflicts.contains(record.id));
            long amount = record.phase != Phase.PENDING ? record.exportAmount : engine == null ? 0 : engine.heldCount();
            String item = record.exportKey != null ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(record.exportKey.item()).toString()
                    : engine == null || engine.heldStack().isEmpty() ? "unknown"
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(engine.heldStack().getItem()).toString();
            return new Summary(record.id, record.dimension, record.position, record.phase.name(), record.conflictsWith,
                    record.owner, record.volume, record.administrator, item, amount, observedKnown, confirmed, record.durable,
                    record.explanation == null ? "" : record.explanation, record.failure);
        }).toList();
    }
    public record Summary(UUID id, String dimension, BlockPos position, String phase, UUID conflictsWith,
                          UUID owner, UUID volume, UUID administrator, String item, long observedAmount,
                          boolean observedKnown, boolean confirmed, boolean durable, String explanation, String failure) { }
    public Tag identityEvidence(UUID id) {
        var record = records.get(id);
        return record == null || record.identityEvidence == null ? null : record.identityEvidence.copy();
    }

    /** Administrator-directed custody handoff; delivery still uses the owner's existing recovery flow. */
    public UUID export(UUID id, UUID owner, UUID volume, UUID administrator, String explanation, ForgeTransferRecovery recovery) {
        return export(id, owner, volume, administrator, explanation, recovery, () -> { });
    }
    UUID export(UUID id, UUID owner, UUID volume, UUID administrator, String explanation,
                ForgeTransferRecovery recovery, Runnable afterAdoption) {
        java.util.Objects.requireNonNull(owner); java.util.Objects.requireNonNull(volume);
        java.util.Objects.requireNonNull(administrator);
        if (explanation == null || explanation.isBlank() || explanation.length() > 512) {
            throw new IllegalArgumentException("A concise handoff explanation is required");
        }
        if (!open()) throw new IllegalStateException("Hopper custody directory requires repair: " + openingFailure);
        var record = records.get(id);
        if (record == null || unreadable != 0 || record.conflictsWith != null
                || records.values().stream().anyMatch(candidate -> id.equals(candidate.conflictsWith))) {
            throw new IllegalStateException("Hopper custody requires conflict or file reconciliation");
        }
        requireFresh(record);
        if (record.phase == Phase.PENDING) {
            if (!record.durable && !persist(record)) throw new IllegalStateException("Hopper state is not durable");
            var engine = record.identity instanceof ForgeHopperTransfer live ? live
                    : record.encoded instanceof CompoundTag compound ? ForgeHopperTransfer.restore(compound) : null;
            if (engine == null || !engine.blocked() || engine.uncertain() || engine.heldCount() <= 0) {
                throw new IllegalStateException("Only confirmed returned items can be handed off");
            }
            var key = ItemKey.of(engine.heldStack());
            record.encoded = record.snapshot == null ? record.encoded.copy() : record.snapshot.get().copy();
            record.owner = owner; record.volume = volume; record.administrator = administrator;
            record.explanation = explanation; record.exportKey = key; record.exportAmount = engine.heldCount();
            record.phase = Phase.HANDOFF; record.durable = false;
        }
        if (!record.owner.equals(owner) || !record.volume.equals(volume)) {
            throw new IllegalArgumentException("Hopper handoff target cannot change on retry");
        }
        if (!record.durable && !persist(record)) throw new IllegalStateException("Hopper handoff intent could not be saved");
        if (record.phase == Phase.EXPORTED) return id;
        recovery.adoptHopper(id, owner, volume, record.exportKey, record.exportAmount);
        afterAdoption.run();
        record.phase = Phase.EXPORTED; record.durable = false;
        if (!persist(record)) throw new IllegalStateException("Recovery owns the items; hopper completion receipt needs retry");
        return id;
    }

    private void requireFresh(Record record) {
        try {
            Path file = root.resolve(record.id + ".dat");
            if (Files.exists(file) ? record.diskEvidence == null || !record.diskEvidence.equals(NbtIo.readCompressed(file.toFile()))
                    : record.diskEvidence != null) throw new IllegalStateException("Custody file changed; reopen before writing");
        } catch (IOException failure) { throw new IllegalStateException("Custody evidence cannot be read", failure); }
    }
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
            if (!open()) throw new IllegalStateException("Cannot open hopper custody: " + openingFailure);
            requireFresh(record);
            Tag encoded = (record.phase == Phase.PENDING
                    ? java.util.Objects.requireNonNull(record.snapshot.get(), "Missing hopper state") : record.encoded).copy();
            Tag evidence = record.identityEvidence;
            if (evidence == null && record.evidenceSnapshot != null) {
                evidence = java.util.Objects.requireNonNull(record.evidenceSnapshot.get(), "Missing identity evidence").copy();
            }
            var saved = new CompoundTag();
            saved.putInt("Version", 2);
            saved.putString("Phase", record.phase.name());
            saved.putUUID("Id", record.id);
            saved.putString("Dimension", record.dimension);
            saved.putLong("Position", record.position.asLong());
            saved.put("State", encoded);
            if (evidence != null) saved.put("IdentityEvidence", evidence.copy());
            if (record.conflictsWith != null) saved.putUUID("ConflictsWith", record.conflictsWith);
            if (record.phase != Phase.PENDING) {
                saved.putUUID("Owner", record.owner); saved.putUUID("Volume", record.volume);
                saved.putUUID("Administrator", record.administrator);
                saved.put("ExportKey", ItemKeyCodec.write(record.exportKey));
                saved.putLong("ExportAmount", record.exportAmount); saved.putString("Explanation", record.explanation);
            }
            ForgeTransferFiles.write(root.resolve(record.id + ".dat"), saved);
            record.encoded = encoded;
            record.identityEvidence = evidence;
            record.durable = true;
            record.failure = "";
            record.diskEvidence = saved.copy();
            if (record.phase == Phase.EXPORTED) {
                if (record.identity instanceof ForgeHopperTransfer live) live.releaseExported();
                record.identity = null; record.snapshot = null; record.evidenceSnapshot = null;
            }
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
        private Object identity;
        private Supplier<Tag> snapshot;
        private Tag encoded;
        private Tag identityEvidence;
        private Supplier<Tag> evidenceSnapshot;
        private boolean durable;
        private UUID conflictsWith;
        private String failure = "";
        private CompoundTag diskEvidence;
        private Phase phase = Phase.PENDING;
        private UUID owner, volume, administrator;
        private ItemKey exportKey;
        private long exportAmount;
        private String explanation;
        private Record(UUID id, String dimension, BlockPos position, Object identity, Tag encoded, boolean durable) {
            this.id = id; this.dimension = dimension; this.position = position;
            this.identity = identity; this.encoded = encoded; this.durable = durable;
        }
    }
    private enum Phase { PENDING, HANDOFF, EXPORTED }
}
