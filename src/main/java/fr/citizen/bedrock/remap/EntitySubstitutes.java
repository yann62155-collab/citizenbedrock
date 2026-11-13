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
            return null;
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
     * Dernier index de données d'entité commun au type moddé et au substitut :
     * Entity (0-7) + LivingEntity (8-14) + Mob (15). Le joueur n'a pas l'index 15 de Mob.
     */
    public static int maxSharedDataId(EntityType<?> substitute) {
        return substitute == EntityType.PLAYER ? 14 : 15;
    }
}
