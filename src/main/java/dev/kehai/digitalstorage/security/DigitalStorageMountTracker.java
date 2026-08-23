package dev.kehai.digitalstorage.security;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class DigitalStorageMountTracker {
    private static final MountIndex INDEX = new MountIndex();

    private DigitalStorageMountTracker() {
    }

    public static void register() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof DigitalStorageAccessorBlockEntity digitalStorage) {
                world.getServer().execute(() -> {
                    if (!digitalStorage.isRemoved() && digitalStorage.getWorld() == world) {
                        update(digitalStorage, world);
                    }
                });
            }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof DigitalStorageAccessorBlockEntity digitalStorage) {
                untrack(world, digitalStorage.getPos());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    public static synchronized void update(DigitalStorageAccessorBlockEntity blockEntity, ServerWorld world) {
        blockEntity.clearOrphanedBinding(dev.kehai.digitalstorage.storage.DigitalStorageState.get(world));
        MountPoint point = MountPoint.of(world, blockEntity.getPos());
        UUID volumeId = blockEntity.boundVolumeId().orElse(null);
        INDEX.update(point, volumeId);
    }

    public static synchronized void untrack(ServerWorld world, BlockPos pos) {
        MountPoint point = MountPoint.of(world, pos);
        INDEX.untrack(point);
    }

    public static synchronized List<MountPoint> mounts(UUID volumeId) {
        return INDEX.mounts(volumeId);
    }

    public static synchronized int loadedAccessorCount() {
        return INDEX.loadedAccessorCount();
    }

    public static synchronized int boundAccessorCount() {
        return INDEX.boundAccessorCount();
    }

    public static synchronized int mountedVolumeCount() {
        return INDEX.mountedVolumeCount();
    }

    public static synchronized int multiMountedVolumeCount() {
        return INDEX.multiMountedVolumeCount();
    }

    public static synchronized Set<UUID> mountedVolumeIds() {
        return INDEX.mountedVolumeIds();
    }

    public static synchronized void clear() {
        INDEX.clear();
    }

    public static void runSelfTest() {
        MountIndex index = new MountIndex();
        Identifier dimension = new Identifier("digitalstorage", "mount_tracker_selftest");
        MountPoint first = new MountPoint(dimension, new BlockPos(1, 2, 3));
        MountPoint second = new MountPoint(dimension, new BlockPos(4, 5, 6));
        MountPoint third = new MountPoint(dimension, new BlockPos(7, 8, 9));
        UUID firstVolume = UUID.randomUUID();
        UUID secondVolume = UUID.randomUUID();

        index.update(first, null);
        expectCounts(index, 1, 0, 0, "unbound loaded accessor");
        index.update(first, firstVolume);
        expectCounts(index, 1, 1, 1, "new binding");
        index.update(second, firstVolume);
        expectCounts(index, 2, 2, 1, "two accessors on one volume");
        if (index.multiMountedVolumeCount() != 1) {
            throw new IllegalStateException("Mount tracker self-test failed for multiply linked volume");
        }
        index.update(third, secondVolume);
        expectCounts(index, 3, 3, 2, "second mounted volume");
        index.update(first, secondVolume);
        expectCounts(index, 3, 3, 2, "binding moved between volumes");
        index.update(second, null);
        expectCounts(index, 3, 2, 1, "loaded accessor unbound");
        index.untrack(second);
        expectCounts(index, 2, 2, 1, "unbound accessor unloaded");
        index.untrack(first);
        index.untrack(third);
        expectCounts(index, 0, 0, 0, "all accessors unloaded");
    }

    private static void expectCounts(
            MountIndex index,
            int loadedAccessors,
            int boundAccessors,
            int mountedVolumes,
            String step
    ) {
        if (index.loadedAccessorCount() != loadedAccessors
                || index.boundAccessorCount() != boundAccessors
                || index.mountedVolumeCount() != mountedVolumes) {
            throw new IllegalStateException("Mount tracker self-test failed after " + step
                    + ": expected " + loadedAccessors + "/" + boundAccessors + "/" + mountedVolumes
                    + ", got " + index.loadedAccessorCount() + "/" + index.boundAccessorCount()
                    + "/" + index.mountedVolumeCount());
        }
    }

    public record MountPoint(Identifier dimension, BlockPos pos) {
        public MountPoint {
            pos = pos.toImmutable();
        }

        private static MountPoint of(ServerWorld world, BlockPos pos) {
            return new MountPoint(world.getRegistryKey().getValue(), pos);
        }

        @Override
        public String toString() {
            return dimension + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        }
    }

    private static final class MountIndex {
        private final Map<UUID, Set<MountPoint>> mounts = new HashMap<>();
        private final Map<MountPoint, UUID> idsByPoint = new HashMap<>();

        private void update(MountPoint point, UUID volumeId) {
            UUID previousId = idsByPoint.put(point, volumeId);
            if (previousId != null && !previousId.equals(volumeId)) {
                removePoint(previousId, point);
            }
            if (volumeId != null) {
                mounts.computeIfAbsent(volumeId, ignored -> new java.util.HashSet<>()).add(point);
            }
        }

        private void untrack(MountPoint point) {
            UUID volumeId = idsByPoint.remove(point);
            if (volumeId != null) {
                removePoint(volumeId, point);
            }
        }

        private List<MountPoint> mounts(UUID volumeId) {
            List<MountPoint> result = new ArrayList<>(mounts.getOrDefault(volumeId, Set.of()));
            result.sort(Comparator.comparing(MountPoint::toString));
            return List.copyOf(result);
        }

        private int loadedAccessorCount() {
            return idsByPoint.size();
        }

        private int boundAccessorCount() {
            return mounts.values().stream().mapToInt(Set::size).sum();
        }

        private int mountedVolumeCount() {
            return mounts.size();
        }

        private int multiMountedVolumeCount() {
            return (int) mounts.values().stream().filter(points -> points.size() > 1).count();
        }

        private Set<UUID> mountedVolumeIds() {
            return Set.copyOf(mounts.keySet());
        }

        private void clear() {
            mounts.clear();
            idsByPoint.clear();
        }

        private void removePoint(UUID storageId, MountPoint point) {
            Set<MountPoint> points = mounts.get(storageId);
            if (points == null) {
                return;
            }
            points.remove(point);
            if (points.isEmpty()) {
                mounts.remove(storageId);
            }
        }
    }
}
