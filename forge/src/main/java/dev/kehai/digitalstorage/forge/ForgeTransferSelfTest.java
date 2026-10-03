package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

/** Actual IItemHandler calls; recovery fixtures never touch player/server storage. */
public final class ForgeTransferSelfTest {
    private static final ItemKey STONE = ItemKey.of(Items.STONE);
    private ForgeTransferSelfTest() { }
    public static void run() {
        Path root;
        try { root = Files.createTempDirectory("digitalstorage-forge-transfer-"); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        try {
            var normal = fixture(root, "normal", 32, () -> { });
            var source = source(160, null);
            var view = new ForgeInventoryEndpoint.View(source, 0);
            var first = normal.executor.move(view, normal.target, STONE, 160);
            var second = normal.executor.move(view, normal.target, STONE, 96);
            var last = normal.executor.move(view, normal.target, STONE, 32);
            expect(first.moved() == 64 && first.revisitSource() && second.moved() == 64 && second.revisitSource()
                    && last.moved() == 32 && !last.revisitSource() && normal.target.amountOf(STONE) == 160
                    && source.getStackInSlot(0).isEmpty() && normal.recovery.pendingCount() == 0,
                    "Physical slot batches lost quantities or continuation");

            var full = fixture(root, "full", 0, () -> { });
            int[] actualCalls = {0};
            source = source(20, (handler, requested) -> { actualCalls[0]++; return handler.take(requested); });
            var result = full.executor.move(new ForgeInventoryEndpoint.View(source, 0), full.target, STONE, 20);
            expect(result.moved() == 0 && actualCalls[0] == 0 && source.getStackInSlot(0).getCount() == 20
                    && full.target.variantCount() == 0, "Full target touched the actual source");

            var partial = fixture(root, "partial", 32, () -> { });
            source = source(20, (handler, requested) -> handler.take(7));
            result = partial.executor.move(new ForgeInventoryEndpoint.View(source, 0), partial.target, STONE, 20);
            expect(result.moved() == 7 && result.revisitSource() && partial.target.amountOf(STONE) == 7
                    && source.getStackInSlot(0).getCount() == 13 && partial.recovery.pendingCount() == 0,
                    "Partial extraction left phantom reservation");

            var ceiling = fixture(root, "ceiling", 32, () -> { });
            ceiling.target.load(STONE, VolumeLedger.MAX_AMOUNT_PER_VARIANT - 5);
            source = source(20, null);
            result = ceiling.executor.move(new ForgeInventoryEndpoint.View(source, 0), ceiling.target, STONE, 20);
            expect(result.moved() == 5 && ceiling.target.amountOf(STONE) == VolumeLedger.MAX_AMOUNT_PER_VARIANT
                    && source.getStackInSlot(0).getCount() == 15, "Capacity reservation extracted more than the target accepted");

            var empty = fixture(root, "empty", 32, () -> { });
            source = source(20, (handler, requested) -> ItemStack.EMPTY);
            result = empty.executor.move(new ForgeInventoryEndpoint.View(source, 0), empty.target, STONE, 20);
            expect(result.moved() == 0 && empty.target.variantCount() == 0 && empty.target.contentVersion() == 0
                    && source.getStackInSlot(0).getCount() == 20, "Empty extraction committed simulated items");

            var changed = fixture(root, "changed", 32, () -> { });
            source = source(20, (handler, requested) -> new ItemStack(Items.DIRT, handler.take(requested).getCount()));
            result = changed.executor.move(new ForgeInventoryEndpoint.View(source, 0), changed.target, STONE, 10);
            expect(result.moved() == 0 && !result.stopDetail().isEmpty() && changed.target.variantCount() == 0
                    && changed.recovery.entries(changed.owner).get(0).key().equals(ItemKey.of(Items.DIRT))
                    && changed.recovery.entries(changed.owner).get(0).amount() == 10,
                    "Changed returned identity was discarded or committed as stone");
            expect(changed.executor.move(new ForgeInventoryEndpoint.View(source, 0), changed.target, STONE, 10).operations() == 0,
                    "Stopped executor continued extraction");

            var excessive = fixture(root, "excessive", 32, () -> { });
            source = source(20, (handler, requested) -> handler.take(requested + 5));
            result = excessive.executor.move(new ForgeInventoryEndpoint.View(source, 0), excessive.target, STONE, 10);
            expect(result.moved() == 0 && excessive.target.variantCount() == 0
                    && excessive.recovery.entries(excessive.owner).get(0).amount() == 15
                    && source.getStackInSlot(0).getCount() == 5, "Excess actual return lost unreserved items");

            var throwing = fixture(root, "throwing", 32, () -> { });
            source = source(20, (handler, requested) -> { handler.take(requested); throw new IllegalStateException("lost return"); });
            result = throwing.executor.move(new ForgeInventoryEndpoint.View(source, 0), throwing.target, STONE, 10);
            expect(result.moved() == 0 && !result.stopDetail().isEmpty() && throwing.target.variantCount() == 0
                    && throwing.recovery.pendingCount() == 0 && throwing.executor.incidents().size() == 1
                    && source.getStackInSlot(0).getCount() == 10, "Throwing source fabricated recovery from a quantity delta");

            var reentrant = fixture(root, "reentrant", 32, () -> { });
            source = source(20, (handler, requested) -> {
                try (var nested = LedgerTransaction.open()) { return handler.take(requested); }
            });
            result = reentrant.executor.move(new ForgeInventoryEndpoint.View(source, 0), reentrant.target, STONE, 10);
            expect(result.moved() == 0 && reentrant.target.variantCount() == 0
                    && source.getStackInSlot(0).getCount() == 20, "Reentrant outer transaction bypassed reservation");

            var notified = fixture(root, "notification", 32, () -> { throw new IllegalStateException("dirty notification"); });
            source = source(20, null);
            result = notified.executor.move(new ForgeInventoryEndpoint.View(source, 0), notified.target, STONE, 10);
            expect(result.moved() == 10 && !result.stopDetail().isEmpty() && notified.target.amountOf(STONE) == 10
                    && source.getStackInSlot(0).getCount() == 10 && notified.recovery.pendingCount() == 0,
                    "Committed notification failure duplicated items into recovery");
            try (var next = LedgerTransaction.open()) { /* prior exception must clear thread ownership */ }

            var digital = fixture(root, "digital", 32, () -> { });
            var ledger = new VolumeLedger(() -> { }, 32);
            ledger.load(STONE, 20);
            result = digital.executor.move(new ForgeInventoryEndpoint.View(ForgeDigitalItemStorage.of(ledger), 0),
                    digital.target, STONE, 10);
            expect(result.moved() == 0 && !result.stopDetail().isEmpty() && ledger.amountOf(STONE) == 20,
                    "Known digital source was treated as physical inventory");

            var filtered = fixture(root, "filtered", 32, () -> { });
            source = source(20, null);
            var keepLast = new com.tom.storagemod.util.FilteredInventoryHandler(source,
                    stack -> stack.getItem() == Items.STONE, true);
            result = filtered.executor.move(new ForgeInventoryEndpoint.View(keepLast, 0), filtered.target, STONE, 20);
            expect(result.moved() == 19 && !result.revisitSource() && result.stopDetail().isEmpty()
                    && source.getStackInSlot(0).getCount() == 1 && filtered.target.amountOf(STONE) == 19,
                    "Real Tom keep-last was bypassed or scheduled as blocked leftover");
            var denied = fixture(root, "denied", 32, () -> { });
            source = source(20, null);
            var reject = new com.tom.storagemod.util.FilteredInventoryHandler(source, stack -> false, false);
            result = denied.executor.move(new ForgeInventoryEndpoint.View(reject, 0), denied.target, STONE, 20);
            expect(result.moved() == 0 && source.getStackInSlot(0).getCount() == 20 && denied.target.variantCount() == 0,
                    "Physical Tom filter was replaced with an unrestricted parent");
        } finally {
            if (!root.getFileName().toString().startsWith("digitalstorage-forge-transfer-")) {
                throw new IllegalStateException("Unexpected transfer test directory");
            }
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            } catch (IOException failure) { throw new IllegalStateException("Could not clean isolated transfer fixture", failure); }
        }
    }

    static void capabilityCaptureFailure(ItemStack stack, Runnable failOn, Runnable failOff) {
        Path root;
        try { root = Files.createTempDirectory("digitalstorage-forge-transfer-"); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        try {
            var session = ForgeTransferSessions.openForTest(root.resolve("capability"));
            UUID owner = UUID.randomUUID();
            UUID volume = UUID.randomUUID();
            var fixture = new Fixture(owner, new VolumeLedger(volume, () -> { }, 32),
                    session.recovery(), session.executor(owner, volume));
            ItemStack actual = stack.copy();
            // Forge copies may keep lazy capability NBT. Initialize this real
            // provider so the injected serializer failure actually executes.
            actual.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                    .orElseThrow(() -> new IllegalStateException("Transfer test energy capability missing")).getEnergyStored();
            ItemKey expected = ItemKey.of(actual);
            var handler = new ItemStackHandler(1) {
                @Override public ItemStack extractItem(int slot, int count, boolean simulate) {
                    if (simulate) return super.extractItem(slot, count, true);
                    setStackInSlot(0, ItemStack.EMPTY);
                    failOn.run();
                    return actual;
                }
            };
            handler.setStackInSlot(0, stack.copy());
            var result = fixture.executor.move(new ForgeInventoryEndpoint.View(handler, 0), fixture.target, expected, stack.getCount());
            expect(result.moved() == 0 && !result.stopDetail().isEmpty() && fixture.target.variantCount() == 0
                    && fixture.recovery.uncapturedCount() == 1 && !fixture.recovery.available() && session.hasUnflushed()
                    && handler.getStackInSlot(0).isEmpty(), "Actual returned capability failure lost raw ownership: result="
                    + result + ", target=" + fixture.target.variantCount() + ", pending=" + fixture.recovery.pendingCount()
                    + ", uncaptured=" + fixture.recovery.uncapturedCount() + ", source=" + handler.getStackInSlot(0).getCount());
            failOff.run();
            expect(session.flush() && !session.hasUnflushed(), "Session flush did not settle retained capability ownership");
            var reopened = new ForgeTransferRecovery(root.resolve("capability/recovery"));
            expect(reopened.available() && reopened.entries(fixture.owner).get(0).key().equals(expected)
                    && reopened.entries(fixture.owner).get(0).amount() == stack.getCount(),
                    "Actual transfer capability ownership did not recover after serializer repair");
        } finally {
            failOff.run();
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            } catch (IOException failure) { throw new IllegalStateException("Could not clean capability transfer fixture", failure); }
        }
    }

    private static Fixture fixture(Path root, String label, int capacity, Runnable dirty) {
        UUID owner = UUID.randomUUID();
        UUID volume = UUID.randomUUID();
        var recovery = new ForgeTransferRecovery(root.resolve(label));
        return new Fixture(owner, new VolumeLedger(volume, dirty, capacity), recovery,
                new ForgeInventoryTransferExecutor(owner, volume, recovery));
    }
    private record Fixture(UUID owner, VolumeLedger target, ForgeTransferRecovery recovery,
                           ForgeInventoryTransferExecutor executor) { }
    private static Source source(int count, BiFunction<Source, Integer, ItemStack> actual) {
        return new Source(count, actual);
    }
    private static final class Source extends ItemStackHandler {
        private final BiFunction<Source, Integer, ItemStack> actual;
        private Source(int count, BiFunction<Source, Integer, ItemStack> actual) {
            super(1);
            this.actual = actual;
            setStackInSlot(0, new ItemStack(Items.STONE, count));
        }
        private ItemStack take(int count) { return super.extractItem(0, count, false); }
        @Override public ItemStack extractItem(int slot, int count, boolean simulate) {
            return simulate || actual == null ? super.extractItem(slot, count, simulate) : actual.apply(this, count);
        }
    }
    private static void expect(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
