package net.quest_items.fabric.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricModelProvider;
import net.minecraft.data.client.BlockStateModelGenerator;
import net.minecraft.data.client.ItemModelGenerator;
import net.minecraft.data.client.Models;
import net.minecraft.registry.RegistryWrapper;
import net.quest_items.Group;
import net.quest_items.QuestItems;

import java.util.concurrent.CompletableFuture;

public class QuestItemsDataGenerator implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
        pack.addProvider(LangGenerator::new);
        pack.addProvider(ModelProvider::new);
    }

    public static class LangGenerator extends FabricLanguageProvider {
        protected LangGenerator(FabricDataOutput dataOutput, CompletableFuture<RegistryWrapper.WrapperLookup> registryLookup) {
            super(dataOutput, "en_us", registryLookup);
        }

        @Override
        public void generateTranslations(RegistryWrapper.WrapperLookup wrapperLookup, TranslationBuilder translationBuilder) {
            translationBuilder.add(Group.translationKey, "Quest Items");
            QuestItems.entries.forEach(entry -> {
                var id = entry.id();
                translationBuilder.add("item." + id.getNamespace() + "." + id.getPath(), entry.translation());
                // translationBuilder.add("item." + id.getNamespace() + "." + id.getPath() + ".description", entry.description());
            });

            var locatePrefix = "commands.quest_items.locate_waystone.";
            translationBuilder.add(locatePrefix + "searching", "Seeking the nearest %s within %s blocks…");
            translationBuilder.add(locatePrefix + "structure_found", "%s located at %s — seeking a waystone nearby…");
            translationBuilder.add(locatePrefix + "surveying", "Surveying uncharted lands for waystones… %s%%");
            translationBuilder.add(locatePrefix + "unlocked", "✦ New waystone unlocked: %s — near %s");
            translationBuilder.add(locatePrefix + "already_unlocked", "✦ Waystone %s is already unlocked — it stands near %s");
            translationBuilder.add(locatePrefix + "no_waystone", "✧ %s located at %s, but no waystone was found within %s blocks");
            translationBuilder.add(locatePrefix + "no_structure", "✧ No %s could be found within %s blocks");
            translationBuilder.add(locatePrefix + "busy", "A waystone search is already underway for %s");
            translationBuilder.add(locatePrefix + "player_offline", "Waystone %s was found near %s, but the player is no longer online to unlock it");
            translationBuilder.add(locatePrefix + "unnamed", "Unnamed Waystone");
        }
    }


    public static class ModelProvider extends FabricModelProvider {
        public ModelProvider(FabricDataOutput output) {
            super(output);
        }

        @Override
        public void generateBlockStateModels(BlockStateModelGenerator blockStateModelGenerator) {

        }

        @Override
        public void generateItemModels(ItemModelGenerator itemModelGenerator) {
            QuestItems.entries.forEach(entry -> {
                itemModelGenerator.register(entry.holder().item, Models.GENERATED);
            });
        }
    }

}