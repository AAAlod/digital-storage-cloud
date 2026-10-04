package dev.kehai.digitalstorage.upgrade;

import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.tier.DigitalStorageTier;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import dev.kehai.digitalstorage.tier.UpgradeIngredient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class DigitalStorageUpgradeService {
    private static final Map<UUID, Long> LAST_SUCCESSFUL_UPGRADE_TICK = new HashMap<>();
    private static final int GUARD_CLEANUP_SIZE = 4096;
    private static final long GUARD_RETENTION_TICKS = 1200;

    private DigitalStorageUpgradeService() {
    }

    public static UpgradeResult tryUpgrade(
            ServerPlayer player,
            UUID storageId,
            DigitalStorageRecord record
    ) {
        long currentTick = player.serverLevel().getGameTime();
        if (LAST_SUCCESSFUL_UPGRADE_TICK.getOrDefault(storageId, Long.MIN_VALUE) == currentTick) {
            return UpgradeResult.failure(Component.translatable("screen.digitalstorage.error.duplicate"));
        }

        DigitalStorageTier currentTier = record.tier();
        Optional<DigitalStorageTier> nextTierResult = DigitalStorageTierRegistry.INSTANCE.next(currentTier.id());
        if (nextTierResult.isEmpty()) {
            return UpgradeResult.failure(Component.translatable(
                    "screen.digitalstorage.error.maximum",
                    DigitalStorageTier.displayName(currentTier.id())
            ));
        }
        DigitalStorageTier nextTier = nextTierResult.get();

        PaymentPlanResult paymentResult = PaymentPlan.create(player.getInventory().items, nextTier.entryCost());
        if (paymentResult.plan() == null) {
            return UpgradeResult.failure(Component.translatable(
                    "screen.digitalstorage.error.missing_item",
                    paymentResult.missingIngredient().displayComponent()
            ));
        }
        if (player.experienceLevel < nextTier.experienceLevels()) {
            return UpgradeResult.failure(Component.translatable(
                    "screen.digitalstorage.error.missing_xp",
                    nextTier.experienceLevels(),
                    player.experienceLevel
            ));
        }

        PaymentPlan paymentPlan = paymentResult.plan();
        List<ItemStack> originalInventory = paymentPlan.apply(player.getInventory().items);
        if (nextTier.experienceLevels() > 0) {
            player.giveExperienceLevels(-nextTier.experienceLevels());
        }

        if (!record.advanceTier(currentTier.id(), nextTier.id())) {
            paymentPlan.restore(player.getInventory().items, originalInventory);
            if (nextTier.experienceLevels() > 0) {
                player.giveExperienceLevels(nextTier.experienceLevels());
            }
            return UpgradeResult.failure(Component.translatable("screen.digitalstorage.error.changed"));
        }

        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        LAST_SUCCESSFUL_UPGRADE_TICK.put(storageId, currentTick);
        cleanGuard(currentTick);
        return UpgradeResult.success(Component.translatable(
                "screen.digitalstorage.success",
                DigitalStorageTier.displayName(currentTier.id()),
                DigitalStorageTier.displayName(nextTier.id()),
                nextTier.variantCapacity()
        ));
    }

    public static boolean canAfford(ServerPlayer player, DigitalStorageTier tier) {
        return PaymentPlan.create(player.getInventory().items, tier.entryCost()).plan() != null
                && player.experienceLevel >= tier.experienceLevels();
    }

    public static void runSelfTest() {
        NonNullList<ItemStack> inventory = NonNullList.withSize(3, ItemStack.EMPTY);
        inventory.set(0, new ItemStack(Items.DIAMOND, 4));
        inventory.set(1, new ItemStack(Items.DIAMOND, 6));

        UpgradeIngredient sixDiamonds = UpgradeIngredient.item(new ResourceLocation("minecraft", "diamond"), 6);
        PaymentPlanResult successful = PaymentPlan.create(inventory, List.of(sixDiamonds));
        expect(successful.plan() != null, "payment plan rejected an affordable exact-item cost");
        successful.plan().apply(inventory);
        expect(inventory.get(0).isEmpty(), "payment plan did not consume the first stack");
        expect(inventory.get(1).getCount() == 4, "payment plan consumed the wrong total");

        NonNullList<ItemStack> overlappingInventory = NonNullList.withSize(1, ItemStack.EMPTY);
        overlappingInventory.set(0, new ItemStack(Items.DIAMOND, 10));
        PaymentPlanResult overlapping = PaymentPlan.create(
                overlappingInventory,
                List.of(
                        UpgradeIngredient.item(new ResourceLocation("minecraft", "diamond"), 6),
                        UpgradeIngredient.item(new ResourceLocation("minecraft", "diamond"), 5)
                )
        );
        expect(overlapping.plan() == null, "overlapping costs reused the same inventory items");
        expect(overlappingInventory.get(0).getCount() == 10, "failed payment planning mutated inventory");
        Component tier = DigitalStorageTier.displayName(new ResourceLocation("digitalstorage", "ultimate"));
        expect(tier.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translation
                && translation.getKey().equals("tier.digitalstorage.digitalstorage.ultimate"),
                "tier name became a literal ID instead of a client-localized component");
        expect(sixDiamonds.displayComponent().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents cost
                && cost.getArgs()[0] instanceof Component, "missing ingredient lost its translated item component");
    }

    private static void cleanGuard(long currentTick) {
        if (LAST_SUCCESSFUL_UPGRADE_TICK.size() < GUARD_CLEANUP_SIZE) {
            return;
        }
        LAST_SUCCESSFUL_UPGRADE_TICK.entrySet().removeIf(
                entry -> currentTick - entry.getValue() > GUARD_RETENTION_TICKS
        );
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public record UpgradeResult(boolean success, Component message) {
        private static UpgradeResult success(Component message) {
            return new UpgradeResult(true, message);
        }

        private static UpgradeResult failure(Component message) {
            return new UpgradeResult(false, message);
        }
    }

    private record PaymentPlan(int[] deductions) {
        private static PaymentPlanResult create(List<ItemStack> inventory, List<UpgradeIngredient> costs) {
            int[] available = new int[inventory.size()];
            int[] deductions = new int[inventory.size()];
            for (int slot = 0; slot < inventory.size(); slot++) {
                available[slot] = inventory.get(slot).getCount();
            }

            for (UpgradeIngredient cost : costs) {
                int remaining = cost.count();
                for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
                    ItemStack stack = inventory.get(slot);
                    if (available[slot] <= 0 || !cost.matches(stack)) {
                        continue;
                    }

                    int reserved = Math.min(remaining, available[slot]);
                    available[slot] -= reserved;
                    deductions[slot] += reserved;
                    remaining -= reserved;
                }
                if (remaining > 0) {
                    return new PaymentPlanResult(null, cost);
                }
            }
            return new PaymentPlanResult(new PaymentPlan(deductions), null);
        }

        private List<ItemStack> apply(List<ItemStack> inventory) {
            List<ItemStack> originals = inventory.stream().map(ItemStack::copy).toList();
            for (int slot = 0; slot < deductions.length; slot++) {
                if (deductions[slot] > 0) {
                    inventory.get(slot).shrink(deductions[slot]);
                }
            }
            return originals;
        }

        private void restore(List<ItemStack> inventory, List<ItemStack> originals) {
            for (int slot = 0; slot < inventory.size(); slot++) {
                inventory.set(slot, originals.get(slot).copy());
            }
        }
    }

    private record PaymentPlanResult(PaymentPlan plan, UpgradeIngredient missingIngredient) {
    }
}
