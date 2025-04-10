package net.quest_items.neoforge;

import net.neoforged.fml.common.Mod;

import net.quest_items.ExampleMod;

@Mod(ExampleMod.MOD_ID)
public final class ExampleModNeoForge {
    public ExampleModNeoForge() {
        // Run our common setup.
        ExampleMod.init();
    }
}
