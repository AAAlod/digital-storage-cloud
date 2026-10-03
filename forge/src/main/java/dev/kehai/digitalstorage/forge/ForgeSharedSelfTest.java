package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;

/** Shared regressions only. Does not claim Forge capability or Tom integration coverage. */
public final class ForgeSharedSelfTest {
    private ForgeSharedSelfTest() { }

    public static void run() {
        dev.kehai.digitalstorage.storage.ItemKeySelfTest.run();
        ForgeItemKeySelfTest.run();
        dev.kehai.digitalstorage.storage.VolumeLedgerSelfTest.run();
        dev.kehai.digitalstorage.optimization.NetworkAnalysis.runSelfTest();
        dev.kehai.digitalstorage.optimization.MigrationTaskSelfTest.run();
        dev.kehai.digitalstorage.hopper.HopperPolicy.runSelfTest();
        dev.kehai.digitalstorage.config.DigitalStorageConfig.runSelfTest();
        dev.kehai.digitalstorage.security.ItemSecurityPolicy.runSelfTest();
        dev.kehai.digitalstorage.security.DigitalStorageMountTracker.runSelfTest();
        dev.kehai.digitalstorage.upgrade.DigitalStorageUpgradeService.runSelfTest();
        dev.kehai.digitalstorage.screen.DigitalStorageScreenState.runCodecSelfTest();
        dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.runCompatibilitySelfTest();
        var block = ForgeDigitalStorage.ACCESSOR.get();
        var entity = ((dev.kehai.digitalstorage.block.DigitalStorageAccessorBlock) block)
                .newBlockEntity(net.minecraft.core.BlockPos.ZERO, block.defaultBlockState());
        if (!(entity instanceof ForgeAccessorBlockEntity) || entity.getType() != ForgeDigitalStorage.ACCESSOR_TYPE.get()) {
            throw new IllegalStateException("Forge accessor registration created an incorrect entity");
        }
        DigitalStorage.LOGGER.info("Forge shared self-test passed: keys, ledger/nested transactions, analysis, migration scheduling, policy, configuration, mounts, upgrades, screen codec and registration; capability/Tom integration not covered");
    }
}
