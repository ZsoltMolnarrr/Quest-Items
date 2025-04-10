package net.quest_items;

import net.tinyconfig.ConfigManager;

public final class QuestItemsMod {
    public static final String ID = "quest_items";

    private static ConfigManager<Config.Items> itemsConfig = new ConfigManager<>
            ("items", new Config.Items())
            .builder()
            .setDirectory(ID)
            .sanitize(true)
            .build();

    private static ConfigManager<Config.Loot> lootConfig = new ConfigManager<>
            ("loot", Config.Loot.example())
            .builder()
            .setDirectory(ID)
            .sanitize(true)
            .build();

    public static void init() {
        itemsConfig.refresh();
        lootConfig.refresh();

        QuestItems.register(itemsConfig.value.items);
        itemsConfig.save();
    }
}
