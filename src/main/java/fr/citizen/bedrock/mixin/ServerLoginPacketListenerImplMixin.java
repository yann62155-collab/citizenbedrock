package fr.citizen.bedrock.mixin;

import com.mojang.authlib.GameProfile;
import fr.citizen.bedrock.BedrockSession;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Les joueurs console sont déjà vérifiés par Xbox Live (côté pont) : on saute la vérification Mojang
 * et on leur donne un profil stable (UUID basé sur le XUID, comme Floodgate).
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Shadow @Final Connection connection;
    @Shadow @Nullable String requestedUsername;

    @Shadow
    private void startClientVerification(GameProfile authenticatedProfile) {
        throw new AssertionError();
    }

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$bedrockLogin(ServerboundHelloPacket packet, CallbackInfo ci) {
        BedrockSession session = BedrockSession.get(connection);
        if (session == null) {
            return;
        }
        ci.cancel();
        GameProfile profile = session.profile();
        this.requestedUsername = profile.getName();
        startClientVerification(profile);
    }
}
