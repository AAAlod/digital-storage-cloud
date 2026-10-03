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
        tag.putInt("SchemaVersion", 1);
        tag.putUUID("Id", entry.id());
        tag.putUUID("Owner", incident.owner());
        tag.putUUID("Volume", incident.volume());
        tag.putString("Handler", incident.handlerClass());
        tag.putInt("Slot", incident.slot());
        tag.putLong("Settled", incident.settled());
        tag.putLong("CreatedMillis", incident.createdMillis());
        tag.putString("Reason", incident.reason());
        if (entry.administrator() != null) tag.putUUID("Administrator", entry.administrator());
        tag.putString("Explanation", entry.explanation());
        return tag;
    }
    static Entry read(CompoundTag tag) {
        if (tag == null || !tag.contains("SchemaVersion", Tag.TAG_INT) || tag.getInt("SchemaVersion") != 1
                || !tag.hasUUID("Id") || !tag.hasUUID("Owner") || !tag.hasUUID("Volume")
                || !tag.contains("Handler", Tag.TAG_STRING) || !tag.contains("Slot", Tag.TAG_INT)
                || !tag.contains("Settled", Tag.TAG_LONG) || !tag.contains("CreatedMillis", Tag.TAG_LONG)
                || !tag.contains("Reason", Tag.TAG_STRING) || !tag.contains("Explanation", Tag.TAG_STRING)
                || (tag.contains("Administrator") && !tag.hasUUID("Administrator"))) {
            throw new IllegalArgumentException("Unsupported or incomplete transfer incident");
        }
        return new Entry(tag.getUUID("Id"), new ForgeInventoryTransferExecutor.Incident(tag.getUUID("Owner"),
                tag.getUUID("Volume"), tag.getString("Handler"), tag.getInt("Slot"), tag.getLong("Settled"),
                tag.getLong("CreatedMillis"), tag.getString("Reason")),
                tag.hasUUID("Administrator") ? tag.getUUID("Administrator") : null, tag.getString("Explanation"));
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
