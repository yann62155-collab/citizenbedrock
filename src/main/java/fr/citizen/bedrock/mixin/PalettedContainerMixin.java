package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.BedrockContext;
import fr.citizen.bedrock.remap.SectionEncoder;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Réécrit les sections de chunk pour les consoles : blocs moddés remplacés,
 * et palette globale recalculée avec le nombre d'états vanilla (sinon la console lirait des données décalées).
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> {
    @Shadow @Final private IdMap<T> registry;

    @Shadow
    public abstract T get(int x, int y, int z);

    @Inject(method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$write(FriendlyByteBuf buffer, CallbackInfo ci) {
        if (BedrockContext.ANY && registry == (Object) Block.BLOCK_STATE_REGISTRY && BedrockContext.isActive()) {
            SectionEncoder.write(buffer, citizenbedrock$ids());
            ci.cancel();
        }
    }

    @Inject(method = "getSerializedSize()I", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$size(CallbackInfoReturnable<Integer> cir) {
        if (BedrockContext.ANY && registry == (Object) Block.BLOCK_STATE_REGISTRY && BedrockContext.isActive()) {
            cir.setReturnValue(SectionEncoder.size(citizenbedrock$ids()));
        }
    }

    /** Identifiants vanilla des 4096 blocs, dans l'ordre réseau (y, z, x). */
    private int[] citizenbedrock$ids() {
        int[] ids = new int[4096];
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    ids[i++] = SectionEncoder.vanillaId((BlockState) get(x, y, z));
                }
            }
        }
        return ids;
    }
}
