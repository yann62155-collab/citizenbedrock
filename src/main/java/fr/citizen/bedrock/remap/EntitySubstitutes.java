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
            EntityType<?> projectile = projectileType(entity);
            if (projectile != null) return projectile;
            return carcassType(entity);
        }
        EntityType<?> special = specialCreature(entity, key);
        if (special != null) return special;
        // Créature moddée qui hérite d'une créature vanilla (ex : zombie de l'ombre -> zombie)
        EntityType<?> parent = vanillaParent(entity);
        if (parent != null) return parent;
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
     * Créatures qui ont besoin d'une apparence choisie à la main (Ice and Fire...).
     * Jamais de cheval / chameau / cochon pour une monture moddée : la console voudrait la piloter
     * elle-même et se battrait avec le serveur.
     */
    @Nullable
    private static EntityType<?> specialCreature(Entity entity, ResourceLocation key) {
        String path = key.getPath();
        String cls = entity.getClass().getName().toLowerCase(Locale.ROOT);
        if (isDragon(entity)) return EntityType.ENDER_DRAGON;
        if (path.contains("hippogryph") || path.contains("amphithere") || path.contains("stymphalian")) return EntityType.PHANTOM;
        if (path.contains("hippocampus")) return EntityType.DOLPHIN;
        if (path.contains("sea_serpent") || path.contains("seaserpent")) return EntityType.GUARDIAN;
        if (path.contains("death_worm") || path.contains("deathworm")) return EntityType.SILVERFISH;
        if (path.contains("cockatrice")) return EntityType.CHICKEN;
        if (path.contains("pixie")) return EntityType.ALLAY;
        if (path.contains("hydra")) return EntityType.RAVAGER;
        if (path.contains("cyclops") || path.contains("troll")) return EntityType.IRON_GOLEM;
        if (path.contains("gorgon") || path.contains("siren")) return EntityType.WITCH;
        if (path.contains("myrmex")) return EntityType.SPIDER;
        if (path.contains("ghost")) return EntityType.VEX;
        if (cls.contains("dread") && (path.contains("horse"))) return EntityType.SKELETON_HORSE;
        if (cls.contains("dread")) return path.contains("lich") ? EntityType.STRAY : EntityType.WITHER_SKELETON;
        return null;
    }

    /** Dragon d'Ice and Fire (ou d'un autre mod) : créature vivante dont la classe descend d'une base « dragon ». */
    public static boolean isDragon(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getSimpleName().toLowerCase(Locale.ROOT);
            if (n.contains("dragonfly")) return false;
            if (n.startsWith("entitydragon") || n.endsWith("dragonbase")) return true;
        }
        return false;
    }

    /** Tirs moddés (souffles de dragon, sorts...) montrés comme un tir vanilla pour qu'on les voie arriver. */
    @Nullable
    private static EntityType<?> projectileType(Entity entity) {
        String name = (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + " "
                + entity.getClass().getSimpleName()).toLowerCase(Locale.ROOT);
        if (entity instanceof net.minecraft.world.entity.projectile.AbstractHurtingProjectile) {
            if (name.contains("ice") || name.contains("frost") || name.contains("snow")) return EntityType.SNOWBALL;
            if (name.contains("lightning") || name.contains("magic") || name.contains("pixie")) return EntityType.DRAGON_FIREBALL;
            if (name.contains("wind")) return EntityType.WIND_CHARGE;
            return entity.getBbWidth() >= 0.8f ? EntityType.FIREBALL : EntityType.SMALL_FIREBALL;
        }
        if (entity instanceof net.minecraft.world.entity.projectile.AbstractArrow) {
            return name.contains("trident") || name.contains("spear") ? EntityType.TRIDENT : EntityType.ARROW;
        }
        if (entity instanceof net.minecraft.world.entity.projectile.ThrowableItemProjectile) {
            return EntityType.SNOWBALL;
        }
        return null;
    }

    /**
     * Taille à donner au substitut pour qu'il ait la taille de la vraie créature (1 = inchangée).
     * La console sait agrandir ou réduire n'importe quelle créature.
     */
    public static double scaleFor(Entity entity, EntityType<?> target) {
        if (!(entity instanceof LivingEntity) || target == EntityType.PLAYER) return 1.0;
        float tw = target.getWidth();
        float th = target.getHeight();
        if (tw <= 0 || th <= 0) return 1.0;
        double ratio;
        if (target == EntityType.ENDER_DRAGON) {
            // le modèle du dragon de l'End est bien plus long que haut : on se base sur la hauteur
            ratio = entity.getBbHeight() / th * 1.6;
        } else {
            ratio = Math.max(entity.getBbWidth() / tw, entity.getBbHeight() / th);
        }
        if (Math.abs(ratio - 1.0) < 0.15) return 1.0;
        return Math.max(0.1, Math.min(10.0, ratio));
    }

    private static final java.util.Map<Class<?>, EntityType<?>> VANILLA = new java.util.HashMap<>();

    /** Première classe vanilla dans l'héritage de la créature, si elle correspond à un type connu. */
    @Nullable
    private static EntityType<?> vanillaParent(Entity entity) {
        synchronized (VANILLA) {
            if (VANILLA.isEmpty()) {
                for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                    if (!RemapTables.isVanilla(BuiltInRegistries.ENTITY_TYPE.getKey(type))) continue;
                    Class<?> base = baseClassOf(type);
                    if (base != null) VANILLA.putIfAbsent(base, type);
                }
            }
        }
        for (Class<?> c = entity.getClass().getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getName().startsWith("net.minecraft.")) continue;
            EntityType<?> t = VANILLA.get(c);
            if (t != null) return t;
            return null; // première classe vanilla trouvée mais abstraite (Monster, Animal...) : règles par taille
        }
        return null;
    }

    /** Classe Java des créatures d'un type vanilla, déduite d'après son nom (zombie -> Zombie...). */
    @Nullable
    private static Class<?> baseClassOf(EntityType<?> type) {
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        String simple = sb.toString();
        for (String pkg : new String[]{"monster", "animal", "npc", "animal.horse", "monster.piglin", "animal.camel",
                "animal.frog", "animal.goat", "animal.allay", "animal.sniffer", "animal.axolotl", "animal.armadillo",
                "monster.warden", "monster.breeze", "boss.wither", "boss.enderdragon", "ambient", "animal.horse"}) {
            try {
                return Class.forName("net.minecraft.world.entity." + pkg + "." + simple, false, Entity.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
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
        if (substitute == EntityType.ENDER_DRAGON) return 14; // pas de données « Mob » utiles, et la phase du dragon (16) est à nous
        return substitute == EntityType.PLAYER ? 14 : 15;
    }
}
