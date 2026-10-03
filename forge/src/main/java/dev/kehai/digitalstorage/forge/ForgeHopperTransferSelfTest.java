package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

/** Real handlers, including divergent simulation and callbacks that mutate before throwing. */
public final class ForgeHopperTransferSelfTest {
    private ForgeHopperTransferSelfTest() { }
    public static void run() {
        var engine = new ForgeHopperTransfer();
        var source = source(40);
        var target = new ItemStackHandler(1);
        var result = engine.move(source, 0, target, 16);
        expect(result.moved() == 16 && !result.stopped() && count(source) == 24 && count(target) == 16,
                "Normal physical batch");
        target.setStackInSlot(0, new ItemStack(Items.STONE, 64));
        expect(engine.move(source, 0, target, 16).moved() == 0 && count(source) == 24, "Full target extraction");

        engine = new ForgeHopperTransfer();
        source = source(20);
        target = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (simulate) return ItemStack.EMPTY;
                var offered = stack.copy(); offered.setCount(3);
                super.insertItem(slot, offered, false);
                var remainder = stack.copy(); remainder.shrink(3); return remainder;
            }
        };
        result = engine.move(source, 0, target, 10);
        expect(result.moved() == 3 && !result.stopped() && count(source) == 17 && count(target) == 3
                && engine.heldCount() == 0, "Actual partial insertion compensated to original slot");

        engine = new ForgeHopperTransfer();
        source = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        };
        source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        target = rejectActual();
        result = engine.move(source, 0, target, 8);
        expect(result.stopped() && result.moved() == 0 && count(source) == 2 && engine.heldCount() == 8
                && !engine.uncertain(), "Rejected compensation retains known returned ownership");
        expect(engine.move(source, 0, new ItemStackHandler(1), 8).stopped() && count(source) == 2,
                "Pending ownership blocks new extraction");

        engine = new ForgeHopperTransfer();
        source = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                super.insertItem(slot, stack, simulate);
                if (!simulate) throw new IllegalStateException("Compensation accepted then failed");
                return ItemStack.EMPTY;
            }
        };
        source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        result = engine.move(source, 0, rejectActual(), 8);
        expect(result.stopped() && engine.uncertain() && engine.heldCount() == 8 && count(source) == 10,
                "Throwing compensation is not replayed as known held ownership");

        engine = new ForgeHopperTransfer(); source = source(10);
        target = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return simulate ? ItemStack.EMPTY : new ItemStack(Items.DIRT, stack.getCount());
            }
        };
        result = engine.move(source, 0, target, 8);
        expect(result.stopped() && engine.uncertain() && engine.heldCount() == 8
                && engine.heldStack().is(Items.STONE), "Malformed remainder cannot replace original retained identity");

        engine = new ForgeHopperTransfer(); source = source(10);
        target = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (simulate) return ItemStack.EMPTY;
                setStackInSlot(slot, stack.copy());
                stack.setCount(1);
                throw new IllegalStateException("After accepting items");
            }
        };
        result = engine.move(source, 0, target, 8);
        expect(result.stopped() && result.moved() == 0 && count(source) == 2 && count(target) == 8
                && engine.heldCount() == 8 && engine.uncertain(), "Throwing insertion is uncertain, original raw instance retained");

        engine = new ForgeHopperTransfer(); source = source(10);
        target = new ItemStackHandler(2) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (slot == 1 && !simulate) throw new IllegalStateException("Second slot failed");
                return super.insertItem(slot, stack, simulate);
            }
            @Override public int getSlotLimit(int slot) { return slot == 0 ? 3 : 64; }
        };
        result = engine.move(source, 0, target, 8);
        expect(result.moved() == 3 && result.stopped() && engine.heldCount() == 5 && engine.uncertain()
                && count(target) == 3 && count(source) == 2, "Earlier completed insertions remain counted");

        for (boolean excessive : new boolean[]{false, true}) {
            engine = new ForgeHopperTransfer();
            source = new ItemStackHandler(1) {
                @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                    if (simulate) return super.extractItem(slot, amount, true);
                    var actual = super.extractItem(slot, excessive ? amount + 1 : amount, false);
                    return excessive ? actual : new ItemStack(Items.DIRT, actual.getCount());
                }
            };
            source.setStackInSlot(0, new ItemStack(Items.STONE, 10)); target = new ItemStackHandler(1);
            result = engine.move(source, 0, target, 4);
            expect(result.stopped() && result.moved() == 0 && count(target) == 0 && !engine.uncertain()
                    && engine.heldCount() == (excessive ? 5 : 4)
                    && engine.heldStack().is(excessive ? Items.STONE : Items.DIRT), "Malformed actual extraction retained without insertion");
        }

        engine = new ForgeHopperTransfer();
        source = new ItemStackHandler(1) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (simulate) return super.extractItem(slot, amount, true);
                super.extractItem(slot, amount, false);
                throw new IllegalStateException("No returned instance");
            }
        };
        source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        result = engine.move(source, 0, new ItemStackHandler(1), 4);
        expect(result.stopped() && engine.uncertain() && engine.heldCount() == 0 && count(source) == 6,
                "Throwing extraction fabricates no stack from source delta");

        var ledger = new VolumeLedger(() -> { }, 4);
        ledger.load(ItemKey.of(Items.STONE), 40);
        var digital = ForgeDigitalItemStorage.of(ledger);
        engine = new ForgeHopperTransfer();
        expect(engine.move(digital.guarded(() -> true), 0, digital.guarded(() -> true), 8).moved() == 0
                && ledger.totalItemCount() == 40, "Distinct leases of the same ledger do not transfer");
        target = new ItemStackHandler(1);
        expect(engine.move(digital, 0, target, 16).moved() == 16 && ledger.totalItemCount() == 24 && count(target) == 16,
                "Digital source to physical target");
        expect(engine.move(target, 0, digital, 8).moved() == 8 && ledger.totalItemCount() == 32 && count(target) == 8,
                "Physical source to digital target");

        var reentrantEngine = new ForgeHopperTransfer();
        var reentrantTarget = new ItemStackHandler(1);
        var reentrantSource = new ItemStackHandler(1) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                expect(reentrantEngine.move(this, 0, reentrantTarget, amount).stopped(), "Reentrant callback rejected");
                return super.extractItem(slot, amount, simulate);
            }
        };
        reentrantSource.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        expect(reentrantEngine.move(reentrantSource, 0, reentrantTarget, 4).moved() == 4
                && count(reentrantSource) == 6 && count(reentrantTarget) == 4, "Outer transfer survives rejected recursion");
        DigitalStorage.LOGGER.info("Forge hopper transfer engine self-test passed: physical/digital batches, compensation, raw remainder, uncertain callbacks, malformed extraction, same-ledger leases and reentrancy; actual update covered by separate device fixture");
    }

    private static ItemStackHandler source(int count) {
        var handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, new ItemStack(Items.STONE, count)); return handler;
    }
    private static ItemStackHandler rejectActual() {
        return new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return simulate ? ItemStack.EMPTY : stack;
            }
        };
    }
    private static int count(ItemStackHandler handler) { return handler.getStackInSlot(0).getCount(); }
    private static void expect(boolean value, String detail) {
        if (!value) throw new IllegalStateException("Forge hopper transfer: " + detail);
    }
}
