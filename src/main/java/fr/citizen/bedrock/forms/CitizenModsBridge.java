package fr.citizen.bedrock.forms;

import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Écrans des mods Citizen pour les joueurs console : hibou postal, affiches, menuiserie, éclipses.
 * Chaque écran PC est remplacé par un menu Bedrock qui envoie au mod le même message que l'écran PC.
 */
public final class CitizenModsBridge {
    private static final String HIBOU = "fr.citizen.hiboupostal.network.Payloads$";

    private CitizenModsBridge() {
    }

    // ================================================================== messages serveur -> joueur

    /** true si le message a été pris en charge (il ne doit alors pas être envoyé tel quel). */
    public static boolean onPayload(ServerPlayer player, CustomPacketPayload payload) {
        String type = payload.getClass().getName();
        try {
            switch (type) {
                case HIBOU + "OpenOwlery" -> {
                    player.server.execute(() -> owlery(player, payload));
                    return true;
                }
                case HIBOU + "SendResult" -> {
                    boolean ok = (boolean) get(payload, "success");
                    String message = (String) get(payload, "message");
                    player.server.execute(() -> player.sendSystemMessage(Component.literal((ok ? "§a" : "§c") + "[Hibou] " + message)));
                    return true;
                }
                case "fr.citizen.eclipse.network.EclipseStatePayload" -> {
                    eclipse(player, payload);
                    return true;
                }
                case "fr.citizen.citizencraft.network.OpenCraftPayload" -> {
                    player.server.execute(() -> CraftBook.open(player));
                    return true;
                }
                case "fr.citizen.chasse.network.WeightSyncPayload" -> {
                    return true; // le poids est déjà affiché au-dessus des carcasses
                }
                default -> {
                    return false;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Message {} non converti", type, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ hibou postal

    private static void owlery(ServerPlayer player, CustomPacketPayload p) {
        try {
            int myNumber = (int) get(p, "myNumber");
            int blank = (int) get(p, "blankLetters");
            String prefill = (String) get(p, "prefill");
            List<?> directory = (List<?>) get(p, "directory");
            List<String> names = new ArrayList<>();
            List<String> numbers = new ArrayList<>();
            names.add("§7(taper un numéro ci-dessous)");
            numbers.add("");
            for (Object e : directory) {
                int n = (int) get(e, "number");
                boolean online = (boolean) get(e, "online");
                names.add((online ? "§a● " : "§7○ ") + get(e, "name") + " §8#" + n);
                numbers.add(String.valueOf(n));
            }
            BedrockForms.custom("Volière — envoyer une lettre")
                    .label("§7Ton numéro de hibou : §f#" + myNumber + "\n§7Lettres vierges : §f" + blank)
                    .dropdown("Destinataire", names, 0)
                    .input("…ou numéro de hibou", "ex : 1042", prefill == null ? "" : prefill)
                    .input("Titre", "Objet de la lettre", "")
                    .input("Message", "Ta lettre…", "")
                    .onSubmit(v -> {
                        // v : [label, liste, numéro, titre, message]
                        int pick = parseIndex(v.size() > 1 ? v.get(1) : "0");
                        String typed = v.size() > 2 ? v.get(2).trim() : "";
                        String recipient = !typed.isEmpty() ? typed : (pick > 0 && pick < numbers.size() ? numbers.get(pick) : "");
                        String title = v.size() > 3 ? v.get(3) : "";
                        String body = v.size() > 4 ? v.get(4) : "";
                        if (recipient.isEmpty()) {
                            player.sendSystemMessage(Component.literal("§c[Hibou] Choisis un destinataire."));
                            return;
                        }
                        PayloadInvoker.invoke(player, HIBOU + "SendLetter", new Class<?>[]{String.class, String.class, String.class},
                                recipient, title, body);
                    }).send(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Volière illisible", e);
        }
    }

    /** Colis : avant de fermer le carton, on demande le destinataire et on l'envoie comme sur PC. */
    private static void parcelSend(ServerPlayer player, AbstractContainerMenu menu) {
        BedrockForms.custom("Envoyer le colis")
                .label("§7Le contenu du carton sera envoyé par hibou.")
                .input("Numéro de hibou du destinataire", "ex : 1042", "")
                .input("Petit mot (facultatif)", "", "")
                .onSubmit(v -> {
                    String recipient = v.size() > 1 ? v.get(1).trim() : "";
                    String note = v.size() > 2 ? v.get(2) : "";
                    if (recipient.isEmpty()) {
                        closeMenu(player, menu);
                        return;
                    }
                    PayloadInvoker.invoke(player, HIBOU + "SendParcel", new Class<?>[]{String.class, String.class}, recipient, note);
                })
                .onClose(() -> closeMenu(player, menu))
                .send(player);
    }

    /** Lire une lettre scellée (sur PC elle s'ouvre dans un écran de lecture). */
    public static boolean readLetter(ServerPlayer player, ItemStack stack) {
        try {
            var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("hiboupostal", "letter"));
            if (type == null) return false;
            Object letter = stack.get(type);
            if (letter == null) return false;
            String text = "§7De : §f" + get(letter, "senderName") + " §8#" + get(letter, "senderNumber")
                    + "\n§7À : §f" + get(letter, "recipientName") + " §8#" + get(letter, "recipientNumber")
                    + "\n\n§6§l" + get(letter, "title") + "§r\n\n" + get(letter, "body");
            BedrockForms.simple("Missive", text).button("Fermer").send(player);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Lettre vierge : ouvre la volière comme sur PC. */
    public static void openOwlery(ServerPlayer player) {
        PayloadInvoker.invoke(player, HIBOU + "RequestOwlery", new Class<?>[]{String.class}, "");
    }

    // ------------------------------------------------------------------ affiches

    /** Clic droit sur une affiche : choix de l'image (images fournies par les mods, ou nom tapé). */
    public static void editPoster(ServerPlayer player, BlockPos pos) {
        List<String> images = new ArrayList<>(posterImages());
        List<String> options = new ArrayList<>();
        options.add("§7(taper le nom ci-dessous)");
        for (String i : images) options.add(i.substring(i.indexOf(':') + 1).replace('_', ' '));
        options.add("§c(retirer l'image)");
        BedrockForms.custom("Affiche")
                .dropdown("Image", options, 0)
                .input("…ou nom de l'image", "ex : citizenaffiches:taverne", "")
                .onSubmit(v -> {
                    int pick = parseIndex(v.isEmpty() ? "0" : v.get(0));
                    String typed = v.size() > 1 ? v.get(1).trim() : "";
                    String image;
                    if (!typed.isEmpty()) image = typed;
                    else if (pick >= 1 && pick <= images.size()) image = images.get(pick - 1);
                    else if (pick == images.size() + 1) image = "";
                    else return;
                    PayloadInvoker.invoke(player, "fr.citizen.affiches.network.SetAfficheImagePayload",
                            new Class<?>[]{BlockPos.class, String.class}, pos, image);
                }).send(player);
    }

    /** Images d'affiches présentes dans les mods du serveur (assets/<mod>/textures/affiches/*.png). */
    private static TreeSet<String> posterImages() {
        TreeSet<String> out = new TreeSet<>();
        ModList.get().getModFiles().forEach(info -> {
            try {
                Path assets = info.getFile().findResource("assets");
                if (!Files.isDirectory(assets)) return;
                try (Stream<Path> ns = Files.list(assets)) {
                    ns.forEach(nsDir -> {
                        Path dir = nsDir.resolve("textures").resolve("affiches");
                        if (!Files.isDirectory(dir)) return;
                        try (Stream<Path> files = Files.walk(dir)) {
                            files.filter(f -> f.toString().endsWith(".png")).forEach(f -> {
                                String rel = dir.relativize(f).toString().replace('\\', '/');
                                out.add(nsDir.getFileName().toString().replace("/", "") + ":" + rel.substring(0, rel.length() - 4));
                            });
                        } catch (IOException ignored) {
                        }
                    });
                }
            } catch (IOException | RuntimeException ignored) {
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ menuiserie

    /** Établi de menuiserie : choix de la porte, du style de clé et du nom de la serrure. */
    private static void carpentry(ServerPlayer player, AbstractContainerMenu menu) {
        try {
            Object[] doors = Class.forName("fr.citizen.menuiserie.DoorType").getEnumConstants();
            Object[] keys = Class.forName("fr.citizen.menuiserie.KeyStyle").getEnumConstants();
            List<String> doorNames = new ArrayList<>();
            for (Object d : doors) doorNames.add((String) get(d, "displayName"));
            List<String> keyNames = new ArrayList<>();
            for (Object k : keys) keyNames.add((String) get(k, "displayName"));
            BedrockForms.custom("Établi de menuiserie")
                    .label("§7Fabrique une porte à clé et sa clé. Les matériaux sont pris dans ton inventaire.")
                    .dropdown("Porte", doorNames, 0)
                    .dropdown("Style de clé", keyNames, 0)
                    .input("Nom de la serrure", "ex : Maison de Paul", "")
                    .onSubmit(v -> {
                        int door = parseIndex(v.size() > 1 ? v.get(1) : "0");
                        int key = parseIndex(v.size() > 2 ? v.get(2) : "0");
                        String lock = v.size() > 3 ? v.get(3) : "";
                        PayloadInvoker.invoke(player, "fr.citizen.menuiserie.network.CraftDoorPayload",
                                new Class<?>[]{int.class, int.class, String.class}, door, key, lock);
                        player.server.execute(() -> closeMenu(player, menu));
                    })
                    .onClose(() -> closeMenu(player, menu))
                    .send(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Établi de menuiserie illisible", e);
            closeMenu(player, menu);
        }
    }

    /** Machine à clés : en fermant le coffre, on propose les actions de la machine. */
    private static void keyMachine(ServerPlayer player, AbstractContainerMenu menu) {
        BedrockForms.custom("Machine à clés")
                .dropdown("Action", List.of("Dupliquer la clé", "Forger 2 clés vierges", "Fermer sans rien faire"), 0)
                .input("Nom du porteur (pour dupliquer)", "ex : Paul", "")
                .onSubmit(v -> {
                    int action = parseIndex(v.isEmpty() ? "2" : v.get(0));
                    if (action > 1) {
                        closeMenu(player, menu);
                        return;
                    }
                    String holder = v.size() > 1 ? v.get(1) : "";
                    PayloadInvoker.invoke(player, "fr.citizen.menuiserie.network.KeyMachinePayload",
                            new Class<?>[]{int.class, String.class}, action, holder);
                })
                .onClose(() -> closeMenu(player, menu))
                .send(player);
    }

    // ------------------------------------------------------------------ menus « à cases » avec boutons

    /** Menus remplacés entièrement par un menu Bedrock (pas de coffre). */
    public static boolean replaceMenu(ServerPlayer player, AbstractContainerMenu menu) {
        String name = menu.getClass().getName();
        if (name.equals("fr.citizen.menuiserie.menu.MenuiserieMenu")) {
            carpentry(player, menu);
            return true;
        }
        return false;
    }

    /** Action à proposer quand le joueur console ferme le coffre de ce menu (ou null). */
    public static Runnable closeAction(ServerPlayer player, AbstractContainerMenu menu) {
        String name = menu.getClass().getName();
        if (name.equals("fr.citizen.hiboupostal.menu.ParcelMenu")) return () -> parcelSend(player, menu);
        if (name.equals("fr.citizen.menuiserie.menu.KeyMachineMenu")) return () -> keyMachine(player, menu);
        return null;
    }

    private static void closeMenu(ServerPlayer player, AbstractContainerMenu menu) {
        player.server.execute(() -> {
            if (player.containerMenu == menu) {
                BedrockSession session = BedrockSession.get(player.connection.getConnection());
                if (session != null) session.closeActions.remove(menu.containerId);
                player.closeContainer();
            }
        });
    }

    // ------------------------------------------------------------------ éclipses

    private static void eclipse(ServerPlayer player, CustomPacketPayload p) throws ReflectiveOperationException {
        long now = (long) get(p, "serverNow");
        long approach = (long) get(p, "approachStart");
        long totality = (long) get(p, "totalityStart");
        long totalityEnd = (long) get(p, "totalityEnd");
        long recede = (long) get(p, "recedeEnd");
        String title;
        String sub;
        if (now >= approach && now < totality) {
            title = "§5Une éclipse approche";
            sub = "§7Le ciel s'assombrit…";
        } else if (now >= totality && now < totalityEnd) {
            title = "§4§lÉCLIPSE TOTALE";
            sub = "§cLes créatures de l'ombre rôdent";
        } else if (now >= totalityEnd && now < recede) {
            title = "§5L'éclipse se retire";
            sub = "§7La lumière revient";
        } else {
            return;
        }
        player.server.execute(() -> {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 70, 20));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(sub)));
        });
    }

    // ------------------------------------------------------------------ outils

    static int parseIndex(String s) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Object get(Object record, String name) throws ReflectiveOperationException {
        var m = record.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }
}
