package fr.citizen.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Réglages de Citizen Bedrock, dans config/citizenbedrock/citizenbedrock.json.
 * Le fichier est créé au premier démarrage ; on peut le modifier par FTP puis redémarrer le serveur.
 */
public final class BedrockConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Valeurs actuelles (jamais null après load()). */
    public static Values VALUES = new Values();

    public static final class Values {
        public String _aide = "Citizen Bedrock - redémarrer le serveur après modification. Les ports doivent être ouverts dans la gestion des ports BoxToPlay.";

        /** Lancer le pont Bedrock (ViaProxy + Geyser) au démarrage du serveur. */
        public boolean pontActive = true;
        /** Port principal Bedrock (UDP + TCP) : c'est celui que les joueurs console tapent. */
        public int portBedrock = 30040;
        /** Second port (UDP) utilisé par la nouvelle connexion Bedrock (NetherNet / WebRTC). */
        public int portWebrtc = 30079;
        /** Adresse publique annoncée aux consoles. */
        public String adressePublique = "mc312.boxtoplay.com";
        /**
         * Port TCP de la page web des cinématiques pour les joueurs console. 0 = désactivé.
         * DÉSACTIVÉE pour l'instant : le port 30053 est utilisé par Citizen Voix (chat vocal).
         */
        public int portWeb = 0;
        /** Mémoire maximale du pont, en Mo. */
        public int memoirePontMo = 512;
        /** Vérifier et télécharger les mises à jour de Geyser et ViaProxy à chaque démarrage. */
        public boolean miseAJourAuto = true;

        /**
         * Compte Xbox du serveur (onglet Amis) : les joueurs console ajoutent ce compte en ami
         * et rejoignent depuis l'onglet Amis de Minecraft, sans DNS ni appli. Utiliser un compte Microsoft dédié.
         */
        public boolean compteXboxAmis = true;

        /** Préfixe ajouté au pseudo des joueurs console (évite qu'un gamertag vole le pseudo d'un joueur PC). */
        public String prefixePseudo = ".";
        /** Texte affiché dans la liste des serveurs côté console. */
        public String motd1 = "Citizen";
        public String motd2 = "Serveur RP";

        /** Objet affiché à la place d'un objet moddé inconnu. */
        public String objetParDefaut = "minecraft:paper";
        /** Bloc affiché à la place d'un bloc moddé plein inconnu (si aucune couleur ne correspond). */
        public String blocParDefaut = "minecraft:stone";
        /** Correspondances forcées bloc moddé -> bloc vanilla, ex : "create:andesite_casing": "minecraft:polished_andesite". */
        public Map<String, String> blocsForces = new LinkedHashMap<>();
        /** Correspondances forcées objet moddé -> objet vanilla, ex : "citizeneconomy:gold_coin": "minecraft:gold_nugget". */
        public Map<String, String> objetsForces = new LinkedHashMap<>();
        /**
         * Créature vanilla forcée pour une créature moddée, ex : "easy_npc:*": "minecraft:player",
         * "iceandfire:fire_dragon": "minecraft:ravager", ou "cache" pour ne pas l'afficher.
         */
        public Map<String, String> entitesForcees = new LinkedHashMap<>(Map.of("easy_npc:*", "minecraft:player"));
        /** Message affiché quand un menu moddé n'est pas encore adapté. */
        public String messageMenuNonAdapte = "§6[Citizen Bedrock] §eCe menu n'est pas encore disponible sur console.";
    }

    private BedrockConfig() {
    }

    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve(CitizenBedrock.MODID);
    }

    public static void load() {
        Path file = folder().resolve("citizenbedrock.json");
        try {
            Files.createDirectories(folder());
            if (Files.exists(file)) {
                Values read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Values.class);
                if (read != null) {
                    if (read.blocsForces == null) read.blocsForces = new LinkedHashMap<>();
                    if (read.objetsForces == null) read.objetsForces = new LinkedHashMap<>();
                    if (read.entitesForcees == null) read.entitesForcees = new LinkedHashMap<>(Map.of("easy_npc:*", "minecraft:player"));
                    VALUES = read;
                }
            }
            // Page web des cinématiques désactivée pour l'instant : 30053 appartient à Citizen Voix.
            VALUES.portWeb = 0;
            // Réécrit le fichier pour y ajouter les nouvelles options éventuelles.
            Files.writeString(file, GSON.toJson(VALUES), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Impossible de lire {} : réglages par défaut utilisés.", file, e);
            VALUES = new Values();
        }
    }
}
