package fr.citizen.bedrock.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.citizen.bedrock.CitizenBedrock;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Télécharge et met à jour les deux programmes du pont :
 *  - Geyser (version ViaProxy) : traduit Bedrock <-> Java, mis à jour à chaque version Xbox ;
 *  - ViaProxy : convertit la dernière version Java vers la 1.21.1 du serveur.
 * Si Internet n'est pas disponible, les fichiers déjà présents sont utilisés.
 */
final class Downloader {
    private static final String GEYSER_INFO = "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest";
    private static final String GEYSER_JAR = GEYSER_INFO + "/downloads/viaproxy";
    private static final String VIAPROXY_INFO = "https://api.github.com/repos/ViaVersion/ViaProxy/releases/latest";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final Path versionsFile;
    private final Properties versions = new Properties();

    Downloader(Path runtime) {
        this.versionsFile = runtime.resolve("versions.properties");
        if (Files.exists(versionsFile)) {
            try (InputStream in = Files.newInputStream(versionsFile)) {
                versions.load(in);
            } catch (IOException ignored) {
                // fichier abîmé : on retéléchargera
            }
        }
    }

    String installed(String name) {
        return versions.getProperty(name, "?");
    }

    void ensureGeyser(Path target, boolean checkUpdates) throws IOException, InterruptedException {
        if (Files.exists(target) && !checkUpdates) return;
        try {
            JsonObject info = getJson(GEYSER_INFO).getAsJsonObject();
            String build = info.get("version").getAsString() + "-b" + info.get("build").getAsString();
            String sha256 = info.getAsJsonObject("downloads").getAsJsonObject("viaproxy").get("sha256").getAsString();
            if (Files.exists(target) && build.equals(versions.getProperty("geyser"))) {
                return;
            }
            CitizenBedrock.LOGGER.info("[Citizen Bedrock] Téléchargement de Geyser {}...", build);
            download(GEYSER_JAR, target, sha256);
            versions.setProperty("geyser", build);
            saveVersions();
        } catch (IOException e) {
            if (!Files.exists(target)) throw e;
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Vérification de mise à jour de Geyser impossible ({}), version actuelle conservée.", e.getMessage());
        }
    }

    void ensureViaProxy(Path target, boolean checkUpdates) throws IOException, InterruptedException {
        if (Files.exists(target) && !checkUpdates) return;
        try {
            JsonObject info = getJson(VIAPROXY_INFO).getAsJsonObject();
            String tag = info.get("tag_name").getAsString();
            if (Files.exists(target) && tag.equals(versions.getProperty("viaproxy"))) {
                return;
            }
            String url = null;
            JsonArray assets = info.getAsJsonArray("assets");
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (name.startsWith("ViaProxy-") && name.endsWith(".jar") && !name.contains("sources")
                        && !name.contains("javadoc") && !name.contains("java8")) {
                    url = asset.get("browser_download_url").getAsString();
                    break;
                }
            }
            if (url == null) throw new IOException("Aucun fichier ViaProxy dans la version " + tag);
            CitizenBedrock.LOGGER.info("[Citizen Bedrock] Téléchargement de ViaProxy {}...", tag);
            download(url, target, null);
            versions.setProperty("viaproxy", tag);
            saveVersions();
        } catch (IOException e) {
            if (!Files.exists(target)) throw e;
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Vérification de mise à jour de ViaProxy impossible ({}), version actuelle conservée.", e.getMessage());
        }
    }

    private JsonElement getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CitizenBedrock/1.0")
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " pour " + url);
        }
        return JsonParser.parseString(response.body());
    }

    private void download(String url, Path target, String expectedSha256) throws IOException, InterruptedException {
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "CitizenBedrock/1.0")
                .build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp);
            throw new IOException("HTTP " + response.statusCode() + " pour " + url);
        }
        if (expectedSha256 != null) {
            String actual = sha256(temp);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(temp);
                throw new IOException("Fichier corrompu (empreinte SHA-256 différente) : " + url);
            }
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private void saveVersions() throws IOException {
        try (var out = Files.newOutputStream(versionsFile)) {
            versions.store(out, "Versions installees par Citizen Bedrock");
        }
    }
}
