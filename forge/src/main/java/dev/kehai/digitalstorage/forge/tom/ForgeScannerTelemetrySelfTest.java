package dev.kehai.digitalstorage.forge.tom;

import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

public final class ForgeScannerTelemetrySelfTest {
    private ForgeScannerTelemetrySelfTest() { }
    /** Called only from the declared task-world fixture, against its actual connected test volume. */
    public static void verifyBackend(MinecraftServer server, dev.kehai.digitalstorage.forge.ForgeAccessorBlockEntity accessor,
                                     IItemHandler network) {
        var target = accessor.getRecord().storage();
        var key = dev.kehai.digitalstorage.storage.ItemKey.of(Items.STONE);
        long before = target.amountOf(key);
        var config = dev.kehai.digitalstorage.config.DigitalStorageConfig.get();
        long expected = config.optimizeTomsHopper ? Math.min(before, Math.min(64, config.normalHopperBatchSize)) : 1;
        var destination = new ItemStackHandler(1);
        var hopper = new Hopper(server, network, destination, true);
        var telemetry = ForgeScannerTelemetry.get(server);
        try {
            hopper.attempt();
            var report = dev.kehai.digitalstorage.optimization.NetworkServices.get().analyze(accessor);
            expect(report.activeScanners() == 1 && report.failingScanners() == 0
                    && target.amountOf(key) == before - expected && destination.getStackInSlot(0).getCount() == expected,
                    "Production backend did not expose actual successful network scanner data");
            destination.setStackInSlot(0, new ItemStack(Items.STONE, 64));
            for (int tick = 0; tick < failureTicks(); tick++) hopper.attempt();
            report = dev.kehai.digitalstorage.optimization.NetworkServices.get().analyze(accessor);
            expect(report.activeScanners() == 1 && report.failingScanners() == 1 && target.amountOf(key) == before - expected,
                    "Production backend did not expose failure scans without additional target extraction");
        } finally { hopper.setRemoved(); telemetry.forget(target); }
    }
    public static void run(MinecraftServer server) {
        var target = new VolumeLedger(UUID.randomUUID(), () -> { }, 32);
        var source = new ItemStackHandler(1);
        source.setStackInSlot(0, new ItemStack(Items.STONE, 1));
        var destination = new ItemStackHandler(1);
        var network = new com.tom.storagemod.util.MultiItemHandler();
        network.add(LazyOptional.of(() -> source)); network.refresh();
        var hopper = new Hopper(server, network, destination, true);
        var live = ForgeScannerTelemetry.get(server);
        live.associate(target, network, () -> true);
        try {
            hopper.attempt();
            expect(source.getStackInSlot(0).isEmpty() && destination.getStackInSlot(0).getCount() == 1
                    && live.snapshot(target, server.getTickCount()).active() == 1
                    && live.snapshot(target, server.getTickCount()).failing() == 0,
                    "Actual Tom successful update was not observed or transfer was changed");
            for (int tick = 0; tick < failureTicks(); tick++) hopper.attempt();
            expect(live.snapshot(target, server.getTickCount()).failing() == 1 && destination.getStackInSlot(0).getCount() == 1,
                    "Actual empty-source attempts did not accumulate failures without transferring more items");
            var disabled = new Hopper(server, network, destination, false);
            disabled.attempt();
            expect(live.snapshot(target, server.getTickCount()).active() == 1, "Disabled hopper was recorded as an active scan");
        } finally { live.forget(target); }

        var collector = new ForgeScannerTelemetry();
        var valid = new AtomicBoolean(true);
        // Every inventory method throws: telemetry must use handles, not iterate/read slots.
        var opaque = new IItemHandler() {
            public int getSlots() { throw new IllegalStateException("Telemetry enumerated slots"); }
            public ItemStack getStackInSlot(int slot) { throw new IllegalStateException("Telemetry read slot"); }
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { throw new IllegalStateException(); }
            public ItemStack extractItem(int slot, int amount, boolean simulate) { throw new IllegalStateException(); }
            public int getSlotLimit(int slot) { throw new IllegalStateException(); }
            public boolean isItemValid(int slot, ItemStack stack) { throw new IllegalStateException(); }
        };
        collector.associate(target, opaque, valid::get);
        collector.record(hopper, opaque, opaque, 0, 0);
        collector.record(hopper, opaque, opaque, 10, 3);
        collector.record(hopper, opaque, opaque, 30, 3);
        var snapshot = collector.snapshot(target, 30);
        expect(snapshot.active() == 1 && snapshot.failing() == 1 && snapshot.interval() == 15,
                "Telemetry duplicated source/destination observations or lost tick-zero interval");
        collector.record(hopper, opaque, null, 40, 0);
        expect(collector.snapshot(target, 40).failing() == 0, "Successful scan did not reset failure status");
        valid.set(false);
        collector.record(hopper, opaque, null, 50, 3);
        expect(collector.snapshot(target, 50).failing() == 0, "Invalid network association still accepted observations");
        expect(collector.snapshot(target, 1241).active() == 0, "Old scanner remained active beyond 1200 ticks");
        collector.prune(1241);
        var record = dev.kehai.digitalstorage.storage.DigitalStorageRecord.createNew(() -> { });
        var report = ForgeTomTopology.analyze(record, () -> network, new ForgeScannerTelemetry.Snapshot(4, 2, 11));
        expect(report.activeScanners() == 4 && report.failingScanners() == 2 && report.averageScanIntervalTicks() == 11,
                "Shared network report discarded scanner metrics");
    }
    private static int failureTicks() {
        var config = dev.kehai.digitalstorage.config.DigitalStorageConfig.get();
        return config.optimizeTomsHopper
                ? config.hopperSuccessCooldown + config.failureCooldown(1) + config.failureCooldown(2) + 3 : 35;
    }
    /** Executes the real mixed-in Tom update with isolated in-memory handlers, without placing blocks in the user world. */
    private static final class Hopper extends com.tom.storagemod.tile.BasicInventoryHopperBlockEntity {
        Hopper(MinecraftServer server, IItemHandler source, IItemHandler destination, boolean enabled) {
            super(BlockPos.ZERO, com.tom.storagemod.Content.invHopperBasic.get().defaultBlockState()
                    .setValue(com.tom.storagemod.block.BasicInventoryHopperBlock.ENABLED, enabled));
            setLevel(server.overworld());
            top = LazyOptional.of(() -> source); bottom = LazyOptional.of(() -> destination);
        }
        void attempt() { super.update(); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
