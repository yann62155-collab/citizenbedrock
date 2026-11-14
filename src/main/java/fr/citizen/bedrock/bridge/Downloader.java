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
    private static final String BROADCAST_INFO = "https://api.github.com/repos/MCXboxBroadcast/Broadcaster/releases/latest";
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
            if (!Files.exists(target)) {
                // API GitHub limitée (HTTP 403) : on passe par la page publique des versions
                String tag = latestTagWithoutApi("ViaVersion/ViaProxy");
                String version = tag.startsWith("v") ? tag.substring(1) : tag;
                CitizenBedrock.LOGGER.info("[Citizen Bedrock] Téléchargement de ViaProxy {} (sans l'API GitHub)...", tag);
                download("https://github.com/ViaVersion/ViaProxy/releases/download/" + tag + "/ViaProxy-" + version + ".jar", target, null);
                versions.setProperty("viaproxy", tag);
                saveVersions();
                return;
            }
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Vérification de mise à jour de ViaProxy impossible ({}), version actuelle conservée.", e.getMessage());
        }
    }

    /** Module « compte Xbox » (MCXboxBroadcast) : le serveur apparaît dans l'onglet Amis des consoles. */
    void ensureBroadcast(Path target, boolean checkUpdates, String assetName) throws IOException, InterruptedException {
        if (Files.exists(target) && !checkUpdates) return;
        try {
            JsonObject info = getJson(BROADCAST_INFO).getAsJsonObject();
            String tag = info.get("tag_name").getAsString();
            if (Files.exists(target) && tag.equals(versions.getProperty("xboxbroadcast"))) {
                return;
            }
            String url = null;
            for (JsonElement element : info.getAsJsonArray("assets")) {
                JsonObject asset = element.getAsJsonObject();
                if (assetName.equals(asset.get("name").getAsString())) {
                    url = asset.get("browser_download_url").getAsString();
                    break;
                }
            }
            if (url == null) throw new IOException("Aucun fichier " + assetName + " dans la version " + tag);
            CitizenBedrock.LOGGER.info("[Citizen Bedrock] Téléchargement du module compte Xbox (build {})...", tag);
            download(url, target, null);
            versions.setProperty("xboxbroadcast", tag);
            saveVersions();
        } catch (IOException e) {
            if (!Files.exists(target)) {
                String tag = latestTagWithoutApi("MCXboxBroadcast/Broadcaster");
                CitizenBedrock.LOGGER.info("[Citizen Bedrock] Téléchargement du module compte Xbox (build {}, sans l'API GitHub)...", tag);
                download("https://github.com/MCXboxBroadcast/Broadcaster/releases/download/" + tag + "/" + assetName, target, null);
                versions.setProperty("xboxbroadcast", tag);
                saveVersions();
                return;
            }
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Vérification de mise à jour du module compte Xbox impossible ({}), version actuelle conservée.", e.getMessage());
        }
    }

    /** Dernière version d'un dépôt GitHub via la redirection de github.com/.../releases/latest (pas de limite d'API). */
    private static String latestTagWithoutApi(String repo) throws IOException, InterruptedException {
        HttpClient noRedirect = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://github.com/" + repo + "/releases/latest"))
                .timeout(Duration.ofSeconds(30)).header("User-Agent", "CitizenBedrock/1.0")
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Void> response = noRedirect.send(request, HttpResponse.BodyHandlers.discarding());
        String location = response.headers().firstValue("location").orElse("");
        int i = location.indexOf("/releases/tag/");
        if (i < 0) throw new IOException("Version introuvable pour " + repo + " (HTTP " + response.statusCode() + ")");
        return java.net.URLDecoder.decode(location.substring(i + "/releases/tag/".length()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonElement getJson(String url) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CitizenBedrock/1.0")
                .header("Accept", "application/json");
        String token = System.getenv("GITHUB_TOKEN"); // seulement pendant la compilation sur GitHub
        if (token != null && !token.isBlank() && url.startsWith("https://api.github.com/")) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.build();
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
