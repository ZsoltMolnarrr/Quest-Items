package net.quest_items.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.structure.StrongholdGenerator;
import net.minecraft.util.math.random.Random;
import net.quest_items.QuestItemsMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(StrongholdGenerator.PortalRoom.class)
public class PortalRoomMixin {

    @WrapOperation(
            method = "generate",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/random/Random;nextFloat()F"),
            require = 0
    )
    private float generate_WRAP_EnderEyeChance(Random instance, Operation<Float> original) {
        if (QuestItemsMod.tweaksConfig.value.stronghold_portal_empty_frame) {
            return 0.0f;
        } else {
            return original.call(instance);
        }
    }
}
