package dev.kehai.digitalstorage.forge.tom;

import com.tom.storagemod.util.FilteredInventoryHandler;
import com.tom.storagemod.util.IProxy;
import com.tom.storagemod.util.MultiItemHandler;
import dev.kehai.digitalstorage.forge.ForgeDigitalItemStorage;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/** Runs against the actual Tom 1.7.1 aggregate and filtering classes, after Mixin application. */
public final class ForgeTomSelfTest {
    private ForgeTomSelfTest() { }

    public static void run() {
        UUID id = UUID.randomUUID();
        var digital = ForgeDigitalItemStorage.of(new VolumeLedger(id, () -> { }, 4));
        AtomicBoolean live = new AtomicBoolean(true);
        var first = LazyOptional.<IItemHandler>of(() -> digital.guarded(live::get));
        var alias = LazyOptional.<IItemHandler>of(() -> digital.guarded(() -> true));
        var network = new MultiItemHandler();
        expect((Object) network instanceof ForgeTomEndpoints.RawDigitalEndpoints,
                "Tom MultiItemHandler Mixin was not applied");
        network.add(first);
        network.add(alias);
        network.add(alias);
        network.refresh();
        expect(network.getHandlers().size() == 1 && network.getSlots() == digital.getSlots()
                        && raw(network).size() == 2, "Empty aliases exposed duplicate slots or lost raw endpoints");
        expect(network.insertItem(17, new ItemStack(Items.STONE, 32), false).isEmpty(), "Tom could not insert into DSC");
        network.clear();
        network.add(first);
        network.add(alias);
        network.refresh();
        expect(network.getHandlers().size() == 1 && network.getStackInSlot(17).getCount() == 32,
                "Populated aliases were counted twice");
        // Two adapters with one volume UUID must not defeat volume identity even
        // if a faulty caller supplies a second ledger for the same volume.
        var duplicateLedger = ForgeDigitalItemStorage.of(new VolumeLedger(id, () -> { }, 4));
        network.add(LazyOptional.of(() -> duplicateLedger));
        expect(network.getHandlers().size() == 1 && raw(network).size() == 3, "Volume UUID deduplication failed");

        Proxy proxy = new Proxy(alias.orElseThrow(() -> new IllegalStateException("Alias missing")));
        network.add(LazyOptional.of(() -> proxy));
        expect(network.getHandlers().size() == 1 && raw(network).size() == 4,
                "Transparent Tom IProxy bypassed deduplication");
        Proxy cycle = new Proxy(null);
        cycle.delegate = cycle;
        expect(ForgeTomEndpoints.digital(cycle) == null, "Proxy cycle was not bounded");

        // Keep the filter wrapper intact: bypassing it would lose rejection and
        // keep-last semantics. It is intentionally outside bare-volume dedup.
        var filtered = new FilteredInventoryHandler(digital, stack -> stack.getItem() == Items.STONE, true);
        expect(ForgeTomEndpoints.digital(filtered) == null, "Filter was folded into unrestricted DSC");
        expect(filtered.insertItem(0, new ItemStack(Items.DIRT, 1), true).getCount() == 1,
                "Tom filter no longer rejected a mismatched item");
        expect(filtered.extractItem(17, 64, true).getCount() == 31
                        && digital.getStackInSlot(17).getCount() == 32, "Tom keep-last or simulation was bypassed");
        var physical = new ItemStackHandler(2);
        physical.setStackInSlot(0, new ItemStack(Items.DIRT, 5));
        var physicalCapability = LazyOptional.<IItemHandler>of(() -> physical);
        network.add(physicalCapability);
        network.refresh();
        expect(network.getHandlers().size() == 2 && network.getSlots() == digital.getSlots() + 2,
                "DSC dedup changed physical inventory aggregation");
        live.set(false);
        first.invalidate();
        network.add(alias);
        network.refresh();
        expect(network.getSlots() == digital.getSlots() + 2 && network.getStackInSlot(19).getCount() == 32,
                "Invalid first lease prevented a surviving alias from joining");
        network.clear();
        expect(raw(network).isEmpty(), "Tom clear retained raw digital endpoints");
        network.add(alias);
        network.refresh();
        expect(network.getHandlers().size() == 1 && raw(network).size() == 1,
                "Tom rebuild retained stale deduplication state");
    }

    private static java.util.List<IItemHandler> raw(MultiItemHandler network) {
        return ((ForgeTomEndpoints.RawDigitalEndpoints) (Object) network).digitalstorage$rawDigitalEndpoints();
    }

    private static final class Proxy implements IItemHandler, IProxy {
        private IItemHandler delegate;
        private Proxy(IItemHandler delegate) { this.delegate = delegate; }
        public IItemHandler get() { return delegate; }
        public int getSlots() { return delegate.getSlots(); }
        public ItemStack getStackInSlot(int slot) { return delegate.getStackInSlot(slot); }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return delegate.insertItem(slot, stack, simulate); }
        public ItemStack extractItem(int slot, int amount, boolean simulate) { return delegate.extractItem(slot, amount, simulate); }
        public int getSlotLimit(int slot) { return delegate.getSlotLimit(slot); }
        public boolean isItemValid(int slot, ItemStack stack) { return delegate.isItemValid(slot, stack); }
    }

    private static void expect(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
