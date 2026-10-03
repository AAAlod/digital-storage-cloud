package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.optimization.InventoryEndpoint;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraftforge.items.IItemHandler;

/** Retains the original sided/filter handler. Never substitutes its underlying inventory for operations. */
public final class ForgeInventoryEndpoint implements InventoryEndpoint {
    private final IItemHandler handler;
    public ForgeInventoryEndpoint(IItemHandler handler) { this.handler = handler; }
    public IItemHandler handler() { return handler; }
    @Override public boolean supportsExtraction() { return true; }
    @Override public Iterator<InventoryEndpoint.View> iterator() {
        int slots = handler.getSlots();
        return new Iterator<>() {
            private int slot;
            public boolean hasNext() { return slot < slots; }
            public InventoryEndpoint.View next() {
                if (!hasNext()) throw new NoSuchElementException();
                return new View(handler, slot++);
            }
        };
    }
    public static final class View implements InventoryEndpoint.View {
        private final IItemHandler handler;
        private final int slot;
        public View(IItemHandler handler, int slot) { this.handler = handler; this.slot = slot; }
        public IItemHandler handler() { return handler; }
        public int slot() { return slot; }
        public boolean isBlank() { return handler.getStackInSlot(slot).isEmpty(); }
        public ItemKey resource() { return ItemKey.of(handler.getStackInSlot(slot)); }
        public long amount() { return handler.getStackInSlot(slot).getCount(); }
    }
}
