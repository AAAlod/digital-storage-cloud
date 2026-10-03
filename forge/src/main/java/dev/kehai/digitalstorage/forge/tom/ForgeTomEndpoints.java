package dev.kehai.digitalstorage.forge.tom;

import com.tom.storagemod.util.IProxy;
import dev.kehai.digitalstorage.forge.ForgeDigitalItemStorage;
import java.util.Collections;
import java.util.IdentityHashMap;
import net.minecraftforge.items.IItemHandler;

/** Resolve transparent Tom proxies only; filters and unknown wrappers remain opaque. */
public final class ForgeTomEndpoints {
    private ForgeTomEndpoints() { }

    public static ForgeDigitalItemStorage digital(IItemHandler handler) {
        return digital(handler, false);
    }

    /** For classification/risk checks only, never a replacement operation handler. */
    public static ForgeDigitalItemStorage underlyingDigital(IItemHandler handler) {
        return digital(handler, true);
    }

    private static ForgeDigitalItemStorage digital(IItemHandler handler, boolean inspectFilters) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<IItemHandler, Boolean>());
        while (handler != null && visited.add(handler)) {
            var digital = ForgeDigitalItemStorage.resolveDigital(handler);
            if (digital != null) return digital;
            if (inspectFilters && handler instanceof FilteredEndpoint filtered) {
                handler = filtered.digitalstorage$parent();
            } else if (handler instanceof IProxy proxy) {
                handler = proxy.get();
            } else return null;
        }
        return null;
    }

    public static boolean sameVolume(ForgeDigitalItemStorage first, ForgeDigitalItemStorage second) {
        var id = first.ledger().volumeId();
        return id.isPresent() ? second.ledger().volumeId().filter(id.get()::equals).isPresent()
                : first.ledger() == second.ledger();
    }

    public interface RawDigitalEndpoints {
        java.util.List<IItemHandler> digitalstorage$rawDigitalEndpoints();
    }

    public interface FilteredEndpoint {
        IItemHandler digitalstorage$parent();
        boolean digitalstorage$keepLast();
    }
}
