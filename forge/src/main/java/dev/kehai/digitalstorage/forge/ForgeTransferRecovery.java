package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.ItemKeyCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Ownership of returned Forge transfer items that cannot settle into a volume.
 * Separate files leave the shared account/volume schemas untouched. HELD may
 * be retried explicitly; DELIVERING must be reconciled after interruption and
 * is never replayed automatically against an independently persisted volume.
 */
public final class ForgeTransferRecovery {
    private static final int SCHEMA = 1;
    private final Path directory;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final List<Path> unreadable = new ArrayList<>();
    private final Map<UUID, Entry> unsaved = new LinkedHashMap<>();
    private final Map<UUID, ReturnedStack> uncaptured = new LinkedHashMap<>();

    public ForgeTransferRecovery(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
            try (var files = Files.list(this.directory)) {
                for (var file : files.filter(path -> path.getFileName().toString().endsWith(".dat")
                        || path.getFileName().toString().endsWith(".tmp")).sorted().toList()) {
                    try {
                        if (file.getFileName().toString().endsWith(".tmp")) {
                            throw new IllegalArgumentException("Interrupted recovery write requires reconciliation");
                        }
                        var entry = read(NbtIo.readCompressed(file.toFile()));
                        if (!file.equals(path(entry.id())) || entries.putIfAbsent(entry.id(), entry) != null) {
                            throw new IllegalArgumentException("Recovery filename or identity mismatch");
                        }
                    } catch (IOException | RuntimeException failure) {
                        // Preserve the original bytes and disable transfers. An
                        // unknown/missing mod item is not an empty recovery stack.
                        unreadable.add(file);
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not open Forge transfer recovery storage", failure);
        }
    }

    public boolean available() { return unreadable.isEmpty() && unsaved.isEmpty() && uncaptured.isEmpty(); }
    public int unreadableFiles() { return unreadable.size(); }
    public int unsavedCount() { return unsaved.size() + uncaptured.size(); }
    public int uncapturedCount() { return uncaptured.size(); }
    public int inFlightCount() { return (int) entries.values().stream().filter(entry -> entry.state() == State.DELIVERING).count(); }
    public List<Entry> entries(UUID owner) {
        return entries.values().stream().filter(entry -> entry.owner().equals(owner)
                && entry.state() != State.DELIVERED).toList();
    }
    public int pendingCount() { return uncaptured.size() + (int) entries.values().stream().filter(entry -> entry.state() != State.DELIVERED).count(); }
    public Entry entry(UUID id) { return entries.get(id); }

    /** Transfers ownership of the actual returned instance before any mod capability serializer runs. */
    public UUID holdReturnedStack(UUID owner, UUID volume, ItemStack returned, String reason) {
        java.util.Objects.requireNonNull(owner);
        java.util.Objects.requireNonNull(volume);
        java.util.Objects.requireNonNull(returned);
        java.util.Objects.requireNonNull(reason);
        if (returned.isEmpty() || returned.getCount() <= 0) throw new IllegalArgumentException("No returned items to hold");
        UUID id = UUID.randomUUID();
        var owned = new ReturnedStack(id, owner, volume, returned, returned.getCount(),
                System.currentTimeMillis(), reason.substring(0, Math.min(512, reason.length())));
        // Do not copy here: ItemStack.copy may itself invoke the failing capability
        // serializer. Caller relinquishes this actual stack and must not mutate it.
        uncaptured.put(id, owned);
        captureReturned(owned);
        return id;
    }

    private void captureReturned(ReturnedStack owned) {
        ItemKey key = ItemKey.of(owned.stack());
        Entry entry = new Entry(owned.id(), owned.owner(), owned.volume(), key, owned.amount(),
                State.HELD, 0, owned.createdMillis(), owned.reason());
        entries.put(entry.id(), entry);
        uncaptured.remove(entry.id());
        try { write(entry); }
        catch (RuntimeException failure) { unsaved.put(entry.id(), entry); throw failure; }
    }

    /** Call only with an actually returned, owned stack; never with simulated or guessed items. */
    public Entry hold(UUID owner, UUID volume, ItemKey key, long amount, String reason) {
        if (!available()) throw new IllegalStateException("Unreadable recovery files require reconciliation");
        Entry entry = new Entry(UUID.randomUUID(), owner, volume, key, amount, State.HELD,
                0, System.currentTimeMillis(), reason);
        entries.put(entry.id(), entry);
        try { write(entry); }
        catch (RuntimeException failure) {
            // Disk failure cannot discard returned items. The server must stop
            // further transfers and retry this retained ownership on flush.
            unsaved.put(entry.id(), entry);
            throw failure;
        }
        return entry;
    }

    public void flushUnsaved() {
        for (var owned : List.copyOf(uncaptured.values())) captureReturned(owned);
        for (var entry : List.copyOf(unsaved.values())) {
            write(entry);
            unsaved.remove(entry.id());
        }
    }

    private record ReturnedStack(UUID id, UUID owner, UUID volume, ItemStack stack,
                                 long amount, long createdMillis, String reason) { }

    /** Persist before committing delivery. A crash from here cannot cause an automatic second delivery. */
    public Entry beginDelivery(UUID id, UUID owner, long amount) {
        Entry previous = requireOwned(id, owner);
        if (previous.state() != State.HELD || amount <= 0 || amount > previous.amount()) {
            throw new IllegalStateException("Recovery delivery is not eligible");
        }
        Entry next = new Entry(id, owner, previous.volume(), previous.key(), previous.amount(), State.DELIVERING,
                amount, previous.createdMillis(), previous.reason());
        write(next);
        entries.put(id, next);
        return next;
    }

    /** Call only after the target volume has committed and its explicit flush has completed. */
    public Entry finishDelivery(UUID id, UUID owner) {
        Entry previous = requireOwned(id, owner);
        if (previous.state() != State.DELIVERING) throw new IllegalStateException("No recovery delivery in progress");
        long remaining = previous.amount() - previous.delivering();
        Entry next = new Entry(id, owner, previous.volume(), previous.key(),
                remaining == 0 ? previous.amount() : remaining,
                remaining == 0 ? State.DELIVERED : State.HELD, 0, previous.createdMillis(), previous.reason());
        // Keep completed receipts. Removing a file is not needed for correctness
        // and cannot turn an interrupted cleanup into another pending delivery.
        write(next);
        entries.put(id, next);
        return next;
    }

    private Entry requireOwned(UUID id, UUID owner) {
        if (!available()) throw new IllegalStateException("Unreadable recovery files require reconciliation");
        Entry entry = entries.get(id);
        if (entry == null || !entry.owner().equals(owner)) throw new IllegalArgumentException("Recovery entry is not owned by this player");
        try {
            // A stale store must not replay a receipt already settled by a
            // reopened store. The world session still owns the single writer.
            Entry persisted = read(NbtIo.readCompressed(path(id).toFile()));
            if (!persisted.id().equals(id) || !persisted.owner().equals(owner)) {
                throw new IllegalArgumentException("Recovery persisted identity changed");
            }
            entries.put(id, persisted);
            return persisted;
        } catch (IOException | RuntimeException failure) {
            if (!unreadable.contains(path(id))) unreadable.add(path(id));
            throw new IllegalStateException("Recovery ownership file requires reconciliation", failure);
        }
    }

    private Path path(UUID id) { return directory.resolve(id + ".dat"); }
    private void write(Entry entry) {
        ForgeTransferFiles.write(path(entry.id()), encode(entry));
    }

    static CompoundTag encode(Entry entry) {
        var tag = new CompoundTag();
        tag.putInt("SchemaVersion", SCHEMA);
        tag.putUUID("Id", entry.id());
        tag.putUUID("Owner", entry.owner());
        tag.putUUID("Volume", entry.volume());
        tag.put("Variant", ItemKeyCodec.write(entry.key()));
        tag.putLong("Amount", entry.amount());
        tag.putString("State", entry.state().name());
        tag.putLong("Delivering", entry.delivering());
        tag.putLong("CreatedMillis", entry.createdMillis());
        tag.putString("Reason", entry.reason());
        return tag;
    }

    static Entry read(CompoundTag tag) {
        if (tag == null || !tag.contains("SchemaVersion", Tag.TAG_INT) || tag.getInt("SchemaVersion") != SCHEMA
                || !tag.hasUUID("Id") || !tag.hasUUID("Owner") || !tag.hasUUID("Volume")
                || !tag.contains("Variant", Tag.TAG_COMPOUND) || !tag.contains("Amount", Tag.TAG_LONG)
                || !tag.contains("State", Tag.TAG_STRING) || !tag.contains("Delivering", Tag.TAG_LONG)
                || !tag.contains("CreatedMillis", Tag.TAG_LONG) || !tag.contains("Reason", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Unsupported or incomplete Forge recovery record");
        }
        var variant = tag.getCompound("Variant");
        if (!variant.contains("item", Tag.TAG_STRING)
                || (variant.contains("tag") && !variant.contains("tag", Tag.TAG_COMPOUND))
                || (variant.contains("attachments") && !variant.contains("attachments", Tag.TAG_COMPOUND))) {
            throw new IllegalArgumentException("Malformed Forge recovery item identity");
        }
        return new Entry(tag.getUUID("Id"), tag.getUUID("Owner"), tag.getUUID("Volume"),
                ItemKeyCodec.read(variant), tag.getLong("Amount"), State.valueOf(tag.getString("State")),
                tag.getLong("Delivering"), tag.getLong("CreatedMillis"), tag.getString("Reason"));
    }

    public enum State { HELD, DELIVERING, DELIVERED }
    public record Entry(UUID id, UUID owner, UUID volume, ItemKey key, long amount,
                        State state, long delivering, long createdMillis, String reason) {
        public Entry {
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(owner);
            java.util.Objects.requireNonNull(volume);
            java.util.Objects.requireNonNull(key);
            java.util.Objects.requireNonNull(state);
            java.util.Objects.requireNonNull(reason);
            if (key.isBlank() || amount <= 0 || delivering < 0 || delivering > amount
                    || (state == State.DELIVERING) != (delivering > 0) || createdMillis < 0 || reason.length() > 512) {
                throw new IllegalArgumentException("Invalid Forge recovery ownership record");
            }
        }
    }
}
