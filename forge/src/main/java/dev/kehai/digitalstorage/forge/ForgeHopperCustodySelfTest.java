package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

/** Private disk fixtures exercise preservation before removal hooks are enabled. */
public final class ForgeHopperCustodySelfTest {
    private ForgeHopperCustodySelfTest() { }
    public static void run() {
        java.nio.file.Path directory = null;
        try {
            directory = Files.createTempDirectory("digitalstorage-hopper-custody-");
            var root = directory.resolve("records");
            var store = new ForgeHopperCustody(root);
            var engine = new ForgeHopperTransfer();
            var source = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            };
            source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
            var target = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    return simulate ? ItemStack.EMPTY : stack;
                }
            };
            engine.move(source, 0, target, 8);
            expect(engine.blocked() && engine.heldCount() == 8, "Known rejected remainder");
            UUID id = UUID.randomUUID();
            var pos = new BlockPos(12, 64, -8);
            expect(store.retain(id, "minecraft:overworld", pos, engine, engine::saveState), "Durable independent record");
            var reopened = new ForgeHopperCustody(root);
            expect(!reopened.available() && reopened.pendingCount() == 1
                    && ForgeHopperTransfer.restore((CompoundTag) reopened.state(id)).heldCount() == 8,
                    "Restart preserves state without delivery");
            var observed = (CompoundTag) reopened.state(id); observed.putInt("Amount", 0);
            expect(((CompoundTag) reopened.state(id)).getInt("Amount") == 8, "Defensive snapshot");

            var second = new Object();
            expect(!reopened.retain(id, "minecraft:overworld", pos, second, engine::saveState)
                    && reopened.pendingCount() == 2, "Loaded identity cannot overwrite existing evidence");
            reopened.retain(id, "minecraft:overworld", pos, second, engine::saveState);
            expect(reopened.pendingCount() == 2 && ((CompoundTag) reopened.state(id)).getInt("Amount") == 8,
                    "Repeated conflict retains one distinct record");
            expect(new ForgeHopperCustody(root).pendingCount() == 2, "Conflict survives restart");

            UUID raw = UUID.randomUUID();
            var original = engine.heldStack();
            boolean[] fail = {true};
            expect(!store.retain(raw, "minecraft:the_nether", pos, original, () -> {
                if (fail[0]) throw new IllegalStateException("injected encoding failure");
                return engine.saveState();
            }), "Encoding failure");
            expect(store.unsavedCount() == 1 && store.retainsIdentity(raw, original)
                    && !store.lastFailure(raw).isEmpty() && original.getCount() == 8,
                    "Original ownership retained before encoding");
            fail[0] = false;
            expect(store.flush() && store.unsavedCount() == 0, "Retry encodes original state");
            expect(ForgeHopperTransfer.restore((CompoundTag) new ForgeHopperCustody(root).state(raw)).heldCount() == 8,
                    "Retry survives restart");

            UUID opaque = UUID.randomUUID();
            var unknown = StringTag.valueOf("future typed state");
            expect(store.retain(opaque, "minecraft:overworld", pos, unknown, () -> unknown), "Opaque state saved");
            expect(new ForgeHopperCustody(root).state(opaque).equals(unknown), "Opaque state unchanged");

            var failureRoot = directory.resolve("disk-failure");
            var failing = new ForgeHopperCustody(failureRoot);
            UUID failedId = UUID.randomUUID();
            Files.createDirectory(failureRoot.resolve(failedId + ".tmp"));
            expect(!failing.retain(failedId, "minecraft:overworld", pos, engine, engine::saveState)
                    && failing.unsavedCount() == 1 && failing.retainsIdentity(failedId, engine), "Disk failure retains engine");
            Files.delete(failureRoot.resolve(failedId + ".tmp"));
            expect(failing.flush() && new ForgeHopperCustody(failureRoot).pendingCount() == 1, "Disk retry");

            var evidence = directory.resolve("unreadable");
            Files.createDirectories(evidence);
            byte[] originalBytes = {1, 2, 3};
            Files.write(evidence.resolve(UUID.randomUUID() + ".dat"), originalBytes);
            Files.write(evidence.resolve(UUID.randomUUID() + ".tmp"), originalBytes);
            var unreadable = new ForgeHopperCustody(evidence);
            expect(unreadable.unreadableFiles() == 2 && !unreadable.available(), "Damaged and interrupted files block");
            unreadable.flush();
            try (var files = Files.list(evidence)) {
                for (var file : files.toList()) expect(java.util.Arrays.equals(Files.readAllBytes(file), originalBytes),
                        "Unreadable evidence untouched");
            }
            DigitalStorage.LOGGER.info("Forge hopper custody self-test passed: independent disk snapshots, raw live identity retention, encoding/disk retries, conflict preservation, opaque tags and damaged/interrupted evidence; lifecycle hooks not enabled");
        } catch (IOException failure) {
            throw new IllegalStateException("Hopper custody fixture failed", failure);
        } finally {
            if (directory != null) {
                try (var files = Files.walk(directory)) {
                    for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                } catch (IOException failure) { throw new IllegalStateException("Cannot clean private custody fixture", failure); }
            }
        }
    }
    private static void expect(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException("Hopper custody: " + detail);
    }

    /** Called while the actual energy provider serializer is failing. */
    static void capabilityCaptureFailure(ForgeHopperTransfer engine, ItemStack original, int energy, Runnable allow) {
        java.nio.file.Path directory = null;
        try {
            directory = Files.createTempDirectory("digitalstorage-hopper-custody-cap-");
            var store = new ForgeHopperCustody(directory);
            UUID id = UUID.randomUUID();
            expect(!store.retain(id, "minecraft:overworld", BlockPos.ZERO, engine, engine::saveState)
                    && store.unsavedCount() == 1 && store.retainsIdentity(id, engine)
                    && engine.heldStack() == original, "Actual capability failure retains original through custody");
            allow.run();
            expect(store.flush() && store.unsavedCount() == 0, "Actual capability serializer retry");
            var restored = ForgeHopperTransfer.restore((CompoundTag) new ForgeHopperCustody(directory).state(id));
            expect(restored.blocked() && !restored.uncertain() && restored.heldCount() == original.getCount()
                    && restored.heldStack().getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                    .orElseThrow(() -> new IllegalStateException("Custody restored energy capability missing"))
                    .getEnergyStored() == energy, "Custody restart preserves actual live capability value");
        } catch (IOException failure) { throw new IllegalStateException("Capability custody fixture failed", failure); }
        finally {
            if (directory != null) {
                try (var files = Files.walk(directory)) {
                    for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                } catch (IOException failure) { throw new IllegalStateException("Cannot clean capability custody fixture", failure); }
            }
        }
    }
}
