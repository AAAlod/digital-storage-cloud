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
        var visited = Collections.newSetFromMap(new IdentityHashMap<IItemHandler, Boolean>());
        while (handler != null && visited.add(handler)) {
            var digital = ForgeDigitalItemStorage.resolveDigital(handler);
            if (digital != null) return digital;
            if (!(handler instanceof IProxy proxy)) return null;
            handler = proxy.get();
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
}
