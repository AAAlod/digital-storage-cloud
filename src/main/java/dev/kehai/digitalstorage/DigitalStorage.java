package dev.kehai.digitalstorage;

import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared identity only; loading this class never registers game content. */
public final class DigitalStorage {
    public static final String MOD_ID = "digitalstorage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private DigitalStorage() {
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }
}
