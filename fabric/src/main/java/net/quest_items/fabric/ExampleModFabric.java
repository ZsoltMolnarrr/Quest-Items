package net.quest_items.fabric;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.quest_items.LootInjector;
import net.quest_items.QuestItemsMod;

public final class ExampleModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        QuestItemsMod.init();

        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            LootInjector.configure(registries, key.getValue(), tableBuilder);
        });
    }
}
