package dev.kehai.digitalstorage.optimization;

import com.google.common.collect.MapMaker;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;

/**
 * Event-invalidated Tom topology snapshots shared by analysis, migration and
 * scanner telemetry. Snapshot construction never enumerates StorageViews.
 * All mutation happens on the server thread.
 */
public final class TomNetworkCache {
    private static final Map<BlockEntity, Entry> ENTRIES = new WeakHashMap<>();
    private static final Map<Storage<ItemVariant>, Set<NetworkIdentity>> NETWORKS_BY_STORAGE
            = new MapMaker().weakKeys().makeMap();
    private static int tomInventoryRange = -1;

    private TomNetworkCache() {
    }

    public static void register() {
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(TomNetworkCache::onBlockEntityUnload);
    }

    static Topology topology(BlockEntity connector, Storage<ItemVariant> network) {
        Entry entry = ENTRIES.computeIfAbsent(connector, Entry::new);
        Topology cached = entry.topology;
        if (cached != null && cached.version() == entry.version && cached.network().get() == network) {
            List<Storage<ItemVariant>> physical = resolve(cached.physicalReferences());
            List<DigitalItemStorage> digital = resolveDigital(cached.digitalReferences());
            List<DigitalItemStorage> rawDigital = resolveDigital(cached.rawDigitalReferences());
            if (physical != null && digital != null && rawDigital != null) {
                return cached.withResolved(physical, digital, rawDigital);
            }
        }

        TomNetworkIntrospection.NetworkParts parts = TomNetworkIntrospection.parts(network);
        List<WeakReference<Storage<ItemVariant>>> physicalReferences = weakReferences(parts.physical());
        List<WeakReference<DigitalItemStorage>> digitalReferences = weakDigitalReferences(parts.digital());
        List<WeakReference<DigitalItemStorage>> rawDigitalReferences = weakDigitalReferences(parts.rawDigital());
        List<Endpoint> physicalEndpoints = new ArrayList<>(physicalReferences.size());
        for (int index = 0; index < physicalReferences.size(); index++) {
            physicalEndpoints.add(new Endpoint(entry.identity, entry.version, index, physicalReferences.get(index)));
        }
        Topology rebuilt = new Topology(
                entry.identity,
                entry.version,
                new WeakReference<>(network),
                physicalReferences,
                digitalReferences,
                rawDigitalReferences,
                List.copyOf(physicalEndpoints),
                parts.physical(),
                parts.digital(),
                parts.rawDigital()
        );
        unregisterStorages(entry);
        entry.topology = rebuilt.withResolved(List.of(), List.of(), List.of());
        entry.invalidationDetail = "";
        registerStorages(entry, network, parts.physical(), parts.digital());
        return rebuilt;
    }

    public static void invalidate(BlockEntity connector) {
        Entry entry = ENTRIES.computeIfAbsent(connector, Entry::new);
        invalidateEntry(entry, "network changed");
    }

    static NetworkIdentity networkFor(Storage<ItemVariant> storage) {
        Set<NetworkIdentity> identities = storage == null ? null : NETWORKS_BY_STORAGE.get(storage);
        if (identities == null) {
            return null;
        }
        return identities.stream().filter(identity -> identity.connector().get() != null).findFirst().orElse(null);
    }

    public static boolean isCurrent(Token token) {
        if (token == null) {
            return false;
        }
        BlockEntity connector = token.identity().connector().get();
        Entry entry = connector == null ? null : ENTRIES.get(connector);
        return connector != null && !connector.isRemoved() && entry != null && entry.version == token.version();
    }

    static String staleDetail(Token token) {
        if (token == null) {
            return "network changed";
        }
        BlockEntity connector = token.identity().connector().get();
        if (connector == null || connector.isRemoved()) {
            return "connector unloaded";
        }
        Entry entry = ENTRIES.get(connector);
        if (entry == null || entry.version == token.version()) {
            return "network changed";
        }
        return entry.invalidationDetail.isEmpty() ? "network changed" : entry.invalidationDetail;
    }

    static void clear() {
        ENTRIES.clear();
        NETWORKS_BY_STORAGE.clear();
    }

    static void runSelfTest() {
        BlockEntity connector = new dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity(
                net.minecraft.util.math.BlockPos.ORIGIN,
                dev.kehai.digitalstorage.DigitalStorageMod.DIGITAL_STORAGE_ACCESSOR.getDefaultState()
        );
        Storage<ItemVariant> source = new Storage<>() {
            @Override
            public long insert(ItemVariant resource, long maxAmount,
                               net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                return 0;
            }

            @Override
            public long extract(ItemVariant resource, long maxAmount,
                                net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                return 0;
            }

            @Override
            public java.util.Iterator<net.fabricmc.fabric.api.transfer.v1.storage.StorageView<ItemVariant>> iterator() {
                return Collections.emptyIterator();
            }
        };
        try {
            Topology sourceTopology = topology(connector, source);
            Token sourceToken = sourceTopology.token();
            if (!isCurrent(sourceToken) || sourceTopology.physicalEndpoints().size() != 1) {
                throw new IllegalStateException("Tom lifecycle self-test could not create a current source endpoint");
            }
            invalidateStorage(source, "inventory unloaded");
            invalidate(connector);
            if (isCurrent(sourceToken)
                    || !"inventory unloaded".equals(staleDetail(sourceToken))
                    || sourceTopology.physicalEndpoints().get(0).resolve(sourceToken) != null) {
                throw new IllegalStateException("Tom lifecycle self-test retained an unloaded source endpoint");
            }

            Token connectorToken = topology(connector, source).token();
            invalidateConnectorOnUnload(connector);
            if (isCurrent(connectorToken) || !"connector unloaded".equals(staleDetail(connectorToken))) {
                throw new IllegalStateException("Tom lifecycle self-test retained an unloaded connector");
            }
        } finally {
            Entry entry = ENTRIES.remove(connector);
            if (entry != null) {
                unregisterStorages(entry);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void onBlockEntityUnload(BlockEntity blockEntity, ServerWorld world) {
        boolean matched = invalidateConnectorOnUnload(blockEntity);
        Set<Storage<ItemVariant>> exposed = Collections.newSetFromMap(new IdentityHashMap<>());
        if (blockEntity instanceof Storage<?> storage) {
            exposed.add((Storage<ItemVariant>) storage);
        }
        if (blockEntity instanceof Inventory inventory) {
            exposed.add(InventoryStorage.of(inventory, null));
            for (Direction direction : Direction.values()) {
                exposed.add(InventoryStorage.of(inventory, direction));
            }
        }
        for (Storage<ItemVariant> storage : exposed) {
            matched |= invalidateStorage(storage, "inventory unloaded");
        }
        if (!matched) {
            invalidateNearbyNetworks(world, blockEntity);
        }
    }

    private static void invalidateNearbyNetworks(ServerWorld world, BlockEntity unloaded) {
        int range = tomInventoryRange();
        if (range <= 0) {
            return;
        }
        long squaredRange = (long) range * range;
        for (Entry entry : List.copyOf(ENTRIES.values())) {
            BlockEntity connector = entry.identity.connector().get();
            if (connector != null
                    && connector.getWorld() == world
                    && connector.getPos().getSquaredDistance(unloaded.getPos()) < squaredRange) {
                invalidateEntry(entry, "inventory unloaded");
            }
        }
    }

    private static int tomInventoryRange() {
        if (tomInventoryRange >= 0) {
            return tomInventoryRange;
        }
        try {
            Class<?> storageMod = Class.forName("com.tom.storagemod.StorageMod");
            Object config = storageMod.getField("CONFIG").get(null);
            tomInventoryRange = Math.max(0, config.getClass().getField("invRange").getInt(config));
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException exception) {
            tomInventoryRange = 0;
        }
        return tomInventoryRange;
    }

    private static boolean invalidateConnectorOnUnload(BlockEntity connector) {
        Entry entry = ENTRIES.get(connector);
        if (entry != null) {
            invalidateEntry(entry, "connector unloaded");
            return true;
        }
        return false;
    }

    private static boolean invalidateStorage(Storage<ItemVariant> storage, String detail) {
        Set<NetworkIdentity> identities = NETWORKS_BY_STORAGE.get(storage);
        if (identities == null) {
            return false;
        }
        boolean matched = false;
        for (NetworkIdentity identity : List.copyOf(identities)) {
            BlockEntity connector = identity.connector().get();
            Entry entry = connector == null ? null : ENTRIES.get(connector);
            if (entry != null) {
                invalidateEntry(entry, detail);
                matched = true;
            }
        }
        return matched;
    }

    private static void invalidateEntry(Entry entry, String detail) {
        boolean wasCurrent = entry.topology != null;
        entry.version++;
        entry.topology = null;
        if (wasCurrent || entry.invalidationDetail.isEmpty()) {
            entry.invalidationDetail = detail;
        }
    }

    @SafeVarargs
    private static void registerStorages(
            Entry entry,
            Storage<ItemVariant> network,
            List<? extends Storage<ItemVariant>>... groups
    ) {
        Set<Storage<ItemVariant>> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        unique.add(network);
        for (List<? extends Storage<ItemVariant>> group : groups) {
            unique.addAll(group);
        }
        for (Storage<ItemVariant> storage : unique) {
            NETWORKS_BY_STORAGE.computeIfAbsent(
                    storage,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>())
            ).add(entry.identity);
        }
        entry.registeredStorages = weakReferences(List.copyOf(unique));
    }

    private static void unregisterStorages(Entry entry) {
        for (WeakReference<Storage<ItemVariant>> reference : entry.registeredStorages) {
            Storage<ItemVariant> storage = reference.get();
            Set<NetworkIdentity> identities = storage == null ? null : NETWORKS_BY_STORAGE.get(storage);
            if (identities == null) {
                continue;
            }
            identities.remove(entry.identity);
            if (identities.isEmpty()) {
                NETWORKS_BY_STORAGE.remove(storage);
            }
        }
        entry.registeredStorages = List.of();
    }

    private static List<WeakReference<Storage<ItemVariant>>> weakReferences(List<Storage<ItemVariant>> storages) {
        return storages.stream().map(WeakReference::new).toList();
    }

    private static List<WeakReference<DigitalItemStorage>> weakDigitalReferences(List<DigitalItemStorage> storages) {
        return storages.stream().map(WeakReference::new).toList();
    }

    private static List<Storage<ItemVariant>> resolve(
            List<WeakReference<Storage<ItemVariant>>> references
    ) {
        List<Storage<ItemVariant>> resolved = new ArrayList<>(references.size());
        for (WeakReference<Storage<ItemVariant>> reference : references) {
            Storage<ItemVariant> storage = reference.get();
            if (storage == null) {
                return null;
            }
            resolved.add(storage);
        }
        return List.copyOf(resolved);
    }

    private static List<DigitalItemStorage> resolveDigital(
            List<WeakReference<DigitalItemStorage>> references
    ) {
        List<DigitalItemStorage> resolved = new ArrayList<>(references.size());
        for (WeakReference<DigitalItemStorage> reference : references) {
            DigitalItemStorage storage = reference.get();
            if (storage == null) {
                return null;
            }
            resolved.add(storage);
        }
        return List.copyOf(resolved);
    }

    static final class NetworkIdentity {
        private final WeakReference<BlockEntity> connector;

        private NetworkIdentity(BlockEntity connector) {
            this.connector = new WeakReference<>(connector);
        }

        WeakReference<BlockEntity> connector() {
            return connector;
        }
    }

    record Token(NetworkIdentity identity, long version) {
    }

    record Endpoint(
            NetworkIdentity identity,
            long version,
            int ordinal,
            WeakReference<Storage<ItemVariant>> storage
    ) {
        Storage<ItemVariant> resolve(Token token) {
            if (token == null || token.identity() != identity || token.version() != version || !isCurrent(token)) {
                return null;
            }
            return storage.get();
        }
    }

    record Topology(
            NetworkIdentity identity,
            long version,
            WeakReference<Storage<ItemVariant>> network,
            List<WeakReference<Storage<ItemVariant>>> physicalReferences,
            List<WeakReference<DigitalItemStorage>> digitalReferences,
            List<WeakReference<DigitalItemStorage>> rawDigitalReferences,
            List<Endpoint> physicalEndpoints,
            List<Storage<ItemVariant>> physical,
            List<DigitalItemStorage> digital,
            List<DigitalItemStorage> rawDigital
    ) {
        Token token() {
            return new Token(identity, version);
        }

        private Topology withResolved(
                List<Storage<ItemVariant>> resolvedPhysical,
                List<DigitalItemStorage> resolvedDigital,
                List<DigitalItemStorage> resolvedRawDigital
        ) {
            return new Topology(
                    identity,
                    version,
                    network,
                    physicalReferences,
                    digitalReferences,
                    rawDigitalReferences,
                    physicalEndpoints,
                    resolvedPhysical,
                    resolvedDigital,
                    resolvedRawDigital
            );
        }

        int digitalEndpointCount(DigitalItemStorage target) {
            return (int) rawDigital.stream().filter(storage -> storage == target).count();
        }

        int duplicateDigitalEndpointCount() {
            java.util.Set<DigitalItemStorage> unique = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>()
            );
            int duplicates = 0;
            for (DigitalItemStorage storage : rawDigital) {
                if (!unique.add(storage)) {
                    duplicates++;
                }
            }
            return duplicates;
        }
    }

    private static final class Entry {
        private final NetworkIdentity identity;
        private long version;
        private Topology topology;
        private String invalidationDetail = "";
        private List<WeakReference<Storage<ItemVariant>>> registeredStorages = List.of();

        private Entry(BlockEntity connector) {
            identity = new NetworkIdentity(connector);
        }
    }
}
