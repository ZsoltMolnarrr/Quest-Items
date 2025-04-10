package net.quest_items.neoforge;

import net.neoforged.fml.common.Mod;

import net.quest_items.QuestItemsMod;

@Mod(QuestItemsMod.ID)
public final class ExampleModNeoForge {
    public ExampleModNeoForge() {
        // Run our common setup.
        QuestItemsMod.init();
    }
}
