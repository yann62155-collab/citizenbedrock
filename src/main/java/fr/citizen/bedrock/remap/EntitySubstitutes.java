package fr.citizen.bedrock.remap;

import fr.citizen.bedrock.BedrockConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Npc;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Choisit une créature vanilla pour représenter une créature moddée chez les joueurs console.
 * PNJ / humanoïdes -> personnage (joueur factice), monstres -> monstre vanilla de taille proche, etc.
 */
public final class EntitySubstitutes {
    private EntitySubstitutes() {
    }

    /** null = ne pas afficher (projectiles, effets... qui n'ont pas d'équivalent sûr). */
    @Nullable
    public static EntityType<?> choose(Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String forced = BedrockConfig.VALUES.entitesForcees.get(key.toString());
        if (forced == null) forced = BedrockConfig.VALUES.entitesForcees.get(key.getNamespace() + ":*");
        if (forced != null) {
            if (forced.equalsIgnoreCase("cache") || forced.equalsIgnoreCase("hide")) return null;
            ResourceLocation loc = ResourceLocation.tryParse(forced);
            if (loc != null && RemapTables.isVanilla(loc)) {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(loc);
                if (type != null) return type;
            }
        }
        if (!(entity instanceof LivingEntity)) {
            return carcassType(entity);
        }
        String name = (key.toString() + " " + entity.getClass().getName()).toLowerCase(Locale.ROOT);
        float width = entity.getBbWidth();
        float height = entity.getBbHeight();
        if (entity instanceof Npc || name.contains("npc") || name.contains("humanoid") || name.contains("villager")
                || name.contains("guard") || name.contains("knight") || name.contains("trader") || name.contains("merchant")) {
            return EntityType.PLAYER;
        }
        if (entity instanceof Enemy) {
            if (width >= 3.0f || height >= 4.0f) return EntityType.RAVAGER;
            if (entity instanceof FlyingAnimal || name.contains("bat") || name.contains("wyvern")) return EntityType.PHANTOM;
            if (height >= 2.5f) return EntityType.IRON_GOLEM;
            if (width >= 1.3f) return EntityType.SPIDER;
            if (name.contains("skeleton") || name.contains("lich")) return EntityType.SKELETON;
            return EntityType.ZOMBIE;
        }
        if (entity instanceof WaterAnimal) {
            return width >= 1.2f ? EntityType.DOLPHIN : EntityType.COD;
        }
        if (entity instanceof FlyingAnimal) {
            return EntityType.PARROT;
        }
        if (entity instanceof Animal || name.contains("animal")) {
            if (width >= 2.5f) return EntityType.CAMEL;
            if (width >= 1.3f) return EntityType.HORSE;
            if (height >= 1.2f) return EntityType.COW;
            if (width >= 0.8f) return EntityType.PIG;
            if (width >= 0.5f) return EntityType.FOX;
            return EntityType.RABBIT;
        }
        if (width >= 3.0f) return EntityType.RAVAGER;
        if (height >= 1.5f) return EntityType.PLAYER;
        return EntityType.PIG;
    }

    /**
     * Carcasses (mod citizenchasse) : affichées comme l'animal chassé, immobile.
     * Le clic droit dessus arrive bien au serveur, donc on peut les ramasser comme sur PC.
     */
    @Nullable
    private static EntityType<?> carcassType(Entity entity) {
        try {
            java.lang.reflect.Method getCarcass = entity.getClass().getMethod("getCarcass");
            Object data = getCarcass.invoke(entity);
            if (data == null) return EntityType.PIG;
            Object opt = data.getClass().getMethod("type").invoke(data);
            if (opt instanceof java.util.Optional<?> o && o.isPresent() && o.get() instanceof EntityType<?> type) {
                if (RemapTables.isVanilla(BuiltInRegistries.ENTITY_TYPE.getKey(type))) return type;
            }
            return EntityType.PIG;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // autre entité non vivante (projectile, effet...) : pas d'équivalent sûr
        }
    }

    /** Nom affiché au-dessus d'une carcasse, ou null. */
    @Nullable
    public static String carcassLabel(Entity entity) {
        try {
            Object data = entity.getClass().getMethod("getCarcass").invoke(entity);
            if (data == null) return "Carcasse";
            Object species = data.getClass().getMethod("speciesName").invoke(data);
            float weight = (float) data.getClass().getMethod("weight").invoke(data);
            String name = species instanceof net.minecraft.network.chat.Component c ? c.getString() : "animal";
            return String.format(Locale.ROOT, "Carcasse de %s (%.1f kg)", name, weight);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Dernier index de données d'entité commun au type moddé et au substitut :
     * Entity (0-7) + LivingEntity (8-14) + Mob (15). Le joueur n'a pas l'index 15 de Mob.
     */
    public static int maxSharedDataId(Entity original, EntityType<?> substitute) {
        if (!(original instanceof LivingEntity)) return 7; // seulement les données communes à toutes les entités
        return substitute == EntityType.PLAYER ? 14 : 15;
    }
}
