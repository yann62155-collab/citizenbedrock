package fr.citizen.bedrock;

import fr.citizen.bedrock.remap.RemapTables;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Montures moddées (dragons, hippogriffes, amphithères, vers des sables d'Ice and Fire...) pour les joueurs console.
 *
 * Sur PC, c'est le jeu du joueur qui pilote la monture et envoie des touches spéciales (monter, descendre, cracher le feu).
 * La console ne connaît pas ces créatures : c'est donc le serveur qui pilote à sa place, d'après
 *  - le joystick (avancer, tourner : la monture suit le regard),
 *  - le regard vers le haut / le bas en avançant, ou le saut, pour monter / descendre en vol,
 *  - s'accroupir en vol pour descendre (au sol, s'accroupir fait descendre de la monture),
 *  - frapper (gâchette droite) pour cracher le feu / la glace / la foudre.
 */
public final class ConsoleMounts {
    private static final byte UP = 1, DOWN = 2, ATTACK = 4;
    private static final Map<Class<?>, Optional<Method>> CONTROL = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> FLYING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> FIRE_TICKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SNEAK_DOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> TOLD = new ConcurrentHashMap<>();

    private ConsoleMounts() {
    }

    /** Monture moddée pilotée par un joueur console : le serveur la fait avancer lui-même. */
    public static boolean serverDriven(Entity vehicle) {
        if (vehicle.level().isClientSide || !(vehicle instanceof net.minecraft.world.entity.LivingEntity)) return false;
        if (!(vehicle.getControllingPassenger() instanceof ServerPlayer rider) || !BedrockPlayers.isBedrock(rider)) return false;
        return !RemapTables.isVanilla(BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()));
    }

    /** Monture qui se pilote avec les commandes spéciales d'Ice and Fire (monter / descendre / attaquer). */
    public static boolean hasControls(Entity vehicle) {
        return control(vehicle).isPresent();
    }

    /** En vol : s'accroupir fait descendre au lieu de lâcher la monture (sinon chute mortelle). */
    public static boolean keepRiding(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null || !BedrockPlayers.isBedrock(player) || !hasControls(vehicle)) return false;
        if (isFlying(vehicle) && !vehicle.onGround()) {
            SNEAK_DOWN.put(player.getUUID(), true);
            return true;
        }
        return false;
    }

    /** Coup dans le vide ou sur une cible en étant monté : souffle de la monture. */
    public static void swing(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        if (vehicle != null && BedrockPlayers.isBedrock(player) && hasControls(vehicle)) {
            FIRE_TICKS.put(player.getUUID(), 12);
        }
    }

    public static void tick(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        UUID id = player.getUUID();
        if (vehicle == null || vehicle.getControllingPassenger() != player) {
            TOLD.remove(id);
            return;
        }
        Optional<Method> control = control(vehicle);
        if (control.isEmpty()) return;
        if (TOLD.putIfAbsent(id, true) == null) {
            player.sendSystemMessage(Component.literal("§6[Monture] §eAvance en regardant vers le haut pour monter, vers le bas pour descendre."
                    + " §eSaute pour décoller, frappe pour cracher ton souffle. §7Accroupis-toi au sol pour descendre de ta monture."));
        }
        boolean forward = player.zza > 0.1f;
        float pitch = player.getXRot();
        boolean jumping = ((fr.citizen.bedrock.mixin.LivingEntityAccessor) player).citizenbedrock$isJumping();
        byte state = 0;
        if (jumping || (forward && pitch < -25f)) state |= UP;
        if (Boolean.TRUE.equals(SNEAK_DOWN.remove(id)) || player.isShiftKeyDown() || (forward && pitch > 30f)) state |= DOWN;
        int fire = FIRE_TICKS.getOrDefault(id, 0);
        if (fire > 0) {
            state |= ATTACK;
            FIRE_TICKS.put(id, fire - 1);
        }
        if ((state & UP) != 0 && (state & DOWN) != 0) state &= ~DOWN;
        try {
            control.get().invoke(vehicle, state);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CONTROL.put(vehicle.getClass(), Optional.empty());
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Commandes de monture indisponibles pour {}", vehicle.getType(), e);
        }
    }

    public static void dismount(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) return;
        player.stopRiding();
        if (isFlying(vehicle) && !vehicle.onGround()) {
            // petite protection : pas de chute mortelle en sautant d'un dragon en plein vol
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 200, 0, false, false));
        }
    }

    public static void forget(UUID id) {
        FIRE_TICKS.remove(id);
        SNEAK_DOWN.remove(id);
        TOLD.remove(id);
    }

    private static Optional<Method> control(Entity vehicle) {
        return CONTROL.computeIfAbsent(vehicle.getClass(), c -> {
            try {
                return Optional.of(c.getMethod("setControlState", byte.class));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
    }

    private static boolean isFlying(Entity vehicle) {
        Optional<Method> m = FLYING.computeIfAbsent(vehicle.getClass(), c -> {
            try {
                return Optional.of(c.getMethod("isFlying"));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
        try {
            return m.isPresent() && (boolean) m.get().invoke(vehicle);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
