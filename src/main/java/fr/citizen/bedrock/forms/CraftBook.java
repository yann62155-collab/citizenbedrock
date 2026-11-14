package fr.citizen.bedrock.forms;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Livre des recettes (/craft) pour les joueurs console : mêmes catégories et mêmes règles RP que
 * l'écran PC de citizencraft, construit à partir des recettes du serveur.
 */
public final class CraftBook {
    private static final int PAGE = 40;

    private static final String NOTE_FORGE = "Artefacts, armures, filets de pêche, boîtes aux lettres, lettres, cartons d'envoi et affiches doivent être confectionnés à la forge.";
    private static final String NOTE_TAVERN = "Nourriture et bière doivent être préparées à la taverne ou au restaurant.";
    private static final String NOTE_MENUISIER = "Les portes à clé et les clés ne peuvent être fabriquées que par le menuisier.";
    private static final String NOTE_PECHE = "La pêche est réservée aux pêcheurs. Seuls le pêcheur, la taverne et le restaurant peuvent cuisiner le poisson.";

    private record Entry(String name, Item output, int count, RecipeHolder<?> recipe, String category) {
    }

    private static final String[] CATEGORIES = {
            "Pêche", "Artefacts", "Chasse & Boucherie", "Forge & Armures", "Nourriture & Bière",
            "Courrier", "Affiches", "Menuiserie", "Économie", "Minecraft", "Autres mods"
    };

    private CraftBook() {
    }

    public static void open(ServerPlayer player) {
        Map<String, List<Entry>> byCategory = index(player);
        BedrockForms.Simple form = BedrockForms.simple("Le Livre des Recettes du Royaume",
                "§7Choisis une catégorie, ou cherche une recette.");
        List<String> cats = new ArrayList<>();
        form.button("§lChercher une recette");
        for (String c : CATEGORIES) {
            int n = byCategory.getOrDefault(c, List.of()).size();
            if (n == 0) continue;
            cats.add(c);
            form.button("§l" + c + "\n§r§8" + n + " recette" + (n > 1 ? "s" : ""));
        }
        form.onClick(i -> {
            if (i == 0) {
                search(player, byCategory);
            } else {
                String c = cats.get(i - 1);
                list(player, c, byCategory.get(c), 0, () -> open(player));
            }
        }).send(player);
    }

    private static void search(ServerPlayer player, Map<String, List<Entry>> byCategory) {
        BedrockForms.custom("Chercher une recette")
                .input("Nom de l'objet", "ex : bière, épée, lettre…", "")
                .onSubmit(v -> {
                    String q = normalize(v.isEmpty() ? "" : v.get(0));
                    List<Entry> found = new ArrayList<>();
                    for (List<Entry> l : byCategory.values()) {
                        for (Entry e : l) if (normalize(e.name()).contains(q)) found.add(e);
                    }
                    found.sort(Comparator.comparing(Entry::name));
                    list(player, "Résultats : « " + (v.isEmpty() ? "" : v.get(0)) + " »", found, 0, () -> open(player));
                }).send(player);
    }

    private static void list(ServerPlayer player, String title, List<Entry> entries, int page, Runnable back) {
        int from = page * PAGE;
        int to = Math.min(entries.size(), from + PAGE);
        String note = note(title);
        BedrockForms.Simple form = BedrockForms.simple(title,
                (note.isEmpty() ? "" : "§6" + note + "\n\n") + (entries.isEmpty() ? "§7Aucune recette." : "§7Page " + (page + 1) + "/" + ((entries.size() - 1) / PAGE + 1)));
        for (int i = from; i < to; i++) {
            Entry e = entries.get(i);
            form.button(e.name() + (e.count() > 1 ? " §8×" + e.count() : ""));
        }
        boolean more = to < entries.size();
        if (more) form.button("§lPage suivante »");
        form.button("§8« Retour");
        int shown = to - from;
        form.onClick(i -> {
            if (i < shown) {
                detail(player, entries.get(from + i), () -> list(player, title, entries, page, back));
            } else if (more && i == shown) {
                list(player, title, entries, page + 1, back);
            } else {
                back.run();
            }
        }).send(player);
    }

    private static void detail(ServerPlayer player, Entry e, Runnable back) {
        Recipe<?> recipe = e.recipe().value();
        StringBuilder text = new StringBuilder();
        text.append("§7Type : §f").append(type(recipe)).append("\n§7Donne : §f").append(e.count()).append(" × ").append(e.name()).append("\n\n§6Ingrédients :§r\n");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;
            String name = items[0].getHoverName().getString() + (items.length > 1 ? " §8(ou autre)§r" : "");
            counts.merge(name, 1, Integer::sum);
        }
        if (counts.isEmpty()) text.append("§7(recette spéciale)\n");
        counts.forEach((n, c) -> text.append("§f• ").append(c).append(" × ").append(n).append('\n'));
        String note = note(e.category());
        if (!note.isEmpty()) text.append("\n§6").append(note);
        BedrockForms.simple(e.name(), text.toString()).button("§8« Retour").onClick(i -> back.run()).send(player);
    }

    // ------------------------------------------------------------------ index des recettes

    private static Map<String, List<Entry>> index(ServerPlayer player) {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        var access = player.server.registryAccess();
        for (RecipeHolder<?> holder : player.server.getRecipeManager().getRecipes()) {
            ItemStack result;
            try {
                result = holder.value().getResultItem(access);
            } catch (RuntimeException ex) {
                continue;
            }
            if (result == null || result.isEmpty()) continue;
            String cat = category(result);
            out.computeIfAbsent(cat, k -> new ArrayList<>())
                    .add(new Entry(result.getHoverName().getString(), result.getItem(), result.getCount(), holder, cat));
        }
        out.values().forEach(l -> l.sort(Comparator.comparing(Entry::name)));
        return out;
    }

    private static String category(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String ns = id.substring(0, id.indexOf(':'));
        String path = id.substring(id.indexOf(':') + 1);
        if (ns.equals("citizenartefacts")) return "Artefacts";
        if (ns.equals("hiboupostal")) return "Courrier";
        if (ns.equals("citizenaffiches")) return "Affiches";
        if (ns.equals("citizenmenuiserie")) return "Menuiserie";
        if (ns.equals("citizeneconomy")) return "Économie";
        if (path.contains("fish") || path.contains("rod") || path.contains("net") || ns.equals("tide") || ns.contains("fish")) return "Pêche";
        if (ns.equals("citizenchasse") || ns.equals("butchery") || path.contains("knife") || path.contains("carcass")) return "Chasse & Boucherie";
        if (ns.equals("citizenfonderie") || ns.equals("citizeneclipse") || stack.getItem() instanceof ArmorItem) return "Forge & Armures";
        if (stack.has(DataComponents.FOOD) || path.contains("beer") || path.contains("ale") || ns.equals("drinkbeer")) return "Nourriture & Bière";
        if (ns.equals("minecraft")) return "Minecraft";
        return "Autres mods";
    }

    private static String note(String category) {
        return switch (category) {
            case "Artefacts", "Forge & Armures", "Courrier", "Affiches" -> NOTE_FORGE;
            case "Nourriture & Bière" -> NOTE_TAVERN;
            case "Menuiserie" -> NOTE_MENUISIER;
            case "Pêche" -> NOTE_PECHE;
            default -> "";
        };
    }

    private static String type(Recipe<?> recipe) {
        String t = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
        return switch (t) {
            case "minecraft:crafting" -> "Établi";
            case "minecraft:smelting" -> "Fourneau";
            case "minecraft:blasting" -> "Haut fourneau";
            case "minecraft:smoking" -> "Fumoir";
            case "minecraft:campfire_cooking" -> "Feu de camp";
            case "minecraft:stonecutting" -> "Tailleur de pierre";
            case "minecraft:smithing" -> "Table de forgeron";
            default -> t;
        };
    }

    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim();
    }
}
