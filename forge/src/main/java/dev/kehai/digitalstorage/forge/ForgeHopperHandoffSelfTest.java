package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

/** Real private files and shared ledger delivery exercise the two-store handoff protocol. */
public final class ForgeHopperHandoffSelfTest {
    private ForgeHopperHandoffSelfTest() { }
    public static void run() {
        Path root = null;
        try {
            root = Files.createTempDirectory("digitalstorage-hopper-handoff-");
            UUID owner = UUID.randomUUID(), volume = UUID.randomUUID(), admin = UUID.randomUUID();
            var custody = new ForgeHopperCustody(root.resolve("custody"));
            var recovery = new ForgeTransferRecovery(root.resolve("recovery"));
            UUID id = UUID.randomUUID();
            var engine = held();
            custody.retain(id, "minecraft:overworld", BlockPos.ZERO, engine, engine::saveState);
            var oldMirror = engine.saveState();
            var stale = new ForgeHopperCustody(root.resolve("custody"));
            var staleIncoming = ForgeHopperTransfer.restore(oldMirror);
            var staleBinding = stale.bind(id, "minecraft:overworld", BlockPos.ZERO, staleIncoming, staleIncoming::saveState);
            expect(custody.export(id, owner, volume, admin, "Inspected actual returned remainder", recovery).equals(id)
                    && custody.available() && custody.pendingCount() == 0 && engine.heldCount() == 0
                    && engine.exported() && engine.blocked(), "Durable handoff releases live ownership and keeps receipt");
            expect(recovery.ownedEntry(id, owner).amount() == 8, "One stable recovery entry");
            boolean targetRejected = false;
            try { custody.export(id, owner, UUID.randomUUID(), admin, "wrong target", recovery); }
            catch (IllegalArgumentException expected) { targetRejected = true; }
            expect(targetRejected, "Handoff target cannot change");
            boolean staleRejected = false;
            try { stale.export(id, owner, volume, admin, "stale writer", recovery); }
            catch (IllegalStateException expected) { staleRejected = true; }
            expect(staleRejected && !stale.retain(id, "minecraft:overworld", BlockPos.ZERO, staleBinding.engine(), staleBinding.engine()::saveState)
                    && !stale.flush(), "Stale store cannot overwrite completed receipt");
            var reopened = new ForgeHopperCustody(root.resolve("custody"));
            var loaded = ForgeHopperTransfer.restore(oldMirror);
            var rebound = reopened.bind(id, "minecraft:overworld", BlockPos.ZERO, loaded, loaded::saveState);
            expect(!rebound.id().equals(id) && !rebound.engine().blocked() && rebound.engine().heldCount() == 0
                    && reopened.available(), "Old chunk mirror retires identity instead of resurrecting items");
            expect(reopened.export(id, owner, volume, admin, "repeat", recovery).equals(id)
                    && recovery.pendingCount() == 1, "Repeated export does not create another recovery entry");

            var target = new VolumeLedger(volume, () -> { }, 32);
            var delivered = ForgeRecoveryDelivery.deliver(recovery, recovery.ownedEntry(id, owner), owner,
                    Long.MAX_VALUE, target, () -> { });
            expect(delivered.state() == ForgeRecoveryDelivery.State.DELIVERED
                    && target.amountOf(ItemKey.of(Items.STONE)) == 8, "Existing recovery delivers exactly once");
            new ForgeHopperCustody(root.resolve("custody")).export(id, owner, volume, admin, "after delivery", recovery);
            expect(recovery.pendingCount() == 0 && target.amountOf(ItemKey.of(Items.STONE)) == 8,
                    "Delivered recovery is not recreated from hopper evidence");

            Path failedRoot = root.resolve("failure");
            var failing = new ForgeHopperCustody(failedRoot.resolve("custody"));
            var failedRecovery = new ForgeTransferRecovery(failedRoot.resolve("recovery"));
            UUID failedId = UUID.randomUUID();
            var failedEngine = held();
            failing.retain(failedId, "minecraft:overworld", BlockPos.ZERO, failedEngine, failedEngine::saveState);
            Path recoveryTemp = failedRoot.resolve("recovery").resolve(failedId + ".tmp");
            Files.createDirectory(recoveryTemp);
            boolean writeRejected = false;
            try { failing.export(failedId, owner, volume, admin, "recovery write failure", failedRecovery); }
            catch (IllegalStateException expected) { writeRejected = true; }
            expect(writeRejected && !failing.available() && failedEngine.heldCount() == 8
                    && failedRecovery.unsavedCount() == 1, "Recovery write failure preserves handoff and source");
            Files.delete(recoveryTemp); failedRecovery.flushUnsaved();
            Path receiptTemp = failedRoot.resolve("custody").resolve(failedId + ".tmp");
            writeRejected = false;
            try {
                failing.export(failedId, owner, volume, admin, "receipt write failure", failedRecovery, () -> {
                    try { Files.createDirectory(receiptTemp); }
                    catch (IOException failure) { throw new IllegalStateException(failure); }
                });
            } catch (IllegalStateException expected) { writeRejected = true; }
            expect(writeRejected && failing.unsavedCount() == 1 && failedEngine.heldCount() == 8
                    && failedRecovery.ownedEntry(failedId, owner).amount() == 8, "Completion failure cannot repeat adoption");
            Files.delete(receiptTemp);
            var diskPending = new ForgeHopperCustody(failedRoot.resolve("custody"));
            expect(!diskPending.available(), "Restart preserves incomplete handoff");
            diskPending.export(failedId, owner, volume, admin, "explicit retry", failedRecovery);
            expect(diskPending.available() && failedRecovery.pendingCount() == 1, "Restart retry reuses stable recovery identity");
            expect(!failing.flush(), "Old in-memory completion cannot overwrite a newer receipt");

            var rejected = new ForgeHopperCustody(root.resolve("rejected"));
            UUID unknownId = UUID.randomUUID();
            var unknown = ForgeHopperTransfer.restore(new net.minecraft.nbt.CompoundTag());
            rejected.retain(unknownId, "minecraft:overworld", BlockPos.ZERO, unknown, unknown::saveState);
            boolean unknownRejected = false;
            try { rejected.export(unknownId, owner, volume, admin, "unknown state", recovery); }
            catch (IllegalStateException expected) { unknownRejected = true; }
            expect(unknownRejected && rejected.pendingCount() == 1, "Unknown state cannot manufacture recoverable items");
            var uncertainSource = new ItemStackHandler(1);
            uncertainSource.setStackInSlot(0, new ItemStack(Items.STONE, 10));
            var acceptingThenThrowing = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    if (simulate) return ItemStack.EMPTY;
                    super.insertItem(slot, stack.copy(), false);
                    throw new IllegalStateException("Accepted before callback failure");
                }
            };
            var uncertain = new ForgeHopperTransfer(); uncertain.move(uncertainSource, 0, acceptingThenThrowing, 8);
            UUID uncertainId = UUID.randomUUID();
            rejected.retain(uncertainId, "minecraft:overworld", BlockPos.ZERO, uncertain, uncertain::saveState);
            boolean uncertainRejected = false;
            try { rejected.export(uncertainId, owner, volume, admin, "Uncertain insertion", recovery); }
            catch (IllegalStateException expected) { uncertainRejected = true; }
            expect(uncertainRejected && uncertain.heldCount() == 8 && recovery.entry(uncertainId) == null
                    && acceptingThenThrowing.getStackInSlot(0).getCount() == 8
                    && uncertainSource.getStackInSlot(0).getCount() == 2, "Uncertain positive stack cannot duplicate accepted items");
            UUID conflictId = UUID.randomUUID();
            var original = held();
            rejected.retain(conflictId, "minecraft:overworld", BlockPos.ZERO, original, original::saveState);
            rejected.retain(conflictId, "minecraft:overworld", BlockPos.ZERO, held(), () -> oldMirror);
            boolean conflictRejected = false;
            try { rejected.export(conflictId, owner, volume, admin, "unresolved conflict", recovery); }
            catch (IllegalStateException expected) { conflictRejected = true; }
            expect(conflictRejected, "Unresolved duplicate evidence prohibits handoff");
            UUID shadowId = rejected.entries().stream().filter(entry -> conflictId.equals(entry.conflictsWith()))
                    .findFirst().orElseThrow().id();
            Path shadowTemp = root.resolve("rejected").resolve(shadowId + ".tmp");
            Files.createDirectory(shadowTemp);
            try { rejected.retire(shadowId, admin, "External evidence identifies this branch as duplicate"); }
            catch (IllegalStateException expected) { }
            conflictRejected = false;
            try { rejected.export(conflictId, owner, volume, admin, "receipt not durable", recovery); }
            catch (IllegalStateException expected) { conflictRejected = true; }
            expect(conflictRejected, "Unflushed retirement cannot unblock the primary source");
            Files.delete(shadowTemp);
            expect(rejected.flush(), "Conflict retirement receipt retries");
            rejected.export(conflictId, owner, volume, admin, "Duplicate branch independently retired", recovery);
            expect(original.heldCount() == 0 && recovery.ownedEntry(conflictId, owner).amount() == 8,
                    "Durable duplicate retirement permits only original confirmed source handoff");
            var retirementRoot = root.resolve("retirement");
            var retiring = new ForgeHopperCustody(retirementRoot);
            UUID retiredId = UUID.randomUUID();
            var retirementEngine = held(); retirementEngine.markUncertain("external outcome requires reconciliation");
            var mirror = retirementEngine.saveState();
            retiring.retain(retiredId, "minecraft:overworld", BlockPos.ZERO, retirementEngine, retirementEngine::saveState);
            var staleRetirement = new ForgeHopperCustody(retirementRoot);
            Path retirementTemp = retirementRoot.resolve(retiredId + ".tmp");
            Files.createDirectory(retirementTemp);
            boolean retirementFailed = false;
            try { retiring.retire(retiredId, admin, "External destination verified; no deliverable remainder"); }
            catch (IllegalStateException expected) { retirementFailed = true; }
            expect(retirementFailed && retirementEngine.heldCount() == 8 && retiring.retainsIdentity(retiredId, retirementEngine)
                    && retiring.unsavedCount() == 1, "Retirement write failure preserves original owner");
            Files.delete(retirementTemp);
            expect(retiring.flush() && retirementEngine.heldCount() == 0 && retiring.pendingCount() == 0,
                    "Only durable retirement releases original live source");
            var retiredDisk = new ForgeHopperCustody(retirementRoot);
            expect(retiredDisk.available() && retiredDisk.entries().get(0).phase().equals("RETIRED")
                    && retiredDisk.entries().get(0).observedAmount() == 8
                    && !retiredDisk.entries().get(0).confirmed() && retiredDisk.state(retiredId).equals(mirror),
                    "Retirement preserves original observation and raw audit state");
            retiredDisk.retire(retiredId, UUID.randomUUID(), "repeat cannot rewrite evidence");
            expect(retiredDisk.entries().get(0).administrator().equals(admin), "Retirement receipt is immutable on retry");
            boolean staleRetirementRejected = false;
            try { staleRetirement.retire(retiredId, admin, "stale reconciliation"); }
            catch (IllegalStateException expected) { staleRetirementRejected = true; }
            expect(staleRetirementRejected, "Stale retirement cannot overwrite newer evidence");
            var retiredMirror = ForgeHopperTransfer.restore(mirror);
            var readyBinding = retiredDisk.bind(retiredId, "minecraft:overworld", BlockPos.ZERO,
                    retiredMirror, retiredMirror::saveState);
            expect(!readyBinding.id().equals(retiredId) && !readyBinding.engine().blocked()
                    && readyBinding.engine().heldCount() == 0, "Retired mirror cannot resurrect ownership");
            int recoveryBefore = recovery.pendingCount();
            boolean retiredExportRejected = false;
            try { retiredDisk.export(retiredId, owner, volume, admin, "cannot resurrect", recovery); }
            catch (IllegalStateException expected) { retiredExportRejected = true; }
            expect(retiredExportRejected && recovery.pendingCount() == recoveryBefore, "Retirement creates no recovery items");
            UUID opaqueRetired = UUID.randomUUID();
            var opaqueState = net.minecraft.nbt.StringTag.valueOf("unknown state; no observed items");
            retiredDisk.retain(opaqueRetired, "minecraft:overworld", BlockPos.ZERO, new Object(), () -> opaqueState);
            retiredDisk.retire(opaqueRetired, admin, "External inventories inspected; no owned returned stack");
            expect(new ForgeHopperCustody(retirementRoot).state(opaqueRetired).equals(opaqueState)
                    && ForgeHopperCommands.observed(retiredDisk.entries().stream()
                            .filter(entry -> entry.id().equals(opaqueRetired)).findFirst().orElseThrow()).equals("未知"),
                    "Unknown retirement does not invent zero observation or alter opaque evidence");
            DigitalStorage.LOGGER.info("Forge hopper handoff self-test passed: stable recovery identity, target guard, permanent receipt, stale writer rejection, old mirror retirement, exact ledger delivery, recovery/receipt failures, explicit restart retry and administrator zero-remainder retirement; administrator commands covered separately");
        } catch (IOException failure) { throw new IllegalStateException("Hopper handoff fixture failed", failure); }
        finally {
            if (root != null) {
                try (var files = Files.walk(root)) {
                    for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                } catch (IOException failure) { throw new IllegalStateException("Cannot clean handoff fixture", failure); }
            }
        }
    }
    private static ForgeHopperTransfer held() {
        var source = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        };
        source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        var target = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return simulate ? ItemStack.EMPTY : stack; }
        };
        var engine = new ForgeHopperTransfer(); engine.move(source, 0, target, 8); return engine;
    }
    /** Called with the real energy provider attached by ForgeItemKeySelfTest. */
    static void capabilityHandoff(ItemStack sample) {
        Path root = null;
        try {
            root = Files.createTempDirectory("digitalstorage-hopper-handoff-cap-");
            var raw = sample.copy(); raw.setCount(1);
            int energy = raw.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                    .orElseThrow(() -> new IllegalStateException("Handoff energy capability missing")).getEnergyStored();
            var source = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            };
            source.setStackInSlot(0, raw);
            var destination = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return simulate ? ItemStack.EMPTY : stack; }
            };
            var engine = new ForgeHopperTransfer();
            expect(engine.move(source, 0, destination, 1).stopped() && engine.heldCount() == 1, "Actual capability remainder");
            UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), volume = UUID.randomUUID();
            var custody = new ForgeHopperCustody(root.resolve("custody"));
            var recovery = new ForgeTransferRecovery(root.resolve("recovery"));
            custody.retain(id, "minecraft:overworld", BlockPos.ZERO, engine, engine::saveState);
            custody.export(id, owner, volume, UUID.randomUUID(), "Inspected energy capability", recovery);
            var diskRecovery = new ForgeTransferRecovery(root.resolve("recovery"));
            var entry = diskRecovery.ownedEntry(id, owner);
            expect(entry.key().hasAttachments() && entry.key().toStack(1)
                    .getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                    .orElseThrow(() -> new IllegalStateException("Reloaded handoff capability missing")).getEnergyStored() == energy,
                    "Handoff preserves live capability value through disk");
            var target = new VolumeLedger(volume, () -> { }, 32);
            var result = ForgeRecoveryDelivery.deliver(diskRecovery, entry, owner, 1, target, () -> { });
            expect(result.state() == ForgeRecoveryDelivery.State.DELIVERED && target.amountOf(entry.key()) == 1
                    && new ForgeHopperCustody(root.resolve("custody")).available(), "Capability item settles once with permanent hopper receipt");
        } catch (IOException failure) { throw new IllegalStateException("Capability handoff fixture failed", failure); }
        finally {
            if (root != null) {
                try (var files = Files.walk(root)) {
                    for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
                } catch (IOException failure) { throw new IllegalStateException("Cannot clean capability handoff fixture", failure); }
            }
        }
    }
    private static void expect(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException("Hopper handoff: " + detail);
    }
}
