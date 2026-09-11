package dev.kehai.digitalstorage.optimization;

import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.util.math.Direction;

/** Structural keys only: never reads item contents or advances a storage iterator. */
public final class TomStorageIdentity {
    private TomStorageIdentity() { }

    public interface Sided {
        Object digitalstorage$identity();
    }

    public static Object sided(Object backing, InventoryStorage storage, Direction direction) {
        // Capture the actual exposed slots once, when Fabric constructs its wrapper.
        // Underlying views are the canonical transactional slots, shared across rebuilds.
        return new SidedKey(new Identity(backing), direction, storage.getSlots().stream()
                .map(slot -> new Identity(slot.getUnderlyingView())).toList());
    }

    static Object key(Storage<?> storage) {
        if (storage instanceof Sided sided) {
            return sided.digitalstorage$identity();
        }
        if (storage instanceof InventoryStorage inventory) {
            return new InventoryKey(new Identity(storage), inventory.getSlotCount());
        }
        // Vanilla double chests use this exact, transparent container. Subclasses
        // may impose filters or other semantics, so retain identity for those.
        if (storage.getClass() == CombinedStorage.class) {
            return new CombinedKey(((CombinedStorage<?, ?>) storage).parts.stream()
                    .map(TomStorageIdentity::key).toList());
        }
        return new Identity(storage);
    }

    private record SidedKey(Identity backing, Direction direction, List<Identity> slots) { }
    private record InventoryKey(Identity storage, int slots) { }
    private record CombinedKey(List<Object> parts) { }

    private static final class Identity {
        private final java.lang.ref.WeakReference<Object> value;
        private final int hash;
        private Identity(Object value) {
            this.value = new java.lang.ref.WeakReference<>(value);
            this.hash = System.identityHashCode(value);
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object resolved = value.get();
            return resolved != null && other instanceof Identity identity && resolved == identity.value.get();
        }
        @Override public int hashCode() { return hash; }
    }
}
