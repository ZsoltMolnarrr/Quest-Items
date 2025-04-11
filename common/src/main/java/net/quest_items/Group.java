package net.quest_items;

import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

import java.util.function.Supplier;

public class Group {
    public static Identifier ID = Identifier.of(QuestItemsMod.ID, "generic");
    public static String translationKey = "itemGroups." + ID.getNamespace() + "." + ID.getPath();
    public static RegistryKey<ItemGroup> KEY = RegistryKey.of(Registries.ITEM_GROUP.getKey(), ID);
    public static ItemGroup GROUP;
    public static Supplier<ItemStack> ICON = () -> {
        return new ItemStack(QuestItems.pharaohs_mask.holder().item);
    };
}
