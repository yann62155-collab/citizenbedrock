package fr.citizen.bedrock.web;

import fr.citizen.bedrock.CitizenBedrock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lecture des cinématiques (fichiers .cine du mod citizencinematics : images JPEG + son Ogg)
 * dans un navigateur, synchronisée avec la cinématique en cours sur le serveur.
 */
public final class CinemaPage {
    public static final int FPS = 24;
    private static final int BATCH = 48;

    private static volatile String currentSha;
    private static volatile String currentName = "";
    private static volatile long startedAt;
    private static final Map<String, Video> CACHE = new ConcurrentHashMap<>();

    private record Video(ZipFile zip, List<ZipEntry> frames, ZipEntry audio) {
    }

    private CinemaPage() {
    }

    /** Appelé quand une cinématique commence pour les joueurs console. */
    public static void started(String sha, String name) {
        if (sha.equals(currentSha) && System.currentTimeMillis() - startedAt < 3000) return; // même lancement, plusieurs joueurs
        currentSha = sha;
        currentName = name;
        startedAt = System.currentTimeMillis();
    }

    public static void stopped() {
        currentSha = null;
    }

    // ------------------------------------------------------------------ routes

    static void route(String path, String range, OutputStream out) throws IOException {
        if (path.equals("/") || path.equals("/index.html")) {
            WebServer.respond(out, 200, "text/html; charset=utf-8", PageHtml.HTML.getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        if (path.equals("/status")) {
            String sha = currentSha;
            int frames = 0;
            if (sha != null) {
                Video v = video(sha);
                frames = v == null ? 0 : v.frames().size();
            }
            String json = "{\"playing\":" + (sha != null && frames > 0)
                    + ",\"sha\":\"" + (sha == null ? "" : sha) + "\""
                    + ",\"name\":\"" + escape(currentName) + "\""
                    + ",\"frames\":" + frames + ",\"fps\":" + FPS
                    + ",\"startedAt\":" + startedAt + ",\"now\":" + System.currentTimeMillis() + "}";
            WebServer.respond(out, 200, "application/json", json.getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        // /v/<sha>/b/<index>  ou  /v/<sha>/audio
        String[] p = path.split("/");
        if (p.length >= 4 && p[1].equals("v")) {
            Video v = video(p[2]);
            if (v == null) {
                WebServer.respond(out, 404, "text/plain", new byte[0], null);
                return;
            }
            if (p[3].equals("audio")) {
                serveAudio(v, range, out);
                return;
            }
            if (p[3].equals("b") && p.length >= 5) {
                int start = Math.max(0, Integer.parseInt(p[4]));
                ByteArrayOutputStream batch = new ByteArrayOutputStream();
                int end = Math.min(v.frames().size(), start + BATCH);
                for (int i = start; i < end; i++) {
                    byte[] img = read(v.zip(), v.frames().get(i));
                    batch.write((img.length >>> 24) & 0xFF);
                    batch.write((img.length >>> 16) & 0xFF);
                    batch.write((img.length >>> 8) & 0xFF);
                    batch.write(img.length & 0xFF);
                    batch.write(img);
                }
                WebServer.respond(out, 200, "application/octet-stream", batch.toByteArray(), null);
                return;
            }
        }
        WebServer.respond(out, 404, "text/plain", "Introuvable".getBytes(StandardCharsets.UTF_8), null);
    }

    private static void serveAudio(Video v, String range, OutputStream out) throws IOException {
        if (v.audio() == null) {
            WebServer.respond(out, 404, "text/plain", new byte[0], null);
            return;
        }
        byte[] audio = read(v.zip(), v.audio());
        if (range != null && range.startsWith("bytes=")) {
            String[] r = range.substring(6).split("-", 2);
            long from = r[0].isEmpty() ? 0 : Long.parseLong(r[0]);
            long to = r.length > 1 && !r[1].isEmpty() ? Long.parseLong(r[1]) : audio.length - 1;
            if (from >= audio.length) {
                WebServer.respond(out, 416, "text/plain", new byte[0], "Content-Range: bytes */" + audio.length + "\r\n");
                return;
            }
            to = Math.min(to, audio.length - 1);
            byte[] part = java.util.Arrays.copyOfRange(audio, (int) from, (int) to + 1);
            WebServer.respond(out, 206, "audio/ogg", part,
                    "Accept-Ranges: bytes\r\nContent-Range: bytes " + from + "-" + to + "/" + audio.length + "\r\n");
            return;
        }
        WebServer.respond(out, 200, "audio/ogg", audio, "Accept-Ranges: bytes\r\n");
    }

    // ------------------------------------------------------------------ fichiers vidéo

    private static Video video(String sha) {
        if (sha == null || !sha.matches("[0-9a-fA-F]{16,128}")) return null;
        return CACHE.computeIfAbsent(sha, CinemaPage::open);
    }

    private static Video open(String sha) {
        try {
            Class<?> lib = Class.forName("fr.citizen.cinematics.server.ServerVideoLibrary");
            Object video = lib.getMethod("byHash", String.class).invoke(null, sha);
            if (video == null) return null;
            Method pathMethod = video.getClass().getMethod("path");
            Path path = (Path) pathMethod.invoke(video);
            ZipFile zip = new ZipFile(path.toFile());
            List<ZipEntry> frames = new ArrayList<>();
            ZipEntry audio = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (n.startsWith("frames/") && n.endsWith(".jpg")) frames.add(e);
                else if (n.endsWith("audio.ogg")) audio = e;
            }
            frames.sort((a, b) -> a.getName().compareTo(b.getName()));
            return new Video(zip, frames, audio);
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Cinématique {} illisible pour la page web : {}", sha, e.toString());
            return null;
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
