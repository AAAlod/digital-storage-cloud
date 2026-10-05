package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

/** Real handler callbacks, including partial acceptance and ambiguous delivery. */
public final class ForgeBatchExportSelfTest {
    private ForgeBatchExportSelfTest() { }
    public static void run() {
        java.nio.file.Path root;
        try { root = Files.createTempDirectory("digitalstorage-batch-export-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        try {
            UUID owner = UUID.randomUUID(), volume = UUID.randomUUID();
            var record = DigitalStorageRecord.createNew(() -> { });
            var tool = new ItemStack(Items.DIAMOND_SWORD); tool.setDamageValue(17);
            tool.setHoverName(net.minecraft.network.chat.Component.literal("Exact sword"));
            var key = ItemKey.of(tool);
            record.storage().load(key, 3);
            var store = new ForgeTransferRecovery(root.resolve("partial"));
            var target = new ItemStackHandler(1);
            var result = ForgeBatchExport.move(record, key, 3, target, 0, owner, volume, store, () -> { });
            expect(result.moved() == 1 && record.storage().amountOf(key) == 2
                    && ItemKey.of(target.getStackInSlot(0)).equals(key) && !record.acceptsUnstackableItems()
                    && store.pendingCount() == 0, "Cleanup lost tool identity or required accepting new tools");
            result = ForgeBatchExport.move(record, key, 2, target, 0, owner, volume, store, () -> { });
            expect(result.moved() == 0 && record.storage().amountOf(key) == 2, "Full slot consumed source");

            var stone = ItemKey.of(Items.STONE); record.storage().load(stone, 20);
            var partial = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    if (simulate) return ItemStack.EMPTY;
                    var accepted = stack.copy(); accepted.setCount(7); super.insertItem(slot, accepted, false);
                    var rest = stack.copy(); rest.shrink(7); return rest;
                }
            };
            result = ForgeBatchExport.move(record, stone, 20, partial, 0, owner, volume, store, () -> { });
            expect(result.moved() == 7 && record.storage().amountOf(stone) == 13
                    && partial.getStackInSlot(0).getCount() == 7 && store.pendingCount() == 0,
                    "Actual partial insertion did not restore exact remainder");

            var uncertain = new ForgeTransferRecovery(root.resolve("uncertain"));
            var broken = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    if (simulate) return ItemStack.EMPTY;
                    super.insertItem(slot, stack, false);
                    throw new IllegalStateException("Injected callback failure after accepting items");
                }
            };
            result = ForgeBatchExport.move(record, stone, 5, broken, 0, owner, volume, uncertain, () -> { });
            expect(result.detail().equals("recovery_required") && result.moved() == 0
                    && record.storage().amountOf(stone) == 8 && broken.getStackInSlot(0).getCount() == 5
                    && new ForgeTransferRecovery(root.resolve("uncertain")).inFlightCount() == 1,
                    "Unknown delivery restored or replayed possibly delivered items");
            expect(ForgeBatchExport.move(record, stone, 5, broken, 0, owner, volume, uncertain, () -> { }).moved() == 0
                    && record.storage().amountOf(stone) == 8 && broken.getStackInSlot(0).getCount() == 5,
                    "Uncertain delivery did not block another output");
            var receipt = uncertain.entries(owner).get(0);
            boolean replayDenied = false;
            try { uncertain.reconcile(receipt.id(), receipt.deliveryId(), owner, ForgeTransferRecovery.Outcome.CONFIRMED_NOT_DELIVERED, "fixture"); }
            catch (IllegalStateException expected) { replayDenied = true; }
            expect(replayDenied && uncertain.inFlightCount() == 1, "Import recovery replayed a possibly restored withdrawal");

            var failed = new ForgeTransferRecovery(root.resolve("intent-failure"));
            Files.delete(root.resolve("intent-failure"));
            boolean denied = false;
            try { failed.holdWithdrawal(owner, volume, stone, 4); }
            catch (IllegalStateException expected) { denied = true; }
            expect(denied && failed.inFlightCount() == 1 && !failed.available()
                    && failed.entries(owner).stream().allMatch(e -> e.state() == ForgeTransferRecovery.State.DELIVERING),
                    "Failed withdrawal intent became an automatically deliverable HELD stack");
            Files.createDirectories(root.resolve("intent-failure")); failed.flushUnsaved();
            expect(new ForgeTransferRecovery(root.resolve("intent-failure")).inFlightCount() == 1,
                    "Intent write retry changed uncertain ownership");
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        finally {
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
    }
    private static void expect(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
}
