package net.quest_items.fabric;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.quest_items.Group;
import net.quest_items.LootInjector;
import net.quest_items.QuestItems;
import net.quest_items.QuestItemsMod;
import net.quest_items.locator.LocateUnlockCommand;
import net.quest_items.locator.TickWorkers;

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

        if (FabricLoader.getInstance().isModLoaded("waystones")) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                    LocateUnlockCommand.register(dispatcher));
            ServerTickEvents.START_SERVER_TICK.register(server -> TickWorkers.tickStart());
            ServerTickEvents.END_SERVER_TICK.register(server -> TickWorkers.tickEnd());
            ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
                TickWorkers.clear();
                LocateUnlockCommand.reset();
            });
        }
    }
}
