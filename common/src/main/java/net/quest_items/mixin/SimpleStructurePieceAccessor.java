package net.quest_items.mixin;

import net.minecraft.structure.SimpleStructurePiece;
import net.minecraft.structure.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SimpleStructurePiece.class)
public interface SimpleStructurePieceAccessor {
    @Accessor("template")
    StructureTemplate questItems$getTemplate();
}
