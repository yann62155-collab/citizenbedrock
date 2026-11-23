package fr.citizen.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Produit un inventaire factuel des contenus moddés présents sur le serveur.
 *
 * Ce rapport sert à prioriser les adaptations Bedrock : une conversion générique
 * de registre permet l'affichage de remplacement, mais ne garantit pas le gameplay,
 * les interfaces, les interactions ou les recettes d'un mod.
 */
public final class BedrockCompatibilityReport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile Summary lastSummary = new Summary(0, 0, 0, 0, 0, 0, null);

    private BedrockCompatibilityReport() {
    }

    public record Summary(int mods, int blocks, int blockStates, int items, int entities,
                          int dataComponents, Path file) {
    }

    private static final class ModStats {
        int blocks;
        int blockStates;
        int items;
        int entities;
        int dataComponents;

        boolean isEmpty() {
            return blocks == 0 && blockStates == 0 && items == 0 && entities == 0 && dataComponents == 0;
        }
    }

    public static Summary lastSummary() {
        return lastSummary;
    }

    /** Reconstruit le rapport à partir des registres réellement chargés. */
    public static synchronized Summary generate() {
        Map<String, ModStats> byMod = new TreeMap<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (isModded(id)) byMod.computeIfAbsent(id.getNamespace(), ignored -> new ModStats()).blocks++;
        }

        for (int i = 0; i < Block.BLOCK_STATE_REGISTRY.size(); i++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(i);
            if (state == null) continue;
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (isModded(id)) byMod.computeIfAbsent(id.getNamespace(), ignored -> new ModStats()).blockStates++;
        }

        BuiltInRegistries.ITEM.forEach(item -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (isModded(id)) byMod.computeIfAbsent(id.getNamespace(), ignored -> new ModStats()).items++;
        });

        BuiltInRegistries.ENTITY_TYPE.forEach(entity -> {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity);
            if (isModded(id)) byMod.computeIfAbsent(id.getNamespace(), ignored -> new ModStats()).entities++;
        });

        BuiltInRegistries.DATA_COMPONENT_TYPE.forEach(component -> {
            ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component);
            if (isModded(id)) byMod.computeIfAbsent(id.getNamespace(), ignored -> new ModStats()).dataComponents++;
        });

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("generated_at_utc", Instant.now().toString());
        root.addProperty("minecraft_version", "1.21.1");
        root.addProperty("neoforge_version", "21.1.215");
        root.addProperty("purpose", "Inventaire des contenus moddés pour préparer leur adaptation Bedrock.");
        root.addProperty("important", "La présence d'un fallback visuel ne signifie pas que les mécaniques, menus, interactions, recettes ou comportements sont compatibles.");

        JsonObject totals = new JsonObject();
        int mods = 0, blocks = 0, states = 0, items = 0, entities = 0, components = 0;
        for (ModStats stats : byMod.values()) {
            if (stats.isEmpty()) continue;
            mods++;
            blocks += stats.blocks;
            states += stats.blockStates;
            items += stats.items;
            entities += stats.entities;
            components += stats.dataComponents;
        }
        totals.addProperty("mods_with_registered_content", mods);
        totals.addProperty("modded_blocks", blocks);
        totals.addProperty("modded_block_states", states);
        totals.addProperty("modded_items", items);
        totals.addProperty("modded_entities", entities);
        totals.addProperty("modded_data_components", components);
        root.add("totals", totals);

        JsonArray modList = new JsonArray();
        for (Map.Entry<String, ModStats> entry : byMod.entrySet()) {
            ModStats stats = entry.getValue();
            if (stats.isEmpty()) continue;
            JsonObject mod = new JsonObject();
            mod.addProperty("mod_id", entry.getKey());
            mod.addProperty("classification", "À classer : fonctionnalité joueur ou outil staff");
            mod.addProperty("blocks", stats.blocks);
            mod.addProperty("block_states", stats.blockStates);
            mod.addProperty("items", stats.items);
            mod.addProperty("entities", stats.entities);
            mod.addProperty("data_components", stats.dataComponents);
            mod.addProperty("block_display_fallback_available", stats.blocks > 0);
            mod.addProperty("item_display_fallback_available", stats.items > 0);
            mod.addProperty("entity_substitution_configurable", stats.entities > 0);
            mod.addProperty("feature_level_test_required", true);
            modList.add(mod);
        }
        root.add("mods", modList);

        JsonArray limitations = new JsonArray();
        limitations.add("Les blocs et objets peuvent recevoir une représentation vanilla de remplacement côté Bedrock.");
        limitations.add("Les menus, interfaces, interactions, recettes, capacités, IA, montures et animations nécessitent des tests et parfois un adaptateur dédié.");
        limitations.add("Les outils réservés au staff doivent être classés séparément et ne sont pas une priorité de compatibilité joueur.");
        limitations.add("Aucune donnée de gameplay n'est modifiée par la génération de ce rapport.");
        root.add("limitations", limitations);

        Path file = BedrockConfig.folder().resolve("compatibility-report.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
            lastSummary = new Summary(mods, blocks, states, items, entities, components, file);
            CitizenBedrock.LOGGER.info(
                    "[Citizen Bedrock] Audit Bedrock : {} namespaces, {} blocs, {} états, {} objets, {} entités, {} composants. Rapport : {}",
                    mods, blocks, states, items, entities, components, file);
        } catch (IOException | RuntimeException e) {
            CitizenBedrock.LOGGER.error("[Citizen Bedrock] Impossible d'écrire le rapport de compatibilité Bedrock.", e);
            lastSummary = new Summary(mods, blocks, states, items, entities, components, null);
        }
        return lastSummary;
    }

    private static boolean isModded(ResourceLocation id) {
        return id != null && !"minecraft".equals(id.getNamespace());
    }
}
