package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

/** Stable virtual slots over one canonical ledger. This is not an external-inventory transaction. */
public final class ForgeDigitalItemStorage implements IItemHandler {
    private static final int SLOTS = DigitalStorageRecord.ABSOLUTE_MAX_VARIANTS;
    private static final Map<VolumeLedger, WeakReference<ForgeDigitalItemStorage>> CANONICAL = new WeakHashMap<>();
    private final VolumeLedger ledger;
    private final ItemKey[] slots = new ItemKey[SLOTS];
    private final Map<ItemKey, Integer> positions = new HashMap<>();
    private long structureVersion = Long.MIN_VALUE;

    private ForgeDigitalItemStorage(VolumeLedger ledger) { this.ledger = ledger; }

    public static synchronized ForgeDigitalItemStorage of(VolumeLedger ledger) {
        var reference = CANONICAL.get(ledger);
        var existing = reference == null ? null : reference.get();
        if (existing != null) return existing;
        var created = new ForgeDigitalItemStorage(ledger);
        CANONICAL.put(ledger, new WeakReference<>(created));
        return created;
    }

    public VolumeLedger ledger() { return ledger; }
    public IItemHandler guarded(BooleanSupplier active) { return new BoundHandler(this, active); }

    public static ForgeDigitalItemStorage resolveDigital(IItemHandler handler) {
        if (handler instanceof ForgeDigitalItemStorage digital) return digital;
        return handler instanceof BoundHandler bound && bound.active.getAsBoolean() ? bound.delegate : null;
    }

    private void refreshSlots() {
        long current = ledger.structureVersion();
        if (structureVersion == current) return;
        var live = new LinkedHashSet<ItemKey>();
        for (var view : ledger) live.add(view.getResource());
        if (live.size() > SLOTS) throw new IllegalStateException("Ledger exceeds Forge virtual slot capacity");
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slots[slot] != null && !live.contains(slots[slot])) {
                positions.remove(slots[slot]);
                slots[slot] = null;
            }
        }
        int nextFree = 0;
        for (ItemKey key : live) {
            if (!positions.containsKey(key)) {
                while (slots[nextFree] != null) nextFree++;
                slots[nextFree] = key;
                positions.put(key, nextFree);
            }
        }
        structureVersion = current;
    }

    private ItemKey keyAt(int slot) {
        validateSlot(slot);
        refreshSlots();
        return slots[slot];
    }

    private static void validateSlot(int slot) {
        if (slot < 0 || slot >= SLOTS) throw new IndexOutOfBoundsException("Invalid digital slot " + slot);
    }

    @Override public int getSlots() { return SLOTS; }

    @Override
    public ItemStack getStackInSlot(int slot) {
        ItemKey key = keyAt(slot);
        return key == null ? ItemStack.EMPTY : key.toStack((int) Math.min(Integer.MAX_VALUE, ledger.amountOf(key)));
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        ItemKey current = keyAt(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemKey key = ItemKey.of(stack);
        // Inserting into an empty slot must not secretly change another slot.
        if (current != null && !current.equals(key) || current == null && positions.containsKey(key)) return stack;
        // Capability-aware copying may invoke mod code. Prepare the return stack
        // before changing quantities, so a copy failure cannot strand inserted items.
        ItemStack remainderStack = stack.copy();
        long inserted;
        try (LedgerTransaction transaction = LedgerTransaction.open()) {
            inserted = ledger.insert(key, stack.getCount(), transaction);
            if (!simulate) transaction.commit();
        }
        if (inserted == 0) return stack;
        if (!simulate && inserted > 0 && current == null) {
            slots[slot] = key;
            positions.put(key, slot);
        }
        int remainder = stack.getCount() - (int) inserted;
        if (remainder == 0) return ItemStack.EMPTY;
        remainderStack.setCount(remainder);
        return remainderStack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemKey key = keyAt(slot);
        if (key == null || amount <= 0) return ItemStack.EMPTY;
        ItemStack sample = key.toStack(1);
        long extracted;
        try (LedgerTransaction transaction = LedgerTransaction.open()) {
            extracted = ledger.extract(key, Math.min(amount, sample.getMaxStackSize()), transaction);
            if (!simulate) transaction.commit();
        }
        // Forge handlers return at most the item's maximum stack size.
        if (extracted == 0) return ItemStack.EMPTY;
        sample.setCount((int) extracted);
        return sample;
    }

    @Override public int getSlotLimit(int slot) { validateSlot(slot); return Integer.MAX_VALUE; }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        validateSlot(slot);
        // Any item type can occupy a virtual slot. Fullness and runtime policy
        // are state-dependent and must be checked by simulated insertion.
        return !stack.isEmpty();
    }

    private static final class BoundHandler implements IItemHandler {
        private final ForgeDigitalItemStorage delegate;
        private final BooleanSupplier active;
        private BoundHandler(ForgeDigitalItemStorage delegate, BooleanSupplier active) { this.delegate = delegate; this.active = active; }
        public int getSlots() { return active.getAsBoolean() ? delegate.getSlots() : 0; }
        public ItemStack getStackInSlot(int slot) { return active.getAsBoolean() ? delegate.getStackInSlot(slot) : ItemStack.EMPTY; }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return active.getAsBoolean() ? delegate.insertItem(slot, stack, simulate) : stack;
        }
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return active.getAsBoolean() ? delegate.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }
        public int getSlotLimit(int slot) { return active.getAsBoolean() ? delegate.getSlotLimit(slot) : 0; }
        public boolean isItemValid(int slot, ItemStack stack) { return active.getAsBoolean() && delegate.isItemValid(slot, stack); }
    }
}
