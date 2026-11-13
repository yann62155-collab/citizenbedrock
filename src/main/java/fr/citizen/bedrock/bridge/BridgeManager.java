package fr.citizen.bedrock.bridge;

import fr.citizen.bedrock.BedrockConfig;
import fr.citizen.bedrock.CitizenBedrock;
import fr.citizen.bedrock.floodgate.FloodgateKey;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lance et surveille le pont Bedrock (ViaProxy + Geyser) dans un processus séparé.
 * Tout est rangé dans le dossier « citizenbedrock » à la racine du serveur.
 * Si le pont s'arrête, il est relancé automatiquement.
 */
public final class BridgeManager {
    public enum State { DESACTIVE, PREPARATION, DEMARRAGE, PRET, ARRETE, ERREUR }

    private static volatile State state = State.DESACTIVE;
    private static volatile String lastError = "";
    private static volatile Process process;
    private static volatile boolean stopping;
    private static volatile Thread thread;
    private static volatile MinecraftServer server;
    private static Downloader downloader;
    private static boolean hookInstalled;

    private BridgeManager() {
    }

    public static State state() {
        return state;
    }

    public static String lastError() {
        return lastError;
    }

    public static String versions() {
        return downloader == null ? "?" : "Geyser " + downloader.installed("geyser") + ", ViaProxy " + downloader.installed("viaproxy")
                + (BedrockConfig.VALUES.compteXboxAmis ? ", compte Xbox " + downloader.installed("xboxbroadcast") : "");
    }

    public static String xboxLoginCode() {
        return XboxAccount.loginCode();
    }

    public static String xboxAccount() {
        return XboxAccount.account();
    }

    static Downloader downloader() {
        return downloader;
    }

    static MinecraftServer server() {
        return server;
    }

    public static Path root() {
        return FMLPaths.GAMEDIR.get().resolve(CitizenBedrock.MODID);
    }

    public static synchronized void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        if (!BedrockConfig.VALUES.pontActive) {
            state = State.DESACTIVE;
            CitizenBedrock.LOGGER.info("[Citizen Bedrock] Pont désactivé dans la configuration.");
            return;
        }
        if (thread != null && thread.isAlive()) {
            return;
        }
        stopping = false;
        if (!hookInstalled) {
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Process p = process;
                if (p != null && p.isAlive()) p.destroyForcibly();
            }, "CitizenBedrock-ArretPont"));
        }
        thread = new Thread(BridgeManager::runLoop, "CitizenBedrock-Pont");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        stopping = true;
        XboxAccount.stop();
        Process p = process;
        if (p != null && p.isAlive()) {
            CitizenBedrock.LOGGER.info("[Citizen Bedrock] Arrêt du pont Bedrock...");
            p.destroy();
            try {
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
        state = State.ARRETE;
    }

    /** Relance le pont (commande /citizenbedrock relancer). */
    public static void restart() {
        MinecraftServer s = server;
        stop();
        Thread t = thread;
        if (t != null) {
            try {
                t.join(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        if (s != null) start(s);
    }

    private static void runLoop() {
        int failures = 0;
        long windowStart = System.currentTimeMillis();
        while (!stopping) {
            try {
                state = State.PREPARATION;
                List<String> command = prepare();
                if (stopping) break;
                state = State.DEMARRAGE;
                int exit = launch(command);
                if (stopping) break;
                lastError = "Le pont s'est arrêté (code " + exit + ")";
                CitizenBedrock.LOGGER.warn("[Citizen Bedrock] {}. Relance...", lastError);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                CitizenBedrock.LOGGER.error("[Citizen Bedrock] Problème avec le pont Bedrock : {}", lastError, e);
            }
            if (stopping) break;
            state = State.ERREUR;
            if (System.currentTimeMillis() - windowStart > TimeUnit.MINUTES.toMillis(10)) {
                windowStart = System.currentTimeMillis();
                failures = 0;
            }
            failures++;
            if (failures > 5) {
                CitizenBedrock.LOGGER.error("[Citizen Bedrock] Le pont a échoué 5 fois en 10 minutes : abandon. Utilise /citizenbedrock relancer après correction.");
                return;
            }
            try {
                Thread.sleep(Math.min(60, 5L * failures) * 1000L);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (stopping) state = State.ARRETE;
    }

    private static List<String> prepare() throws IOException, InterruptedException {
        BedrockConfig.Values cfg = BedrockConfig.VALUES;
        Path runtime = root().resolve("runtime");
        Path plugins = runtime.resolve("plugins");
        Path geyserFolder = plugins.resolve("Geyser");
        Files.createDirectories(geyserFolder);

        if (downloader == null) downloader = new Downloader(runtime);
        Path viaProxyJar = runtime.resolve("ViaProxy.jar");
        Path geyserJar = plugins.resolve("Geyser-ViaProxy.jar");
        downloader.ensureViaProxy(viaProxyJar, cfg.miseAJourAuto);
        downloader.ensureGeyser(geyserJar, cfg.miseAJourAuto);

        // Ancienne version du module (extension Geyser) : remplacée par le programme séparé XboxAccount
        Files.deleteIfExists(geyserFolder.resolve("extensions").resolve("MCXboxBroadcastExtension.jar"));

        // Clé partagée : c'est elle qui prouve au serveur que la connexion vient bien du pont.
        FloodgateKey.get();
        Files.copy(FloodgateKey.file(), geyserFolder.resolve("key.pem"), StandardCopyOption.REPLACE_EXISTING);

        String targetHost = server.getLocalIp() == null || server.getLocalIp().isBlank() ? "127.0.0.1" : server.getLocalIp();
        int internalPort = freeLocalPort();
        String yml = String.join("\n",
                "# Fichier géré automatiquement par Citizen Bedrock : il est réécrit à chaque démarrage.",
                "bind-address: 127.0.0.1:" + internalPort,
                "target-address: " + targetHost + ":" + server.getPort(),
                "target-version: 1.21-1.21.1",
                "proxy-online-mode: false",
                "bungeecord-player-info-passthrough: true",
                "ignore-protocol-translation-errors: true",
                "suppress-client-protocol-errors: true",
                "log-ips: false",
                "");
        Files.writeString(runtime.resolve("viaproxy.yml"), yml, StandardCharsets.UTF_8);

        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Xms64M");
        command.add("-Xmx" + Math.max(256, cfg.memoirePontMo) + "M");
        command.add("-Djava.awt.headless=true");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-jar");
        command.add(viaProxyJar.getFileName().toString());
        command.add("config");
        command.add("viaproxy.yml");
        return command;
    }

    private static int launch(List<String> command) throws IOException, InterruptedException {
        BedrockConfig.Values cfg = BedrockConfig.VALUES;
        Path runtime = root().resolve("runtime");
        ProcessBuilder builder = new ProcessBuilder(command).directory(runtime.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        // Réglages de Geyser passés par variables d'environnement (prioritaires sur son fichier de config).
        env.put("GEYSER_BEDROCK_ADDRESS", "0.0.0.0");
        env.put("GEYSER_BEDROCK_PORT", String.valueOf(cfg.portBedrock));
        env.put("GEYSER_BEDROCK_TRANSPORT", cfg.portWebrtc > 0 ? "both" : "nethernet");
        if (cfg.portWebrtc > 0) env.put("GEYSER_BEDROCK_WEBRTC_PORT", String.valueOf(cfg.portWebrtc));
        if (cfg.adressePublique != null && !cfg.adressePublique.isBlank()) {
            env.put("GEYSER_BEDROCK_SIGNALING_ADVERTISE_ADDRESSES", cfg.adressePublique);
        }
        env.put("GEYSER_MOTD_PRIMARY_MOTD", cfg.motd1);
        env.put("GEYSER_MOTD_SECONDARY_MOTD", cfg.motd2);
        env.put("GEYSER_MOTD_PASSTHROUGH_MOTD", "false");

        CitizenBedrock.LOGGER.info("[Citizen Bedrock] Démarrage du pont Bedrock sur le port {} (UDP/TCP) et {} (UDP)...",
                cfg.portBedrock, cfg.portWebrtc);
        Process p = builder.start();
        process = p;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = line.replaceAll("\u001B\\[[;\\d]*m", "");
                if (clean.contains("Done (") || clean.contains("Started Geyser")) {
                    if (state != State.PRET) {
                        state = State.PRET;
                        lastError = "";
                        CitizenBedrock.LOGGER.info("[Citizen Bedrock] Pont Bedrock prêt : les joueurs console peuvent rejoindre {}:{}.",
                                cfg.adressePublique, cfg.portBedrock);
                        if (cfg.compteXboxAmis) XboxAccount.start();
                    }
                }
                CitizenBedrock.LOGGER.info("[Pont] {}", clean);
            }
        }
        int exit = p.waitFor();
        process = null;
        return exit;
    }

    private static int freeLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
