package dev.kehai.digitalstorage.forge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Called while the real chunk still owns its entities, before removal invalidates endpoints. */
public final class ForgeHopperLifecycle {
    private static final ThreadLocal<ForgeHopperCustody> TEST_STORE = new ThreadLocal<>();
    private ForgeHopperLifecycle() { }
    public static void attached(LevelChunk chunk, BlockEntity entity) {
        if (!(entity instanceof ForgeHopperState state) || !(chunk.getLevel() instanceof ServerLevel world)) return;
        var custody = store(world);
        if (custody != null) state.digitalstorage$reconcile(custody, world.dimension().location().toString(), entity.getBlockPos());
    }
    public static void removing(LevelChunk chunk, BlockEntity entity) {
        if (!(entity instanceof ForgeHopperState state) || !(chunk.getLevel() instanceof ServerLevel world)) return;
        var custody = store(world);
        if (custody != null && state.digitalstorage$transferState().blocked()) {
            state.digitalstorage$retain(custody, world.dimension().location().toString(), entity.getBlockPos());
        }
    }
    public static void checkpoint(BlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel world)
                || world.getChunkAt(entity.getBlockPos()).getBlockEntities().get(entity.getBlockPos()) != entity) return;
        attached(world.getChunkAt(entity.getBlockPos()), entity);
        removing(world.getChunkAt(entity.getBlockPos()), entity);
    }
    private static ForgeHopperCustody store(ServerLevel world) {
        var test = TEST_STORE.get();
        if (test != null) return test;
        var session = ForgeTransferSessions.find(world.getServer());
        return session == null ? null : session.hoppers();
    }
    static void withStoreForTest(ForgeHopperCustody store, Runnable fixture) {
        if (TEST_STORE.get() != null) throw new IllegalStateException("Nested custody fixture");
        TEST_STORE.set(store);
        try { fixture.run(); } finally { TEST_STORE.remove(); }
    }
    static void withReopenedStoreForTest(ForgeHopperCustody store, Runnable fixture) {
        var previous = TEST_STORE.get();
        if (previous == null) throw new IllegalStateException("Reopen requires an active private custody fixture");
        TEST_STORE.set(store);
        try { fixture.run(); } finally { TEST_STORE.set(previous); }
    }
}
