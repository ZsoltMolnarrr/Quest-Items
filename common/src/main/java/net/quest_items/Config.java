package net.quest_items;

import java.util.LinkedHashMap;
import java.util.List;

public class Config {
    public static class Items {
        public LinkedHashMap<String, QuestItems.Config> items = new LinkedHashMap<>();
    }

    public static class Loot {
        public record Item(String itemId, int weight) {  }
        public record Entry(float chance, List<Item> items) { }
        public LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

        public static Loot example() {
            Loot loot = new Loot();
            loot.entries.put("example:chests/misc", new Entry(0.5f, List.of(new Item("minecraft:diamond", 1))));
            return loot;
        }
    }
}
