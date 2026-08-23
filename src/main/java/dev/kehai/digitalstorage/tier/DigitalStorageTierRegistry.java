package dev.kehai.digitalstorage.tier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

public final class DigitalStorageTierRegistry implements SimpleSynchronousResourceReloadListener {
    public static final DigitalStorageTierRegistry INSTANCE = new DigitalStorageTierRegistry();

    private static final String RESOURCE_PATH = "digital_storage_tiers";
    private volatile Snapshot snapshot = createBuiltInSnapshot();

    private DigitalStorageTierRegistry() {
    }

    @Override
    public Identifier getFabricId() {
        return DigitalStorageMod.id("tier_registry");
    }

    @Override
    public void reload(ResourceManager manager) {
        try {
            Map<Identifier, Resource> resources = manager.findResources(
                    RESOURCE_PATH,
                    id -> id.getPath().endsWith(".json")
            );
            if (resources.isEmpty()) {
                throw new IllegalStateException("No digital storage tier definitions were found");
            }

            List<OrderedTier> definitions = new ArrayList<>();
            resources.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> readResource(entry.getKey(), entry.getValue(), definitions));
            Snapshot loaded = buildSnapshot(definitions);
            snapshot = loaded;
            DigitalStorageMod.LOGGER.info("Loaded {} digital storage tiers", loaded.tiers().size());
        } catch (RuntimeException exception) {
            DigitalStorageMod.LOGGER.error("Tier reload failed; keeping the previous valid tier registry", exception);
        }
    }

    public DigitalStorageTier first() {
        return snapshot.tiers().get(0);
    }

    public DigitalStorageTier highest() {
        List<DigitalStorageTier> tiers = snapshot.tiers();
        return tiers.get(tiers.size() - 1);
    }

    public Optional<DigitalStorageTier> find(Identifier id) {
        return Optional.ofNullable(snapshot.byId().get(id));
    }

    public DigitalStorageTier require(Identifier id) {
        return find(id).orElseGet(this::first);
    }

    public Optional<DigitalStorageTier> next(Identifier id) {
        List<DigitalStorageTier> tiers = snapshot.tiers();
        for (int index = 0; index < tiers.size(); index++) {
            if (tiers.get(index).id().equals(id)) {
                return index + 1 < tiers.size() ? Optional.of(tiers.get(index + 1)) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    public DigitalStorageTier tierAtLeastCapacity(int requestedCapacity) {
        for (DigitalStorageTier tier : snapshot.tiers()) {
            if (tier.variantCapacity() >= requestedCapacity) {
                return tier;
            }
        }
        return highest();
    }

    public List<DigitalStorageTier> all() {
        return snapshot.tiers();
    }

    private static void readResource(Identifier resourceId, Resource resource, List<OrderedTier> output) {
        try (Reader reader = resource.getReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray tiers = requireArray(root, "tiers", resourceId);
            for (JsonElement element : tiers) {
                JsonObject tierObject = element.getAsJsonObject();
                int order = requireInt(tierObject, "order", resourceId);
                Identifier id = requireIdentifier(tierObject, "id", resourceId);
                int capacity = requireInt(tierObject, "capacity", resourceId);
                int experienceLevels = tierObject.has("experience_levels")
                        ? tierObject.get("experience_levels").getAsInt()
                        : 0;
                List<UpgradeIngredient> cost = parseCost(tierObject, resourceId);
                output.add(new OrderedTier(order, new DigitalStorageTier(id, capacity, cost, experienceLevels)));
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to read tier resource " + resourceId, exception);
        }
    }

    private static List<UpgradeIngredient> parseCost(JsonObject tierObject, Identifier resourceId) {
        if (!tierObject.has("cost")) {
            return List.of();
        }

        List<UpgradeIngredient> cost = new ArrayList<>();
        for (JsonElement element : tierObject.getAsJsonArray("cost")) {
            JsonObject ingredient = element.getAsJsonObject();
            boolean hasItem = ingredient.has("item");
            boolean hasTag = ingredient.has("tag");
            if (hasItem == hasTag) {
                throw new IllegalArgumentException("Each cost in " + resourceId + " must contain exactly one of item or tag");
            }

            int count = requireInt(ingredient, "count", resourceId);
            Identifier id = requireIdentifier(ingredient, hasItem ? "item" : "tag", resourceId);
            if (hasItem && !Registries.ITEM.containsId(id)) {
                throw new IllegalArgumentException("Unknown item " + id + " in " + resourceId);
            }
            cost.add(hasItem ? UpgradeIngredient.item(id, count) : UpgradeIngredient.tag(id, count));
        }
        return cost;
    }

    private static Snapshot buildSnapshot(List<OrderedTier> definitions) {
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("Tier registry cannot be empty");
        }

        definitions.sort(Comparator.comparingInt(OrderedTier::order).thenComparing(entry -> entry.tier().id()));
        List<DigitalStorageTier> tiers = new ArrayList<>();
        Map<Identifier, DigitalStorageTier> byId = new HashMap<>();
        int previousCapacity = 0;
        for (OrderedTier definition : definitions) {
            DigitalStorageTier tier = definition.tier();
            if (tier.variantCapacity() > DigitalStorageRecord.ABSOLUTE_MAX_VARIANTS) {
                throw new IllegalArgumentException("Tier " + tier.id() + " exceeds the hard variant limit");
            }
            if (tier.variantCapacity() <= previousCapacity) {
                throw new IllegalArgumentException("Tier capacities must increase strictly: " + tier.id());
            }
            if (!tiers.isEmpty() && (tier.entryCost().size() != 1 || tier.experienceLevels() != 0)) {
                throw new IllegalArgumentException(
                        "Upgrade tier " + tier.id() + " must consume exactly one resource and no XP"
                );
            }
            if (byId.putIfAbsent(tier.id(), tier) != null) {
                throw new IllegalArgumentException("Duplicate tier id " + tier.id());
            }
            tiers.add(tier);
            previousCapacity = tier.variantCapacity();
        }
        return new Snapshot(List.copyOf(tiers), Map.copyOf(byId));
    }

    private static Snapshot createBuiltInSnapshot() {
        List<OrderedTier> defaults = List.of(
                new OrderedTier(0, new DigitalStorageTier(
                        DigitalStorageMod.id("basic"), 64, List.of(), 0)),
                new OrderedTier(10, new DigitalStorageTier(
                        DigitalStorageMod.id("advanced"), 128,
                        List.of(UpgradeIngredient.item(new Identifier("minecraft", "diamond"), 16)), 0)),
                new OrderedTier(20, new DigitalStorageTier(
                        DigitalStorageMod.id("elite"), 256,
                        List.of(UpgradeIngredient.item(new Identifier("minecraft", "diamond"), 32)), 0)),
                new OrderedTier(30, new DigitalStorageTier(
                        DigitalStorageMod.id("ultimate"), 512,
                        List.of(UpgradeIngredient.item(new Identifier("minecraft", "diamond"), 64)), 0)),
                new OrderedTier(40, new DigitalStorageTier(
                        DigitalStorageMod.id("maximum"), 1024,
                        List.of(UpgradeIngredient.item(new Identifier("minecraft", "diamond"), 128)), 0))
        );
        return buildSnapshot(new ArrayList<>(defaults));
    }

    private static JsonArray requireArray(JsonObject object, String key, Identifier resourceId) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("Missing array " + key + " in " + resourceId);
        }
        return object.getAsJsonArray(key);
    }

    private static int requireInt(JsonObject object, String key, Identifier resourceId) {
        if (!object.has(key)) {
            throw new IllegalArgumentException("Missing integer " + key + " in " + resourceId);
        }
        return object.get(key).getAsInt();
    }

    private static Identifier requireIdentifier(JsonObject object, String key, Identifier resourceId) {
        if (!object.has(key)) {
            throw new IllegalArgumentException("Missing identifier " + key + " in " + resourceId);
        }
        Identifier id = Identifier.tryParse(object.get(key).getAsString());
        if (id == null) {
            throw new IllegalArgumentException("Invalid identifier " + object.get(key) + " in " + resourceId);
        }
        return id;
    }

    private record OrderedTier(int order, DigitalStorageTier tier) {
    }

    private record Snapshot(List<DigitalStorageTier> tiers, Map<Identifier, DigitalStorageTier> byId) {
    }
}
