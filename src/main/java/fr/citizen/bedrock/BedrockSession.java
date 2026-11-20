package fr.citizen.bedrock;

import com.mojang.authlib.GameProfile;
import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import net.minecraft.network.Connection;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * État d'une connexion console. Attaché au canal réseau dès la poignée de main.
 */
public final class BedrockSession {
    public static final AttributeKey<BedrockSession> KEY = AttributeKey.valueOf("citizenbedrock:session");

    private final BedrockData data;
    private final GameProfile profile;
    /** Entités moddées cachées à ce joueur (la console ne saurait pas les afficher). */
    public final IntSet hiddenEntities = IntSets.synchronize(new IntOpenHashSet());
    /** Créatures moddées affichées sous une autre forme : id réseau -> dernier index de données autorisé. */
    public final java.util.Map<Integer, Integer> substitutedEntities = new java.util.concurrent.ConcurrentHashMap<>();
    /** Créatures affichées comme des personnages (joueurs factices) : id réseau -> UUID du profil. */
    /** Créatures affichées à une autre taille (attribut « scale ») : id -> taille. */
    public final java.util.Map<Integer, Double> entityScales = new java.util.concurrent.ConcurrentHashMap<>();
    /** Créatures montrées en dragon de l'End : sa tête est à l'opposé du sens habituel, on retourne l'orientation. */
    public final IntSet flippedYaw = IntSets.synchronize(new IntOpenHashSet());
    public final java.util.Map<Integer, UUID> fakePlayers = new java.util.concurrent.ConcurrentHashMap<>();
    /** Menus moddés affichés comme un coffre : id du menu -> correspondance des cases. */
    public final java.util.Map<Integer, fr.citizen.bedrock.remap.ContainerBridge.Mapping> containerMappings = new java.util.concurrent.ConcurrentHashMap<>();
    /** Menus moddés en cours d'ouverture (le coffre sera envoyé à la fin du tick). */
    public final IntSet pendingContainers = IntSets.synchronize(new IntOpenHashSet());
    /** Action à proposer quand le joueur ferme certains menus (envoi de colis, machine à clés...). */
    public final java.util.Map<Integer, Runnable> closeActions = new java.util.concurrent.ConcurrentHashMap<>();
    /** Derniers messages du chat reçus par ce joueur (pour /historique). */
    public final java.util.Deque<String> chatHistory = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public void remember(String line) {
        String time = java.time.LocalTime.now(java.time.ZoneId.of("Europe/Paris")).withNano(0).toString().substring(0, 5);
        chatHistory.addLast("§8" + time + "§r " + line);
        while (chatHistory.size() > 150) chatHistory.pollFirst();
    }

    /** Menus moddés fermés pour ce joueur. */
    public final IntSet hiddenContainers = IntSets.synchronize(new IntOpenHashSet());

    public BedrockSession(BedrockData data) {
        this.data = data;
        this.profile = new GameProfile(new UUID(0L, Long.parseLong(data.xuid())), javaName(data.username()));
    }

    /** Pseudo Java : préfixe + gamertag sans espaces, 16 caractères maximum. */
    public static String javaName(String gamertag) {
        String prefix = BedrockConfig.VALUES.prefixePseudo == null ? "" : BedrockConfig.VALUES.prefixePseudo;
        StringBuilder sb = new StringBuilder(prefix);
        for (char c : gamertag.toCharArray()) {
            if (c == ' ') sb.append('_');
            else if (c > 32 && c != 127 && c != '§') sb.append(c);
        }
        String name = sb.toString();
        // 16 caractères maximum, en comptant en octets UTF-8 sûrs
        while (name.length() > 16 || name.getBytes(StandardCharsets.UTF_8).length > 16 * 4) {
            name = name.substring(0, name.length() - 1);
        }
        return name.isEmpty() ? "BedrockPlayer" : name;
    }

    public BedrockData data() {
        return data;
    }

    public GameProfile profile() {
        return profile;
    }

    @Nullable
    public static BedrockSession get(@Nullable Connection connection) {
        if (connection == null || connection.channel() == null) {
            return null;
        }
        return connection.channel().attr(KEY).get();
    }

    public static void attach(Connection connection, BedrockSession session) {
        connection.channel().attr(KEY).set(session);
        BedrockContext.sessionOpened();
        connection.channel().closeFuture().addListener(f -> BedrockContext.sessionClosed());
    }
}
