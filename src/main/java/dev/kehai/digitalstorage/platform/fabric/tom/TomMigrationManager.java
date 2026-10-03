package dev.kehai.digitalstorage.platform.fabric.tom;

import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.MigrationTaskSelfTest;
import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.InventoryEndpoint;
import dev.kehai.digitalstorage.optimization.TopologyToken;
import dev.kehai.digitalstorage.optimization.NetworkServices.StartResult;
import com.tom.storagemod.util.MergedStorage;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.platform.fabric.FabricDigitalItemStorage;
import dev.kehai.digitalstorage.storage.StorageVolume;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.optimization.MigrationTask.State;
import dev.kehai.digitalstorage.optimization.MigrationTask.Status;
import dev.kehai.digitalstorage.optimization.InventoryTransferExecutor.MoveResult;
import dev.kehai.digitalstorage.platform.fabric.FabricTransferExecutor;
import dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkCache.Topology;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class TomMigrationManager {
    private static final long FINISHED_STATUS_TICKS = 1_200;
    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final Map<UUID, Status> STATUSES = new HashMap<>();

    private TomMigrationManager() {
    }

    public static void clear() {
        JOBS.clear();
        STATUSES.clear();
        TomScannerTelemetry.clear();
        TomNetworkCache.clear();
    }

    public static StartResult start(
            ServerPlayer player,
            DigitalStorageAccessorBlockEntity accessor,
            NetworkAnalysis.Report report
    ) {
        StorageVolume volume = accessor.getVolume();
        if (volume == null || !volume.ownerId().equals(player.getUUID())) {
            return StartResult.NOT_OWNER;
        }
        if (!report.available()) {
            return StartResult.NO_NETWORK;
        }
        if (report.targetEndpointCount() > 1) {
            return StartResult.DUPLICATE_TARGET_ENDPOINTS;
        }
        if (report.candidates().isEmpty()) {
            return StartResult.NOTHING_TO_MOVE;
        }
        if (!TopologyToken.isCurrent(report.topology())) {
            return StartResult.NETWORK_CHANGED;
        }
        if (JOBS.containsKey(volume.id())) {
            return StartResult.ALREADY_RUNNING;
        }
        if (!(accessor.getLevel() instanceof ServerLevel world)) {
            return StartResult.NO_NETWORK;
        }

        List<ItemKey> variants = report.candidates().stream()
                .map(NetworkAnalysis.Candidate::variant)
                .toList();
        Job job = new Job(
                volume.id(),
                player.getUUID(),
                world.dimension(),
                accessor.getBlockPos().immutable(),
                variants,
                report.estimatedFreedViews(),
                report.topology(),
                report.sourceEndpoints()
        );
        JOBS.put(volume.id(), job);
        STATUSES.put(volume.id(), job.status(player.getServer().getTickCount(), State.RUNNING, ""));
        return StartResult.STARTED;
    }

    public static boolean cancel(ServerPlayer player, UUID volumeId) {
        Job job = JOBS.get(volumeId);
        if (job == null || !job.ownerId.equals(player.getUUID())) {
            return false;
        }
        JOBS.remove(volumeId);
        long tick = player.getServer().getTickCount();
        STATUSES.put(volumeId, job.status(tick, State.CANCELLED, "cancelled"));
        return true;
    }

    public static Status status(UUID volumeId) {
        return STATUSES.getOrDefault(volumeId, Status.idle());
    }

    public static void tick(MinecraftServer server) {
        TomScannerTelemetry.tick(server.getTickCount());
        Iterator<Job> iterator = JOBS.values().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            ServerLevel world = server.getLevel(job.worldKey);
            if (world == null || !world.hasChunkAt(job.accessorPos)) {
                iterator.remove();
                STATUSES.put(job.volumeId, job.status(server.getTickCount(), State.STOPPED, "accessor unavailable"));
                continue;
            }
            BlockEntity blockEntity = world.getBlockEntity(job.accessorPos);
            if (!(blockEntity instanceof DigitalStorageAccessorBlockEntity accessor)
                    || accessor.getVolume() == null
                    || !accessor.getVolume().id().equals(job.volumeId)) {
                iterator.remove();
                STATUSES.put(job.volumeId, job.status(server.getTickCount(), State.STOPPED, "accessor unavailable"));
                continue;
            }

            State result = job.tick(accessor, DigitalStorageConfig.get().migrationViewsScannedPerTick);
            if (result != State.RUNNING) {
                iterator.remove();
            }
            STATUSES.put(job.volumeId, job.status(server.getTickCount(), result, job.task.stopDetail()));
        }

        long now = server.getTickCount();
        STATUSES.entrySet().removeIf(entry -> entry.getValue().state() != State.RUNNING
                && now - entry.getValue().updatedTick() > FINISHED_STATUS_TICKS);
    }

    public static void runSelfTest() {
        MigrationTaskSelfTest.run();
        ItemVariant stone = ItemVariant.of(net.minecraft.world.item.Items.STONE);
        FabricDigitalItemStorage source = new FabricDigitalItemStorage(() -> { }, 64);
        FabricDigitalItemStorage target = new FabricDigitalItemStorage(() -> { }, 64);
        source.load(stone, 64);
        StorageView<ItemVariant> sourceView = source.iterator().next();
        MoveResult moved = FabricTransferExecutor.moveView(sourceView, target, stone, 64);
        if (moved.moved() != 64 || moved.operations() != 2
                || source.amountOf(stone) != 0 || target.amountOf(stone) != 64) {
            throw new IllegalStateException("Tom migration atomic move self-test failed");
        }

        FabricDigitalItemStorage rollbackSource = new FabricDigitalItemStorage(() -> { }, 64);
        rollbackSource.load(stone, 32);
        Storage<ItemVariant> rejectingTarget = new Storage<>() {
            @Override
            public long insert(ItemVariant resource, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                return 0;
            }

            @Override
            public long extract(ItemVariant resource, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                return 0;
            }

            @Override
            public Iterator<StorageView<ItemVariant>> iterator() {
                return java.util.Collections.emptyIterator();
            }
        };
        StorageView<ItemVariant> rollbackView = rollbackSource.iterator().next();
        MoveResult rejected = FabricTransferExecutor.moveView(rollbackView, rejectingTarget, stone, 32);
        if (rejected.moved() != 0 || rejected.operations() != 2 || rollbackSource.amountOf(stone) != 32) {
            throw new IllegalStateException("Tom migration rollback self-test failed");
        }
        FabricDigitalItemStorage partialTarget = new FabricDigitalItemStorage(() -> { }, 64);
        partialTarget.load(stone, FabricDigitalItemStorage.MAX_AMOUNT_PER_VARIANT - 10);
        MoveResult partial = FabricTransferExecutor.moveView(rollbackView, partialTarget, stone, 32);
        if (partial.moved() != 0 || rollbackSource.amountOf(stone) != 32
                || partialTarget.amountOf(stone) != FabricDigitalItemStorage.MAX_AMOUNT_PER_VARIANT - 10) {
            throw new IllegalStateException("Tom migration partial insertion did not roll back both sides");
        }
        TomNetworkCache.runSelfTest();
        for (int slots : new int[] {3_000, 10_000, 20_001}) {
            rebuildMigrationSelfTest(slots);
        }
    }

    private static void rebuildMigrationSelfTest(int slots) {
        var connector = new DigitalStorageAccessorBlockEntity(BlockPos.ZERO,
                dev.kehai.digitalstorage.DigitalStorageMod.DIGITAL_STORAGE_ACCESSOR.defaultBlockState());
        var inventory = new MigrationInventory(slots);
        inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
        inventory.setItem(slots - 1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 32));
        var network = new com.tom.storagemod.util.MergedStorage();
        Storage<ItemVariant> original = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(
                inventory, net.minecraft.core.Direction.UP);
        network.add(original);
        var topology = TomNetworkCache.topology(connector, network);
        var record = dev.kehai.digitalstorage.storage.DigitalStorageRecord.createNew(() -> { });
        var job = new Job(UUID.randomUUID(), UUID.randomUUID(), Level.OVERWORLD, BlockPos.ZERO,
                List.of(ItemKey.of(net.minecraft.world.item.Items.STONE)), 2, topology.token(), topology.physicalEndpoints());
        State result = State.RUNNING;
        int ticks = 0;
        try {
            while (result == State.RUNNING && ticks < 300) {
                if (++ticks % 20 == 0) {
                    var replacement = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(
                            inventory, net.minecraft.core.Direction.UP);
                    if (replacement == original || !TomStorageIdentity.key(original).equals(TomStorageIdentity.key(replacement))) {
                        throw new IllegalStateException("Sided wrapper churn fixture/identity failed");
                    }
                    network.clear();
                    network.add(replacement);
                    TomNetworkCache.rebuilt(connector, network);
                }
                long previous = job.task.scannedViews();
                result = job.tick(record, 128);
                if (job.task.scannedViews() - previous > 128) {
                    throw new IllegalStateException("Migration exceeded its empty-slot scan budget");
                }
            }
            if (result != State.COMPLETE || ticks <= 20 || job.task.scannedViews() != slots
                    || job.task.movedItems() != 96 || !inventory.isEmpty()
                    || record.storage().amountOf(dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE)) != 96) {
                throw new IllegalStateException("Bulk migration rebuild regression failed: " + slots + "/" + result);
            }
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.info(
                    "Bulk migration regression passed: views={}, ticks={}, rebuilds={}, moved={}, budget=128, state={}",
                    slots, ticks, ticks / 20, job.task.movedItems(), result);
            // Same endpoint count but changed side or exposed slot set must invalidate.
            network.clear();
            network.add(net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(inventory, net.minecraft.core.Direction.DOWN));
            TomNetworkCache.rebuilt(connector, network);
            if (TomNetworkCache.isCurrent(topology.token()) || job.tick(record, 128) != State.STOPPED) {
                throw new IllegalStateException("Migration accepted a changed access direction");
            }
            var sideTopology = TomNetworkCache.topology(connector, network);
            inventory.limit = slots - 1;
            network.clear();
            network.add(net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(inventory, net.minecraft.core.Direction.DOWN));
            TomNetworkCache.rebuilt(connector, network);
            if (TomNetworkCache.isCurrent(sideTopology.token())) {
                throw new IllegalStateException("Migration accepted changed exposed slots");
            }
            var removedTopology = TomNetworkCache.topology(connector, network);
            network.clear();
            TomNetworkCache.rebuilt(connector, network);
            if (TomNetworkCache.isCurrent(removedTopology.token())) {
                throw new IllegalStateException("Migration accepted removed endpoint");
            }
            var emptyTopology = TomNetworkCache.topology(connector, network);
            network.add(original);
            TomNetworkCache.rebuilt(connector, network);
            if (TomNetworkCache.isCurrent(emptyTopology.token())) {
                throw new IllegalStateException("Migration accepted added endpoint");
            }
        } finally {
            TomNetworkCache.invalidate(connector);
        }
    }

    private static final class MigrationInventory extends net.minecraft.world.SimpleContainer
            implements net.minecraft.world.WorldlyContainer {
        private int limit;
        private MigrationInventory(int size) { super(size); limit = size; }
        @Override public int[] getSlotsForFace(net.minecraft.core.Direction side) {
            return java.util.stream.IntStream.range(0, limit).toArray();
        }
        @Override public boolean canPlaceItemThroughFace(int slot, net.minecraft.world.item.ItemStack stack, net.minecraft.core.Direction side) { return true; }
        @Override public boolean canTakeItemThroughFace(int slot, net.minecraft.world.item.ItemStack stack, net.minecraft.core.Direction side) { return true; }
    }

    static long benchmarkMoveView(
            StorageView<ItemVariant> view,
            Storage<ItemVariant> target,
            ItemVariant variant
    ) {
        return FabricTransferExecutor.moveView(view, target, variant, FabricDigitalItemStorage.MAX_AMOUNT_PER_VARIANT).moved();
    }

private static final class Job {
        private final UUID volumeId;
        private final UUID ownerId;
        private final ResourceKey<Level> worldKey;
        private final BlockPos accessorPos;
        private final MigrationTask task;

        private Job(UUID volumeId, UUID ownerId, ResourceKey<Level> worldKey, BlockPos accessorPos,
                    List<ItemKey> candidates, int estimatedFreedViews, TopologyToken topology,
                    List<? extends InventoryEndpoint.Reference> sources) {
            this.volumeId = volumeId;
            this.ownerId = ownerId;
            this.worldKey = worldKey;
            this.accessorPos = accessorPos;
            this.task = new MigrationTask(candidates, estimatedFreedViews, topology, sources, FabricTransferExecutor.INSTANCE);
        }

        private State tick(DigitalStorageAccessorBlockEntity accessor, int viewBudget) {
            return tick(accessor.getRecord(), viewBudget);
        }

        private State tick(dev.kehai.digitalstorage.storage.DigitalStorageRecord record, int viewBudget) {
            return task.tick(record, viewBudget);
        }

        private Status status(long tick, State state, String detail) {
            return task.status(tick, state, detail);
        }
    }
}
