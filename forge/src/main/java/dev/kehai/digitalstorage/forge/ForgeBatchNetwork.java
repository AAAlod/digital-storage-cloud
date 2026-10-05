package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.optimization.BatchTransfer;
import dev.kehai.digitalstorage.optimization.BatchTransfers;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.forge.tom.ForgeTomTopology;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Direction;
import net.minecraftforge.items.IItemHandler;

public final class ForgeBatchNetwork implements BatchTransfers.Backend {
    public BatchTransfer.Route open(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        if (!(accessor instanceof ForgeAccessorBlockEntity)) return null;
        var volume = accessor.getVolume();
        var session = ForgeTransferSessions.get(player.getServer());
        if (!session.available()) throw new IllegalStateException("recovery_required");
        for (var direction : Direction.values()) {
            var position = accessor.getBlockPos().relative(direction);
            if (!accessor.getLevel().hasChunkAt(position)) continue;
            var neighbor = accessor.getLevel().getBlockEntity(position);
            if (!(neighbor instanceof com.tom.storagemod.tile.InventoryConnectorBlockEntity connector)) continue;
            java.util.function.Supplier<IItemHandler> current = () -> connector.isRemoved()
                    || !connector.getLevel().hasChunkAt(position) || connector.getLevel().getBlockEntity(position) != connector
                    ? null : connector.getInventory().orElse(null);
            var root = current.get(); if (root == null) continue;
            var report = ForgeTomTopology.analyze(volume.record(), current);
            var origin = new ForgeInventoryTransferExecutor.Origin(true, player.serverLevel().dimension().location().toString(),
                    accessor.getBlockPos().asLong(), position.asLong());
            var executor = session.executor(player.getUUID(), volume.id(), origin);
            var ports = ForgeTomTopology.physicalHandlers(root).stream()
                    .map(handler -> (BatchTransfer.Port) new Port(handler, player.getUUID(), volume.id(), session.recovery(),
                            () -> DigitalStorageState.get(player.serverLevel()).flushNow(), executor)).toList();
            return new BatchTransfer.Route(ports, () -> !accessor.isRemoved() && player.serverLevel() == accessor.getLevel()
                    && accessor.getLevel().hasChunkAt(accessor.getBlockPos())
                    && accessor.getLevel().getBlockEntity(accessor.getBlockPos()) == accessor
                    && accessor.getVolume() == volume && volume.ownerId().equals(player.getUUID()) && report.topology().isCurrent(), position.toShortString());
        }
        return null;
    }
    static final class Port implements BatchTransfer.Port {
        private final IItemHandler handler;
        private final java.util.UUID owner, volume;
        private final ForgeTransferRecovery recovery;
        private final Runnable flush;
        private final ForgeInventoryTransferExecutor importer;
        Port(IItemHandler handler, java.util.UUID owner, java.util.UUID volume, ForgeTransferRecovery recovery,
             Runnable flush, ForgeInventoryTransferExecutor importer) {
            this.handler = handler; this.owner = owner; this.volume = volume; this.recovery = recovery; this.flush = flush; this.importer = importer;
        }
        public Iterable<BatchTransfer.Entry> contents() {
            var result = new ArrayList<BatchTransfer.Entry>();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                var stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) result.add(new BatchTransfer.Entry(ItemKey.of(stack), stack.getCount()));
            }
            return result;
        }
        public BatchTransfer.Cursor cursor(boolean exporting) {
            return new BatchTransfer.Cursor() {
                int slot;
                public boolean hasNext() { return slot < handler.getSlots(); }
                public BatchTransfer.Result next(DigitalStorageRecord record, ItemKey key, long maximum) {
                    BatchTransfer.Result result;
                    if (exporting) result = ForgeBatchExport.move(record, key, maximum, handler, slot, owner, volume, recovery, flush);
                    else {
                        var moved = importer.move(new ForgeInventoryEndpoint.View(handler, slot), record.storage(), key, maximum);
                        result = new BatchTransfer.Result(moved.moved(), moved.stopDetail().isEmpty() ? "" : "recovery_required");
                    }
                    if (result.moved() == 0) slot++;
                    return result;
                }
            };
        }
    }
}
