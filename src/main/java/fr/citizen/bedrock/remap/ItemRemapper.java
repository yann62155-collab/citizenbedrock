package fr.citizen.bedrock.remap;

import fr.citizen.bedrock.BedrockConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remplace les objets moddés par un objet vanilla qui garde le nom de l'objet d'origine.
 * L'objet réel reste intact côté serveur : seul l'affichage console change.
 */
public final class ItemRemapper {
    public static final String MARKER = "citizenbedrock_original";
    private static final Map<Item, Item> FALLBACKS = new ConcurrentHashMap<>();

    private ItemRemapper() {
    }

    public static boolean isVanilla(Item item) {
        return RemapTables.isVanilla(BuiltInRegistries.ITEM.getKey(item));
    }

    /** Vrai si l'objet reçu d'une console est un objet de remplacement créé par Citizen Bedrock. */
    public static boolean isStandIn(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARKER);
    }

    public static ItemStack toVanilla(ItemStack stack) {
        if (stack.isEmpty()) {
            return stack;
        }
        Item item = stack.getItem();
        if (isVanilla(item)) {
            return hasModdedData(stack) ? cleanVanilla(stack) : stack;
        }
        ItemStack out = new ItemStack(fallbackItem(item), stack.getCount());
        out.set(DataComponents.CUSTOM_NAME, Component.literal(stack.getHoverName().getString())
                .withStyle(style -> style.withItalic(false)));
        CompoundTag tag = new CompoundTag();
        tag.putString(MARKER, BuiltInRegistries.ITEM.getKey(item).toString());
        out.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        // Barre d'usure : même proportion que l'objet réel
        if (stack.isDamageableItem() && out.isDamageableItem() && stack.getMaxDamage() > 0) {
            int damage = Math.round((float) stack.getDamageValue() / stack.getMaxDamage() * out.getMaxDamage());
            out.setDamageValue(Math.min(out.getMaxDamage() - 1, Math.max(0, damage)));
        }
        if (stack.isEnchanted()) {
            out.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return out;
    }

    private static boolean hasModdedData(ItemStack stack) {
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : stack.getComponentsPatch().entrySet()) {
            if (!RemapTables.isVanilla(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey()))) {
                return true;
            }
        }
        Optional<? extends ItemAttributeModifiers> modifiers = stack.getComponentsPatch().get(DataComponents.ATTRIBUTE_MODIFIERS);
        return modifiers != null && modifiers.isPresent() && hasModdedAttribute(modifiers.get());
    }

    private static boolean hasModdedAttribute(ItemAttributeModifiers modifiers) {
        for (ItemAttributeModifiers.Entry e : modifiers.modifiers()) {
            if (!isVanillaAttribute(e.attribute())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVanillaAttribute(Holder<Attribute> attribute) {
        return attribute.unwrapKey().map(k -> RemapTables.isVanilla(k.location())).orElse(false);
    }

    /** Objet vanilla : on retire seulement les données ajoutées par des mods. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ItemStack cleanVanilla(ItemStack stack) {
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : stack.getComponentsPatch().entrySet()) {
            DataComponentType type = entry.getKey();
            if (!RemapTables.isVanilla(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type))) {
                continue;
            }
            if (entry.getValue().isPresent()) {
                Object value = entry.getValue().get();
                if (value instanceof ItemAttributeModifiers modifiers && hasModdedAttribute(modifiers)) {
                    List<ItemAttributeModifiers.Entry> kept = new ArrayList<>();
                    for (ItemAttributeModifiers.Entry e : modifiers.modifiers()) {
                        if (isVanillaAttribute(e.attribute())) kept.add(e);
                    }
                    value = new ItemAttributeModifiers(kept, modifiers.showInTooltip());
                }
                builder.set(type, value);
            } else {
                builder.remove(type);
            }
        }
        return new ItemStack(stack.getItemHolder(), stack.getCount(), builder.build());
    }

    /** Objet vanilla choisi pour représenter un objet moddé. */
    public static Item fallbackItem(Item item) {
        return FALLBACKS.computeIfAbsent(item, ItemRemapper::computeFallback);
    }

    private static Item computeFallback(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        String forced = BedrockConfig.VALUES.objetsForces.get(key.toString());
        if (forced == null) {
            forced = BedrockConfig.VALUES.objetsForces.get(key.getNamespace() + ":*");
        }
        if (forced != null) {
            ResourceLocation loc = ResourceLocation.tryParse(forced);
            if (loc != null) {
                Optional<Item> target = BuiltInRegistries.ITEM.getOptional(loc).filter(ItemRemapper::isVanilla);
                if (target.isPresent()) return target.get();
            }
        }
        if (item instanceof BlockItem blockItem) {
            Block mapped = RemapTables.vanillaState(blockItem.getBlock().defaultBlockState()).getBlock();
            Item asItem = mapped.asItem();
            if (mapped != Blocks.AIR && asItem != Items.AIR && isVanilla(asItem)) return asItem;
        }
        if (item instanceof SwordItem) return Items.IRON_SWORD;
        if (item instanceof AxeItem) return Items.IRON_AXE;
        if (item instanceof PickaxeItem) return Items.IRON_PICKAXE;
        if (item instanceof ShovelItem) return Items.IRON_SHOVEL;
        if (item instanceof HoeItem) return Items.IRON_HOE;
        if (item instanceof CrossbowItem) return Items.CROSSBOW;
        if (item instanceof BowItem) return Items.BOW;
        if (item instanceof TridentItem) return Items.TRIDENT;
        if (item instanceof ShieldItem) return Items.SHIELD;
        if (item instanceof FishingRodItem) return Items.FISHING_ROD;
        if (item instanceof SpawnEggItem) return Items.PIG_SPAWN_EGG;
        if (item instanceof BucketItem) return Items.WATER_BUCKET;
        if (item instanceof ArmorItem armor) {
            return switch (armor.getType()) {
                case HELMET -> Items.IRON_HELMET;
                case CHESTPLATE -> Items.IRON_CHESTPLATE;
                case LEGGINGS -> Items.IRON_LEGGINGS;
                case BOOTS -> Items.IRON_BOOTS;
                default -> Items.IRON_HORSE_ARMOR;
            };
        }
        if (item.components().has(DataComponents.FOOD)) return Items.BREAD;
        Optional<Item> configured = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(BedrockConfig.VALUES.objetParDefaut))
                .filter(ItemRemapper::isVanilla);
        return configured.orElse(Items.PAPER);
    }
}
