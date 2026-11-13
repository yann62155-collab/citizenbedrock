package fr.citizen.bedrock;

/**
 * Informations sur un joueur console, envoyées par Geyser (format Floodgate) puis vérifiées par le mod.
 */
public record BedrockData(String version, String username, String xuid, int deviceOs, String languageCode,
                          int uiProfile, int inputMode, String ip) {

    private static final String[] DEVICES = {
            "Inconnu", "Android", "iOS", "macOS", "Amazon", "Gear VR", "Windows", "Windows x86",
            "Dedicated", "PlayStation", "Switch", "Xbox", "Linux"
    };

    /** Lit le texte déchiffré : champs séparés par le caractère nul. */
    public static BedrockData parse(String decrypted) {
        String[] f = decrypted.split("\0", -1);
        if (f.length < 8) {
            throw new IllegalArgumentException("Données Bedrock incomplètes (" + f.length + " champs)");
        }
        Long.parseLong(f[2]); // le XUID doit être un nombre
        return new BedrockData(f[0], f[1], f[2], safeInt(f[3]), f[4], safeInt(f[5]), safeInt(f[6]), f[7]);
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String deviceName() {
        return deviceOs >= 0 && deviceOs < DEVICES.length ? DEVICES[deviceOs] : "Inconnu";
    }

    /** Clavier/souris = 1, tactile = 2, manette = 3, réalité virtuelle = 4 (valeurs Bedrock). */
    public boolean usesController() {
        return inputMode == 3;
    }
}
