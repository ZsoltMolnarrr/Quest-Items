package net.quest_items;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

import java.util.ArrayList;
import java.util.Map;

public class QuestItems {
    public record Config(Rarity rarity) { }
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

    public static final Entry pharaohs_mask = entry("pharaohs_mask", "Pharaoh's Mask", new Config(DEFAULT_RARITY));
    public static final Entry ancient_king_scepter = entry("ancient_king_scepter", "Ancient King's Scepter", new Config(DEFAULT_RARITY));
    public static final Entry red_urn = entry("red_urn", "Red Urn of Binding", new Config(DEFAULT_RARITY));
    public static final Entry desert_rose = entry("desert_rose", "Desert Rose", new Config(DEFAULT_RARITY));

    public static void register(Map<String, Config> configs) {
        for (var entry : entries) {
            var config = configs.get(entry.id.toString());
            if (config == null) {
                config = entry.defaults;
                configs.put(entry.id.toString(), config);
            }
            var item = new Item(new Item.Settings()
                    .rarity(config.rarity)
            );
            entry.holder.item = item;
            Registry.register(Registries.ITEM, entry.id, item);
        }
    }
}
