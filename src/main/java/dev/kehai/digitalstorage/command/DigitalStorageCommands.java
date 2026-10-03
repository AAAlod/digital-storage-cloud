package dev.kehai.digitalstorage.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.integration.TomIntegrationStatus;
import dev.kehai.digitalstorage.platform.fabric.tom.TomPerformanceBenchmark;
import dev.kehai.digitalstorage.storage.DigitalItemStorageSelfTest;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class DigitalStorageCommands {
    private DigitalStorageCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("digitalstorage"));
        dispatcher.register(root("dsc"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String rootName) {
        return ManagementCommands.root(rootName)
                .then(literal("selftest")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> selfTest(context.getSource())))
                .then(literal("benchmark")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> benchmark(context.getSource())))
                .then(literal("flush")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> flush(context.getSource())))
                .then(literal("probe")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> probe(
                                        context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos")
                                ))))
                .then(literal("diagnostics")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> diagnostics(context.getSource())));
    }

    private static int selfTest(CommandSourceStack source) {
        try {
            ManagementCommandsSelfTest.run(source.getServer());
            String result = DigitalItemStorageSelfTest.run();
            source.sendSuccess(() -> Component.literal(result), false);
            return 1;
        } catch (RuntimeException exception) {
            DigitalStorage.LOGGER.error("Digital Storage self-test failed", exception);
            source.sendFailure(Component.literal("Digital Storage self-test failed: " + exception));
            return 0;
        }
    }

    private static int benchmark(CommandSourceStack source) {
        try {
            TomPerformanceBenchmark.Result result = TomPerformanceBenchmark.run();
            source.sendSuccess(() -> Component.literal(result.summary()), false);
            DigitalStorage.LOGGER.info(result.summary());
            return 1;
        } catch (RuntimeException exception) {
            DigitalStorage.LOGGER.error("Digital Storage benchmark failed", exception);
            source.sendFailure(Component.literal("Digital Storage benchmark failed: " + exception));
            return 0;
        }
    }

    private static int flush(CommandSourceStack source) {
        DigitalStorageState state = DigitalStorageState.get(source.getLevel());
        state.flushNow();
        source.sendSuccess(() -> Component.literal("Digital Storage files flushed successfully"), false);
        return 1;
    }

    private static int probe(CommandSourceStack source, BlockPos pos) {
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(source.getLevel(), pos, null);
        if (storage == null) {
            source.sendFailure(Component.literal("No bound Fabric item storage found at " + pos.toShortString()));
            return 0;
        }
        int views = 0;
        for (var ignored : storage) {
            views++;
        }
        int finalViews = views;
        source.sendSuccess(() -> Component.literal(
                "Fabric item storage found at " + pos.toShortString() + " with " + finalViews + " non-empty views"
        ), false);
        return 1;
    }

    private static int diagnostics(CommandSourceStack source) {
        TomIntegrationStatus.Snapshot status = TomIntegrationStatus.snapshot();
        source.sendSuccess(() -> Component.literal(
                "Tom integration: " + (status.allActive() ? "ACTIVE" : "INCOMPLETE")
        ), false);
        source.sendSuccess(() -> Component.literal(
                "hopper=" + state(status.hopperOptimizationMixinApplied())
                        + ", connector stagger=" + state(status.connectorStaggerMixinApplied())
                        + ", topology tracking=" + state(status.topologyTrackingMixinApplied())
                        + ", volume dedup=" + state(status.volumeDedupMixinApplied())
                        + ", block entity extension=" + state(status.blockEntityTypeExtensionMixinApplied())
                        + ", advanced hopper=" + state(status.advancedHopperSupportActive())
        ), false);
        return status.allActive() ? 1 : 0;
    }

    private static String state(boolean value) {
        return value ? "ACTIVE" : "INACTIVE";
    }

}
