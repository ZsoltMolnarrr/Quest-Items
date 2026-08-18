package net.quest_items.neoforge;

import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import net.quest_items.QuestItemsMod;
import net.quest_items.locator.LocateUnlockCommand;
import net.quest_items.locator.TickWorkers;

@Mod(QuestItemsMod.ID)
public final class ExampleModNeoForge {
    public ExampleModNeoForge() {
        // Run our common setup.
        QuestItemsMod.init();

        if (ModList.get().isLoaded("waystones")) {
            NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                    LocateUnlockCommand.register(event.getDispatcher()));
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> TickWorkers.tickStart());
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> TickWorkers.tickEnd());
            NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
                TickWorkers.clear();
                LocateUnlockCommand.reset();
            });
        }
    }
}
