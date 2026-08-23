package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import dev.kehai.digitalstorage.storage.StorageVolume;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class TomMigrationManager {
    private static final long FINISHED_STATUS_TICKS = 1_200;
    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final Map<UUID, Status> STATUSES = new HashMap<>();

    private TomMigrationManager() {
    }

    public static void register() {
        TomNetworkCache.register();
        ServerTickEvents.END_SERVER_TICK.register(TomMigrationManager::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            JOBS.clear();
            STATUSES.clear();
            TomScannerTelemetry.clear();
            TomNetworkCache.clear();
        });
    }

    public static StartResult start(
            ServerPlayerEntity player,
            DigitalStorageAccessorBlockEntity accessor,
            TomNetworkAnalysis.Report report
    ) {
        StorageVolume volume = accessor.getVolume();
        if (volume == null || !volume.ownerId().equals(player.getUuid())) {
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
        if (!TomNetworkCache.isCurrent(report.topology())) {
            return StartResult.NETWORK_CHANGED;
        }
        if (JOBS.containsKey(volume.id())) {
            return StartResult.ALREADY_RUNNING;
        }
        if (!(accessor.getWorld() instanceof ServerWorld world)) {
            return StartResult.NO_NETWORK;
        }

        List<ItemVariant> variants = report.candidates().stream()
                .map(TomNetworkAnalysis.Candidate::variant)
                .toList();
        Job job = new Job(
                volume.id(),
                player.getUuid(),
                world.getRegistryKey(),
                accessor.getPos().toImmutable(),
                variants,
                report.estimatedFreedViews(),
                report.topology(),
                report.sourceEndpoints()
        );
        JOBS.put(volume.id(), job);
        STATUSES.put(volume.id(), job.status(player.getServer().getTicks(), State.RUNNING, ""));
        return StartResult.STARTED;
    }

    public static boolean cancel(ServerPlayerEntity player, UUID volumeId) {
        Job job = JOBS.get(volumeId);
        if (job == null || !job.ownerId.equals(player.getUuid())) {
            return false;
        }
        JOBS.remove(volumeId);
        long tick = player.getServer().getTicks();
        STATUSES.put(volumeId, job.status(tick, State.CANCELLED, "cancelled"));
        return true;
    }

    public static Status status(UUID volumeId) {
        return STATUSES.getOrDefault(volumeId, Status.idle());
    }

    private static void tick(MinecraftServer server) {
        TomScannerTelemetry.tick(server.getTicks());
        Iterator<Job> iterator = JOBS.values().iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            ServerWorld world = server.getWorld(job.worldKey);
            if (world == null || !world.isChunkLoaded(job.accessorPos)) {
                iterator.remove();
                STATUSES.put(job.volumeId, job.status(server.getTicks(), State.STOPPED, "accessor unavailable"));
                continue;
            }
            BlockEntity blockEntity = world.getBlockEntity(job.accessorPos);
            if (!(blockEntity instanceof DigitalStorageAccessorBlockEntity accessor)
                    || accessor.getVolume() == null
                    || !accessor.getVolume().id().equals(job.volumeId)) {
                iterator.remove();
                STATUSES.put(job.volumeId, job.status(server.getTicks(), State.STOPPED, "accessor unavailable"));
                continue;
            }

            State result = job.tick(accessor, DigitalStorageConfig.get().migrationViewsScannedPerTick);
            if (result != State.RUNNING) {
                iterator.remove();
            }
            STATUSES.put(job.volumeId, job.status(server.getTicks(), result, job.stopDetail));
        }

        long now = server.getTicks();
        STATUSES.entrySet().removeIf(entry -> entry.getValue().state() != State.RUNNING
                && now - entry.getValue().updatedTick() > FINISHED_STATUS_TICKS);
    }

    public static void runSelfTest() {
        ItemVariant stone = ItemVariant.of(net.minecraft.item.Items.STONE);
        DigitalItemStorage source = new DigitalItemStorage(() -> { }, 64);
        DigitalItemStorage target = new DigitalItemStorage(() -> { }, 64);
        source.load(stone, 64);
        StorageView<ItemVariant> sourceView = source.iterator().next();
        MoveResult moved = Job.moveView(sourceView, target, stone, 64);
        if (moved.moved != 64 || moved.operations != 2
                || source.amountOf(stone) != 0 || target.amountOf(stone) != 64) {
            throw new IllegalStateException("Tom migration atomic move self-test failed");
        }

        DigitalItemStorage rollbackSource = new DigitalItemStorage(() -> { }, 64);
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
        MoveResult rejected = Job.moveView(rollbackView, rejectingTarget, stone, 32);
        if (rejected.moved != 0 || rejected.operations != 2 || rollbackSource.amountOf(stone) != 32) {
            throw new IllegalStateException("Tom migration rollback self-test failed");
        }
        TomNetworkCache.runSelfTest();
    }

    static long benchmarkMoveView(
            StorageView<ItemVariant> view,
            Storage<ItemVariant> target,
            ItemVariant variant
    ) {
        return Job.moveView(view, target, variant, DigitalItemStorage.MAX_AMOUNT_PER_VARIANT).moved;
    }

    public enum StartResult {
        STARTED,
        NOT_OWNER,
        NO_NETWORK,
        NOTHING_TO_MOVE,
        DUPLICATE_TARGET_ENDPOINTS,
        NETWORK_CHANGED,
        ALREADY_RUNNING
    }

    public enum State {
        IDLE,
        RUNNING,
        COMPLETE,
        CANCELLED,
        STOPPED
    }

    public record Status(
            State state,
            String movedItems,
            int completedCandidates,
            int totalCandidates,
            int estimatedFreedViews,
            long scannedViews,
            long inventoryOperations,
            long updatedTick,
            String detail
    ) {
        private static Status idle() {
            return new Status(State.IDLE, "0", 0, 0, 0, 0, 0, 0, "");
        }

        public boolean active() {
            return state == State.RUNNING;
        }
    }

    private static final class Job {
        private final UUID volumeId;
        private final UUID ownerId;
        private final RegistryKey<World> worldKey;
        private final BlockPos accessorPos;
        private final List<ItemVariant> candidates;
        private final Set<ItemVariant> selectedVariants;
        private final int estimatedFreedViews;
        private final TomNetworkCache.Token topology;
        private final List<TomNetworkCache.Endpoint> sources;
        private final Set<ItemVariant> migratedVariants = new HashSet<>();
        private long movedItems;
        private int sourceIndex;
        private Iterator<StorageView<ItemVariant>> currentViews = Collections.emptyIterator();
        private long scannedViews;
        private long inventoryOperations;
        private boolean blocked;
        private String stopDetail = "";

        private Job(
                UUID volumeId,
                UUID ownerId,
                RegistryKey<World> worldKey,
                BlockPos accessorPos,
                List<ItemVariant> candidates,
                int estimatedFreedViews,
                TomNetworkCache.Token topology,
                List<TomNetworkCache.Endpoint> sources
        ) {
            this.volumeId = volumeId;
            this.ownerId = ownerId;
            this.worldKey = worldKey;
            this.accessorPos = accessorPos;
            this.candidates = candidates;
            this.selectedVariants = Set.copyOf(candidates);
            this.estimatedFreedViews = estimatedFreedViews;
            this.topology = topology;
            this.sources = List.copyOf(sources);
        }

        private State tick(DigitalStorageAccessorBlockEntity accessor, int viewBudget) {
            DigitalItemStorage target = accessor.getCanonicalStorage();
            if (target == null) {
                stopDetail = "target unavailable";
                return State.STOPPED;
            }
        if (!TomNetworkCache.isCurrent(topology)) {
                stopDetail = TomNetworkCache.staleDetail(topology);
                return State.STOPPED;
            }
            int scannedThisTick = 0;
            while (scannedThisTick < viewBudget) {
                while (!currentViews.hasNext()) {
                    if (sourceIndex >= sources.size()) {
                        if (blocked) {
                            stopDetail = "target full or inventory changed";
                        }
                        return blocked ? State.STOPPED : State.COMPLETE;
                    }
                    Storage<ItemVariant> source = sources.get(sourceIndex++).resolve(topology);
                    if (source == null) {
                        stopDetail = "inventory unloaded";
                        return State.STOPPED;
                    }
                    currentViews = source.supportsExtraction()
                            ? source.iterator()
                            : Collections.emptyIterator();
                }

                StorageView<ItemVariant> view = currentViews.next();
                scannedThisTick++;
                scannedViews++;
                if (view.isResourceBlank() || view.getAmount() <= 0
                        || !selectedVariants.contains(view.getResource())) {
                    continue;
                }
                ItemVariant variant = view.getResource();
                boolean exists = target.amountOf(variant) > 0;
                if (!ItemSecurityPolicy.canInsert(variant)
                        || (!exists && (!ItemSecurityPolicy.canCreateVariant(variant)
                        || target.variantCount() >= accessor.getRecord().variantCapacity()))) {
                    blocked = true;
                    continue;
                }
                long available = DigitalItemStorage.MAX_AMOUNT_PER_VARIANT - target.amountOf(variant);
                if (available <= 0) {
                    blocked = true;
                    continue;
                }

                MoveResult move = moveView(view, target, variant, available);
                inventoryOperations += move.operations;
                if (move.moved > 0) {
                    movedItems = Math.addExact(movedItems, move.moved);
                    migratedVariants.add(variant);
                } else if (move.operations > 0) {
                    blocked = true;
                }
            }
            return State.RUNNING;
        }

        private static MoveResult moveView(
                StorageView<ItemVariant> view,
                Storage<ItemVariant> target,
                ItemVariant variant,
                long maximum
        ) {
            if (view.isResourceBlank() || !variant.equals(view.getResource()) || view.getAmount() <= 0) {
                return new MoveResult(0, 0);
            }
            long requested = Math.min(maximum, view.getAmount());
            try (Transaction transaction = Transaction.openOuter()) {
                long extracted = view.extract(variant, requested, transaction);
                if (extracted <= 0) {
                    return new MoveResult(0, 1);
                }
                long inserted = target.insert(variant, extracted, transaction);
                if (inserted != extracted) {
                    return new MoveResult(0, 2);
                }
                transaction.commit();
                return new MoveResult(inserted, 2);
            }
        }

        private Status status(long tick, State state, String detail) {
            return new Status(
                    state,
                    Long.toString(movedItems),
                    state == State.COMPLETE ? candidates.size() : migratedVariants.size(),
                    candidates.size(),
                    estimatedFreedViews,
                    scannedViews,
                    inventoryOperations,
                    tick,
                    detail
            );
        }
    }

    private record MoveResult(long moved, int operations) {
    }
}
