package fr.citizen.bedrock.forms;

import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Fait comme si le jeu PC du joueur avait envoyé un message réseau d'un mod : on retrouve le
 * traitement enregistré par ce mod côté serveur et on l'exécute pour le joueur console.
 * Le mod refait donc toutes ses propres vérifications (droits, objets, distance...).
 */
public final class PayloadInvoker {
    private PayloadInvoker() {
    }

    /** Crée le message d'un mod par réflexion (record) puis l'exécute. */
    public static boolean invoke(ServerPlayer player, String payloadClass, Class<?>[] types, Object... args) {
        try {
            Class<?> c = Class.forName(payloadClass);
            Object payload = c.getConstructor(types).newInstance(args);
            return invoke(player, (CustomPacketPayload) payload);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Message {} impossible à créer", payloadClass, e);
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean invoke(ServerPlayer player, CustomPacketPayload payload) {
        try {
            Class<?> registry = Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry");
            Field f = registry.getDeclaredField("PAYLOAD_REGISTRATIONS");
            f.setAccessible(true);
            Map<ConnectionProtocol, Map<ResourceLocation, ?>> all = (Map) f.get(null);
            Object registration = all.get(ConnectionProtocol.PLAY).get(payload.type().id());
            if (registration == null) {
                CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Aucun traitement serveur pour {}", payload.type().id());
                return false;
            }
            Object handler = registration.getClass().getMethod("handler").invoke(registration);
            Class<?> ctxClass = Class.forName("net.neoforged.neoforge.network.handling.IPayloadContext");
            Object ctx = context(player, ctxClass);
            Method handle = handler.getClass().getMethod("handle", CustomPacketPayload.class, ctxClass);
            handle.setAccessible(true);
            player.server.execute(() -> {
                try {
                    handle.invoke(handler, payload, ctx);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    CitizenBedrock.LOGGER.error("[Citizen Bedrock] Traitement de {} en erreur", payload.type().id(), e);
                }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Impossible d'exécuter {}", payload.type().id(), e);
            return false;
        }
    }

    private static Object context(ServerPlayer player, Class<?> ctxClass) {
        InvocationHandler h = (proxy, method, a) -> {
            switch (method.getName()) {
                case "player":
                    return player;
                case "listener":
                    return player.connection;
                case "flow":
                    return PacketFlow.SERVERBOUND;
                case "protocol":
                    return ConnectionProtocol.PLAY;
                case "enqueueWork":
                    if (a != null && a.length == 1 && a[0] instanceof Runnable r) {
                        return CompletableFuture.runAsync(r, player.server);
                    }
                    if (a != null && a.length == 1 && a[0] instanceof Supplier<?> s) {
                        return CompletableFuture.supplyAsync(s, player.server);
                    }
                    return CompletableFuture.completedFuture(null);
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == a[0];
                case "toString":
                    return "CitizenBedrockContext[" + player.getGameProfile().getName() + "]";
                default:
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, a);
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
            }
        };
        return Proxy.newProxyInstance(ctxClass.getClassLoader(), new Class<?>[]{ctxClass}, h);
    }
}
