package fr.citizen.bedrock.forms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Nouveaux mods Citizen adaptés pour la console :
 *  - Citizen Boutique (F6 sur PC) : catalogue, fiche article et paiement en menus Bedrock ;
 *  - Citizen Guildes (N sur PC) : fiche de guilde, membres, banque, invitations ;
 *  - Citizen Mancie (roulette G sur PC) : grimoire des sorts connus, un clic = lancer le sort ;
 *  - Citizen Statues : la statue guide propose ses destinations dès qu'on s'en approche
 *    (sur PC elle écoute la voix, impossible sur console).
 * Tout passe par les fonctions serveur des mods : aucune règle n'est contournée.
 */
public final class CitizenExtrasBridge {
    public static final String SHOP_SYNC = "citizenboutique:sync";
    public static final String SHOP_MESSAGE = "citizenboutique:message";
    public static final String STATUE_DESTINATIONS = "citizenstatues:player_destinations";
    public static final String STATUE_VOICE_RESULT = "citizenstatues:voice_intent_result";
    public static final String STATUE_ADMIN = "citizenstatues:admin_snapshot";

    /**
     * Canaux que l'on fait passer pour « présents » chez les joueurs console : leurs mods vérifient
     * que le joueur a le mod avant d'envoyer, alors que c'est nous qui affichons ces écrans.
     * (Hibou Postal n'y est pas : il a sa propre interface console en coffres.)
     */
    public static final Set<String> HANDLED_CHANNELS = Set.of(SHOP_SYNC, SHOP_MESSAGE);

    private CitizenExtrasBridge() {
    }

    /** Paquets serveur -> client interceptés pour la console ; true si traité. */
    public static boolean onPayload(ServerPlayer player, CustomPacketPayload payload) {
        String id = payload.type().id().toString();
        try {
            switch (id) {
                case SHOP_SYNC -> {
                    if ((boolean) get(payload, "open")) {
                        JsonObject root = JsonParser.parseString((String) get(payload, "json")).getAsJsonObject();
                        player.server.execute(() -> shopHome(player, root));
                    }
                    return true;
                }
                case SHOP_MESSAGE -> {
                    String kind = (String) get(payload, "kind");
                    String text = (String) get(payload, "text");
                    if (!"upload".equals(kind)) {
                        player.server.execute(() -> player.sendSystemMessage(Component.literal(
                                ("error".equals(kind) ? "§c" : "§a") + "[Boutique] " + text)));
                    }
                    return true;
                }
                case STATUE_DESTINATIONS -> {
                    String statueId = (String) get(payload, "statueId");
                    String title = (String) get(payload, "title");
                    @SuppressWarnings("unchecked") List<String> dest = new ArrayList<>((List<String>) get(payload, "destinations"));
                    player.server.execute(() -> statueMenu(player, statueId, title, dest));
                    return true;
                }
                case STATUE_VOICE_RESULT -> {
                    String msg = (String) get(payload, "message");
                    boolean ok = (boolean) get(payload, "success");
                    player.server.execute(() -> player.sendSystemMessage(Component.literal((ok ? "§a" : "§e") + msg)));
                    return true;
                }
                case STATUE_ADMIN -> {
                    @SuppressWarnings("unchecked") List<String> lines = (List<String>) get(payload, "lines");
                    String text = String.join("\n", lines);
                    player.server.execute(() -> BedrockForms.simple("Statues guides", text).button("Fermer").send(player));
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Écran {} illisible", id, e);
            return true;
        }
    }

    // =====================================================================
    // Citizen Boutique
    // =====================================================================

    public static void openShop(ServerPlayer player) {
        callStatic("fr.citizen.boutique.shop.ShopService", "sendShop",
                new Class<?>[]{ServerPlayer.class, boolean.class}, player, true);
    }

    private static void shopHome(ServerPlayer player, JsonObject root) {
        JsonObject shop = obj(root, "shop");
        JsonObject settings = obj(shop, "settings");
        List<JsonObject> offers = new ArrayList<>();
        for (JsonElement e : arr(shop, "offers")) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("enabled") || o.get("enabled").getAsBoolean()) offers.add(o);
        }
        Map<String, List<JsonObject>> byCategory = new LinkedHashMap<>();
        for (JsonElement c : arr(shop, "categories")) byCategory.put(c.getAsString(), new ArrayList<>());
        for (JsonObject o : offers) byCategory.computeIfAbsent(str(o, "category"), k -> new ArrayList<>()).add(o);
        byCategory.values().removeIf(List::isEmpty);

        StringBuilder text = new StringBuilder();
        if (!str(settings, "subtitle").isEmpty()) text.append("§7").append(str(settings, "subtitle")).append("§r\n\n");
        text.append("§6Bourse : §f").append(po(num(root, "wallet")));
        if (root.has("bankAvailable") && root.get("bankAvailable").getAsBoolean() && num(root, "bank") >= 0) {
            text.append("   §6Banque : §f").append(po(num(root, "bank")));
        }
        text.append("   §6Niveaux : §f").append(num(root, "xp"));
        if (offers.isEmpty()) text.append("\n\n§7Aucun article en vente pour le moment.");

        String title = str(settings, "title").isEmpty() ? "Boutique" : str(settings, "title");
        List<String> cats = new ArrayList<>(byCategory.keySet());
        BedrockForms.Simple form = BedrockForms.simple(title, text.toString());
        for (String c : cats) form.button(c + "\n§8" + byCategory.get(c).size() + " article(s)");
        form.onClick(i -> shopCategory(player, root, cats.get(i), byCategory.get(cats.get(i)))).send(player);
    }

    private static void shopCategory(ServerPlayer player, JsonObject root, String category, List<JsonObject> offers) {
        BedrockForms.Simple form = BedrockForms.simple(category, "§7Choisis un article.");
        for (JsonObject o : offers) {
            String price = num(o, "price") > 0 ? po(num(o, "price")) : (num(o, "xpLevels") > 0 ? num(o, "xpLevels") + " niv." : "");
            String stock = num(o, "stock") == 0 ? " §c(rupture)" : "";
            form.button(str(o, "name") + stock + (price.isEmpty() ? "" : "\n§8" + price));
        }
        form.button("§8« Retour");
        form.onClick(i -> {
            if (i >= offers.size()) shopHome(player, root);
            else shopOffer(player, root, category, offers, offers.get(i));
        }).send(player);
    }

    private static void shopOffer(ServerPlayer player, JsonObject root, String category, List<JsonObject> offers, JsonObject o) {
        JsonObject settings = obj(obj(root, "shop"), "settings");
        boolean bankOk = root.has("bankAvailable") && root.get("bankAvailable").getAsBoolean();
        long price = num(o, "price");
        int barter = (int) num(o, "barterCount");
        int xp = (int) num(o, "xpLevels");
        long stock = num(o, "stock");

        StringBuilder text = new StringBuilder();
        if (!str(o, "description").isEmpty()) text.append(str(o, "description")).append("\n\n");
        if (stock >= 0) text.append("§7Stock : §f").append(stock == 0 ? "§crupture" : stock).append('\n');
        long limit = num(o, "limitPerPlayer");
        if (limit > 0) {
            long bought = obj(root, "bought").has(str(o, "id")) ? obj(root, "bought").get(str(o, "id")).getAsLong() : 0;
            text.append("§7Limite : §f").append(bought).append(" / ").append(limit).append('\n');
        }

        List<String> labels = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        if (price <= 0 && barter <= 0 && xp <= 0) {
            labels.add("§2Obtenir gratuitement");
            methods.add("free");
        }
        if (price > 0 && bool(settings, "payCoins") && bool(o, "allowCoins")) {
            labels.add("Payer en pièces\n§8" + po(price));
            methods.add("coins");
        }
        if (price > 0 && bankOk && bool(settings, "payBank") && bool(o, "allowBank")) {
            labels.add("Payer par la banque\n§8" + po(price));
            methods.add("bank");
        }
        if (barter > 0 && !str(o, "barterItem").isEmpty() && bool(settings, "payBarter")) {
            labels.add("Troquer\n§8" + barter + " × " + str(o, "barterItem").replaceFirst("^[^:]*:", "").replace('_', ' '));
            methods.add("barter");
        }
        if (xp > 0 && bool(settings, "payXp")) {
            labels.add("Payer en expérience\n§8" + xp + " niveaux");
            methods.add("xp");
        }
        if (methods.isEmpty()) text.append("\n§7Aucun moyen de paiement disponible pour cet article.");

        BedrockForms.Simple form = BedrockForms.simple(str(o, "name"), text.toString().trim());
        for (String l : labels) form.button(l);
        form.button("§8« Retour");
        String offerId = str(o, "id");
        form.onClick(i -> {
            if (i >= methods.size()) {
                shopCategory(player, root, category, offers);
                return;
            }
            String method = methods.get(i);
            player.server.execute(() -> callStatic("fr.citizen.boutique.shop.ShopService", "buy",
                    new Class<?>[]{ServerPlayer.class, String.class, String.class}, player, offerId, method));
        }).send(player);
    }

    private static String po(long amount) {
        return String.format(java.util.Locale.FRANCE, "%,d PO", amount).replace(' ', ' ').replace(' ', ' ');
    }

    // =====================================================================
    // Citizen Guildes
    // =====================================================================

    public static void openGuild(ServerPlayer player) {
        JsonObject g;
        try {
            Class<?> net = Class.forName("fr.citizen.guilds.GuildNetwork");
            Method m = net.getDeclaredMethod("createSnapshot", ServerPlayer.class);
            m.setAccessible(true);
            g = JsonParser.parseString((String) m.invoke(null, player)).getAsJsonObject();
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Guilde illisible", e);
            player.sendSystemMessage(Component.literal("§cMenu des guildes indisponible, utilise /guild."));
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        if (bool(g, "hasInvitation")) {
            text.append("§eInvitation de la guilde §f").append(str(g, "invitationGuild"))
                    .append(" §7(").append(num(g, "invitationGuildMembers")).append(" membre(s))§r\n\n");
            labels.add("§2Accepter l'invitation");
            actions.add(() -> run(player, "guild accept"));
            labels.add("§4Refuser l'invitation");
            actions.add(() -> run(player, "guild refuse"));
        }

        if (!bool(g, "hasGuild")) {
            text.append("§7Tu n'as pas encore de guilde.");
            labels.add("Fonder ma guilde");
            actions.add(() -> BedrockForms.custom("Fonder une guilde").input("Nom de la guilde", "Les Lames d'Argent", "")
                    .onSubmit(v -> {
                        String name = safe(v.isEmpty() ? "" : v.get(0));
                        if (!name.isEmpty()) run(player, "guild create \"" + name + "\"");
                    }).send(player));
        } else {
            boolean manage = bool(g, "canManage");
            boolean leader = bool(g, "isLeader");
            text.append("§6§l").append(str(g, "name")).append("§r\n")
                    .append("§7Membres : §f").append(num(g, "memberCount")).append('\n')
                    .append("§7Objets déposés : §f").append(num(g, "totalDepositedItems")).append('\n')
                    .append("§7Donjons : §f").append(num(g, "dungeonWins")).append(" victoire(s), ")
                    .append(num(g, "dungeonDeaths")).append(" élimination(s)");
            labels.add("Membres");
            actions.add(() -> guildMembers(player, g));
            labels.add("Banque de guilde");
            actions.add(() -> guildBank(player, g));
            labels.add("Journal");
            actions.add(() -> {
                List<String> lines = new ArrayList<>();
                for (JsonElement e : arr(g, "journal")) lines.add(e.getAsString());
                BedrockForms.simple("Journal de la guilde", lines.isEmpty() ? "§7Rien pour l'instant." : String.join("\n", lines))
                        .button("§8« Retour").onClick(i -> openGuild(player)).send(player);
            });
            if (manage) {
                labels.add("Recruter");
                actions.add(() -> BedrockForms.custom("Recruter").input("Numéro de hibou du joueur", "12", "")
                        .onSubmit(v -> {
                            String n = (v.isEmpty() ? "" : v.get(0)).replaceAll("[^0-9]", "");
                            if (!n.isEmpty()) run(player, "guild invite " + n);
                        }).send(player));
            }
            if (leader) {
                labels.add("Renommer");
                actions.add(() -> BedrockForms.custom("Renommer la guilde").input("Nouveau nom", "", str(g, "name"))
                        .onSubmit(v -> {
                            String name = safe(v.isEmpty() ? "" : v.get(0));
                            if (!name.isEmpty()) run(player, "guild rename \"" + name + "\"");
                        }).send(player));
            }
            labels.add("§4Quitter la guilde");
            actions.add(() -> confirm(player, "Quitter " + str(g, "name") + " ?", () -> run(player, "guild leave")));
            if (leader) {
                labels.add("§4Dissoudre la guilde");
                actions.add(() -> confirm(player, "Dissoudre définitivement " + str(g, "name") + " ?", () -> run(player, "guild disband")));
            }
        }
        labels.add("Classements");
        actions.add(() -> BedrockForms.simple("Classements", "").button("Dépôts").button("Donjons")
                .onClick(i -> run(player, i == 0 ? "guild top" : "guild top dungeons")).send(player));
        labels.add("Toutes les guildes");
        actions.add(() -> {
            StringBuilder list = new StringBuilder();
            for (JsonElement e : arr(g, "guilds")) {
                JsonObject o = e.getAsJsonObject();
                list.append("§f").append(str(o, "name")).append(" §7(").append(num(o, "members")).append(" membre(s))\n");
            }
            BedrockForms.simple("Guildes du royaume", list.length() == 0 ? "§7Aucune guilde." : list.toString().trim())
                    .button("§8« Retour").onClick(i -> openGuild(player)).send(player);
        });
        if (bool(g, "isAdmin")) {
            labels.add("§8Administration");
            actions.add(() -> run(player, "guild admin list"));
        }
        BedrockForms.Simple form = BedrockForms.simple("Guilde", text.toString());
        for (String l : labels) form.button(l);
        form.onClick(i -> actions.get(i).run()).send(player);
    }

    private static void guildMembers(ServerPlayer player, JsonObject g) {
        JsonArray members = arr(g, "members");
        boolean manage = bool(g, "canManage");
        BedrockForms.Simple form = BedrockForms.simple("Membres", manage ? "§7Choisis un membre pour le promouvoir ou l'exclure." : "");
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement e : members) list.add(e.getAsJsonObject());
        for (JsonObject m : list) form.button(str(m, "name") + (bool(m, "self") ? " §8(toi)" : "") + "\n§8" + role(str(m, "role")));
        form.button("§8« Retour");
        form.onClick(i -> {
            if (i >= list.size()) {
                openGuild(player);
                return;
            }
            JsonObject m = list.get(i);
            if (!manage || bool(m, "self")) {
                openGuild(player);
                return;
            }
            String name = str(m, "name");
            BedrockForms.simple(name, "§7Rôle : §f" + role(str(m, "role")))
                    .button("Promouvoir / rétrograder").button("§4Exclure").button("§8« Retour")
                    .onClick(j -> {
                        if (j == 0) run(player, "guild promote " + name);
                        else if (j == 1) confirm(player, "Exclure " + name + " ?", () -> run(player, "guild kick " + name));
                        else guildMembers(player, g);
                    }).send(player);
        }).send(player);
    }

    private static void guildBank(ServerPlayer player, JsonObject g) {
        List<JsonObject> items = new ArrayList<>();
        for (JsonElement e : arr(g, "bank")) items.add(e.getAsJsonObject());
        BedrockForms.Simple form = BedrockForms.simple("Banque de guilde",
                items.isEmpty() ? "§7La banque est vide." : "§7Choisis un objet pour le retirer.");
        form.button("§2Déposer l'objet en main");
        for (JsonObject it : items) form.button(str(it, "name") + "\n§8× " + num(it, "count"));
        form.button("§8« Retour");
        form.onClick(i -> {
            if (i == 0) {
                run(player, "guild deposit");
            } else if (i <= items.size()) {
                JsonObject it = items.get(i - 1);
                BedrockForms.custom("Retirer " + str(it, "name"))
                        .input("Quantité (max " + num(it, "count") + ")", "", String.valueOf(num(it, "count")))
                        .onSubmit(v -> {
                            String q = (v.isEmpty() ? "" : v.get(0)).replaceAll("[^0-9]", "");
                            run(player, "guild withdraw " + num(it, "slot") + " " + (q.isEmpty() ? "1" : q));
                        }).send(player);
            } else {
                openGuild(player);
            }
        }).send(player);
    }

    private static String role(String r) {
        return switch (r) {
            case "LEADER" -> "Chef";
            case "OFFICER" -> "Officier";
            default -> "Membre";
        };
    }

    // =====================================================================
    // Citizen Mancie
    // =====================================================================

    public static void openSpells(ServerPlayer player) {
        List<Object> spells = new ArrayList<>();
        try {
            Class<?> att = Class.forName("fr.citizen.mancie.spell.ModAttachments");
            @SuppressWarnings("unchecked")
            net.neoforged.neoforge.attachment.AttachmentType<Object> type =
                    (net.neoforged.neoforge.attachment.AttachmentType<Object>) ((Supplier<?>) att.getField("DATA").get(null)).get();
            Object data = player.getData(type);
            spells.addAll((List<?>) data.getClass().getMethod("known").invoke(data));
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Grimoire de mancie illisible", e);
        }
        if (spells.isEmpty()) {
            BedrockForms.simple("Grimoire", "§7Tu ne connais aucun sort.\nSeul le staff peut enseigner la mancie.")
                    .button("Fermer").send(player);
            return;
        }
        BedrockForms.Simple form = BedrockForms.simple("Grimoire", "§7Touche un sort pour le lancer (vise d'abord ta cible).");
        List<String> ids = new ArrayList<>();
        for (Object s : spells) {
            try {
                ids.add((String) s.getClass().getMethod("id").invoke(s));
                String name = (String) s.getClass().getMethod("displayName").invoke(s);
                Object school = s.getClass().getMethod("mancie").invoke(s);
                String schoolName;
                try {
                    schoolName = (String) school.getClass().getMethod("displayName").invoke(school);
                } catch (NoSuchMethodException ex) {
                    schoolName = school.toString();
                }
                form.button(name + "\n§8" + schoolName);
            } catch (ReflectiveOperationException | RuntimeException e) {
                ids.remove(ids.size() - 1);
            }
        }
        form.onClick(i -> run(player, "sort " + ids.get(i))).send(player);
    }

    // =====================================================================
    // Citizen Statues
    // =====================================================================

    private static void statueMenu(ServerPlayer player, String statueId, String title, List<String> destinations) {
        List<String> ids = new ArrayList<>();
        BedrockForms.Simple form = BedrockForms.simple(title, "§7Où veux-tu aller ?");
        for (String line : destinations) {
            String[] p = line.split("\\|", 5);
            if (p.length < 2) continue;
            ids.add(p[0]);
            form.button(p[1] + (p.length > 2 && !p[2].isBlank() ? "\n§8" + shorten(p[2]) : ""));
        }
        if (ids.isEmpty()) return;
        form.button("§8Rester ici");
        form.onClick(i -> {
            if (i >= ids.size()) return;
            String dest = ids.get(i);
            player.server.execute(() -> {
                try {
                    Class<?> payloadClass = Class.forName("fr.citizen.statues.network.PlayerTeleportPayload");
                    Object payload = payloadClass.getConstructor(String.class, String.class).newInstance(statueId, dest);
                    Class.forName("fr.citizen.statues.network.PlayerTeleportHandler")
                            .getMethod("handle", payloadClass, net.minecraft.world.entity.player.Player.class)
                            .invoke(null, payload, player);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    CitizenBedrock.LOGGER.error("[Citizen Bedrock] Téléportation de statue impossible", e);
                }
            });
        }).send(player);
    }

    // =====================================================================
    // Outils
    // =====================================================================

    private static void confirm(ServerPlayer player, String question, Runnable yes) {
        BedrockForms.modal("Confirmation", question, "Confirmer", "Annuler").onAnswer(ok -> {
            if (ok) yes.run();
        }).send(player);
    }

    private static void run(ServerPlayer player, String command) {
        player.server.execute(() -> player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command));
    }

    private static void callStatic(String className, String method, Class<?>[] types, Object... args) {
        try {
            Method m = Class.forName(className).getDeclaredMethod(method, types);
            m.setAccessible(true);
            m.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Appel {}.{} impossible", className, method, e);
        }
    }

    private static Object get(Object record, String component) throws ReflectiveOperationException {
        return record.getClass().getMethod(component).invoke(record);
    }

    private static String safe(String s) {
        return s.replace("\"", "").replace("\\", "").trim();
    }

    private static String shorten(String s) {
        String clean = s.replaceAll("§.", "").trim();
        return clean.length() > 60 ? clean.substring(0, 57) + "..." : clean;
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }
}
