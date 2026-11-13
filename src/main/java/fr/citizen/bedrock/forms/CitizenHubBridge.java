package fr.citizen.bedrock.forms;

import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Parchemin /citizen (mod citizenhub) pour les joueurs console : le contenu envoyé au jeu PC
 * est relu ici et affiché sous forme de menus Bedrock. Aucune modification de citizenhub nécessaire.
 */
public final class CitizenHubBridge {
    public static final String OPEN_HUB = "citizenhub:open_hub";

    private CitizenHubBridge() {
    }

    private record Entry(int s, int e, String nom, String description, String commande, boolean suggerer) {
    }

    private record Section(String nom, String icone, String description, List<Entry> entrees) {
    }

    /** Appelé quand citizenhub veut ouvrir le parchemin chez un joueur console. */
    public static void open(ServerPlayer player, CustomPacketPayload payload) {
        try {
            String titre = (String) get(payload, "titre");
            List<Section> sections = new java.util.ArrayList<>();
            for (Object s : (List<?>) get(payload, "sections")) {
                List<Entry> entries = new java.util.ArrayList<>();
                for (Object e : (List<?>) get(s, "entrees")) {
                    entries.add(new Entry((int) get(e, "s"), (int) get(e, "e"), (String) get(e, "nom"),
                            (String) get(e, "description"), (String) get(e, "commande"), (boolean) get(e, "suggerer")));
                }
                sections.add(new Section((String) get(s, "nom"), (String) get(s, "icone"), (String) get(s, "description"), entries));
            }
            player.server.execute(() -> showSections(player, titre, sections));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Impossible de lire le parchemin citizenhub", ex);
        }
    }

    private static void showSections(ServerPlayer player, String titre, List<Section> sections) {
        BedrockForms.Simple form = BedrockForms.simple(titre, "§7Choisis une rubrique :");
        for (Section s : sections) {
            form.button("§l" + s.nom() + (s.description().isBlank() ? "" : "\n§r§8" + shorten(s.description())), icon(s.icone()));
        }
        form.onClick(i -> showEntries(player, titre, sections, sections.get(i)))
                .onClose(() -> stopReading(player))
                .send(player);
    }

    private static void showEntries(ServerPlayer player, String titre, List<Section> sections, Section section) {
        BedrockForms.Simple form = BedrockForms.simple(section.nom(), section.description());
        for (Entry e : section.entrees()) {
            form.button("§l" + e.nom() + (e.description().isBlank() ? "" : "\n§r§8" + shorten(e.description())));
        }
        form.button("§8« Retour");
        form.onClick(i -> {
            if (i >= section.entrees().size()) {
                showSections(player, titre, sections);
                return;
            }
            Entry e = section.entrees().get(i);
            if (e.suggerer()) {
                // Commande à compléter : on la propose pré-remplie, le joueur ajoute ce qui manque.
                BedrockForms.custom(e.nom())
                        .label(e.description())
                        .input("Commande", e.commande(), e.commande() + " ")
                        .onSubmit(values -> {
                            stopReading(player);
                            String cmd = values.isEmpty() ? "" : values.get(values.size() - 1).trim();
                            if (!cmd.isEmpty()) {
                                player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), cmd);
                            }
                        })
                        .onClose(() -> showEntries(player, titre, sections, section))
                        .send(player);
            } else {
                stopReading(player);
                runEntry(player, e);
            }
        }).onClose(() -> stopReading(player)).send(player);
    }

    /** Exécute l'entrée via citizenhub lui-même (il revérifie les droits du joueur). */
    private static void runEntry(ServerPlayer player, Entry e) {
        try {
            Class<?> hub = Class.forName("fr.citizen.hub.server.HubServer");
            hub.getMethod("runEntry", ServerPlayer.class, int.class, int.class).invoke(null, player, e.s(), e.e());
        } catch (ReflectiveOperationException ex) {
            player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), e.commande());
        }
    }

    private static void stopReading(ServerPlayer player) {
        try {
            Class<?> hub = Class.forName("fr.citizen.hub.server.HubServer");
            hub.getMethod("stopReading", ServerPlayer.class).invoke(null, player);
        } catch (ReflectiveOperationException ignored) {
            // citizenhub absent ou différent : rien à faire
        }
    }

    private static String icon(String icone) {
        if (icone == null || icone.isBlank()) return null;
        String path = icone.contains(":") ? icone.substring(icone.indexOf(':') + 1) : icone;
        return icone.startsWith("minecraft:") || !icone.contains(":") ? "textures/items/" + path : null;
    }

    private static String shorten(String s) {
        String clean = s.replace('\n', ' ');
        return clean.length() > 60 ? clean.substring(0, 57) + "..." : clean;
    }

    private static Object get(Object record, String component) throws ReflectiveOperationException {
        Method m = record.getClass().getMethod(component);
        m.setAccessible(true);
        return m.invoke(record);
    }
}
