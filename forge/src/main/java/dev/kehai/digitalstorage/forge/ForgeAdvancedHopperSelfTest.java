package dev.kehai.digitalstorage.forge;

import com.tom.storagemod.Content;
import com.tom.storagemod.block.BasicInventoryHopperBlock;
import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.DigitalStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/** Registration and persisted Tom entity compatibility; never places blocks in a player world. */
public final class ForgeAdvancedHopperSelfTest {
    private ForgeAdvancedHopperSelfTest() { }

    public static void run(MinecraftServer server) {
        var block = (ForgeAdvancedInventoryHopperBlock) ForgeDigitalStorage.ADVANCED_HOPPER.get();
        var type = Content.invHopperBasicTile.get();
        expect(type.isValid(Content.invHopperBasic.get().defaultBlockState()), "Original Tom block remains valid");
        for (var direction : Direction.values()) {
            var state = block.defaultBlockState().setValue(BasicInventoryHopperBlock.FACING, direction)
                    .setValue(BasicInventoryHopperBlock.ENABLED, false);
            expect(type.isValid(state), "Advanced block accepted by original type");
            var entity = (BasicInventoryHopperBlockEntity) block.newBlockEntity(BlockPos.ZERO, state);
            expect(entity != null && entity.getType() == type, "Original Tom entity type");
            entity.setFilter(new ItemStack(Items.STONE));
            var saved = entity.saveWithFullMetadata();
            var restored = BlockEntity.loadStatic(BlockPos.ZERO, state, saved);
            expect(restored instanceof BasicInventoryHopperBlockEntity && restored.getType() == type
                    && restored.getBlockState().equals(state), "Tom type reload with advanced state");
            expect(ItemStack.matches(((BasicInventoryHopperBlockEntity) restored).getFilter(), entity.getFilter()),
                    "Filter survives reload");
            expect(block.getTicker(server.overworld(), state, type) != null, "Server ticker available");
        }
        var state = block.defaultBlockState();
        expect(state.is(BlockTags.MINEABLE_WITH_PICKAXE) && state.is(BlockTags.NEEDS_IRON_TOOL), "Mining tags loaded");
        var recipe = server.getRecipeManager().byKey(DigitalStorage.id("advanced_inventory_hopper"));
        expect(recipe.isPresent() && recipe.get().getResultItem(server.registryAccess())
                .is(ForgeDigitalStorage.ADVANCED_HOPPER_ITEM.get()), "Crafting recipe loaded");
        var loot = server.getLootData().getLootTable(DigitalStorage.id("blocks/advanced_inventory_hopper"));
        expect(loot != LootTable.EMPTY, "Drop table loaded");
        var drops = loot.getRandomItems(new LootParams.Builder(server.overworld())
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withParameter(LootContextParams.TOOL, new ItemStack(Items.DIAMOND_PICKAXE))
                .create(LootContextParamSets.BLOCK));
        expect(drops.size() == 1 && drops.get(0).getCount() == 1
                && drops.get(0).is(ForgeDigitalStorage.ADVANCED_HOPPER_ITEM.get()), "Self drop generated");
        DigitalStorage.LOGGER.info("Forge advanced hopper registration self-test passed: six facings, Tom type/filter reload, server ticker, recipe, drops and mining tags; bulk transfer not covered");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Forge advanced hopper: " + message);
    }
}
