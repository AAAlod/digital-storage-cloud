package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;

/** Exact-identity, quantity-limited batch. Tick budgets bound exposed endpoint/slot calls. */
public final class BatchTransfer {
    public static final int MAX_ENTRIES = 4096;
    public record Entry(ItemKey key, long amount) {
        public Entry { if (key.isBlank() || amount <= 0) throw new IllegalArgumentException("Invalid batch entry"); }
    }
    public record Result(long moved, String detail) {
        public Result { if (moved < 0) throw new IllegalArgumentException("Negative settlement"); }
        public static Result empty() { return new Result(0, ""); }
    }
    public interface Cursor {
        boolean hasNext();
        Result next(DigitalStorageRecord record, ItemKey key, long maximum);
    }
    public interface Port {
        Iterable<Entry> contents();
        Cursor cursor(boolean exporting);
    }
    public record Route(List<Port> ports, BooleanSupplier current, String location) {
        public Route { ports = List.copyOf(ports); }
    }
    private final DigitalStorageRecord record;
    private final java.util.UUID id = java.util.UUID.randomUUID();
    private final Route route;
    private final List<Entry> selection;
    private final boolean exporting;
    private int itemIndex, portIndex;
    private Cursor cursor;
    private long itemMoved, moved;
    private boolean blocked;
    private String state = "RUNNING", detail = "", blockedDetail = "";

    public BatchTransfer(DigitalStorageRecord record, Route route, List<Entry> selection, boolean exporting) {
        this.record = record; this.route = route; this.selection = List.copyOf(selection); this.exporting = exporting;
        if (selection.isEmpty() || selection.size() > MAX_ENTRIES) throw new IllegalArgumentException("Invalid batch size");
        var identities = new java.util.HashSet<ItemKey>();
        for (var entry : selection) if (!identities.add(entry.key())) throw new IllegalArgumentException("Duplicate selection");
    }
    public static List<Entry> preview(DigitalStorageRecord record, Route route, boolean exporting, boolean unstackable) {
        Map<ItemKey, Long> amounts = new LinkedHashMap<>();
        if (exporting) {
            for (var view : record.storage()) amounts.put(view.getResource(), view.getAmount());
        } else {
            for (var port : route.ports()) for (var entry : port.contents()) {
                amounts.merge(entry.key(), entry.amount(), (a, b) -> Math.min(Integer.MAX_VALUE, a + Math.min(Integer.MAX_VALUE, b)));
                if (amounts.size() > MAX_ENTRIES) throw new IllegalStateException("too_many_items");
            }
        }
        return amounts.entrySet().stream().filter(e -> !unstackable || e.getKey().maximumStackSize() == 1)
                .map(e -> new Entry(e.getKey(), e.getValue())).toList();
    }
    public void tick(int budget) {
        if (!active()) return;
        if (!route.current().getAsBoolean()) { stop("network_changed"); return; }
        int steps = 0;
        while (active() && steps++ < Math.max(1, budget)) {
            if (itemIndex == selection.size()) { state = blocked ? "STOPPED" : "COMPLETE"; if (blocked) detail = blockedDetail.isEmpty() ? "no_space_or_filtered" : blockedDetail; return; }
            var item = selection.get(itemIndex);
            if (!exporting) {
                String rejected = !dev.kehai.digitalstorage.security.ItemSecurityPolicy.canInsert(item.key(), record.acceptsUnstackableItems())
                        ? "unstackables_disabled" : record.storage().amountOf(item.key()) == 0
                        && record.storage().variantCount() >= record.variantCapacity() ? "volume_full"
                        : record.storage().amountOf(item.key()) == 0 && !dev.kehai.digitalstorage.security.ItemSecurityPolicy.canCreateVariant(item.key())
                        ? "volume_rejected" : "";
                if (!rejected.isEmpty()) {
                    blocked = true; if (blockedDetail.isEmpty()) blockedDetail = rejected;
                    itemIndex++; portIndex = 0; cursor = null; itemMoved = 0; continue;
                }
            }
            if (itemMoved >= item.amount() || portIndex >= route.ports().size()) {
                if (itemMoved < item.amount()) blocked = true;
                itemIndex++; portIndex = 0; cursor = null; itemMoved = 0; continue;
            }
            if (cursor == null) cursor = route.ports().get(portIndex).cursor(exporting);
            if (!cursor.hasNext()) { portIndex++; cursor = null; continue; }
            if (!route.current().getAsBoolean()) { stop("network_changed"); return; }
            Result result = cursor.next(record, item.key(), item.amount() - itemMoved);
            if (result.moved() > item.amount() - itemMoved) throw new IllegalStateException("Settlement exceeds requested quantity");
            itemMoved += result.moved(); moved += result.moved();
            if (!result.detail().isEmpty()) stop(result.detail());
        }
    }
    public void stop(String reason) { state = "STOPPED"; detail = reason; }
    public void cancel() { state = "CANCELLED"; detail = "cancelled"; }
    public boolean active() { return state.equals("RUNNING"); }
    public long moved() { return moved; }
    public long total() { return selection.stream().mapToLong(Entry::amount).sum(); }
    public String state() { return state; }
    public String detail() { return detail; }
    public boolean exporting() { return exporting; }
    public java.util.UUID id() { return id; }
}
