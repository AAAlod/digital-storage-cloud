package dev.kehai.digitalstorage.forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Observations require reconciliation; they never represent owned or deliverable items. */
public final class ForgeTransferIncidents {
    private final Path directory;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Entry> unsaved = new LinkedHashMap<>();
    private final Set<Path> unreadable = new LinkedHashSet<>();
    public ForgeTransferIncidents(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
            try (var files = Files.list(this.directory)) {
                for (var file : files.filter(path -> path.getFileName().toString().endsWith(".dat")
                        || path.getFileName().toString().endsWith(".tmp")).sorted().toList()) {
                    try {
                        if (file.getFileName().toString().endsWith(".tmp")) throw new IllegalArgumentException("Interrupted incident write");
                        Entry entry = read(NbtIo.readCompressed(file.toFile()));
                        if (!file.equals(path(entry.id())) || entries.putIfAbsent(entry.id(), entry) != null) {
                            throw new IllegalArgumentException("Incident filename or identity mismatch");
                        }
                    } catch (IOException | RuntimeException failure) { unreadable.add(file); }
                }
            }
        } catch (IOException failure) { throw new IllegalStateException("Could not open transfer incident storage", failure); }
    }
    public boolean available() { return unreadable.isEmpty() && unsaved.isEmpty() && unresolvedCount() == 0; }
    public int unreadableFiles() { return unreadable.size(); }
    public int unsavedCount() { return unsaved.size(); }
    public int unresolvedCount() { return (int) entries.values().stream().filter(entry -> entry.administrator() == null).count(); }
    public List<Entry> entries() { return List.copyOf(entries.values()); }

    public UUID record(ForgeInventoryTransferExecutor.Incident incident) {
        Entry entry = new Entry(UUID.randomUUID(), incident, null, "");
        entries.put(entry.id(), entry);
        try { write(entry); }
        catch (RuntimeException failure) { unsaved.put(entry.id(), entry); throw failure; }
        return entry.id();
    }
    public void flushUnsaved() {
        for (var entry : List.copyOf(unsaved.values())) { write(entry); unsaved.remove(entry.id()); }
    }
    /** Caller must require administrator permission and an explicit reconciliation explanation. No items are delivered. */
    public void acknowledge(UUID id, UUID administrator, String explanation) {
        java.util.Objects.requireNonNull(administrator);
        if (explanation == null || explanation.isBlank() || explanation.length() > 1024) {
            throw new IllegalArgumentException("Incident acknowledgement needs a reconciliation explanation");
        }
        if (!unreadable.isEmpty() || !unsaved.isEmpty() || !entries.containsKey(id)) {
            throw new IllegalStateException("Incident storage is unavailable or identity is unknown");
        }
        Entry persisted;
        try {
            persisted = read(NbtIo.readCompressed(path(id).toFile()));
            if (!persisted.id().equals(id)) throw new IllegalArgumentException("Incident identity changed");
        } catch (IOException | RuntimeException failure) {
            unreadable.add(path(id));
            throw new IllegalStateException("Incident file requires reconciliation", failure);
        }
        if (persisted.administrator() != null) throw new IllegalStateException("Incident was already acknowledged");
        Entry next = new Entry(id, persisted.incident(), administrator, explanation);
        write(next);
        entries.put(id, next);
    }
    private Path path(UUID id) { return directory.resolve(id + ".dat"); }
    private void write(Entry entry) { ForgeTransferFiles.write(path(entry.id()), encode(entry)); }
    static CompoundTag encode(Entry entry) {
        var tag = new CompoundTag();
        var incident = entry.incident();
        tag.putInt("SchemaVersion", 2);
        tag.putUUID("Id", entry.id());
        tag.putUUID("Owner", incident.owner());
        tag.putUUID("Volume", incident.volume());
        tag.putString("Handler", incident.handlerClass());
        tag.putInt("Slot", incident.slot());
        tag.putLong("Settled", incident.settled());
        tag.putLong("CreatedMillis", incident.createdMillis());
        tag.putString("Reason", incident.reason());
        var origin = new CompoundTag();
        origin.putBoolean("Known", incident.origin().known());
        origin.putString("Dimension", incident.origin().dimension());
        origin.putLong("Accessor", incident.origin().accessorPosition());
        origin.putLong("Connector", incident.origin().connectorPosition());
        tag.put("Origin", origin);
        var observation = new CompoundTag();
        var observed = incident.observation();
        observation.putBoolean("Known", observed.known());
        observation.put("ExpectedVariant", observed.expectedVariant());
        observation.putLong("Maximum", observed.maximum());
        observation.putLong("Observed", observed.observed());
        observation.putLong("Requested", observed.requested());
        observation.putLong("Reserved", observed.reserved());
        observation.putBoolean("ActualStarted", observed.actualStarted());
        observation.putString("Stage", observed.stage().name());
        tag.put("Observation", observation);
        if (entry.administrator() != null) tag.putUUID("Administrator", entry.administrator());
        tag.putString("Explanation", entry.explanation());
        return tag;
    }
    static Entry read(CompoundTag tag) {
        if (tag == null || !tag.contains("SchemaVersion", Tag.TAG_INT)
                || (tag.getInt("SchemaVersion") != 1 && tag.getInt("SchemaVersion") != 2)
                || !tag.hasUUID("Id") || !tag.hasUUID("Owner") || !tag.hasUUID("Volume")
                || !tag.contains("Handler", Tag.TAG_STRING) || !tag.contains("Slot", Tag.TAG_INT)
                || !tag.contains("Settled", Tag.TAG_LONG) || !tag.contains("CreatedMillis", Tag.TAG_LONG)
                || !tag.contains("Reason", Tag.TAG_STRING) || !tag.contains("Explanation", Tag.TAG_STRING)
                || (tag.contains("Administrator") && !tag.hasUUID("Administrator"))) {
            throw new IllegalArgumentException("Unsupported or incomplete transfer incident");
        }
        return new Entry(tag.getUUID("Id"), new ForgeInventoryTransferExecutor.Incident(tag.getUUID("Owner"),
                tag.getUUID("Volume"), tag.getString("Handler"), tag.getInt("Slot"), tag.getLong("Settled"),
                tag.getLong("CreatedMillis"), tag.getString("Reason"), readOrigin(tag), readObservation(tag)),
                tag.hasUUID("Administrator") ? tag.getUUID("Administrator") : null, tag.getString("Explanation"));
    }
    private static ForgeInventoryTransferExecutor.Origin readOrigin(CompoundTag record) {
        if (record.getInt("SchemaVersion") == 1) return ForgeInventoryTransferExecutor.Origin.unknown();
        if (!record.contains("Origin", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing incident origin");
        var tag = record.getCompound("Origin");
        if (!isBoolean(tag, "Known") || !tag.contains("Dimension", Tag.TAG_STRING)
                || !tag.contains("Accessor", Tag.TAG_LONG) || !tag.contains("Connector", Tag.TAG_LONG)) {
            throw new IllegalArgumentException("Malformed incident origin");
        }
        return new ForgeInventoryTransferExecutor.Origin(tag.getBoolean("Known"), tag.getString("Dimension"),
                tag.getLong("Accessor"), tag.getLong("Connector"));
    }
    private static ForgeInventoryTransferExecutor.Observation readObservation(CompoundTag record) {
        if (record.getInt("SchemaVersion") == 1) return ForgeInventoryTransferExecutor.Observation.unknown();
        if (!record.contains("Observation", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing incident observation");
        var tag = record.getCompound("Observation");
        if (!isBoolean(tag, "Known") || !isBoolean(tag, "ActualStarted") || !tag.contains("ExpectedVariant", Tag.TAG_COMPOUND)
                || !tag.contains("Maximum", Tag.TAG_LONG) || !tag.contains("Observed", Tag.TAG_LONG)
                || !tag.contains("Requested", Tag.TAG_LONG) || !tag.contains("Reserved", Tag.TAG_LONG)
                || !tag.contains("Stage", Tag.TAG_STRING)) throw new IllegalArgumentException("Malformed incident observation");
        return new ForgeInventoryTransferExecutor.Observation(tag.getBoolean("Known"), tag.getCompound("ExpectedVariant"),
                tag.getLong("Maximum"), tag.getLong("Observed"), tag.getLong("Requested"), tag.getLong("Reserved"),
                tag.getBoolean("ActualStarted"), ForgeInventoryTransferExecutor.Stage.valueOf(tag.getString("Stage")));
    }
    private static boolean isBoolean(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_BYTE) && (tag.getByte(key) == 0 || tag.getByte(key) == 1);
    }
    public record Entry(UUID id, ForgeInventoryTransferExecutor.Incident incident, UUID administrator, String explanation) {
        public Entry {
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(incident);
            java.util.Objects.requireNonNull(explanation);
            if (incident.owner() == null || incident.volume() == null || incident.handlerClass().isBlank()
                    || incident.handlerClass().length() > 512 || incident.slot() < 0 || incident.settled() < 0
                    || incident.createdMillis() < 0 || incident.reason().isBlank() || incident.reason().length() > 1024
                    || explanation.length() > 1024 || (administrator == null ? !explanation.isEmpty() : explanation.isBlank())) {
                throw new IllegalArgumentException("Invalid transfer incident");
            }
        }
    }
}
