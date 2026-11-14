package fr.citizen.bedrock.forms;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;

import java.util.List;

/**
 * Cinématiques (mod citizencinematics) : une console ne peut pas lire la vidéo du mod.
 * Pendant la cinématique, le joueur console voit son titre en grand à l'écran, puis l'affichage
 * disparaît quand la cinématique se termine.
 */
public final class CinematicsBridge {
    private static final String OFFER = "fr.citizen.cinematics.network.OfferVideoPayload";
    private static final String STOP = "fr.citizen.cinematics.network.StopCinematicPayload";

    private CinematicsBridge() {
    }

    /** Paquets à envoyer à la console à la place, ou null si ce n'est pas un message de cinématique. */
    public static List<Packet<?>> translate(CustomPacketPayload payload) {
        String type = payload.getClass().getName();
        if (type.equals(STOP)) {
            fr.citizen.bedrock.web.CinemaPage.stopped();
            return List.of(new ClientboundClearTitlesPacket(true));
        }
        if (type.equals(OFFER)) {
            try {
                boolean play = (boolean) payload.getClass().getMethod("play").invoke(payload);
                if (!play) return List.of();
                String name = String.valueOf(payload.getClass().getMethod("name").invoke(payload));
                String sha = String.valueOf(payload.getClass().getMethod("sha256").invoke(payload));
                fr.citizen.bedrock.web.CinemaPage.started(sha, name);
                String title = name.replaceAll("\\.[A-Za-z0-9]{2,4}$", "").replace('_', ' ');
                return List.of(
                        new ClientboundSetTitlesAnimationPacket(20, 20 * 60 * 30, 20),
                        new ClientboundSetTitleTextPacket(Component.literal("§6§l" + title)),
                        new ClientboundSetSubtitleTextPacket(Component.literal(
                                fr.citizen.bedrock.web.WebServer.running()
                                        ? "§eÀ regarder sur : §f" + fr.citizen.bedrock.web.WebServer.address()
                                        : "§eCinématique en cours (visible sur PC)")));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return List.of();
            }
        }
        return null;
    }
}
