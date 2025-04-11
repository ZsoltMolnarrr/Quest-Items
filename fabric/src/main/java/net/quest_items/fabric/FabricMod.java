package net.quest_items.fabric;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.text.Text;
import net.quest_items.Group;
import net.quest_items.LootInjector;
import net.quest_items.QuestItems;
import net.quest_items.QuestItemsMod;

public final class FabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        Group.GROUP = FabricItemGroup.builder()
                .icon(Group.ICON)
                .displayName(Text.translatable(Group.translationKey))
                .build();

        QuestItemsMod.init();

        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            LootInjector.configure(registries, key.getValue(), tableBuilder);
        });

        ItemGroupEvents.modifyEntriesEvent(Group.KEY).register(content -> {
            for(var entry: QuestItems.entries) {
                content.add(entry.holder().item);
            }
        });
    }
}
