package dev.kehai.digitalstorage.tier;

import java.util.List;
import net.minecraft.util.Identifier;

public record DigitalStorageTier(
        Identifier id,
        int variantCapacity,
        List<UpgradeIngredient> entryCost,
        int experienceLevels
) {
    public DigitalStorageTier {
        entryCost = List.copyOf(entryCost);
        if (variantCapacity <= 0) {
            throw new IllegalArgumentException("Tier capacity must be positive");
        }
        if (experienceLevels < 0) {
            throw new IllegalArgumentException("Tier experience cost cannot be negative");
        }
    }

    public String describeEntryCost() {
        String itemCost = entryCost.isEmpty()
                ? "no items"
                : entryCost.stream().map(UpgradeIngredient::displayName).reduce((left, right) -> left + ", " + right).orElse("");
        return experienceLevels > 0 ? itemCost + ", " + experienceLevels + " XP levels" : itemCost;
    }
}
