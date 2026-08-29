package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.util.List;

/** Exposes the pre-deduplication digital endpoints seen by one Tom merged storage. */
public interface TomDigitalEndpointTracker {
    List<DigitalItemStorage> digitalstorage$rawDigitalEndpoints();
}
