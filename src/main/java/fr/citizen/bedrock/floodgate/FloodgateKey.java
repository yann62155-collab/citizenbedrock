package fr.citizen.bedrock.floodgate;

import fr.citizen.bedrock.BedrockConfig;
import fr.citizen.bedrock.CitizenBedrock;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Clé secrète partagée entre le pont (Geyser) et le serveur.
 * Format identique à Floodgate : 16 octets bruts (AES-128) dans key.pem.
 * Ne jamais partager ce fichier : quiconque l'a peut se faire passer pour n'importe quel joueur console.
 */
public final class FloodgateKey {
    private static SecretKey key;

    private FloodgateKey() {
    }

    public static Path file() {
        return BedrockConfig.folder().resolve("key.pem");
    }

    public static synchronized SecretKey get() {
        if (key == null) {
            key = loadOrCreate();
        }
        return key;
    }

    private static SecretKey loadOrCreate() {
        Path file = file();
        try {
            if (Files.exists(file)) {
                byte[] raw = Files.readAllBytes(file);
                if (raw.length == 16 || raw.length == 24 || raw.length == 32) {
                    return new SecretKeySpec(raw, "AES");
                }
                CitizenBedrock.LOGGER.warn("[Citizen Bedrock] key.pem invalide ({} octets), une nouvelle clé est créée.", raw.length);
            }
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(128, new SecureRandom());
            SecretKey created = generator.generateKey();
            Files.createDirectories(file.getParent());
            Files.write(file, created.getEncoded());
            CitizenBedrock.LOGGER.info("[Citizen Bedrock] Nouvelle clé secrète créée : {}", file);
            return created;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Impossible de préparer la clé Citizen Bedrock", e);
        }
    }
}
