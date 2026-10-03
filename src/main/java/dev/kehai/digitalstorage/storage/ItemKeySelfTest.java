package dev.kehai.digitalstorage.storage;

import java.util.HashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ItemKeySelfTest {
    private ItemKeySelfTest() {
    }

    public static void run() {
        immutableIdentity();
        legacyRecordRoundTrip();
        invalidAndBlankKeys();
    }

    private static void immutableIdentity() {
        CompoundTag tag = parse("{display:{Name:'{\"text\":\"旧卷物品\"}'},Nested:{Unknown:[I;1,2,3]}}");
        ItemKey key = ItemKey.of(Items.PAPER, tag);
        ItemKey original = ItemKey.of(Items.PAPER, tag);
        var map = new HashMap<ItemKey, Long>();
        map.put(key, 42L);
        int bytes = key.tagBytes();
        tag.getCompound("Nested").putInt("Changed", 1);
        key.copyTag().getCompound("Nested").putInt("Changed", 2);
        ItemStack stack = key.toStack(64);
        stack.getTag().getCompound("Nested").putInt("Changed", 3);
        expect(key.equals(original) && map.get(original) == 42L && key.tagBytes() == bytes,
                "Mutable NBT changed an item key or its map lookup");
        expect(ItemKey.of(key.toStack(1)).equals(ItemKey.of(key.toStack(64))),
                "Stack count became part of item identity");
        expect(!ItemKey.of(Items.PAPER).equals(ItemKey.of(Items.PAPER, new CompoundTag())),
                "Null and empty tags were incorrectly collapsed");
        expect(!key.equals(ItemKey.of(Items.PAPER, tag)), "Different nested tags collapsed into one key");
    }

    private static void legacyRecordRoundTrip() {
        // Pre-decoupling format: Variant.item/tag and Amount long, including a duplicate and over-cap count.
        CompoundTag fixture = parse("""
                {Tier:"digitalstorage:basic",LastKnownVariantCapacity:64,AcceptUnstackableItems:1b,
                 CreatedTime:123L,LastAccessTime:456L,Items:[
                  {Variant:{item:"minecraft:paper",tag:{Custom:{Unknown:[I;1,2,3]},display:{Name:'{"text":"旧卷"}'}},
                            UnknownRoot:{Keep:1b}},Amount:2147483711L},
                  {Variant:{item:"minecraft:paper",tag:{Custom:{Unknown:[I;1,2,3]},display:{Name:'{"text":"旧卷"}'}}},Amount:17L},
                  {Variant:{item:"minecraft:iron_pickaxe",tag:{Damage:9}},Amount:3L},
                  {Variant:{item:"minecraft:air"},Amount:100L}]}
                """);
        CompoundTag firstSerialized = fixture.getList("Items", 10).getCompound(0).getCompound("Variant").copy();
        ItemKey paper = ItemKeyCodec.read(firstSerialized);
        DigitalStorageRecord loaded = DigitalStorageRecord.fromNbt(fixture, () -> { });
        fixture.getList("Items", 10).getCompound(0).getCompound("Variant").putString("item", "minecraft:dirt");
        expect(loaded.storage().variantCount() == 2 && loaded.storage().amountOf(paper) == 2147483728L,
                "Legacy duplicate, blank or over-cap amount did not load correctly");
        CompoundTag written = new CompoundTag();
        loaded.writeNbt(written);
        // Captured from the archived 1.1.14 binary running in an isolated server.
        CompoundTag legacyWritten = parse("""
                {AcceptUnstackableItems:1b,CreatedTime:123L,Items:[
                 {Amount:2147483728L,Variant:{UnknownRoot:{Keep:1b},item:"minecraft:paper",
                   tag:{Custom:{Unknown:[I;1,2,3]},display:{Name:'{"text":"旧卷"}'}}}},
                 {Amount:3L,Variant:{item:"minecraft:iron_pickaxe",tag:{Damage:9}}}],
                 LastAccessTime:456L,LastKnownVariantCapacity:64,Tier:"digitalstorage:basic"}
                """);
        CompoundTag envelope = written.copy();
        envelope.remove("Items");
        CompoundTag legacyEnvelope = legacyWritten.copy();
        legacyEnvelope.remove("Items");
        expect(envelope.equals(legacyEnvelope), "Record metadata differs from the archived 1.1.14 output");
        ListTag legacyItems = legacyWritten.getList("Items", 10);
        boolean preserved = false;
        ListTag items = written.getList("Items", 10);
        expect(items.size() == legacyItems.size(), "Record entry count differs from the archived 1.1.14 output");
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            expect(legacyItems.contains(entry), "Record entry differs from the archived 1.1.14 output");
            if (ItemKeyCodec.read(entry.getCompound("Variant")).equals(paper)) {
                preserved = firstSerialized.equals(entry.getCompound("Variant"))
                        && entry.getLong("Amount") == 2147483728L;
            }
        }
        expect(preserved, "Rewriting a legacy record lost its unknown Variant fields or long amount");
        DigitalStorageRecord reread = DigitalStorageRecord.fromNbt(written, () -> { });
        expect(reread.storage().amountOf(paper) == loaded.storage().amountOf(paper)
                        && reread.storage().totalItemCount() == loaded.storage().totalItemCount()
                        && reread.storage().totalVariantNbtBytes() == loaded.storage().totalVariantNbtBytes(),
                "Legacy read/write/read changed quantities or NBT accounting");
    }

    private static void invalidAndBlankKeys() {
        expect(ItemKeyCodec.read(parse("{item:'INVALID ID'}")).isBlank(), "Invalid item identifier became a key");
        expect(ItemKeyCodec.read(parse("{item:'missing:unregistered'}")).isBlank(), "Unknown item did not become blank");
        expect(ItemKey.of(Items.AIR, parse("{Payload:1}")).equals(ItemKey.blank()), "Blank key retained a tag");
        expect(ItemKeyCodec.read(ItemKeyCodec.write(ItemKey.blank())).isBlank(), "Blank codec changed identity");
    }

    private static CompoundTag parse(String snbt) {
        try {
            return TagParser.parseTag(snbt);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            throw new IllegalStateException("Invalid item key test fixture", exception);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
