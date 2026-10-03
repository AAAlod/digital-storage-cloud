package dev.kehai.digitalstorage.forge;

/** A Tom hopper owns its stopped transfer state independently of its current endpoints. */
public interface ForgeHopperState {
    String NBT_KEY = "DSCHopperTransfer";
    ForgeHopperTransfer digitalstorage$transferState();
}
