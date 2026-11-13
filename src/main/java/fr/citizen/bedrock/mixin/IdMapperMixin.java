package fr.citizen.bedrock.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import fr.citizen.bedrock.BedrockContext;
import fr.citizen.bedrock.remap.RemapTables;
import net.minecraft.core.IdMapper;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Convertit les identifiants d'états de blocs moddés en blocs vanilla pour les consoles. */
@Mixin(IdMapper.class)
public abstract class IdMapperMixin {
    @ModifyReturnValue(method = "getId(Ljava/lang/Object;)I", at = @At("RETURN"))
    private int citizenbedrock$remap(int id) {
        if (BedrockContext.ANY && (Object) this == Block.BLOCK_STATE_REGISTRY && BedrockContext.isActive()) {
            return RemapTables.remapBlockState(id);
        }
        return id;
    }
}
