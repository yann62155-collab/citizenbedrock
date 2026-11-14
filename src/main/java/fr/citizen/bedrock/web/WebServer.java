package fr.citizen.bedrock.web;

import fr.citizen.bedrock.BedrockConfig;
import fr.citizen.bedrock.CitizenBedrock;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Petit serveur web (TCP) pour les joueurs console : page des cinématiques.
 * Il partage le numéro de port du chat vocal : Simple Voice Chat utilise l'UDP, la page le TCP.
 */
public final class WebServer {
    private static ServerSocket socket;
    private static ExecutorService workers;

    private WebServer() {
    }

    public static synchronized void start() {
        int port = BedrockConfig.VALUES.portCinematiques;
        if (port <= 0 || socket != null) return;
        if (port == 30053 && net.neoforged.fml.ModList.get().isLoaded("citizenvoice")) {
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Page des cinématiques NON lancée : 30053 est le port du site vocal. Mets un autre port dans \"portCinematiques\".");
            return;
        }
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Page web impossible à ouvrir sur le port TCP {} : {}", port, e.getMessage());
            socket = null;
            return;
        }
        workers = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "CitizenBedrock-Web");
            t.setDaemon(true);
            return t;
        });
        Thread accept = new Thread(() -> {
            while (socket != null && !socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    workers.submit(() -> handle(client));
                } catch (IOException e) {
                    if (socket == null || socket.isClosed()) break;
                }
            }
        }, "CitizenBedrock-WebAccept");
        accept.setDaemon(true);
        accept.start();
        CitizenBedrock.LOGGER.info("[Citizen Bedrock] Page des cinématiques : http://{}:{}", BedrockConfig.VALUES.adressePublique, port);
    }

    public static synchronized void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        if (workers != null) workers.shutdownNow();
    }

    public static boolean running() {
        return socket != null && !socket.isClosed();
    }

    public static String address() {
        return BedrockConfig.VALUES.adressePublique + ":" + BedrockConfig.VALUES.portCinematiques;
    }

    /** Requête HTTP/1.1 minimale : GET uniquement, une requête par connexion. */
    private static void handle(Socket client) {
        try (client) {
            client.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String range = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) range = line.substring(6).trim();
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) {
                respond(client.getOutputStream(), 405, "text/plain", "Methode non autorisee".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            CinemaPage.route(path, range, client.getOutputStream());
        } catch (IOException | RuntimeException ignored) {
            // client parti en cours de route
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("ligne trop longue");
        }
        return c == -1 && sb.isEmpty() ? null : sb.toString();
    }

    static void respond(OutputStream out, int status, String type, byte[] body, String extraHeaders) throws IOException {
        String reason = switch (status) {
            case 200 -> "OK";
            case 206 -> "Partial Content";
            case 404 -> "Not Found";
            case 416 -> "Range Not Satisfiable";
            default -> "Error";
        };
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + (extraHeaders == null ? "" : extraHeaders)
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
