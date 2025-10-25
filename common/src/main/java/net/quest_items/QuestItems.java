package net.quest_items;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

import java.util.ArrayList;
import java.util.Map;

public class QuestItems {
    public record Config(Rarity rarity, Boolean fireproof) {
        public Config(Rarity rarity) {
            this(rarity, Boolean.TRUE);
        }
        public Config {
            fireproof = (fireproof == null) ? Boolean.TRUE : fireproof;
        }
    }
    public static class Holder { Holder() { }; public Holder(Item item) { this.item = item; }; public Item item; }
    public record Entry(Identifier id, String translation, Config defaults, Holder holder) { }
    public static final ArrayList<Entry> entries = new ArrayList<>();

    private static Entry entry(String name, String translation, Config defaults) {
        var id = Identifier.of(QuestItemsMod.ID, name);
        var entry = new Entry(id, translation, defaults, new Holder());
        entries.add(entry);
        return entry;
    }
    public static final Rarity DEFAULT_RARITY = Rarity.UNCOMMON;

    public static final Entry aether_essence = entry("aether_essence", "Aether Essence", new Config(DEFAULT_RARITY));
    public static final Entry ancient_king_scepter = entry("ancient_king_scepter", "Ancient King's Scepter", new Config(DEFAULT_RARITY));
    public static final Entry black_pearl = entry("black_pearl", "Black Pearl", new Config(DEFAULT_RARITY));
    public static final Entry corruption_orb = entry("corruption_orb", "Corruption Orb", new Config(DEFAULT_RARITY));
    public static final Entry corrupted_grail = entry("corrupted_grail", "Corrupted Grail", new Config(DEFAULT_RARITY));
    public static final Entry crystal_petal = entry("crystal_petal", "Crystal Petal", new Config(DEFAULT_RARITY));
    public static final Entry cursed_crown = entry("cursed_crown", "Cursed Crown", new Config(DEFAULT_RARITY));
    public static final Entry deep_see_crystal = entry("deep_see_crystal", "Deep Sea Crystal", new Config(DEFAULT_RARITY));
    public static final Entry desert_rose = entry("desert_rose", "Desert Rose", new Config(DEFAULT_RARITY));
    public static final Entry evil_codex = entry("evil_codex", "Evil Codex", new Config(DEFAULT_RARITY));
    public static final Entry frozen_grail = entry("frozen_grail", "Frozen Grail", new Config(DEFAULT_RARITY));
    public static final Entry frozen_skull = entry("frozen_skull", "Frozen Skull", new Config(DEFAULT_RARITY));
    public static final Entry golden_lantern = entry("golden_lantern", "Golden Lantern", new Config(DEFAULT_RARITY));
    public static final Entry magnificent_clam_shell = entry("magnificent_clam_shell", "Magnificent Clam Shell", new Config(DEFAULT_RARITY));
    public static final Entry molten_tablet = entry("molten_tablet", "Molten Tablet", new Config(DEFAULT_RARITY));
    public static final Entry mossy_tablet = entry("mossy_tablet", "Mossy Tablet", new Config(DEFAULT_RARITY));
    public static final Entry pharaohs_mask = entry("pharaohs_mask", "Pharaoh's Mask", new Config(DEFAULT_RARITY));
    public static final Entry red_urn = entry("red_urn", "Red Urn of Binding", new Config(DEFAULT_RARITY));
    public static final Entry rotten_heart = entry("rotten_heart", "Rotten Heart", new Config(DEFAULT_RARITY));
    public static final Entry sack_of_magic_dust = entry("sack_of_magic_dust", "Sack of Magic Dust", new Config(DEFAULT_RARITY));
    public static final Entry shackles = entry("shackles", "Shackles", new Config(DEFAULT_RARITY));
    public static final Entry silver_feather = entry("silver_feather", "Silver Feather", new Config(DEFAULT_RARITY));
    public static final Entry skeletal_hand = entry("skeletal_hand", "Skeletal Hand", new Config(DEFAULT_RARITY));
    public static final Entry steel_heart = entry("steel_heart", "Steel Heart", new Config(DEFAULT_RARITY));
    public static final Entry stone_idol = entry("stone_idol", "Stone Idol", new Config(DEFAULT_RARITY));
    public static final Entry supercooled_core = entry("supercooled_core", "Supercooled Core", new Config(DEFAULT_RARITY));
    public static final Entry toxic_vial = entry("toxic_vial", "Toxic Vial", new Config(DEFAULT_RARITY));
    public static final Entry tribal_totem = entry("tribal_totem", "Tribal Totem", new Config(DEFAULT_RARITY));
    public static final Entry undead_essence = entry("undead_essence", "Undead Essence", new Config(DEFAULT_RARITY));
    public static final Entry unholy_candle = entry("unholy_candle", "Unholy Candle", new Config(DEFAULT_RARITY));
    public static final Entry warden_ears = entry("warden_ears", "Warden Ears", new Config(DEFAULT_RARITY));
    public static final Entry wildfire_essence = entry("wildfire_essence", "Wildfire Essence", new Config(DEFAULT_RARITY));
    public static final Entry wither_ribcage = entry("wither_ribcage", "Wither Ribcage", new Config(DEFAULT_RARITY));
    public static final Entry wicked_tentacle = entry("wicked_tentacle", "Wicked Tentacle", new Config(DEFAULT_RARITY));

    public static void register(Map<String, Config> configs) {
        for (var entry : entries) {
            var config = configs.get(entry.id.toString());
            if (config == null) {
                config = entry.defaults;
                configs.put(entry.id.toString(), config);
            }
            var settings = new Item.Settings()
                    .rarity(config.rarity);
            if (config.fireproof) {
                settings.fireproof();
            }
            var item = new Item(settings);
            entry.holder.item = item;
            Registry.register(Registries.ITEM, entry.id, item);
        }
    }
}
