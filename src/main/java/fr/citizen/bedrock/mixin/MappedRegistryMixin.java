package fr.citizen.bedrock.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import fr.citizen.bedrock.BedrockContext;
import fr.citizen.bedrock.remap.RemapTables;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Convertit les identifiants moddés (objets, entités, sons, particules, menus...) en identifiants vanilla
 * quand un paquet est écrit pour une console.
 */
@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin {
    @ModifyReturnValue(method = "getId(Ljava/lang/Object;)I", at = @At("RETURN"))
    private int citizenbedrock$remap(int id) {
        if (BedrockContext.ANY && BedrockContext.isActive()) {
            return RemapTables.remapRegistryId((Registry<?>) (Object) this, id);
        }
        return id;
    }
}
