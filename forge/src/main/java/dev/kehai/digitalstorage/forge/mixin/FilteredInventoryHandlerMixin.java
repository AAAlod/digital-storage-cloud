package dev.kehai.digitalstorage.forge.mixin;

import com.tom.storagemod.util.FilteredInventoryHandler;
import dev.kehai.digitalstorage.forge.tom.ForgeTomEndpoints;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes identity only. All storage operations still run through Tom's filter. */
@Mixin(value = FilteredInventoryHandler.class, remap = false)
public abstract class FilteredInventoryHandlerMixin implements ForgeTomEndpoints.FilteredEndpoint {
    @Shadow private IItemHandler parent;
    @Shadow private boolean keepLastInSlot;

    @Override public IItemHandler digitalstorage$parent() { return parent; }
    @Override public boolean digitalstorage$keepLast() { return keepLastInSlot; }
}
