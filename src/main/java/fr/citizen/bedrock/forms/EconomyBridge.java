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
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Citizen Economy pour les joueurs console : chaque écran (banque, entreprises, factures, taxes, annonces...)
 * est envoyé par le serveur sous forme de « vue » JSON ; on l'affiche en menu Bedrock et on renvoie l'action
 * choisie au mod exactement comme le ferait l'écran PC. Toutes les vérifications restent faites par le mod.
 */
public final class EconomyBridge {
    public static final String VIEW = "citizeneconomy:view";
    public static final String HUD = "citizeneconomy:announcement_hud";

    private record Btn(String label, String action, Map<String, String> args, String confirm) {
    }

    private EconomyBridge() {
    }

    /** Annonce publique (affichée à gauche de l'écran sur PC) : envoyée dans le chat de la console. */
    public static void announcement(ServerPlayer player, CustomPacketPayload payload) {
        try {
            String company = (String) payload.getClass().getMethod("company").invoke(payload);
            String title = (String) payload.getClass().getMethod("title").invoke(payload);
            String body = (String) payload.getClass().getMethod("body").invoke(payload);
            player.server.execute(() -> player.sendSystemMessage(Component.literal(
                    "§6§l[" + company + "] §e" + title + "\n§f" + body)));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    public static void view(ServerPlayer player, CustomPacketPayload payload) {
        try {
            String json = (String) payload.getClass().getMethod("json").invoke(payload);
            JsonObject v = JsonParser.parseString(json).getAsJsonObject();
            player.server.execute(() -> show(player, v));
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Écran Citizen Economy illisible", e);
        }
    }

    private static void show(ServerPlayer player, JsonObject v) {
        String id = str(v, "id");
        String nonce = str(v, "nonce");
        String title = str(v, "title");
        String kind = str(v, "kind");
        String message = str(v, "message");

        StringBuilder text = new StringBuilder();
        if (!message.isEmpty()) {
            String color = switch (kind) {
                case "ok" -> "§a";
                case "err" -> "§c";
                default -> "§e";
            };
            text.append(color).append(message).append("§r\n\n");
        }
        List<Btn> buttons = new ArrayList<>();
        for (JsonElement le : arr(v, "lines")) {
            JsonObject line = le.getAsJsonObject();
            String t = str(line, "t");
            String style = str(line, "s");
            if (!t.isEmpty()) {
                text.append(style.startsWith("h") || style.equals("title") ? "§6§l" + t + "§r" : t).append('\n');
            }
            for (JsonElement be : arr(line, "b")) {
                Btn b = btn(be.getAsJsonObject());
                String prefix = t.isEmpty() ? "" : shorten(t) + " — ";
                buttons.add(new Btn(prefix + b.label(), b.action(), b.args(), b.confirm()));
            }
        }
        for (JsonElement be : arr(v, "buttons")) {
            buttons.add(btn(be.getAsJsonObject()));
        }
        JsonArray fields = arr(v, "fields");

        if (fields.isEmpty()) {
            BedrockForms.Simple form = BedrockForms.simple(title, text.toString().trim());
            for (Btn b : buttons) form.button(b.label());
            form.onClick(i -> fire(player, buttons.get(i), id, nonce, Map.of())).send(player);
            return;
        }

        // Écran avec champs (montant, matricule, n° de hibou...) : formulaire + choix de l'action
        BedrockForms.Custom form = BedrockForms.custom(title);
        if (text.length() > 0) form.label(text.toString().trim());
        List<String> fieldIds = new ArrayList<>();
        for (JsonElement fe : fields) {
            JsonObject f = fe.getAsJsonObject();
            fieldIds.add(str(f, "id"));
            form.input(str(f, "label"), "", str(f, "value"));
        }
        List<String> labels = new ArrayList<>();
        for (Btn b : buttons) labels.add(b.label());
        boolean choose = buttons.size() > 1;
        if (choose) form.dropdown("Action", labels, 0);
        form.onSubmit(values -> {
            int offset = text.length() > 0 ? 1 : 0; // le texte compte comme un élément (vide) dans la réponse
            Map<String, String> filled = new LinkedHashMap<>();
            for (int i = 0; i < fieldIds.size(); i++) {
                int idx = offset + i;
                filled.put(fieldIds.get(i), idx < values.size() ? values.get(idx) : "");
            }
            int chosen = 0;
            if (choose) {
                int idx = offset + fieldIds.size();
                try {
                    chosen = idx < values.size() ? Integer.parseInt(values.get(idx).replace(".0", "")) : 0;
                } catch (NumberFormatException ignored) {
                }
            }
            if (!buttons.isEmpty()) {
                fire(player, buttons.get(Math.max(0, Math.min(chosen, buttons.size() - 1))), id, nonce, filled);
            }
        }).send(player);
    }

    /** Envoie l'action au mod économie (avec confirmation si le bouton en demande une). */
    private static void fire(ServerPlayer player, Btn b, String viewId, String nonce, Map<String, String> fields) {
        if (b.confirm() != null && !b.confirm().isEmpty()) {
            BedrockForms.modal("Confirmation", b.confirm(), "Confirmer", "Annuler")
                    .onAnswer(yes -> {
                        if (yes) send(player, b, viewId, nonce, fields);
                    }).send(player);
            return;
        }
        send(player, b, viewId, nonce, fields);
    }

    private static void send(ServerPlayer player, Btn b, String viewId, String nonce, Map<String, String> fields) {
        Map<String, String> args = new LinkedHashMap<>(b.args());
        if (!b.action().equals("open")) {
            args.putAll(fields);
            args.put("_n", nonce);
        } else {
            args.put("_v", viewId);
        }
        try {
            Class<?> payloadClass = Class.forName("fr.citizen.economy.platform.ActionPayload");
            Object payload = payloadClass.getConstructor(String.class, String.class).newInstance(b.action(), toJson(args));
            Class<?> network = Class.forName("fr.citizen.economy.platform.Network");
            Class<?> ctxClass = Class.forName("net.neoforged.neoforge.network.handling.IPayloadContext");
            Object ctx = Proxy.newProxyInstance(ctxClass.getClassLoader(), new Class<?>[]{ctxClass}, (proxy, method, a) -> {
                if (method.getName().equals("player")) return player;
                if (method.getName().equals("enqueueWork") && a != null && a.length == 1 && a[0] instanceof Runnable r) {
                    player.server.execute(r);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                if (method.getReturnType() == boolean.class) return false;
                return null;
            });
            Method onAction = network.getDeclaredMethod("onAction", payloadClass, ctxClass);
            onAction.setAccessible(true);
            onAction.invoke(null, payload, ctx);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Action Citizen Economy impossible", e);
            player.sendSystemMessage(Component.literal("§c[Citizen Bedrock] Action impossible, préviens un admin."));
        }
    }

    private static Btn btn(JsonObject b) {
        Map<String, String> args = new LinkedHashMap<>();
        if (b.has("args") && b.get("args").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("args").entrySet()) {
                args.put(e.getKey(), e.getValue().isJsonNull() ? "" : e.getValue().getAsString());
            }
        }
        String confirm = b.has("c") && !b.get("c").isJsonNull() ? b.get("c").getAsString() : null;
        return new Btn(str(b, "l"), str(b, "a"), args, confirm);
    }

    private static String toJson(Map<String, String> args) {
        JsonObject o = new JsonObject();
        args.forEach(o::addProperty);
        return o.toString();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String shorten(String s) {
        String clean = s.replaceAll("§.", "").replace('\n', ' ').trim();
        return clean.length() > 40 ? clean.substring(0, 37) + "..." : clean;
    }
}
