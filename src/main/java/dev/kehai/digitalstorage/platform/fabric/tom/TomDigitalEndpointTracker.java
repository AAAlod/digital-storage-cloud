package dev.kehai.digitalstorage.platform.fabric.tom;

import dev.kehai.digitalstorage.platform.fabric.FabricDigitalItemStorage;
import java.util.List;

/** Exposes the pre-deduplication digital endpoints seen by one Tom merged storage. */
public interface TomDigitalEndpointTracker {
    List<FabricDigitalItemStorage> digitalstorage$rawDigitalEndpoints();
}
