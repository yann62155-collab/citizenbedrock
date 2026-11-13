package fr.citizen.bedrock.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * Pendant la configuration, certains mods ajoutent des étapes qui attendent une réponse que seul
 * un client PC moddé sait envoyer : un joueur console resterait bloqué pour toujours.
 * Pour les joueurs console, on passe directement toutes les étapes ajoutées par des mods.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin {
    @Shadow
    public abstract void finishCurrentTask(ConfigurationTask.Type type);

    @WrapOperation(method = "startNextTask",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ConfigurationTask;start(Ljava/util/function/Consumer;)V"))
    private void citizenbedrock$skipModdedTasks(ConfigurationTask task, Consumer<Packet<?>> sender, Operation<Void> original) {
        Connection connection = ((ServerCommonPacketListenerImplAccessor) (Object) this).citizenbedrock$connection();
        String id = String.valueOf(task.type().id());
        boolean vanilla = !id.contains(":") || id.startsWith("minecraft:");
        if (!vanilla && BedrockSession.get(connection) != null) {
            CitizenBedrock.LOGGER.debug("[Citizen Bedrock] Étape de configuration « {} » passée pour un joueur console.", id);
            finishCurrentTask(task.type());
            return;
        }
        original.call(task, sender);
    }
}
