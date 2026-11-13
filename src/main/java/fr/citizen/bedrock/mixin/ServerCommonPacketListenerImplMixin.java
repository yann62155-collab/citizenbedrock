package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.remap.PacketFilter;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Point de passage de tous les paquets envoyés à un joueur : pour les joueurs console,
 * on convertit ou on retire ce que la console ne saurait pas afficher.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Shadow @Final protected Connection connection;

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable PacketSendListener listener);

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$filter(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
        BedrockSession session = BedrockSession.get(connection);
        if (session == null || PacketFilter.isReentrant()) {
            return;
        }
        Packet<?> converted = PacketFilter.process(packet, session, (ServerCommonPacketListenerImpl) (Object) this);
        if (converted == packet) {
            return;
        }
        ci.cancel();
        if (converted != null) {
            PacketFilter.runReentrant(() -> send(converted, listener));
        }
    }
}
