package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.Direction;

public final class TomNetworkIntrospection {
    private static final String TOM_PACKAGE = "com.tom.storagemod.";
    private static final ClassValue<Accessors> ACCESSORS = new ClassValue<>() {
        @Override
        protected Accessors computeValue(Class<?> type) {
            return new Accessors(
                    findMethod(type, "get"),
                    findMethod(type, "getInventory"),
                    findMethod(type, "getStorages")
            );
        }
    };

    private TomNetworkIntrospection() {
    }

    /** Resolve a Tom proxy or Accessor to its canonical digital Volume storage. */
    public static DigitalItemStorage canonicalDigitalEndpoint(Storage<ItemVariant> storage) {
        Set<Storage<ItemVariant>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Storage<ItemVariant> current = storage;
        while (current != null && visited.add(current)) {
            if (current instanceof DigitalItemStorage digital) {
                return digital;
            }
            if (current instanceof DigitalStorageAccessorBlockEntity accessor) {
                return accessor.getCanonicalStorage();
            }
            if (!current.getClass().getName().startsWith(TOM_PACKAGE)) {
                return null;
            }
            Storage<ItemVariant> delegate = invokeStorage(current, ACCESSORS.get(current.getClass()).get());
            if (delegate == current) {
                return null;
            }
            current = delegate;
        }
        return null;
    }

    static Storage<ItemVariant> discover(DigitalStorageAccessorBlockEntity accessor) {
        Discovery discovery = discoverContext(accessor);
        return discovery == null ? null : discovery.storage();
    }

    static Discovery discoverContext(DigitalStorageAccessorBlockEntity accessor) {
        if (accessor.getWorld() == null) {
            return null;
        }
        for (Direction direction : Direction.values()) {
            BlockEntity neighbor = accessor.getWorld().getBlockEntity(accessor.getPos().offset(direction));
            if (neighbor == null || !neighbor.getClass().getName().startsWith(TOM_PACKAGE)) {
                continue;
            }
            Accessors accessors = ACCESSORS.get(neighbor.getClass());
            Storage<ItemVariant> storage = invokeStorage(neighbor, accessors.get());
            if (storage == null) {
                storage = invokeStorage(neighbor, accessors.getInventory());
            }
            if (storage != null) {
                return new Discovery(neighbor, storage);
            }
        }
        return null;
    }

    record Discovery(BlockEntity connector, Storage<ItemVariant> storage) {
    }

    static List<Storage<ItemVariant>> physicalParts(Storage<ItemVariant> network) {
        return parts(network).physical();
    }

    static NetworkParts parts(Storage<ItemVariant> network) {
        return parts(network, rawDigitalSnapshot(network));
    }

    static NetworkParts parts(Storage<ItemVariant> network, RawDigitalSnapshot rawDigitalSnapshot) {
        if (network == null) {
            return new NetworkParts(List.of(), List.of(), List.of(), 0L);
        }
        List<Storage<ItemVariant>> rawEndpoints = new ArrayList<>();
        Set<Storage<ItemVariant>> path = Collections.newSetFromMap(new IdentityHashMap<>());
        flattenRaw(network, rawEndpoints, path);
        List<DigitalItemStorage> acceptedDigital = rawEndpoints.stream()
                .filter(DigitalItemStorage.class::isInstance)
                .map(DigitalItemStorage.class::cast)
                .toList();
        List<DigitalItemStorage> digital = volumeDistinct(acceptedDigital);
        List<Storage<ItemVariant>> physical = identityDistinct(rawEndpoints.stream()
                .filter(storage -> !(storage instanceof DigitalItemStorage))
                .toList());
        return new NetworkParts(
                physical,
                digital,
                rawDigitalSnapshot.endpoints(),
                rawDigitalSnapshot.fingerprint()
        );
    }

    record NetworkParts(
            List<Storage<ItemVariant>> physical,
            List<DigitalItemStorage> digital,
            List<DigitalItemStorage> rawDigital,
            long digitalEndpointFingerprint
    ) {
    }

    record RawDigitalSnapshot(List<DigitalItemStorage> endpoints, long fingerprint) {
    }

    static RawDigitalSnapshot rawDigitalSnapshot(Storage<ItemVariant> network) {
        if (network == null) {
            return new RawDigitalSnapshot(List.of(), 0L);
        }
        List<DigitalItemStorage> endpoints = new ArrayList<>();
        Set<Storage<ItemVariant>> path = Collections.newSetFromMap(new IdentityHashMap<>());
        collectRawDigital(network, endpoints, path);
        List<DigitalItemStorage> snapshot = List.copyOf(endpoints);
        return new RawDigitalSnapshot(snapshot, digitalEndpointFingerprint(snapshot));
    }

    static int digitalEndpointCount(List<DigitalItemStorage> endpoints, DigitalItemStorage target) {
        if (target == null) {
            return 0;
        }
        java.util.Optional<UUID> targetVolumeId = target.volumeId();
        if (targetVolumeId.isPresent()) {
            UUID volumeId = targetVolumeId.get();
            return (int) endpoints.stream()
                    .filter(storage -> storage.volumeId().filter(volumeId::equals).isPresent())
                    .count();
        }
        return (int) endpoints.stream().filter(storage -> storage == target).count();
    }

    static int duplicateDigitalEndpointCount(List<DigitalItemStorage> endpoints) {
        EndpointCounts counts = endpointCounts(endpoints);
        return counts.volumeCounts().values().stream().mapToInt(count -> Math.max(0, count - 1)).sum()
                + counts.anonymousCounts().values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
    }

    @SuppressWarnings("unchecked")
    private static void flattenRaw(
            Storage<ItemVariant> storage,
            List<Storage<ItemVariant>> output,
            Set<Storage<ItemVariant>> path
    ) {
        if (!path.add(storage)) {
            return;
        }
        try {
            if (storage instanceof DigitalStorageAccessorBlockEntity accessor) {
                DigitalItemStorage canonical = accessor.getCanonicalStorage();
                if (canonical != null) {
                    flattenRaw(canonical, output, path);
                    return;
                }
            }
            Accessors accessors = ACCESSORS.get(storage.getClass());
            Collection<?> children = invokeCollection(storage, accessors.getStorages());
            Storage<ItemVariant> delegate = storage.getClass().getName().startsWith(TOM_PACKAGE)
                    ? invokeStorage(storage, accessors.get())
                    : null;
            if (delegate != null && delegate != storage) {
                flattenRaw(delegate, output, path);
                return;
            }
            if (children == null) {
                output.add(storage);
                return;
            }
            for (Object child : children) {
                if (child instanceof Storage<?> childStorage) {
                    flattenRaw((Storage<ItemVariant>) childStorage, output, path);
                }
            }
        } finally {
            path.remove(storage);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectRawDigital(
            Storage<ItemVariant> storage,
            List<DigitalItemStorage> output,
            Set<Storage<ItemVariant>> path
    ) {
        if (!path.add(storage)) {
            return;
        }
        try {
            if (storage instanceof TomDigitalEndpointTracker tracker) {
                output.addAll(tracker.digitalstorage$rawDigitalEndpoints());
                return;
            }
            if (storage instanceof DigitalStorageAccessorBlockEntity accessor) {
                DigitalItemStorage canonical = accessor.getCanonicalStorage();
                if (canonical != null) {
                    output.add(canonical);
                }
                return;
            }
            if (storage instanceof DigitalItemStorage digital) {
                output.add(digital);
                return;
            }
            Accessors accessors = ACCESSORS.get(storage.getClass());
            Storage<ItemVariant> delegate = storage.getClass().getName().startsWith(TOM_PACKAGE)
                    ? invokeStorage(storage, accessors.get())
                    : null;
            if (delegate != null && delegate != storage) {
                collectRawDigital(delegate, output, path);
                return;
            }
            Collection<?> children = invokeCollection(storage, accessors.getStorages());
            if (children == null) {
                return;
            }
            for (Object child : children) {
                if (child instanceof Storage<?> childStorage) {
                    collectRawDigital((Storage<ItemVariant>) childStorage, output, path);
                }
            }
        } finally {
            path.remove(storage);
        }
    }

    private static long digitalEndpointFingerprint(List<DigitalItemStorage> endpoints) {
        EndpointCounts counts = endpointCounts(endpoints);
        long sum = endpoints.size() * 0x9E3779B97F4A7C15L;
        long xor = 0L;
        for (Map.Entry<UUID, Integer> entry : counts.volumeCounts().entrySet()) {
            UUID volumeId = entry.getKey();
            long mixed = mix64(volumeId.getMostSignificantBits()
                    ^ Long.rotateLeft(volumeId.getLeastSignificantBits(), 23)
                    ^ (long) entry.getValue() * 0xD6E8FEB86659FD93L);
            sum += mixed;
            xor ^= mixed;
        }
        for (Map.Entry<DigitalItemStorage, Integer> entry : counts.anonymousCounts().entrySet()) {
            long mixed = mix64(Integer.toUnsignedLong(System.identityHashCode(entry.getKey()))
                    ^ (long) entry.getValue() * 0xA0761D6478BD642FL);
            sum += mixed;
            xor ^= mixed;
        }
        return mix64(sum) ^ Long.rotateLeft(xor, 17);
    }

    private static EndpointCounts endpointCounts(List<DigitalItemStorage> endpoints) {
        Map<UUID, Integer> volumeCounts = new HashMap<>();
        Map<DigitalItemStorage, Integer> anonymousCounts = new IdentityHashMap<>();
        for (DigitalItemStorage storage : endpoints) {
            java.util.Optional<UUID> volumeId = storage.volumeId();
            if (volumeId.isPresent()) {
                volumeCounts.merge(volumeId.get(), 1, Integer::sum);
            } else {
                anonymousCounts.merge(storage, 1, Integer::sum);
            }
        }
        return new EndpointCounts(volumeCounts, anonymousCounts);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private record EndpointCounts(
            Map<UUID, Integer> volumeCounts,
            Map<DigitalItemStorage, Integer> anonymousCounts
    ) {
    }

    private static <T> List<T> identityDistinct(List<T> values) {
        Set<T> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return values.stream().filter(visited::add).toList();
    }

    private static List<DigitalItemStorage> volumeDistinct(List<DigitalItemStorage> values) {
        Set<UUID> volumeIds = new HashSet<>();
        Set<DigitalItemStorage> anonymous = Collections.newSetFromMap(new IdentityHashMap<>());
        return values.stream().filter(storage -> storage.volumeId()
                .map(volumeIds::add)
                .orElseGet(() -> anonymous.add(storage))).toList();
    }

    @SuppressWarnings("unchecked")
    private static Method findMethod(Class<?> type, String methodName) {
        try {
            return type.getMethod(methodName);
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Storage<ItemVariant> invokeStorage(Object target, Method method) {
        if (method == null) {
            return null;
        }
        try {
            Object value = method.invoke(target);
            return value instanceof Storage<?> storage ? (Storage<ItemVariant>) storage : null;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return null;
        }
    }

    private static Collection<?> invokeCollection(Object target, Method method) {
        if (method == null) {
            return null;
        }
        try {
            Object value = method.invoke(target);
            return value instanceof Collection<?> collection ? collection : null;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return null;
        }
    }

    private record Accessors(Method get, Method getInventory, Method getStorages) {
    }
}
