package fr.citizen.bedrock.forms;

import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.ModList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bestiaire d'Ice and Fire : sur PC c'est un livre dessiné par le jeu du joueur.
 * Sur console on l'affiche en menus, avec les vrais textes du mod (en français),
 * et seulement les chapitres débloqués dans ce bestiaire.
 */
public final class BestiaryBridge {
    private static final String[] LANGS = {"fr_fr_0", "en_us_0"};
    private static final Map<String, String> TITLES = new LinkedHashMap<>();

    static {
        TITLES.put("INTRODUCTION", "Introduction");
        TITLES.put("FIREDRAGON", "Dragon de feu");
        TITLES.put("FIREDRAGONEGG", "Œuf de dragon de feu");
        TITLES.put("ICEDRAGON", "Dragon de glace");
        TITLES.put("ICEDRAGONEGG", "Œuf de dragon de glace");
        TITLES.put("TAMEDDRAGONS", "Dragons apprivoisés");
        TITLES.put("MATERIALS", "Matériaux de dragon");
        TITLES.put("ALCHEMY", "Alchimie");
        TITLES.put("DRAGONFORGE", "Forge draconique");
        TITLES.put("HIPPOGRYPH", "Hippogriffe");
        TITLES.put("GORGON", "Gorgone");
        TITLES.put("PIXIE", "Lutin");
        TITLES.put("CYCLOPS", "Cyclope");
        TITLES.put("SIREN", "Sirène");
        TITLES.put("HIPPOCAMPUS", "Hippocampe");
        TITLES.put("DEATHWORM", "Ver de la mort");
        TITLES.put("COCKATRICE", "Cocatrix");
        TITLES.put("STYMPHALIANBIRD", "Oiseau de Stymphale");
        TITLES.put("TROLL", "Troll");
        TITLES.put("MYRMEX", "Myrmex");
        TITLES.put("AMPHITHERE", "Amphithère");
        TITLES.put("SEASERPENT", "Serpent de mer");
        TITLES.put("HYDRA", "Hydre");
        TITLES.put("DREAD_MOBS", "Créatures de l'effroi");
        TITLES.put("LIGHTNINGDRAGON", "Dragon de foudre");
        TITLES.put("LIGHTNINGDRAGONEGG", "Œuf de dragon de foudre");
        TITLES.put("GHOST", "Fantôme");
    }

    private BestiaryBridge() {
    }

    /** Depuis le menu rapide : premier bestiaire trouvé dans l'inventaire. */
    public static void openFromInventory(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("iceandfire:bestiary")) {
                open(player, stack);
                return;
            }
        }
        BedrockForms.simple("Bestiaire", "§7Tu n'as pas de bestiaire sur toi.\nFabrique-le avec un livre et des pages trouvées dans le monde.")
                .button("Fermer").send(player);
    }

    public static void open(ServerPlayer player, ItemStack stack) {
        List<String> chapters = unlocked(stack);
        if (chapters.isEmpty()) {
            BedrockForms.simple("Bestiaire", "§7Ce bestiaire est encore vide.\nAjoute-lui des pages de manuscrit pour débloquer des chapitres.")
                    .button("Fermer").send(player);
            return;
        }
        BedrockForms.Simple form = BedrockForms.simple("Bestiaire", "§7" + chapters.size() + " chapitre(s) débloqué(s).");
        for (String c : chapters) form.button(TITLES.getOrDefault(c, c));
        form.onClick(i -> chapter(player, stack, chapters.get(i), 0)).send(player);
    }

    private static void chapter(ServerPlayer player, ItemStack stack, String chapter, int page) {
        List<String> pages = pages(chapter);
        if (pages.isEmpty()) {
            BedrockForms.simple(TITLES.getOrDefault(chapter, chapter), "§7Texte introuvable.").button("§8« Retour")
                    .onClick(i -> open(player, stack)).send(player);
            return;
        }
        int p = Math.max(0, Math.min(page, pages.size() - 1));
        String title = TITLES.getOrDefault(chapter, chapter) + " (" + (p + 1) + "/" + pages.size() + ")";
        List<Runnable> actions = new ArrayList<>();
        BedrockForms.Simple form = BedrockForms.simple(title, pages.get(p));
        if (p + 1 < pages.size()) {
            form.button("Page suivante »");
            actions.add(() -> chapter(player, stack, chapter, p + 1));
        }
        if (p > 0) {
            form.button("« Page précédente");
            actions.add(() -> chapter(player, stack, chapter, p - 1));
        }
        form.button("§8Sommaire");
        actions.add(() -> open(player, stack));
        form.onClick(i -> actions.get(i).run()).send(player);
    }

    /** Chapitres débloqués (tableau « Pages » de l'objet, numéros des chapitres dans l'ordre du mod). */
    private static List<String> unlocked(ItemStack stack) {
        List<String> out = new ArrayList<>();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return out;
        int[] ids = data.copyTag().getIntArray("Pages");
        List<String> all = new ArrayList<>(TITLES.keySet());
        try {
            Class<?> e = Class.forName("com.github.alexthe666.iceandfire.enums.EnumBestiaryPages");
            all.clear();
            for (Object c : e.getEnumConstants()) all.add(((Enum<?>) c).name());
        } catch (ClassNotFoundException ignored) {
        }
        for (int id : ids) {
            if (id >= 0 && id < all.size() && !out.contains(all.get(id))) out.add(all.get(id));
        }
        out.sort(java.util.Comparator.comparingInt(all::indexOf));
        return out;
    }

    /** Textes des pages, lus dans le fichier du mod (les dessins et recettes sont retirés). */
    private static List<String> pages(String chapter) {
        List<String> out = new ArrayList<>();
        var file = ModList.get().getModFileById("iceandfire");
        if (file == null) return out;
        String name = chapter.toLowerCase(java.util.Locale.ROOT);
        for (String lang : LANGS) {
            for (int n = 0; n < 20; n++) {
                Path path = file.getFile().findResource("assets", "iceandfire", "lang", "bestiary", lang, name + "_" + n + ".txt");
                if (!Files.exists(path)) break;
                try {
                    StringBuilder text = new StringBuilder();
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        String l = line.replaceAll("<[^>]*>", "").strip();
                        if (l.isEmpty()) {
                            if (text.length() > 0 && !text.toString().endsWith("\n\n")) text.append("\n\n");
                        } else {
                            if (text.length() > 0 && !text.toString().endsWith("\n")) text.append(' ');
                            text.append(l);
                        }
                    }
                    String page = text.toString().strip();
                    if (!page.isEmpty()) out.add(page);
                } catch (Exception e) {
                    CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Page de bestiaire illisible : {}", path, e);
                }
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }
}
