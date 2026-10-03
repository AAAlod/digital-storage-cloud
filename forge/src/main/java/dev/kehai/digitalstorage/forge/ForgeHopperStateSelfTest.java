package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

/** Disk round trips of stopped ownership and unknown callback outcomes; no player files. */
public final class ForgeHopperStateSelfTest {
    private ForgeHopperStateSelfTest() { }
    public static void run() {
        Path root;
        try { root = Files.createTempDirectory("digitalstorage-forge-hopper-state-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        try {
            var engine = roundTrip(root, "ready", new ForgeHopperTransfer());
            var source = source(10);
            var target = new ItemStackHandler(1);
            expect(!engine.blocked() && engine.move(source, 0, target, 4).moved() == 4, "Ready state resumes normally");

            engine = new ForgeHopperTransfer();
            source = new ItemStackHandler(1) {
                @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                    if (simulate) return super.extractItem(slot, amount, true);
                    var remaining = getStackInSlot(slot).copy();
                    var returned = remaining.copy();
                    returned.setCount(256);
                    remaining.shrink(256);
                    setStackInSlot(slot, remaining);
                    return returned;
                }
            };
            source.setStackInSlot(0, new ItemStack(Items.STONE, 300));
            expect(engine.move(source, 0, new ItemStackHandler(1), 16).stopped(), "Excessive returned stack retained");
            var saved = engine.saveState();
            expect(saved.getInt("Amount") == 256 && saved.getCompound("Item").getByte("Count") == 1,
                    "Full amount separate from vanilla byte count");
            saved.getCompound("Item").putString("id", "minecraft:dirt");
            expect(engine.heldStack().is(Items.STONE), "Snapshot cannot mutate original identity");
            engine = roundTrip(root, "excessive", engine);
            expect(engine.blocked() && !engine.uncertain() && engine.heldCount() == 256
                    && engine.heldStack().is(Items.STONE), "Oversized original return survives disk");
            source = source(10);
            expect(engine.move(source, 0, new ItemStackHandler(1), 4).stopped()
                    && source.getStackInSlot(0).getCount() == 10, "Restart cannot continue while holding ownership");

            engine = new ForgeHopperTransfer(); source = source(10);
            target = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    if (simulate) return ItemStack.EMPTY;
                    setStackInSlot(slot, stack.copy());
                    throw new IllegalStateException("Insertion outcome uncertain");
                }
            };
            engine.move(source, 0, target, 8);
            engine = roundTrip(root, "uncertain", engine);
            expect(engine.blocked() && engine.uncertain() && engine.heldCount() == 8
                    && engine.move(source, 0, target, 8).stopped() && target.getStackInSlot(0).getCount() == 8,
                    "Uncertain insertion remains blocked without replay after disk reload");

            engine = new ForgeHopperTransfer();
            source = new ItemStackHandler(1) {
                @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                    if (simulate) return super.extractItem(slot, amount, true);
                    super.extractItem(slot, amount, false);
                    throw new IllegalStateException("Extraction had no returned stack");
                }
            };
            source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
            engine.move(source, 0, new ItemStackHandler(1), 4);
            engine = roundTrip(root, "no-return", engine);
            expect(engine.blocked() && engine.uncertain() && engine.heldCount() == 0,
                    "Unknown extraction persists without inventing a stack");

            for (int test = 0; test < 6; test++) {
                var invalid = new ForgeHopperTransfer().saveState();
                switch (test) {
                    case 0 -> { invalid.putInt("Version", 99); invalid.putString("Future", "preserve this"); }
                    case 1 -> invalid.remove("Amount");
                    case 2 -> invalid.putByte("Uncertain", (byte) 2);
                    case 3 -> invalid.putInt("Amount", -1);
                    case 4 -> { invalid.putInt("Amount", 8); invalid.putBoolean("Blocked", true); invalid.putString("Detail", "held"); }
                    case 5 -> { invalid.putBoolean("Uncertain", true); }
                }
                var expected = invalid.copy();
                engine = ForgeHopperTransfer.restore(invalid);
                invalid.putString("Mutation", "not part of retained state");
                expect(engine.blocked() && engine.uncertain() && engine.saveState().equals(expected),
                        "Malformed/future state preserved and blocked: " + test);
                engine = roundTrip(root, "invalid-" + test, engine);
                expect(engine.blocked() && engine.saveState().equals(expected), "Invalid data preserved across disk: " + test);
            }
            DigitalStorage.LOGGER.info("Forge hopper state self-test passed: disk/oversized counts/uncertain outcomes/unknown extraction/future and malformed state preserved; device save hooks not integrated");
        } finally {
            try {
                try (var paths = Files.list(root)) {
                    for (var file : paths.toList()) Files.delete(file);
                }
                Files.delete(root);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
    }

    static void capabilityCaptureFailure(ItemStack sample, Runnable fail, Runnable allow) {
        var raw = sample.copy();
        raw.setCount(1);
        // A Forge copy can retain lazy capability NBT without calling the live
        // serializer. Initialize the provider before injecting its failure.
        int energy = raw.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .orElseThrow(() -> new IllegalStateException("Hopper test energy capability missing")).getEnergyStored();
        var engine = new ForgeHopperTransfer();
        var source = new ItemStackHandler(1) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (simulate) return raw.copy();
                setStackInSlot(slot, ItemStack.EMPTY);
                fail.run();
                return raw;
            }
        };
        source.setStackInSlot(0, raw.copy());
        try {
            var result = engine.move(source, 0, new ItemStackHandler(1), 1);
            expect(result.stopped() && engine.heldStack() == raw && !engine.uncertain(),
                    "Capture failure retains original capability stack: " + result + ", count=" + engine.heldCount()
                            + ", same=" + (engine.heldStack() == raw) + ", uncertain=" + engine.uncertain());
            boolean rejected = false;
            try { engine.saveState(); } catch (RuntimeException expected) { rejected = true; }
            expect(rejected && engine.heldStack() == raw && engine.heldCount() == raw.getCount(),
                    "Failed encoding cannot discard raw ownership");
        } finally { allow.run(); }
        var restored = ForgeHopperTransfer.restore(engine.saveState());
        expect(restored.blocked() && !restored.uncertain() && restored.heldCount() == raw.getCount()
                && ItemKey.of(restored.heldStack()).equals(ItemKey.of(raw)), "Capability state retained after encoding retry");
        expect(restored.heldStack().getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .orElseThrow(() -> new IllegalStateException("Restored hopper capability missing")).getEnergyStored() == energy,
                "Live restored energy value matches retained original");
    }

    private static ForgeHopperTransfer roundTrip(Path root, String name, ForgeHopperTransfer engine) {
        try {
            var file = root.resolve(name + ".nbt").toFile();
            NbtIo.writeCompressed(engine.saveState(), file);
            return ForgeHopperTransfer.restore(NbtIo.readCompressed(file));
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static ItemStackHandler source(int count) {
        var source = new ItemStackHandler(1); source.setStackInSlot(0, new ItemStack(Items.STONE, count)); return source;
    }
    private static void expect(boolean value, String detail) {
        if (!value) throw new IllegalStateException("Forge hopper state: " + detail);
    }
}
