package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.BedrockData;
import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.CitizenBedrock;
import fr.citizen.bedrock.floodgate.FloodgateCipher;
import fr.citizen.bedrock.floodgate.FloodgateKey;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lit les données chiffrées ajoutées par le pont à l'adresse de connexion,
 * vérifie qu'elles ont bien été produites avec notre clé, puis marque la connexion comme « console ».
 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class ServerHandshakePacketListenerImplMixin {
    @Shadow @Final private Connection connection;

    @Shadow
    public abstract void handleIntention(ClientIntentionPacket packet);

    @Inject(method = "handleIntention", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$readBedrockData(ClientIntentionPacket packet, CallbackInfo ci) {
        String host = packet.hostName();
        int nul = host.indexOf('\0');
        if (nul < 0) {
            return;
        }
        String rest = host.substring(nul + 1);
        int end = rest.indexOf('\0');
        String candidate = end < 0 ? rest : rest.substring(0, end);
        if (!FloodgateCipher.looksLikeFloodgate(candidate)) {
            return;
        }
        ci.cancel();
        try {
            BedrockData data = BedrockData.parse(FloodgateCipher.decrypt(candidate, FloodgateKey.get()));
            BedrockSession.attach(connection, new BedrockSession(data));
            fr.citizen.bedrock.SkinFetcher.prefetch(data.xuid());
        } catch (Exception e) {
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Connexion console refusée depuis {} : données invalides ({}).",
                    connection.getLoggableAddress(false), e.toString());
            connection.disconnect(Component.literal("Connexion console refusée : données invalides. Préviens un admin."));
            return;
        }
        // On reprend le traitement normal avec l'adresse nettoyée.
        handleIntention(new ClientIntentionPacket(packet.protocolVersion(), host.substring(0, nul), packet.port(), packet.intention()));
    }
}
