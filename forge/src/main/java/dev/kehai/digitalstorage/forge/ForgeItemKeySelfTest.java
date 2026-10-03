package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.ItemKeyCodec;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** A real Forge energy capability attached only during this server-thread regression. */
public final class ForgeItemKeySelfTest {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private ForgeItemKeySelfTest() { }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<ItemStack> event) {
        if (ACTIVE.get() && event.getObject().getItem() == Items.PAPER) {
            Provider provider = new Provider();
            event.addCapability(DigitalStorage.id("selftest_energy"), provider);
            event.addListener(provider.capability::invalidate);
        }
    }

    public static void run() {
        ACTIVE.set(true);
        try {
            ItemStack stack = new ItemStack(Items.PAPER, 64);
            var energy = stack.getCapability(ForgeCapabilities.ENERGY).orElseThrow(() ->
                    new IllegalStateException("Forge test energy capability was not attached"));
            energy.receiveEnergy(42, false);
            ItemKey key = ItemKey.of(stack);
            expect(key.hasAttachments() && key.copyAttachments().contains("ForgeCaps"), "ForgeCaps was omitted from identity");
            ItemKey reread = ItemKeyCodec.read(ItemKeyCodec.write(key));
            ItemStack restored = reread.toStack(64);
            expect(restored.getCount() == 64 && restored.getCapability(ForgeCapabilities.ENERGY)
                    .orElseThrow(() -> new IllegalStateException("Restored capability missing")).getEnergyStored() == 42,
                    "Forge capability NBT did not survive key/codec/stack round-trip");
            energy.extractEnergy(10, false);
            expect(!key.equals(ItemKey.of(stack)) && key.equals(ItemKey.of(key.toStack(1))),
                    "Mutable capability data changed an existing key or stack count entered identity");
            restored.getCapability(ForgeCapabilities.ENERGY).orElseThrow(() ->
                    new IllegalStateException("Restored capability missing")).extractEnergy(1, false);
            expect(key.equals(reread) && !key.equals(ItemKey.of(restored)), "Returned stack capability mutated stored identity");
            VolumeLedger ledger = new VolumeLedger(() -> { }, 2);
            try (LedgerTransaction transaction = LedgerTransaction.open()) {
                ledger.insert(key, 64, transaction);
                ledger.insert(ItemKey.of(stack), 32, transaction);
                transaction.commit();
            }
            expect(ledger.variantCount() == 2 && ledger.amountOf(reread) == 64, "Distinct energy capabilities merged in ledger");
            for (var snapshot : ledger.snapshotEntries()) {
                ItemKey loaded = ItemKeyCodec.read(snapshot.serializedVariant());
                expect(loaded.hasAttachments(), "Ledger snapshot lost platform data");
            }
        } finally { ACTIVE.remove(); }
    }

    private static final class Provider implements ICapabilitySerializable<CompoundTag> {
        private final TestEnergy energy = new TestEnergy();
        private final LazyOptional<net.minecraftforge.energy.IEnergyStorage> capability = LazyOptional.of(() -> energy);
        public <T> LazyOptional<T> getCapability(Capability<T> requested, Direction side) {
            return requested == ForgeCapabilities.ENERGY ? capability.cast() : LazyOptional.empty();
        }
        public CompoundTag serializeNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Energy", energy.getEnergyStored());
            return tag;
        }
        public void deserializeNBT(CompoundTag tag) { energy.restore(tag.getInt("Energy")); }
    }
    private static final class TestEnergy extends EnergyStorage {
        private TestEnergy() { super(100); }
        private void restore(int value) { energy = value; }
    }
    private static void expect(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
