package fr.citizen.bedrock.bridge;

import fr.citizen.bedrock.BedrockConfig;
import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compte Xbox du serveur (programme MCXboxBroadcast, lancé à part) :
 * le serveur apparaît dans l'onglet Amis de Minecraft pour les joueurs qui ont ajouté ce compte en ami.
 * Il fonctionne dans son propre processus pour ne pas entrer en conflit avec les bibliothèques de ViaProxy.
 */
final class XboxAccount {
    private static final String ASSET = "MCXboxBroadcastStandalone.jar";
    private static final Pattern LOGIN_CODE = Pattern.compile("enter the code ([A-Z0-9-]+)");
    private static final Pattern AUTHENTICATED = Pattern.compile("Successfully authenticated as (.+?) \\(");

    private static volatile String loginCode = "";
    private static volatile String account = "";
    private static volatile Process process;
    private static volatile Thread thread;
    private static volatile boolean stopping;

    private XboxAccount() {
    }

    static String loginCode() {
        return loginCode;
    }

    static String account() {
        return account;
    }

    static synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        stopping = false;
        thread = new Thread(XboxAccount::runLoop, "CitizenBedrock-CompteXbox");
        thread.setDaemon(true);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = process;
            if (p != null && p.isAlive()) p.destroyForcibly();
        }, "CitizenBedrock-ArretCompteXbox"));
    }

    static synchronized void stop() {
        stopping = true;
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private static void runLoop() {
        int failures = 0;
        while (!stopping && failures < 5) {
            try {
                Path folder = BridgeManager.root().resolve("runtime").resolve("xbox");
                Files.createDirectories(folder);
                Path jar = folder.resolve(ASSET);
                BridgeManager.downloader().ensureBroadcast(jar, BedrockConfig.VALUES.miseAJourAuto, ASSET);
                writeConfig(folder.resolve("config.yml"));
                int exit = launch(folder, jar);
                if (stopping) break;
                CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Le compte Xbox s'est arrêté (code {}). Relance...", exit);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                CitizenBedrock.LOGGER.error("[Citizen Bedrock] Problème avec le compte Xbox : {}", e.toString());
            }
            failures++;
            try {
                Thread.sleep(15000L * failures);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Fichier géré par Citizen Bedrock : réécrit à chaque démarrage. Les jetons de connexion restent dans « cache ». */
    private static void writeConfig(Path file) throws IOException {
        BedrockConfig.Values cfg = BedrockConfig.VALUES;
        String yml = String.join("\n",
                "# Géré automatiquement par Citizen Bedrock (réécrit à chaque démarrage).",
                "session:",
                "  remote-address: 127.0.0.1",
                "  remote-port: \"" + cfg.portBedrock + "\"",
                "  query-server: true",
                "  config-fallback: true",
                "  session-info:",
                "    host-name: " + quote(cfg.motd2),
                "    world-name: " + quote(cfg.motd1),
                "friend-sync:",
                "  auto-friend: true",
                "  initial-invite: true",
                "config-version: 9",
                "");
        Files.writeString(file, yml, StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    private static int launch(Path folder, Path jar) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder builder = new ProcessBuilder(List.of(java, "-Xms32M", "-Xmx192M", "-Dfile.encoding=UTF-8",
                "-jar", jar.getFileName().toString()))
                .directory(folder.toFile())
                .redirectErrorStream(true);
        Process p = builder.start();
        process = p;
        // Le programme attend des commandes sur l'entrée standard : on la laisse ouverte.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = line.replaceAll("\u001B\\[[;\\d]*m", "");
                CitizenBedrock.LOGGER.info("[Xbox] {}", clean);
                watch(clean);
            }
        }
        int exit = p.waitFor();
        process = null;
        return exit;
    }

    private static void watch(String line) {
        Matcher code = LOGIN_CODE.matcher(line);
        if (code.find()) {
            loginCode = code.group(1);
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] COMPTE XBOX : ouvre https://www.microsoft.com/link et tape le code {} pour connecter le compte du serveur.", loginCode);
            notifyAdmins("§6[Citizen Bedrock] §eConnexion du compte Xbox du serveur : va sur §fmicrosoft.com/link§e et tape le code §f§l"
                    + loginCode + "§e (avec le compte Microsoft dédié au serveur).");
            return;
        }
        Matcher auth = AUTHENTICATED.matcher(line);
        if (auth.find()) {
            loginCode = "";
            account = auth.group(1);
            CitizenBedrock.LOGGER.info("[Citizen Bedrock] Compte Xbox du serveur connecté : {}. Les joueurs console l'ajoutent en ami.", account);
            notifyAdmins("§6[Citizen Bedrock] §aCompte Xbox connecté : §f" + account
                    + "§a. Les joueurs console l'ajoutent en ami puis rejoignent depuis l'onglet Amis.");
        }
    }

    private static void notifyAdmins(String message) {
        MinecraftServer s = BridgeManager.server();
        if (s == null) return;
        s.execute(() -> s.getPlayerList().getPlayers().stream()
                .filter(p -> s.getProfilePermissions(p.getGameProfile()) >= 3)
                .forEach(p -> p.sendSystemMessage(Component.literal(message))));
    }
}
