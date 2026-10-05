package dev.kehai.digitalstorage.forge.tom;

import com.tom.storagemod.util.IProxy;
import com.tom.storagemod.util.MultiItemHandler;
import dev.kehai.digitalstorage.forge.ForgeDigitalItemStorage;
import dev.kehai.digitalstorage.forge.ForgeInventoryEndpoint;
import dev.kehai.digitalstorage.optimization.InventoryEndpoint;
import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.TopologyToken;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraftforge.items.IItemHandler;

/** Conservative identity/slot topology over Tom handles; inventory contents are never a topology key. */
public final class ForgeTomTopology {
    private ForgeTomTopology() { }

    public static List<IItemHandler> physicalHandlers(IItemHandler root) {
        return inspect(root).physical.stream().filter(ForgeTomTopology::trustedPhysical).toList();
    }
    private static boolean trustedPhysical(IItemHandler handler) {
        if (handler instanceof ForgeTomEndpoints.FilteredEndpoint filtered) return trustedPhysical(filtered.digitalstorage$parent());
        if (handler instanceof IProxy proxy) return trustedPhysical(proxy.get());
        if (handler instanceof MultiItemHandler multi) return multi.getHandlers().stream()
                .allMatch(child -> child.orElse(null) != null && trustedPhysical(child.orElse(null)));
        return handler.getClass().getName().startsWith("net.minecraftforge.items.");
    }

    public static NetworkAnalysis.Report analyze(DigitalStorageRecord record, Supplier<IItemHandler> currentNetwork) {
        return analyze(record, currentNetwork, new ForgeScannerTelemetry.Snapshot(0, 0, 0));
    }
    public static NetworkAnalysis.Report analyze(DigitalStorageRecord record, Supplier<IItemHandler> currentNetwork,
                                                  ForgeScannerTelemetry.Snapshot scanners) {
        var root = currentNetwork.get();
        if (record == null || root == null) return NetworkAnalysis.Report.unavailable();
        var parts = inspect(root);
        var token = new Token(currentNetwork, parts.nodes);
        var sources = parts.physical.stream().map(handler -> (InventoryEndpoint) new ForgeInventoryEndpoint(handler)).toList();
        var references = parts.physical.stream().map(handler -> (InventoryEndpoint.Reference) new Reference(handler, token)).toList();
        var target = ForgeDigitalItemStorage.of(record.storage());
        int targetCount = 0;
        int duplicates = 0;
        var seen = new HashSet<Object>();
        for (var endpoint : parts.raw) {
            if (ForgeTomEndpoints.sameVolume(endpoint, target)) targetCount++;
            Object identity = endpoint.ledger().volumeId().<Object>map(id -> id).orElse(endpoint.ledger());
            if (!seen.add(identity)) duplicates++;
        }
        return NetworkAnalysis.analyze(record, new NetworkAnalysis.Snapshot(sources,
                parts.digital.stream().mapToInt(handler -> handler.ledger().variantCount()).sum(),
                duplicates, targetCount, scanners.active(), scanners.failing(), scanners.interval(), token, references));
    }

    private static Parts inspect(IItemHandler root) {
        var parts = new Parts();
        walk(root, parts, Collections.newSetFromMap(new IdentityHashMap<>()), true);
        return parts;
    }

    private static void walk(IItemHandler handler, Parts parts, Set<IItemHandler> path, boolean captureRaw) {
        if (handler == null) return;
        if (!path.add(handler)) throw new IllegalStateException("Cyclic Tom inventory topology");
        try {
            parts.nodes.add(new Node(handler, handler.getSlots(), handler instanceof ForgeTomEndpoints.FilteredEndpoint filtered
                    && filtered.digitalstorage$keepLast()));
            var digital = ForgeTomEndpoints.underlyingDigital(handler);
            if (digital != null) {
                traceDigitalIdentity(handler, parts.nodes);
                if (captureRaw) parts.raw.add(digital);
                if (parts.digital.stream().noneMatch(existing -> ForgeTomEndpoints.sameVolume(existing, digital))) {
                    parts.digital.add(digital);
                }
                return;
            }
            if (handler instanceof MultiItemHandler multi) {
                if (!(handler instanceof ForgeTomEndpoints.RawDigitalEndpoints tracker)) {
                    throw new IllegalStateException("Tom raw endpoint tracker missing");
                }
                for (var raw : tracker.digitalstorage$rawDigitalEndpoints()) {
                    parts.nodes.add(new Node(raw, raw.getSlots(), false));
                    var rawDigital = ForgeTomEndpoints.underlyingDigital(raw);
                    if (rawDigital != null) {
                        traceDigitalIdentity(raw, parts.nodes);
                        parts.raw.add(rawDigital);
                    }
                }
                for (var child : multi.getHandlers()) walk(child.orElse(null), parts, path, false);
            } else if (handler instanceof IProxy proxy) {
                walk(proxy.get(), parts, path, captureRaw);
            } else if (handler instanceof ForgeTomEndpoints.FilteredEndpoint filtered) {
                // A filter around a mixed aggregate cannot be flattened without
                // losing its rules, nor safely treated as a purely physical source.
                var parentParts = new Parts();
                walk(filtered.digitalstorage$parent(), parentParts, path, true);
                if (!parentParts.digital.isEmpty()) throw new IllegalStateException("Filtered mixed digital/physical aggregate");
                parts.nodes.addAll(parentParts.nodes);
                if (parts.physical.stream().noneMatch(existing -> existing == handler)) parts.physical.add(handler);
            } else if (parts.physical.stream().noneMatch(existing -> existing == handler)) {
                parts.physical.add(handler);
            }
        } finally { path.remove(handler); }
    }

    private static void traceDigitalIdentity(IItemHandler handler, List<Node> nodes) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<IItemHandler, Boolean>());
        while (handler != null && seen.add(handler)) {
            nodes.add(new Node(handler, handler.getSlots(), handler instanceof ForgeTomEndpoints.FilteredEndpoint filtered
                    && filtered.digitalstorage$keepLast()));
            if (handler instanceof ForgeTomEndpoints.FilteredEndpoint filtered) handler = filtered.digitalstorage$parent();
            else if (handler instanceof IProxy proxy) handler = proxy.get();
            else return;
        }
    }

    private static final class Parts {
        final List<IItemHandler> physical = new ArrayList<>();
        final List<ForgeDigitalItemStorage> digital = new ArrayList<>();
        final List<ForgeDigitalItemStorage> raw = new ArrayList<>();
        final List<Node> nodes = new ArrayList<>();
    }

    private record Node(WeakReference<IItemHandler> handler, int slots, boolean keepLast) {
        Node(IItemHandler handler, int slots, boolean keepLast) { this(new WeakReference<>(handler), slots, keepLast); }
        boolean matches(Node other) {
            var current = handler.get();
            return current != null && current == other.handler.get() && slots == other.slots && keepLast == other.keepLast;
        }
    }

    private static final class Token implements TopologyToken {
        private final Supplier<IItemHandler> root;
        private final List<Node> nodes;
        private String detail = "";
        private boolean invalid;
        Token(Supplier<IItemHandler> root, List<Node> nodes) { this.root = root; this.nodes = List.copyOf(nodes); }
        public boolean isCurrent() {
            if (invalid) return false;
            try {
                var current = root.get();
                if (current == null) return invalidate("Tom network capability unavailable");
                var trace = inspect(current).nodes;
                if (trace.size() != nodes.size()) return invalidate("Tom endpoint count changed");
                for (int index = 0; index < nodes.size(); index++) {
                    if (!nodes.get(index).matches(trace.get(index))) {
                        return invalidate("Tom handler identity, slot mapping or filter changed");
                    }
                }
                return true;
            } catch (RuntimeException failure) {
                return invalidate("Tom topology could not be resolved: " + failure.getClass().getSimpleName());
            }
        }
        private boolean invalidate(String reason) { invalid = true; detail = reason; return false; }
        public String staleDetail() { return detail; }
    }

    private record Reference(WeakReference<IItemHandler> handler, Token token) implements InventoryEndpoint.Reference {
        Reference(IItemHandler handler, Token token) { this(new WeakReference<>(handler), token); }
        public InventoryEndpoint resolve() {
            var current = handler.get();
            return current != null && token.isCurrent() ? new ForgeInventoryEndpoint(current) : null;
        }
    }
}
