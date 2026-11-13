package fr.citizen.bedrock;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API publique : les autres mods Citizen peuvent savoir si un joueur est sur console.
 * Exemple : {@code if (BedrockPlayers.isBedrock(player)) { ... ouvrir un formulaire ... }}
 */
public final class BedrockPlayers {
    private static final Map<UUID, BedrockSession> ONLINE = new ConcurrentHashMap<>();

    private BedrockPlayers() {
    }

    public static boolean isBedrock(UUID uuid) {
        return ONLINE.containsKey(uuid);
    }

    public static boolean isBedrock(ServerPlayer player) {
        return isBedrock(player.getUUID());
    }

    @Nullable
    public static BedrockSession get(UUID uuid) {
        return ONLINE.get(uuid);
    }

    public static Collection<BedrockSession> all() {
        return ONLINE.values();
    }

    static void add(UUID uuid, BedrockSession session) {
        ONLINE.put(uuid, session);
    }

    static void remove(UUID uuid) {
        ONLINE.remove(uuid);
    }
}
