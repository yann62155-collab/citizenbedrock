package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.forms.CitizenExtrasBridge;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Certains mods (ex. Citizen Boutique) n'ouvrent leur écran que si le joueur « a le mod ».
 * Pour les joueurs console, on répond oui sur ces canaux précis : c'est Citizen Bedrock qui les affiche.
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class NetworkRegistryChannelMixin {
    @Inject(method = "hasChannel(Lnet/minecraft/network/Connection;Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/resources/ResourceLocation;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void citizenbedrock$bridgedChannels(Connection connection, @Nullable ConnectionProtocol protocol,
                                                       ResourceLocation payloadId, CallbackInfoReturnable<Boolean> cir) {
        if (CitizenExtrasBridge.HANDLED_CHANNELS.contains(payloadId.toString()) && BedrockSession.get(connection) != null) {
            cir.setReturnValue(true);
        }
    }
}
