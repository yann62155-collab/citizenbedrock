package fr.citizen.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skin des joueurs console vu par les joueurs PC (service public de GeyserMC).
 * La recherche se fait en arrière-plan dès le début de la connexion : rien n'est jamais bloqué.
 * Si le skin n'est pas encore arrivé au moment du login, il sera utilisé à la connexion suivante.
 */
public final class SkinFetcher {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Map<String, Property> CACHE = new ConcurrentHashMap<>();

    private SkinFetcher() {
    }

    /** Lance la recherche du skin sans attendre (appelé à la poignée de main). */
    public static void prefetch(String xuid) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.geysermc.org/v2/skin/" + xuid))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "CitizenBedrock/1.0")
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            try {
                if (response.statusCode() != 200 || response.body().isBlank()) return;
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!json.has("value") || !json.has("signature")) return;
                CACHE.put(xuid, new Property("textures", json.get("value").getAsString(), json.get("signature").getAsString()));
            } catch (RuntimeException ignored) {
                // réponse inattendue : pas de skin
            }
        });
    }

    /** Applique le skin s'il est déjà connu (aucune attente). */
    public static void apply(GameProfile profile, String xuid) {
        Property skin = CACHE.get(xuid);
        if (skin == null) return;
        profile.getProperties().removeAll("textures");
        profile.getProperties().put("textures", skin);
    }
}
