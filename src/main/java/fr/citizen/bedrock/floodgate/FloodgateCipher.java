package fr.citizen.bedrock.floodgate;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiffrement compatible avec le format Floodgate utilisé par Geyser (AES/GCM, base64) :
 *   "^Floodgate^" + (char)(version + 0x3E) + base64(iv) + '!' + base64(données chiffrées)
 * Réécrit ici pour que le serveur n'ait pas besoin du mod Floodgate.
 */
public final class FloodgateCipher {
    public static final String IDENTIFIER = "^Floodgate^";
    private static final int VERSION = 0;
    public static final String HEADER = IDENTIFIER + (char) (VERSION + 0x3E);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private FloodgateCipher() {
    }

    public static boolean looksLikeFloodgate(String data) {
        return data.startsWith(IDENTIFIER);
    }

    public static String decrypt(String data, SecretKey key) throws Exception {
        if (!data.startsWith(HEADER)) {
            throw new IllegalArgumentException("En-tête Floodgate invalide");
        }
        String body = data.substring(HEADER.length());
        int split = body.indexOf('!');
        if (split <= 0) {
            throw new IllegalArgumentException("Séparateur Floodgate absent");
        }
        byte[] iv = Base64.getDecoder().decode(body.substring(0, split));
        byte[] cipherText = Base64.getDecoder().decode(body.substring(split + 1));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    /** Utilisé par l'autotest (et identique à ce que fait Geyser). */
    public static String encrypt(String plain, SecretKey key) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return HEADER + Base64.getEncoder().encodeToString(iv) + '!' + Base64.getEncoder().encodeToString(cipherText);
    }
}
