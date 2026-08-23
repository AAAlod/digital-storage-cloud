package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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
        if (network == null) {
            return new NetworkParts(List.of(), List.of(), List.of());
        }
        List<Storage<ItemVariant>> rawEndpoints = new ArrayList<>();
        Set<Storage<ItemVariant>> path = Collections.newSetFromMap(new IdentityHashMap<>());
        flattenRaw(network, rawEndpoints, path);
        List<DigitalItemStorage> rawDigital = rawEndpoints.stream()
                .filter(DigitalItemStorage.class::isInstance)
                .map(DigitalItemStorage.class::cast)
                .toList();
        List<DigitalItemStorage> digital = volumeDistinct(rawDigital);
        List<Storage<ItemVariant>> physical = identityDistinct(rawEndpoints.stream()
                .filter(storage -> !(storage instanceof DigitalItemStorage))
                .toList());
        return new NetworkParts(physical, digital, rawDigital);
    }

    record NetworkParts(
            List<Storage<ItemVariant>> physical,
            List<DigitalItemStorage> digital,
            List<DigitalItemStorage> rawDigital
    ) {
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
