package fr.citizen.bedrock.forms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Menus natifs Bedrock (formulaires) envoyés aux joueurs console à travers Geyser.
 * Les autres mods Citizen peuvent s'en servir :
 * <pre>
 * BedrockForms.simple("Titre", "Texte").button("Choix 1").button("Choix 2")
 *         .onClick(index -> ...).send(player);
 * </pre>
 * Les réponses sont toujours traitées sur le fil principal du serveur.
 */
public final class BedrockForms {
    private static final int SIMPLE = 0;
    private static final int MODAL = 1;
    private static final int CUSTOM = 2;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<UUID, Map<Integer, Consumer<String>>> PENDING = new ConcurrentHashMap<>();

    private BedrockForms() {
    }

    // ------------------------------------------------------------------ constructeurs de menus

    public static Simple simple(String title, String content) {
        return new Simple(title, content);
    }

    public static Custom custom(String title) {
        return new Custom(title);
    }

    public static Modal modal(String title, String content, String yes, String no) {
        return new Modal(title, content, yes, no);
    }

    /** Liste de boutons. onClick reçoit l'index du bouton, onClose est appelé si le joueur ferme le menu. */
    public static final class Simple {
        private final JsonObject json = new JsonObject();
        private final JsonArray buttons = new JsonArray();
        private IntConsumer onClick = i -> { };
        private Runnable onClose = () -> { };

        private Simple(String title, String content) {
            json.addProperty("type", "form");
            json.addProperty("title", title);
            json.addProperty("content", content == null ? "" : content);
            json.add("buttons", buttons);
        }

        public Simple button(String text) {
            return button(text, null);
        }

        /** image : chemin de texture Bedrock (« textures/items/book ») ou adresse https. */
        public Simple button(String text, @Nullable String image) {
            JsonObject button = new JsonObject();
            button.addProperty("text", text);
            if (image != null && !image.isBlank()) {
                JsonObject img = new JsonObject();
                img.addProperty("type", image.startsWith("http") ? "url" : "path");
                img.addProperty("data", image);
                button.add("image", img);
            }
            buttons.add(button);
            return this;
        }

        public Simple onClick(IntConsumer handler) {
            this.onClick = handler;
            return this;
        }

        public Simple onClose(Runnable handler) {
            this.onClose = handler;
            return this;
        }

        public boolean send(ServerPlayer player) {
            return BedrockForms.send(player, SIMPLE, json, response -> {
                Integer index = parseInt(response);
                if (index == null || index < 0 || index >= buttons.size()) onClose.run();
                else onClick.accept(index);
            });
        }
    }

    /** Formulaire avec champs : onSubmit reçoit les valeurs dans l'ordre des champs. */
    public static final class Custom {
        private final JsonObject json = new JsonObject();
        private final JsonArray content = new JsonArray();
        private Consumer<List<String>> onSubmit = v -> { };
        private Runnable onClose = () -> { };

        private Custom(String title) {
            json.addProperty("type", "custom_form");
            json.addProperty("title", title);
            json.add("content", content);
        }

        public Custom label(String text) {
            JsonObject c = new JsonObject();
            c.addProperty("type", "label");
            c.addProperty("text", text);
            content.add(c);
            return this;
        }

        public Custom input(String text, String placeholder, String defaultValue) {
            JsonObject c = new JsonObject();
            c.addProperty("type", "input");
            c.addProperty("text", text);
            c.addProperty("placeholder", placeholder == null ? "" : placeholder);
            c.addProperty("default", defaultValue == null ? "" : defaultValue);
            content.add(c);
            return this;
        }

        public Custom toggle(String text, boolean defaultValue) {
            JsonObject c = new JsonObject();
            c.addProperty("type", "toggle");
            c.addProperty("text", text);
            c.addProperty("default", defaultValue);
            content.add(c);
            return this;
        }

        public Custom dropdown(String text, List<String> options, int defaultIndex) {
            JsonObject c = new JsonObject();
            c.addProperty("type", "dropdown");
            c.addProperty("text", text);
            JsonArray opts = new JsonArray();
            options.forEach(opts::add);
            c.add("options", opts);
            c.addProperty("default", defaultIndex);
            content.add(c);
            return this;
        }

        public Custom onSubmit(Consumer<List<String>> handler) {
            this.onSubmit = handler;
            return this;
        }

        public Custom onClose(Runnable handler) {
            this.onClose = handler;
            return this;
        }

        public boolean send(ServerPlayer player) {
            return BedrockForms.send(player, CUSTOM, json, response -> {
                List<String> values = parseArray(response);
                if (values == null) onClose.run();
                else onSubmit.accept(values);
            });
        }
    }

    /** Question oui / non. */
    public static final class Modal {
        private final JsonObject json = new JsonObject();
        private Consumer<Boolean> onAnswer = b -> { };

        private Modal(String title, String content, String yes, String no) {
            json.addProperty("type", "modal");
            json.addProperty("title", title);
            json.addProperty("content", content);
            json.addProperty("button1", yes);
            json.addProperty("button2", no);
        }

        public Modal onAnswer(Consumer<Boolean> handler) {
            this.onAnswer = handler;
            return this;
        }

        public boolean send(ServerPlayer player) {
            return BedrockForms.send(player, MODAL, json, response -> onAnswer.accept("true".equals(response == null ? "" : response.trim())));
        }
    }

    // ------------------------------------------------------------------ envoi / réception

    private static boolean send(ServerPlayer player, int type, JsonObject json, Consumer<String> callback) {
        BedrockSession session = BedrockSession.get(player.connection.getConnection());
        if (session == null) {
            return false;
        }
        int id = NEXT_ID.getAndUpdate(i -> i >= Short.MAX_VALUE ? 1 : i + 1);
        PENDING.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>()).put(id, callback);
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[body.length + 3];
        data[0] = (byte) type;
        data[1] = (byte) (id >> 8);
        data[2] = (byte) id;
        System.arraycopy(body, 0, data, 3, body.length);
        // Envoi direct sur la connexion : ce canal est géré par Geyser, pas par un mod du client.
        player.connection.getConnection().send(new ClientboundCustomPayloadPacket(new FormPayload(data)));
        return true;
    }

    /** Réponse du joueur : 2 octets d'identifiant puis la réponse Bedrock (texte). */
    public static void handleResponse(ServerPlayer player, byte[] data) {
        if (data.length < 2) return;
        int id = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        String response = data.length > 2 ? new String(data, 2, data.length - 2, StandardCharsets.UTF_8) : null;
        Map<Integer, Consumer<String>> pending = PENDING.get(player.getUUID());
        Consumer<String> callback = pending == null ? null : pending.remove(id);
        if (callback == null) return;
        player.server.execute(() -> {
            try {
                callback.accept(response);
            } catch (RuntimeException e) {
                CitizenBedrock.LOGGER.error("[Citizen Bedrock] Erreur dans la réponse d'un menu console", e);
            }
        });
    }

    public static void forget(UUID player) {
        PENDING.remove(player);
    }

    @Nullable
    private static Integer parseInt(@Nullable String response) {
        if (response == null) return null;
        String s = response.trim();
        if (s.isEmpty() || s.equals("null")) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static List<String> parseArray(@Nullable String response) {
        if (response == null || response.isBlank() || response.trim().equals("null")) return null;
        try {
            JsonElement element = JsonParser.parseString(response.trim());
            if (!element.isJsonArray()) return null;
            List<String> values = new ArrayList<>();
            for (JsonElement e : element.getAsJsonArray()) {
                values.add(e.isJsonNull() ? "" : e.getAsString());
            }
            return values;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
