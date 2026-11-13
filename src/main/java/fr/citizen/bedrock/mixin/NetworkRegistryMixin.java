package fr.citizen.bedrock.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import fr.citizen.bedrock.BedrockSession;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.neoforged.neoforge.network.negotiation.NegotiationResult;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NeoForge refuse normalement les clients sans mods quand des mods (Create, etc.) ont des canaux obligatoires.
 * Pour les joueurs console, on accepte quand même : ces canaux ne leur seront simplement jamais envoyés.
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class NetworkRegistryMixin {
    @WrapOperation(
            method = {
                    "initializeOtherConnection(Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;)Z",
                    "initializeNeoForgeConnection(Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;Ljava/util/Map;)V"
            },
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/network/negotiation/NegotiationResult;success()Z"))
    private static boolean citizenbedrock$acceptBedrock(NegotiationResult result, Operation<Boolean> original,
                                                        @Local(argsOnly = true) ServerConfigurationPacketListener listener) {
        return original.call(result) || BedrockSession.get(listener.getConnection()) != null;
    }
}
