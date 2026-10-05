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
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

/**
 * Ownership of returned Forge transfer items that cannot settle into a volume.
 * Separate files leave the shared account/volume schemas untouched. HELD may
 * be retried explicitly; DELIVERING must be reconciled after interruption and
 * is never replayed automatically against an independently persisted volume.
 */
public final class ForgeTransferRecovery {
    private static final int SCHEMA = 2;
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
    public int unsavedCount(UUID owner) {
        return (int) unsaved.values().stream().filter(entry -> entry.owner().equals(owner)).count()
                + (int) uncaptured.values().stream().filter(entry -> entry.owner().equals(owner)).count();
    }
    public int inFlightCount() { return (int) entries.values().stream().filter(entry -> entry.state() == State.DELIVERING).count(); }
    public List<Entry> entries(UUID owner) {
        return entries.values().stream().filter(entry -> entry.owner().equals(owner)
                && entry.state() != State.DELIVERED).toList();
    }
    public int pendingCount() { return uncaptured.size() + (int) entries.values().stream().filter(entry -> entry.state() != State.DELIVERED).count(); }
    public Entry entry(UUID id) { return entries.get(id); }
    /** Refresh authoritative ownership/state before resolving a delivery target. */
    public Entry ownedEntry(UUID id, UUID owner) { return requireOwned(id, owner); }

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

    /** Exact ledger withdrawal ownership. Intent is persisted before any external insertion. */
    public Entry holdWithdrawal(UUID owner, UUID volume, ItemKey key, long amount) {
        if (!available()) throw new IllegalStateException("Recovery store requires reconciliation");
        // A withdrawal is not a returned stack. Never expose it as HELD: a
        // failed intent write may roll back the source transaction.
        var entry = new Entry(UUID.randomUUID(), owner, volume, key, amount, State.DELIVERING,
                amount, System.currentTimeMillis(), "batch export withdrawal; interrupted source/destination require reconciliation",
                UUID.randomUUID(), List.of());
        entries.put(entry.id(), entry);
        try { write(entry); }
        catch (RuntimeException failure) { unsaved.put(entry.id(), entry); throw failure; }
        return entry;
    }

    /** Both accepted and restored amounts have settled; the whole withdrawal receipt is consumed. */
    public void finishWithdrawal(UUID id, UUID owner, UUID delivery, long delivered) {
        Entry previous = requireOwned(id, owner);
        if (previous.state() != State.DELIVERING || !java.util.Objects.equals(delivery, previous.deliveryId())
                || delivered < 0 || delivered > previous.amount()) throw new IllegalStateException("Invalid export receipt");
        var next = new Entry(id, owner, previous.volume(), previous.key(), previous.amount(), State.DELIVERED,
                0, previous.createdMillis(), previous.reason() + "; settled=" + delivered, null, previous.reconciliations());
        write(next); entries.put(id, next);
    }

    /** Only after a durable hopper handoff intent; stable identity makes retries idempotent. */
    Entry adoptHopper(UUID id, UUID owner, UUID volume, ItemKey key, long amount) {
        if (!available()) throw new IllegalStateException("Recovery store requires reconciliation");
        String reason = "hopper custody " + id + " amount=" + amount;
        if (entries.containsKey(id)) {
            var existing = requireOwned(id, owner);
            if (!existing.volume().equals(volume) || !existing.key().equals(key)
                    || !existing.reason().equals(reason) || existing.amount() > amount) {
                throw new IllegalStateException("Recovery identity conflicts with hopper handoff");
            }
            return existing;
        }
        if (Files.exists(path(id))) throw new IllegalStateException("Recovery file changed; reopen before handoff");
        var entry = new Entry(id, owner, volume, key, amount, State.HELD, 0, System.currentTimeMillis(), reason);
        entries.put(id, entry);
        try { write(entry); }
        catch (RuntimeException failure) { unsaved.put(id, entry); throw failure; }
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
                amount, previous.createdMillis(), previous.reason(), UUID.randomUUID(), previous.reconciliations());
        entries.put(id, next);
        try { write(next); }
        catch (RuntimeException failure) {
            // A failed intent write must block another attempt in this session,
            // even if replacement status cannot be proved. Flush persists the
            // conservative DELIVERING state; it never repeats target delivery.
            unsaved.put(id, next);
            throw failure;
        }
        return next;
    }

    /** Call only after the target volume has committed and its explicit flush has completed. */
    public Entry finishDelivery(UUID id, UUID owner, UUID expectedDelivery) {
        Entry previous = requireOwned(id, owner);
        if (previous.state() != State.DELIVERING || !java.util.Objects.equals(expectedDelivery, previous.deliveryId())) {
            throw new IllegalStateException("No matching recovery delivery in progress");
        }
        long remaining = previous.amount() - previous.delivering();
        Entry next = new Entry(id, owner, previous.volume(), previous.key(),
                remaining == 0 ? previous.amount() : remaining,
                remaining == 0 ? State.DELIVERED : State.HELD, 0, previous.createdMillis(), previous.reason(),
                null, previous.reconciliations());
        // Keep completed receipts. Removing a file is not needed for correctness
        // and cannot turn an interrupted cleanup into another pending delivery.
        write(next);
        entries.put(id, next);
        return next;
    }

    /** Administrator must explicitly verify external persistence; this method never moves target items. */
    public Entry reconcile(UUID id, UUID expectedDelivery, UUID administrator, Outcome outcome, String explanation) {
        java.util.Objects.requireNonNull(expectedDelivery);
        java.util.Objects.requireNonNull(administrator);
        java.util.Objects.requireNonNull(outcome);
        Entry known = entries.get(id);
        if (known == null) throw new IllegalArgumentException("Unknown recovery entry");
        Entry previous = requireOwned(id, known.owner());
        if (previous.reason().startsWith("batch export withdrawal")) {
            // The source may already contain a known rejected remainder, or a
            // failed intent may have rolled its reservation back entirely.
            // Import recovery's binary outcome cannot reconstruct that split.
            throw new IllegalStateException("Batch withdrawal requires source and destination reconciliation; import recovery cannot replay it");
        }
        if (previous.state() != State.DELIVERING || !expectedDelivery.equals(previous.deliveryId())) {
            throw new IllegalStateException("Recovery delivery attempt changed or is no longer uncertain");
        }
        var receipt = new Reconciliation(expectedDelivery, administrator, outcome, previous.delivering(),
                System.currentTimeMillis(), explanation);
        var receipts = new ArrayList<>(previous.reconciliations());
        receipts.add(receipt);
        long remaining = outcome == Outcome.CONFIRMED_DELIVERED ? previous.amount() - previous.delivering() : previous.amount();
        Entry next = new Entry(id, previous.owner(), previous.volume(), previous.key(),
                remaining == 0 ? previous.amount() : remaining, remaining == 0 ? State.DELIVERED : State.HELD,
                0, previous.createdMillis(), previous.reason(), null, receipts);
        // Receipt and state share one forced/replace file. A failing write keeps
        // the explicit decision unsaved and blocks another transfer until flush.
        entries.put(id, next);
        try { write(next); }
        catch (RuntimeException failure) { unsaved.put(id, next); throw failure; }
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
        if (entry.deliveryId() != null) tag.putUUID("DeliveryId", entry.deliveryId());
        var receipts = new ListTag();
        for (var receipt : entry.reconciliations()) {
            var saved = new CompoundTag();
            saved.putUUID("DeliveryId", receipt.deliveryId());
            saved.putUUID("Administrator", receipt.administrator());
            saved.putString("Outcome", receipt.outcome().name());
            saved.putLong("Amount", receipt.amount());
            saved.putLong("CreatedMillis", receipt.createdMillis());
            saved.putString("Explanation", receipt.explanation());
            receipts.add(saved);
        }
        tag.put("Reconciliations", receipts);
        return tag;
    }

    static Entry read(CompoundTag tag) {
        if (tag == null || !tag.contains("SchemaVersion", Tag.TAG_INT)
                || (tag.getInt("SchemaVersion") != 1 && tag.getInt("SchemaVersion") != SCHEMA)
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
        if (tag.getInt("SchemaVersion") == 1) return new Entry(tag.getUUID("Id"), tag.getUUID("Owner"), tag.getUUID("Volume"),
                ItemKeyCodec.read(variant), tag.getLong("Amount"), State.valueOf(tag.getString("State")),
                tag.getLong("Delivering"), tag.getLong("CreatedMillis"), tag.getString("Reason"));
        if (!tag.contains("Reconciliations", Tag.TAG_LIST) || (tag.contains("DeliveryId") && !tag.hasUUID("DeliveryId"))) {
            throw new IllegalArgumentException("Malformed recovery delivery history");
        }
        var savedReceipts = (ListTag) tag.get("Reconciliations");
        if (!savedReceipts.isEmpty() && savedReceipts.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Malformed reconciliation list");
        }
        var receipts = new ArrayList<Reconciliation>();
        for (var saved : savedReceipts) {
            var receipt = (CompoundTag) saved;
            if (!receipt.hasUUID("DeliveryId") || !receipt.hasUUID("Administrator") || !receipt.contains("Outcome", Tag.TAG_STRING)
                    || !receipt.contains("Amount", Tag.TAG_LONG) || !receipt.contains("CreatedMillis", Tag.TAG_LONG)
                    || !receipt.contains("Explanation", Tag.TAG_STRING)) throw new IllegalArgumentException("Incomplete reconciliation receipt");
            receipts.add(new Reconciliation(receipt.getUUID("DeliveryId"), receipt.getUUID("Administrator"),
                    Outcome.valueOf(receipt.getString("Outcome")), receipt.getLong("Amount"),
                    receipt.getLong("CreatedMillis"), receipt.getString("Explanation")));
        }
        return new Entry(tag.getUUID("Id"), tag.getUUID("Owner"), tag.getUUID("Volume"), ItemKeyCodec.read(variant),
                tag.getLong("Amount"), State.valueOf(tag.getString("State")), tag.getLong("Delivering"),
                tag.getLong("CreatedMillis"), tag.getString("Reason"), tag.hasUUID("DeliveryId") ? tag.getUUID("DeliveryId") : null, receipts);
    }

    public enum State { HELD, DELIVERING, DELIVERED }
    public record Entry(UUID id, UUID owner, UUID volume, ItemKey key, long amount,
                        State state, long delivering, long createdMillis, String reason,
                        UUID deliveryId, List<Reconciliation> reconciliations) {
        public Entry(UUID id, UUID owner, UUID volume, ItemKey key, long amount, State state, long delivering,
                     long createdMillis, String reason) {
            // Legacy DELIVERING has no original attempt token. A stable derived
            // marker permits explicit reconciliation; new attempts use random IDs.
            this(id, owner, volume, key, amount, state, delivering, createdMillis, reason,
                    state == State.DELIVERING ? UUID.nameUUIDFromBytes(("legacy:" + id + ":" + amount + ":" + delivering + ":" + createdMillis)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)) : null, List.of());
        }
        public Entry {
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(owner);
            java.util.Objects.requireNonNull(volume);
            java.util.Objects.requireNonNull(key);
            java.util.Objects.requireNonNull(state);
            java.util.Objects.requireNonNull(reason);
            reconciliations = List.copyOf(reconciliations);
            if (key.isBlank() || amount <= 0 || delivering < 0 || delivering > amount
                    || (state == State.DELIVERING) != (delivering > 0) || (state == State.DELIVERING) != (deliveryId != null)
                    || createdMillis < 0 || reason.length() > 512
                    || reconciliations.stream().map(Reconciliation::deliveryId).distinct().count() != reconciliations.size()
                    || reconciliations.stream().anyMatch(receipt -> receipt.deliveryId().equals(deliveryId))) {
                throw new IllegalArgumentException("Invalid Forge recovery ownership record");
            }
        }
    }
    public enum Outcome { CONFIRMED_DELIVERED, CONFIRMED_NOT_DELIVERED }
    public record Reconciliation(UUID deliveryId, UUID administrator, Outcome outcome, long amount,
                                 long createdMillis, String explanation) {
        public Reconciliation {
            java.util.Objects.requireNonNull(deliveryId);
            java.util.Objects.requireNonNull(administrator);
            java.util.Objects.requireNonNull(outcome);
            java.util.Objects.requireNonNull(explanation);
            if (amount <= 0 || createdMillis < 0 || explanation.isBlank() || explanation.length() > 1024) {
                throw new IllegalArgumentException("Reconciliation needs an explicit verified explanation");
            }
        }
    }
}
