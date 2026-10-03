package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;

/** Shared and Forge capability regressions. Tom topology/transfer integration is verified separately. */
public final class ForgeSharedSelfTest {
    private ForgeSharedSelfTest() { }

    public static void run(net.minecraft.server.MinecraftServer server) {
        dev.kehai.digitalstorage.storage.ItemKeySelfTest.run();
        ForgeItemKeySelfTest.run();
        ForgeRecoverySelfTest.run();
        ForgeTransferSelfTest.run();
        ForgeTransferSessionSelfTest.run(server);
        ForgeRecoveryDeliverySelfTest.run(server);
        ForgeRecoveryReconciliationSelfTest.run();
        ForgeMigrationSelfTest.run(server);
        ForgeMigrationWorldSelfTest.run(server);
        ForgeLedgerSelfTest.run();
        ForgeAccessorSelfTest.run(server);
        dev.kehai.digitalstorage.storage.VolumeLedgerSelfTest.run();
        dev.kehai.digitalstorage.optimization.NetworkAnalysis.runSelfTest();
        dev.kehai.digitalstorage.optimization.MigrationTaskSelfTest.run();
        dev.kehai.digitalstorage.hopper.HopperPolicy.runSelfTest();
        dev.kehai.digitalstorage.config.DigitalStorageConfig.runSelfTest();
        dev.kehai.digitalstorage.security.ItemSecurityPolicy.runSelfTest();
        ForgeStackPolicySelfTest.run();
        dev.kehai.digitalstorage.forge.tom.ForgeTomSelfTest.run();
        dev.kehai.digitalstorage.forge.tom.ForgeTomAnalysisSelfTest.run();
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
        DigitalStorage.LOGGER.info("Forge shared self-test passed: keys/ForgeCaps, stable IItemHandler slots/simulation/remainders/leases, ledger/nested transactions, analysis, migration jobs/budget/cancel/lifetime, policy, configuration, mounts, upgrades, screen codec, registration and actual Tom aggregate dedup/filter/keep-last; placed world fixture runs only when configured; scanner/proxy direction not covered");
    }
}
