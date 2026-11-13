package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.BedrockContext;
import fr.citizen.bedrock.remap.ItemRemapper;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Codecs réseau des ItemStack (classes anonymes ItemStack$1 et ItemStack$2) :
 * remplace l'objet moddé par un objet vanilla qui garde son nom.
 */
@Mixin(targets = {"net.minecraft.world.item.ItemStack$1", "net.minecraft.world.item.ItemStack$2"})
public abstract class ItemStackStreamCodecMixin {
    @ModifyVariable(method = "encode(Lnet/minecraft/network/RegistryFriendlyByteBuf;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), argsOnly = true)
    private ItemStack citizenbedrock$toVanilla(ItemStack stack) {
        if (BedrockContext.ANY && BedrockContext.isActive()) {
            return ItemRemapper.toVanilla(stack);
        }
        return stack;
    }
}
