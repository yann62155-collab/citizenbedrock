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

/**
 * Skin des joueurs console vu par les joueurs PC : Geyser envoie le skin Bedrock du joueur au service
 * public de GeyserMC, qui le convertit en skin Java signé. On le récupère à la connexion (cela peut
 * prendre une reconnexion la toute première fois, le temps que le service l'ait reçu).
 */
public final class SkinFetcher {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private SkinFetcher() {
    }

    public static void apply(GameProfile profile, String xuid) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.geysermc.org/v2/skin/" + xuid))
                    .timeout(Duration.ofMillis(2500))
                    .header("User-Agent", "CitizenBedrock/1.0")
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().isBlank()) return;
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("value") || !json.has("signature")) return;
            profile.getProperties().removeAll("textures");
            profile.getProperties().put("textures", new Property("textures",
                    json.get("value").getAsString(), json.get("signature").getAsString()));
        } catch (Exception e) {
            CitizenBedrock.LOGGER.debug("[Citizen Bedrock] Skin indisponible pour {} : {}", xuid, e.toString());
        } 
    }
}
