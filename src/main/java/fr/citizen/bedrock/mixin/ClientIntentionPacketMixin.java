package fr.citizen.bedrock.mixin;

import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Minecraft limite l'adresse de la poignée de main à 255 caractères ;
 * les données chiffrées du joueur console sont plus longues.
 */
@Mixin(ClientIntentionPacket.class)
public abstract class ClientIntentionPacketMixin {
    @ModifyConstant(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", constant = @Constant(intValue = 255))
    private static int citizenbedrock$longerHostName(int original) {
        return Short.MAX_VALUE;
    }
}
