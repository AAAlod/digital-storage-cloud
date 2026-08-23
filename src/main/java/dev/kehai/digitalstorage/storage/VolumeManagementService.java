package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import java.util.UUID;

/** Applies the same Volume deletion rules to every player-facing entry point. */
public final class VolumeManagementService {
    private VolumeManagementService() {
    }

    public static DeleteResult delete(
            DigitalStorageState state,
            UUID ownerId,
            UUID volumeId
    ) {
        if (!state.ownsVolume(ownerId, volumeId)) {
            return DeleteResult.NOT_OWNED_OR_MISSING;
        }
        if (!DigitalStorageMountTracker.mounts(volumeId).isEmpty()) {
            return DeleteResult.MOUNTED;
        }
        return state.deleteEmptyVolume(ownerId, volumeId)
                ? DeleteResult.SUCCESS
                : DeleteResult.NOT_EMPTY;
    }

    public enum DeleteResult {
        SUCCESS,
        NOT_OWNED_OR_MISSING,
        MOUNTED,
        NOT_EMPTY
    }
}
