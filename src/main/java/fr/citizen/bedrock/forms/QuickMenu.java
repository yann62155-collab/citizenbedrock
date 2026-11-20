package fr.citizen.bedrock.forms;

import fr.citizen.bedrock.BedrockPlayers;
import fr.citizen.bedrock.web.WebServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Menu rapide des joueurs console : s'accroupir deux fois rapidement (ou /menu) ouvre un menu
 * avec les fonctions du serveur, sans rien taper.
 */
public final class QuickMenu {
    private static final long DOUBLE_TAP_MS = 450;
    private static final Map<UUID, Long> LAST_SNEAK = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> WAS_SNEAKING = new ConcurrentHashMap<>();

    private record Entry(String label, String icon, Runnable action) {
    }

    private QuickMenu() {
    }

    /** Appelé à chaque tick pour les joueurs console : détecte le double accroupissement. */
    public static void tick(ServerPlayer player) {
        boolean sneaking = player.isShiftKeyDown();
        boolean was = WAS_SNEAKING.getOrDefault(player.getUUID(), false);
        WAS_SNEAKING.put(player.getUUID(), sneaking);
        if (!sneaking || was) return; // on ne compte que le moment où il s'accroupit
        long now = System.currentTimeMillis();
        Long last = LAST_SNEAK.put(player.getUUID(), now);
        if (last != null && now - last <= DOUBLE_TAP_MS && player.containerMenu == player.inventoryMenu) {
            LAST_SNEAK.remove(player.getUUID());
            open(player);
        }
    }

    public static void forget(UUID uuid) {
        LAST_SNEAK.remove(uuid);
        WAS_SNEAKING.remove(uuid);
    }

    public static void open(ServerPlayer player) {
        if (!BedrockPlayers.isBedrock(player)) return;
        List<Entry> entries = new ArrayList<>();
        ModList mods = ModList.get();
        if (mods.isLoaded("citizenhub")) entries.add(new Entry("Registre du Royaume\n§8toutes les fonctions (/citizen)", "textures/items/book_writable", () -> run(player, "citizen")));
        if (mods.isLoaded("citizeneconomy")) entries.add(new Entry("Banque & factures\n§8économie (/ceconomy)", "textures/items/gold_ingot", () -> run(player, "ceconomy")));
        if (mods.isLoaded("citizenboutique")) entries.add(new Entry("Boutique\n§8l'échoppe du royaume", "textures/items/emerald", () -> CitizenExtrasBridge.openShop(player)));
        if (mods.isLoaded("citizenmancie")) entries.add(new Entry("Grimoire\n§8lancer un sort", "textures/items/book_enchanted", () -> CitizenExtrasBridge.openSpells(player)));
        if (mods.isLoaded("citizenfactions")) entries.add(new Entry("Faction\n§8(/factions)", "textures/items/banner_pattern", () -> run(player, "factions")));
        if (mods.isLoaded("citizenguilds")) entries.add(new Entry("Guilde\n§8membres, banque, donjons", "textures/items/iron_sword", () -> CitizenExtrasBridge.openGuild(player)));
        if (mods.isLoaded("hiboupostal")) entries.add(new Entry("Envoyer une lettre\n§8volière du hibou", "textures/items/feather", () -> CitizenModsBridge.openOwlery(player)));
        if (mods.isLoaded("citizencraft")) entries.add(new Entry("Livre des recettes\n§8(/craft)", "textures/blocks/crafting_table_front", () -> CraftBook.open(player)));
        entries.add(new Entry("Relire le chat\n§8(/historique)", "textures/items/paper", () -> run(player, "historique")));
        if (mods.isLoaded("citizenvoice")) entries.add(new Entry("Chat vocal\n§8lien pour ton téléphone", "textures/items/villagebell", () -> run(player, "voix")));
        if (WebServer.running()) entries.add(new Entry("Cinématiques\n§8regarder sur ton téléphone", "textures/items/painting", () ->
                player.sendSystemMessage(Component.literal("§6[Cinématiques] §eOuvre sur ton téléphone : §fhttp://" + WebServer.address()))));
        entries.add(new Entry("§8Aide console", "textures/items/compass_item", () -> help(player)));

        BedrockForms.Simple form = BedrockForms.simple("Menu du Royaume", "§7Astuce : accroupis-toi deux fois rapidement pour rouvrir ce menu.");
        for (Entry e : entries) form.button(e.label(), e.icon());
        form.onClick(i -> entries.get(i).action().run()).send(player);
    }

    private static void run(ServerPlayer player, String command) {
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static void help(ServerPlayer player) {
        BedrockForms.simple("Aide console", String.join("\n",
                "§6Menu rapide :§r accroupis-toi deux fois vite, ou tape §f/menu§r.",
                "§6Chat :§r croix directionnelle droite pour écrire, §f/historique§r pour relire.",
                "§6PNJ :§r clic droit (ou un coup) pour leur parler.",
                "§6Affiches :§r accroupi + clic droit pour choisir l'image.",
                "§6Lettres :§r clic droit sur une lettre scellée pour la lire.",
                "§6Colis :§r en fermant le carton, on te demande le destinataire.",
                "§6Machines des mods :§r elles s'ouvrent comme un coffre.",
                "§6Boutique, guilde, faction, sorts :§r dans ce menu.",
                "§6Statue guide :§r approche-toi, la liste des voyages s'ouvre."))
                .button("§8« Retour").onClick(i -> open(player)).send(player);
    }
}
