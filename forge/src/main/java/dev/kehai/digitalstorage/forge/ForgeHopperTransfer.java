package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.ItemKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.items.IItemHandler;

/** Sequential Forge transfer with explicit returned-stack ownership.
 * One instance belongs to one hopper. It must be persisted by the owning device
 * before production use; uncertain callbacks require reconciliation, never replay.
 */
public final class ForgeHopperTransfer {
    private ItemStack held = ItemStack.EMPTY;
    private boolean uncertain;
    private boolean blocked;
    private boolean running;
    private String detail = "";
    private CompoundTag unreadableState;
    private boolean exported;

    public boolean blocked() { return blocked; }
    public boolean uncertain() { return uncertain; }
    public int heldCount() { return held.getCount(); }
    /** Original returned instance: the owning device must retain it even if encoding fails. */
    ItemStack heldStack() { return held; }
    public String detail() { return detail; }
    public boolean exported() { return exported; }
    void releaseExported() {
        held = ItemStack.EMPTY;
        uncertain = false;
        blocked = true;
        exported = true;
        detail = "hopper custody exported; rotate device identity before resuming";
    }
    public void halt(String reason) {
        blocked = true;
        if (detail.isBlank()) detail = java.util.Objects.requireNonNull(reason);
    }
    public void markUncertain(String reason) { uncertain = true; halt(reason); }

    /** Caller must persist the returned compound; a successful encoding is not a disk flush. */
    public CompoundTag saveState() {
        if (running) throw new IllegalStateException("Cannot snapshot an active hopper callback");
        if (unreadableState != null) return unreadableState.copy();
        var saved = new CompoundTag();
        saved.putInt("Version", 1);
        saved.putBoolean("Blocked", blocked);
        saved.putBoolean("Uncertain", uncertain);
        saved.putString("Detail", detail);
        int amount = held.getCount();
        saved.putInt("Amount", amount);
        if (amount > 0) {
            // ItemStack's vanilla Count is a byte. Keep the authoritative amount
            // separately so malformed excessive returns cannot wrap on restart.
            var item = held.save(new CompoundTag());
            item.putByte("Count", (byte) 1);
            saved.put("Item", item);
        }
        return saved;
    }

    /** Invalid/future state is retained verbatim and blocks transfer; never silently reset it. */
    public static ForgeHopperTransfer restore(CompoundTag saved) {
        var engine = new ForgeHopperTransfer();
        try {
            require(saved.contains("Version", Tag.TAG_INT) && saved.getInt("Version") == 1, "Unsupported version");
            require(saved.contains("Blocked", Tag.TAG_BYTE) && saved.contains("Uncertain", Tag.TAG_BYTE)
                    && (saved.getByte("Blocked") == 0 || saved.getByte("Blocked") == 1)
                    && (saved.getByte("Uncertain") == 0 || saved.getByte("Uncertain") == 1), "Invalid flags");
            require(saved.contains("Amount", Tag.TAG_INT) && saved.getInt("Amount") >= 0
                    && saved.contains("Detail", Tag.TAG_STRING) && saved.getString("Detail").length() <= 512, "Invalid fields");
            int amount = saved.getInt("Amount");
            boolean blocked = saved.getBoolean("Blocked");
            boolean uncertain = saved.getBoolean("Uncertain");
            String detail = saved.getString("Detail");
            require(blocked ? !detail.isBlank() : amount == 0 && !uncertain && detail.isEmpty(), "Inconsistent state");
            ItemStack stack = ItemStack.EMPTY;
            if (amount > 0) {
                require(saved.contains("Item", Tag.TAG_COMPOUND), "Missing returned item");
                var item = saved.getCompound("Item");
                require(item.contains("Count", Tag.TAG_BYTE) && item.getByte("Count") == 1, "Invalid encoded count");
                stack = ItemStack.of(item.copy());
                require(!stack.isEmpty(), "Unreadable returned item");
                stack.setCount(amount);
            } else require(!saved.contains("Item"), "Unexpected returned item");
            engine.held = stack;
            engine.blocked = blocked;
            engine.uncertain = uncertain;
            engine.detail = detail;
        } catch (RuntimeException failure) {
            engine.blocked = true;
            engine.uncertain = true;
            engine.detail = "hopper state requires attention: " + failure.getClass().getSimpleName();
            engine.unreadableState = saved.copy();
        }
        return engine;
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalArgumentException(reason);
    }

    public Result move(IItemHandler source, int slot, IItemHandler destination, int maximum) {
        if (running) return new Result(0, true, "reentrant hopper transfer");
        if (blocked || !held.isEmpty()) return new Result(0, true, detail);
        if (source == destination || maximum <= 0) return new Result(0, false, "");
        int moved = 0;
        boolean extracting = false;
        running = true;
        try {
            var sourceDigital = dev.kehai.digitalstorage.forge.tom.ForgeTomEndpoints.underlyingDigital(source);
            var destinationDigital = dev.kehai.digitalstorage.forge.tom.ForgeTomEndpoints.underlyingDigital(destination);
            if (sourceDigital != null && destinationDigital != null
                    && dev.kehai.digitalstorage.forge.tom.ForgeTomEndpoints.sameVolume(sourceDigital, destinationDigital)) {
                return new Result(0, false, "");
            }
            ItemStack current = source.getStackInSlot(slot);
            if (current.isEmpty()) return new Result(0, false, "");
            ItemKey expected = ItemKey.of(current);
            int requested = Math.min(maximum, Math.min(current.getCount(), current.getMaxStackSize()));
            if (requested <= 0) return new Result(0, false, "");
            ItemStack simulated = source.extractItem(slot, requested, true);
            if (simulated.isEmpty()) return new Result(0, false, "");
            check(simulated, expected, requested, "source simulation");
            int offered = simulated.getCount();
            int accepted = offered;
            ItemStack probe = simulated;
            int slots = destination.getSlots();
            for (int targetSlot = 0; targetSlot < slots && !probe.isEmpty(); targetSlot++) {
                int count = probe.getCount();
                ItemStack remainder = destination.insertItem(targetSlot, probe.copy(), true);
                check(remainder, expected, count, "destination simulation");
                probe = remainder;
            }
            accepted -= probe.getCount();
            if (accepted <= 0) return new Result(0, false, "");
            extracting = true;
            held = java.util.Objects.requireNonNull(source.extractItem(slot, accepted, false), "extraction returned null");
            extracting = false;
            if (held.isEmpty()) return new Result(0, false, "");
            check(held, expected, accepted, "actual extraction");
            for (int targetSlot = 0; targetSlot < slots && !held.isEmpty(); targetSlot++) {
                ItemStack offeredStack = held.copy();
                int count = held.getCount();
                // A throwing insertion may already have accepted items. No source
                // delta or simulation can prove ownership after that callback.
                uncertain = true;
                ItemStack remainder = destination.insertItem(targetSlot, offeredStack, false);
                check(remainder, expected, count, "actual insertion");
                uncertain = false;
                int received = count - remainder.getCount();
                moved += received;
                held.setCount(remainder.getCount());
            }
            if (!held.isEmpty()) {
                ItemStack returning = held.copy();
                int count = held.getCount();
                uncertain = true;
                ItemStack remainder = source.insertItem(slot, returning, false);
                check(remainder, expected, count, "source compensation");
                uncertain = false;
                held.setCount(remainder.getCount());
                if (!held.isEmpty()) return stop(moved, "source rejected returned remainder");
            }
            held = ItemStack.EMPTY;
            return new Result(moved, false, "");
        } catch (RuntimeException failure) {
            if (extracting) uncertain = true;
            return stop(moved, "hopper callback failed: " + failure.getClass().getSimpleName());
        } finally {
            running = false;
        }
    }

    private static void check(ItemStack stack, ItemKey expected, int maximum, String stage) {
        java.util.Objects.requireNonNull(stack, stage + " returned null");
        if (!stack.isEmpty() && (stack.getCount() < 0 || stack.getCount() > maximum
                || !ItemKey.of(stack).equals(expected))) {
            throw new IllegalStateException(stage + " changed quantity or identity");
        }
    }

    private Result stop(int moved, String reason) {
        blocked = true;
        detail = reason;
        return new Result(moved, true, reason);
    }

    public record Result(int moved, boolean stopped, String detail) { }
}
