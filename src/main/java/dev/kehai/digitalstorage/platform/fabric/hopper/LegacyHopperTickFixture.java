package dev.kehai.digitalstorage.platform.fabric.hopper;

import com.tom.storagemod.block.BasicInventoryHopperBlock;
import com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity;
import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.DigitalStorageMod;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Private audit world only; all hopper updates are scheduled by the server. */
public final class LegacyHopperTickFixture {
    private static final Field BOTTOM = field(AbstractInventoryHopperBlockEntity.class, "bottom");
    private static final Field TOP = field(AbstractInventoryHopperBlockEntity.class, "top");
    private static final Field CURSORS = field(HopperTransferOptimizer.class, "FILTER_CURSORS");
    private static final Field BASIC_COOLDOWN = field(BasicInventoryHopperBlockEntity.class, "cooldown");
    private static final Field ADVANCED_COOLDOWN = field(FabricAdvancedInventoryHopperBlockEntity.class, "cooldown");
    private static int scenario, phase, ticks, before, removedTicks;
    private static BlockPos pos, sourcePos;
    private static BlockEntity hopper;
    private static Storage<?> old;
    private static Object retainedCursor;
    private static boolean done;

    private static Field field(Class<?> owner, String name) {
        try { Field f = owner.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    public static void tick(MinecraftServer server) {
        if (done) return;
        ServerLevel world = server.overworld();
        try {
            if (pos == null) {
                pos = world.getSharedSpawnPos().above(40).east(16);
                world.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            }
            if (++ticks > 180) throw new IllegalStateException("tick fixture timeout scenario=" + scenario + " phase=" + phase);
            if (phase == 0) {
                sourcePos = scenario >= 6 ? pos.above().east() : pos.above();
                if (!world.isEmptyBlock(pos) || !world.isEmptyBlock(pos.below()) || !world.isEmptyBlock(pos.above())
                        || !world.isEmptyBlock(sourcePos)) throw new IllegalStateException("fixture location occupied");
                world.setBlockAndUpdate(sourcePos, Blocks.CHEST.defaultBlockState());
                if (scenario >= 6) world.setBlockAndUpdate(pos.above(), com.tom.storagemod.Content.connector.get().defaultBlockState());
                world.setBlockAndUpdate(pos.below(), Blocks.COMPOSTER.defaultBlockState());
                world.setBlockAndUpdate(pos, (scenario >= 6 ? com.tom.storagemod.Content.invHopperBasic.get()
                        : DigitalStorageMod.ADVANCED_INVENTORY_HOPPER).defaultBlockState()
                        .setValue(BasicInventoryHopperBlock.FACING, Direction.DOWN)
                        .setValue(BasicInventoryHopperBlock.ENABLED, true));
                if (scenario < 3) {
                    var legacy = new BasicInventoryHopperBlockEntity(pos, world.getBlockState(pos));
                    var nbt = legacy.saveWithFullMetadata();
                    var restored = BlockEntity.loadStatic(pos, world.getBlockState(pos), nbt);
                    if (!(restored instanceof BasicInventoryHopperBlockEntity)) throw new IllegalStateException("legacy NBT did not load");
                    world.removeBlockEntity(pos);
                    world.setBlockEntity(restored);
                }
                hopper = world.getBlockEntity(pos);
                if (scenario >= 6) ((BasicInventoryHopperBlockEntity) hopper).setFilter(new ItemStack(Items.PUMPKIN_PIE));
                ((Container) world.getBlockEntity(sourcePos)).setItem(0, new ItemStack(Items.PUMPKIN_PIE, 32));
                phase = 1;
            } else if (phase == 1) {
                Storage<?> bottom = (Storage<?>) BOTTOM.get(hopper);
                Container chest = (Container) world.getBlockEntity(sourcePos);
                if (bottom instanceof BlockStorageGuard && chest.getItem(0).getCount() < 32) {
                    if (chest.getItem(0).getCount() != 31 || world.getBlockState(pos.below()).getValue(ComposterBlock.LEVEL) != 1)
                        throw new IllegalStateException("initial transfer did not conserve the compostable item");
                    old = bottom;
                    if (scenario < 6) {
                        var source = (Storage<?>) TOP.get(hopper);
                        @SuppressWarnings("unchecked") var cursors = (java.util.Map<Object, java.lang.ref.WeakReference<?>>) CURSORS.get(null);
                        var reference = cursors.get(source);
                        retainedCursor = reference == null ? null : reference.get();
                        if (retainedCursor == null) throw new IllegalStateException("successful transfer did not cache source view");
                        if (scenario % 3 == 1) {
                            cursors.remove(source);
                            retainedCursor = null;
                        }
                    }
                    before = chest.getItem(0).getCount();
                    world.setBlockAndUpdate(pos.below(), (scenario % 3 == 1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                    if (scenario % 3 == 2) world.setBlockAndUpdate(pos.below(), Blocks.COMPOSTER.defaultBlockState());
                    (hopper instanceof BasicInventoryHopperBlockEntity ? BASIC_COOLDOWN : ADVANCED_COOLDOWN).setInt(hopper, 0);
                    phase = 2;
                }
            } else if (phase == 2) {
                if (BOTTOM.get(hopper) != old) throw new IllegalStateException("endpoint refreshed before stale tick; fixture inconclusive");
                if (((Container) world.getBlockEntity(sourcePos)).getItem(0).getCount() != before)
                    throw new IllegalStateException("stale endpoint consumed items");
                if ((hopper instanceof BasicInventoryHopperBlockEntity ? BASIC_COOLDOWN : ADVANCED_COOLDOWN).getInt(hopper) <= 0)
                    throw new IllegalStateException("hopper did not attempt the stale transfer on its scheduled tick");
                if (scenario < 6 && retainedCursor != null
                        && ((java.util.Map<?, ?>) CURSORS.get(null)).containsKey(TOP.get(hopper)))
                    throw new IllegalStateException("stale cached source view was not attempted");
                if (scenario % 3 == 2 && world.getBlockState(pos.below()).getValue(ComposterBlock.LEVEL) != 0)
                    throw new IllegalStateException("stale endpoint modified the replacement composter");
                if (scenario % 3 == 2) phase = 4;
                else { removedTicks = 0; phase = 3; }
            } else if (phase == 3) {
                if (((Container) world.getBlockEntity(sourcePos)).getItem(0).getCount() != before)
                    throw new IllegalStateException("item lost during ticks after removal");
                if (++removedTicks == 40) {
                    world.setBlockAndUpdate(pos.below(), Blocks.COMPOSTER.defaultBlockState());
                    phase = 4;
                }
            } else if (phase == 4 && BOTTOM.get(hopper) != old && BOTTOM.get(hopper) instanceof BlockStorageGuard
                    && ((Container) world.getBlockEntity(sourcePos)).getItem(0).getCount() < before) {
                System.out.println("DSC natural hopper stale endpoint case passed " + scenario);
                world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                world.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
                world.setBlockAndUpdate(sourcePos, Blocks.AIR.defaultBlockState());
                world.setBlockAndUpdate(pos.below(), Blocks.AIR.defaultBlockState());
                phase = 0;
                ticks = 0;
                if (++scenario == 9) {
                    world.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, false);
                    done = true;
                    System.out.println("DSC natural legacy hopper fixture passed");
                }
            }
        } catch (Exception e) {
            done = true;
            e.printStackTrace();
            System.out.println("DSC natural legacy hopper fixture FAILED: " + e);
        }
    }
}
