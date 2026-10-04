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
        expect(ForgeDigitalItemStorage.of(ledger) == handler && handler.getSlots() == 2, "Canonical handler or tier capacity changed");
        ItemStack stone = new ItemStack(Items.STONE, 32);
        expect(handler.insertItem(1, stone, true).isEmpty() && ledger.variantCount() == 0
                        && ledger.contentVersion() == 0 && dirty.get() == 0 && stone.getCount() == 32,
                "Simulated insertion mutated the ledger or input");
        expect(handler.insertItem(1, stone, false).isEmpty() && handler.getStackInSlot(1).getCount() == 32
                        && handler.getStackInSlot(0).isEmpty() && dirty.get() == 1 && ledger.contentVersion() == 1,
                "Committed insertion did not occupy the requested slot");
        handler.getStackInSlot(1).setCount(1);
        expect(handler.getStackInSlot(1).getCount() == 32, "Returned stack mutated ledger quantity");
        expect(handler.insertItem(0, stone, false).getCount() == 32 && ledger.totalItemCount() == 32,
                "Empty-slot insertion secretly changed an existing variant's slot");
        expect(handler.insertItem(0, new ItemStack(Items.DIRT, 4), false).isEmpty()
                        && handler.getStackInSlot(1).getCount() == 32 && handler.getStackInSlot(0).getCount() == 4,
                "New variant reordered existing slots");
        expect(handler.insertItem(4, new ItemStack(Items.PAPER, 1), false).getCount() == 1,
                "Full variant capacity accepted a new key");
        expect(handler.isItemValid(1, new ItemStack(Items.PAPER, 1)),
                "Item validity incorrectly depended on slot contents or fullness");
        long version = ledger.contentVersion();
        int callbacks = dirty.get();
        expect(handler.extractItem(1, 8, true).getCount() == 8 && handler.getStackInSlot(1).getCount() == 32
                        && version == ledger.contentVersion() && dirty.get() == callbacks,
                "Simulated extraction changed committed data");
        expect(handler.extractItem(1, 1000, false).getCount() == 32 && handler.getStackInSlot(1).isEmpty()
                        && handler.getStackInSlot(0).getCount() == 4, "Extraction failed or shifted another slot");
        AtomicBoolean active = new AtomicBoolean(true);
        var guarded = handler.guarded(active::get);
        expect(ForgeDigitalItemStorage.resolveDigital(guarded) == handler, "Guarded identity did not resolve canonical storage");
        active.set(false);
        expect(guarded.getSlots() == 0 && guarded.getStackInSlot(3).isEmpty() && guarded.extractItem(3, 4, false).isEmpty()
                        && guarded.insertItem(3, stone, false) == stone && ledger.totalItemCount() == 4
                        && ForgeDigitalItemStorage.resolveDigital(guarded) == null,
                "Revoked handler retained access to its ledger");
        partialInsertionAndPolicy();
        liveCapacity();
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
        expect(handler.insertItem(0, stack, true).isEmpty() && ledger.totalItemCount() == 0,
                "Capability insertion simulation mutated storage");
        expect(handler.insertItem(0, stack, false).isEmpty(), "Capability item insertion failed");
        var displayed = handler.getStackInSlot(0);
        expect(ItemKey.of(displayed).equals(expected), "Capability display stack lost identity");
        var extracted = handler.extractItem(0, stack.getCount(), false);
        expect(ItemKey.of(extracted).equals(expected) && extracted.getCount() == stack.getCount()
                        && ledger.totalItemCount() == 0, "Capability extraction lost identity or quantity");
    }

    private static void liveCapacity() {
        AtomicInteger capacity = new AtomicInteger(64);
        var ledger = new VolumeLedger(() -> { }, capacity::get);
        var handler = ForgeDigitalItemStorage.of(ledger);
        ItemStack stone = new ItemStack(Items.STONE, 32);
        expect(handler.getSlots() == 64 && handler.getSlotLimit(64) == 0
                && !handler.isItemValid(64, stone) && handler.insertItem(64, stone, true) == stone
                && handler.insertItem(64, stone, false) == stone && ledger.variantCount() == 0,
                "Unupgraded volume exposed or accepted a locked slot");
        capacity.set(128);
        expect(handler.getSlots() == 128 && handler.insertItem(127, stone, true).isEmpty()
                && ledger.variantCount() == 0 && handler.insertItem(127, stone, false).isEmpty(),
                "Upgrade did not expose usable new slots or simulation mutated data");
        capacity.set(64);
        expect(handler.getSlots() == 128 && handler.getStackInSlot(127).getCount() == 32
                && handler.extractItem(127, 32, false).getCount() == 32 && handler.getSlots() == 64,
                "Tier reduction hid existing high-slot data or retained empty phantom slots");
        capacity.set(1);
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("fixture", 1);
        ledger.load(ItemKey.of(Items.STONE, tag), 5);
        tag.putInt("fixture", 2);
        ledger.load(ItemKey.of(Items.STONE, tag), 7);
        expect(handler.getSlots() == 2 && ledger.variantCount() == 2,
                "Stored over-capacity identities were hidden after load");
        handler.extractItem(1, 64, false);
        handler.extractItem(0, 64, false);
        expect(ledger.totalItemCount() == 0 && handler.getSlots() == 1, "Loaded overflow could not be extracted");
    }

    private static void expect(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
