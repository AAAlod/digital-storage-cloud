package dev.kehai.digitalstorage.forge;

/** A Tom hopper owns its stopped transfer state independently of its current endpoints. */
public interface ForgeHopperState {
    String NBT_KEY = "DSCHopperTransfer";
    String ID_KEY = "DSCHopperId";
    String CUSTODY_ID_KEY = "DSCHopperCustodyId";
    ForgeHopperTransfer digitalstorage$transferState();
    void digitalstorage$reconcile(ForgeHopperCustody custody, String dimension, net.minecraft.core.BlockPos position);
    void digitalstorage$retain(ForgeHopperCustody custody, String dimension, net.minecraft.core.BlockPos position);
}
