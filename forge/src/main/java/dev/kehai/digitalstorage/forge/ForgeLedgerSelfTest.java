package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ForgeLedgerSelfTest {
    private ForgeLedgerSelfTest() { }

    public static void run() {
        AtomicInteger dirty = new AtomicInteger();
        VolumeLedger ledger = new VolumeLedger(dirty::incrementAndGet, 2);
        var handler = ForgeDigitalItemStorage.of(ledger);
        expect(ForgeDigitalItemStorage.of(ledger) == handler && handler.getSlots() == 1024, "Canonical handler or stable slot count changed");
        ItemStack stone = new ItemStack(Items.STONE, 32);
        expect(handler.insertItem(17, stone, true).isEmpty() && ledger.variantCount() == 0
                        && ledger.contentVersion() == 0 && dirty.get() == 0 && stone.getCount() == 32,
                "Simulated insertion mutated the ledger or input");
        expect(handler.insertItem(17, stone, false).isEmpty() && handler.getStackInSlot(17).getCount() == 32
                        && handler.getStackInSlot(0).isEmpty() && dirty.get() == 1 && ledger.contentVersion() == 1,
                "Committed insertion did not occupy the requested slot");
        handler.getStackInSlot(17).setCount(1);
        expect(handler.getStackInSlot(17).getCount() == 32, "Returned stack mutated ledger quantity");
        expect(handler.insertItem(0, stone, false).getCount() == 32 && ledger.totalItemCount() == 32,
                "Empty-slot insertion secretly changed an existing variant's slot");
        expect(handler.insertItem(3, new ItemStack(Items.DIRT, 4), false).isEmpty()
                        && handler.getStackInSlot(17).getCount() == 32 && handler.getStackInSlot(3).getCount() == 4,
                "New variant reordered existing slots");
        expect(handler.insertItem(4, new ItemStack(Items.PAPER, 1), false).getCount() == 1,
                "Full variant capacity accepted a new key");
        expect(handler.isItemValid(17, new ItemStack(Items.PAPER, 1)),
                "Item validity incorrectly depended on slot contents or fullness");
        long version = ledger.contentVersion();
        int callbacks = dirty.get();
        expect(handler.extractItem(17, 8, true).getCount() == 8 && handler.getStackInSlot(17).getCount() == 32
                        && version == ledger.contentVersion() && dirty.get() == callbacks,
                "Simulated extraction changed committed data");
        expect(handler.extractItem(17, 1000, false).getCount() == 32 && handler.getStackInSlot(17).isEmpty()
                        && handler.getStackInSlot(3).getCount() == 4, "Extraction failed or shifted another slot");
        AtomicBoolean active = new AtomicBoolean(true);
        var guarded = handler.guarded(active::get);
        expect(ForgeDigitalItemStorage.resolveDigital(guarded) == handler, "Guarded identity did not resolve canonical storage");
        active.set(false);
        expect(guarded.getSlots() == 0 && guarded.getStackInSlot(3).isEmpty() && guarded.extractItem(3, 4, false).isEmpty()
                        && guarded.insertItem(3, stone, false) == stone && ledger.totalItemCount() == 4
                        && ForgeDigitalItemStorage.resolveDigital(guarded) == null,
                "Revoked handler retained access to its ledger");
        partialInsertionAndPolicy();
    }

    private static void partialInsertionAndPolicy() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        VolumeLedger ledger = new VolumeLedger(() -> { }, () -> 1, key -> allowed.get(), key -> true);
        ledger.load(ItemKey.of(Items.STONE), Integer.MAX_VALUE - 16L);
        var handler = ForgeDigitalItemStorage.of(ledger);
        ItemStack stack = new ItemStack(Items.STONE, 64);
        expect(handler.insertItem(0, stack, true).getCount() == 48
                        && ledger.totalItemCount() == Integer.MAX_VALUE - 16L, "Simulated partial remainder changed quantity");
        expect(handler.insertItem(0, stack, false).getCount() == 48 && stack.getCount() == 64
                        && ledger.totalItemCount() == Integer.MAX_VALUE, "Partial insertion did not conserve input/remainder");
        allowed.set(false);
        expect(handler.insertItem(0, stack, false) == stack && handler.extractItem(0, 1000, false).getCount() == 64,
                "Insertion policy stranded extraction or returned an oversized stack");
    }

    public static void capabilityRoundTrip(ItemStack stack) {
        VolumeLedger ledger = new VolumeLedger(() -> { }, 1);
        var handler = ForgeDigitalItemStorage.of(ledger);
        ItemKey expected = ItemKey.of(stack);
        expect(handler.insertItem(7, stack, true).isEmpty() && ledger.totalItemCount() == 0,
                "Capability insertion simulation mutated storage");
        expect(handler.insertItem(7, stack, false).isEmpty(), "Capability item insertion failed");
        var displayed = handler.getStackInSlot(7);
        expect(ItemKey.of(displayed).equals(expected), "Capability display stack lost identity");
        var extracted = handler.extractItem(7, stack.getCount(), false);
        expect(ItemKey.of(extracted).equals(expected) && extracted.getCount() == stack.getCount()
                        && ledger.totalItemCount() == 0, "Capability extraction lost identity or quantity");
    }

    private static void expect(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
