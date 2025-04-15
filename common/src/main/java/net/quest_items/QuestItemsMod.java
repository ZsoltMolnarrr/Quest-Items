package net.quest_items;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.tinyconfig.ConfigManager;

public final class QuestItemsMod {
    public static final String ID = "quest_items";
    public static final ConfigManager<Config.Items> itemsConfig = new ConfigManager<>
            ("items", new Config.Items())
            .builder()
            .setDirectory(ID)
            .sanitize(true)
            .build();
    public static final ConfigManager<Config.Loot> lootConfig = new ConfigManager<>
            ("loot", Config.Loot.example())
            .builder()
            .setDirectory(ID)
            .sanitize(true)
            .build();
    public static final ConfigManager<Config.Tweaks> tweaksConfig = new ConfigManager<>
            ("tweaks", new Config.Tweaks())
            .builder()
            .setDirectory(ID)
            .sanitize(true)
            .build();

    public static void init() {
        itemsConfig.refresh();
        lootConfig.refresh();
        tweaksConfig.refresh();

        QuestItems.register(itemsConfig.value.items);
        Registry.register(Registries.ITEM_GROUP, Group.KEY, Group.GROUP);
        itemsConfig.save();
    }
}
