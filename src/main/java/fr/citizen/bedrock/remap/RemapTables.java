package fr.citizen.bedrock.remap;

import fr.citizen.bedrock.BedrockConfig;
import fr.citizen.bedrock.CitizenBedrock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tables de correspondance moddé -> vanilla, calculées une fois au démarrage du serveur.
 */
public final class RemapTables {
    private static volatile int[] blockStates = new int[0];
    private static volatile Map<Registry<?>, int[]> registries = new IdentityHashMap<>();
    private static int vanillaStateCount;
    private static int globalBits;
    private static int moddedStates;

    private RemapTables() {
    }

    public static int vanillaStateCount() {
        return vanillaStateCount;
    }

    /** Nombre de bits de la palette globale tel que le calcule un client vanilla. */
    public static int globalBits() {
        return globalBits;
    }

    public static int remapBlockState(int id) {
        int[] table = blockStates;
        return id >= 0 && id < table.length ? table[id] : id;
    }

    public static int remapRegistryId(Registry<?> registry, int id) {
        if (id < 0) {
            return id;
        }
        int[] table = registries.get(registry);
        return table != null && id < table.length ? table[id] : id;
    }

    public static boolean isVanilla(ResourceLocation id) {
        return id != null && "minecraft".equals(id.getNamespace());
    }

    // ------------------------------------------------------------------ construction

    public static synchronized void build() {
        long start = System.currentTimeMillis();
        buildBlockStates();
        buildRegistries();
        CitizenBedrock.LOGGER.info("[Citizen Bedrock] Tables de conversion prêtes : {} états vanilla, {} états moddés convertis, {} registres ({} ms).",
                vanillaStateCount, moddedStates, registries.size(), System.currentTimeMillis() - start);
    }

    private static void buildBlockStates() {
        int size = Block.BLOCK_STATE_REGISTRY.size();
        int[] table = new int[size];
        int vanilla = 0;
        int lastVanilla = -1;
        boolean contiguous = true;
        for (int id = 0; id < size; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state != null && isVanilla(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
                vanilla++;
                if (lastVanilla != id - 1) contiguous = false;
                lastVanilla = id;
            }
        }
        if (!contiguous) {
            CitizenBedrock.LOGGER.warn("[Citizen Bedrock] Les états de blocs vanilla ne sont pas contigus : l'affichage console peut être incorrect.");
        }
        vanillaStateCount = vanilla;
        globalBits = Mth.ceillog2(Math.max(1, vanilla));

        BlockMapper mapper = new BlockMapper();
        int modded = 0;
        for (int id = 0; id < size; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) {
                table[id] = 0;
                continue;
            }
            if (isVanilla(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
                table[id] = id;
            } else {
                BlockState target = mapper.map(state);
                int targetId = Block.BLOCK_STATE_REGISTRY.getId(target);
                table[id] = targetId >= 0 && targetId < vanilla ? targetId : 0;
                modded++;
            }
        }
        moddedStates = modded;
        blockStates = table;
    }

    /** État vanilla qui remplace un état moddé (ou l'état lui-même s'il est vanilla). */
    public static BlockState vanillaState(BlockState state) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        BlockState mapped = Block.BLOCK_STATE_REGISTRY.byId(remapBlockState(id));
        return mapped == null ? Blocks.STONE.defaultBlockState() : mapped;
    }

    private static final Map<String, String> REGISTRY_FALLBACKS = Map.ofEntries(
            Map.entry("minecraft:block", "minecraft:stone"),
            Map.entry("minecraft:entity_type", "minecraft:pig"),
            Map.entry("minecraft:block_entity_type", "minecraft:furnace"),
            Map.entry("minecraft:menu", "minecraft:generic_9x3"),
            Map.entry("minecraft:sound_event", "minecraft:intentionally_empty"),
            Map.entry("minecraft:particle_type", "minecraft:poof"),
            Map.entry("minecraft:mob_effect", "minecraft:luck"),
            Map.entry("minecraft:potion", "minecraft:water"),
            Map.entry("minecraft:fluid", "minecraft:empty"),
            Map.entry("minecraft:villager_profession", "minecraft:none"),
            Map.entry("minecraft:villager_type", "minecraft:plains"),
            Map.entry("minecraft:attribute", "minecraft:generic.luck"),
            Map.entry("minecraft:recipe_serializer", "minecraft:crafting_shapeless"),
            Map.entry("minecraft:recipe_type", "minecraft:crafting"),
            Map.entry("minecraft:stat_type", "minecraft:custom"),
            Map.entry("minecraft:custom_stat", "minecraft:jump"),
            Map.entry("minecraft:game_event", "minecraft:step")
    );

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void buildRegistries() {
        Map<Registry<?>, int[]> result = new IdentityHashMap<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            Registry<Object> reg = (Registry<Object>) registry;
            int size = reg.size();
            if (size == 0) continue;
            String regName = reg.key().location().toString();
            int fallback = fallbackId(reg, regName);
            boolean anyModded = false;
            int[] table = new int[size];
            for (int id = 0; id < size; id++) {
                Object value = reg.byId(id);
                ResourceLocation key = value == null ? null : reg.getKey(value);
                if (key == null || isVanilla(key)) {
                    table[id] = id;
                } else {
                    anyModded = true;
                    table[id] = specificFallback(regName, value, fallback);
                }
            }
            if (anyModded) {
                result.put(registry, table);
            }
        }
        registries = result;
    }

    private static int fallbackId(Registry<Object> reg, String regName) {
        String fallbackName = REGISTRY_FALLBACKS.get(regName);
        if ("minecraft:item".equals(regName)) {
            fallbackName = BedrockConfig.VALUES.objetParDefaut;
        }
        if (fallbackName != null) {
            ResourceLocation loc = ResourceLocation.tryParse(fallbackName);
            if (loc != null) {
                Optional<Object> value = reg.getOptional(loc);
                if (value.isPresent()) {
                    return reg.getId(value.get());
                }
            }
        }
        // Premier élément vanilla du registre
        for (int id = 0; id < reg.size(); id++) {
            Object value = reg.byId(id);
            if (value != null && isVanilla(reg.getKey(value))) {
                return id;
            }
        }
        return 0;
    }

    private static int specificFallback(String regName, Object value, int fallback) {
        try {
            if ("minecraft:block".equals(regName) && value instanceof Block block) {
                Block target = vanillaState(block.defaultBlockState()).getBlock();
                return BuiltInRegistries.BLOCK.getId(target);
            }
            if ("minecraft:item".equals(regName) && value instanceof Item item) {
                return BuiltInRegistries.ITEM.getId(ItemRemapper.fallbackItem(item));
            }
        } catch (RuntimeException ignored) {
            // on garde la valeur par défaut
        }
        return fallback;
    }

    // ------------------------------------------------------------------ choix du bloc vanilla

    /** Devine un bloc vanilla ressemblant à un bloc moddé. */
    static final class BlockMapper {
        private final Map<Integer, Block> byMapColor = new HashMap<>();
        private final Map<String, Block> forced = new HashMap<>();
        private final Block defaultBlock;

        BlockMapper() {
            defaultBlock = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(BedrockConfig.VALUES.blocParDefaut))
                    .filter(b -> isVanilla(BuiltInRegistries.BLOCK.getKey(b)))
                    .orElse(Blocks.STONE);
            BedrockConfig.VALUES.blocsForces.forEach((from, to) -> {
                ResourceLocation loc = ResourceLocation.tryParse(to);
                if (loc == null) return;
                BuiltInRegistries.BLOCK.getOptional(loc)
                        .filter(b -> isVanilla(BuiltInRegistries.BLOCK.getKey(b)))
                        .ifPresent(b -> forced.put(from, b));
            });
            Block[] preferred = {
                    Blocks.STONE, Blocks.OAK_PLANKS, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.WHITE_WOOL,
                    Blocks.IRON_BLOCK, Blocks.SNOW_BLOCK, Blocks.CLAY, Blocks.QUARTZ_BLOCK, Blocks.PACKED_ICE,
                    Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL, Blocks.LIGHT_BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL,
                    Blocks.PINK_WOOL, Blocks.GRAY_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.CYAN_WOOL, Blocks.PURPLE_WOOL,
                    Blocks.BLUE_WOOL, Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL,
                    Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.LAPIS_BLOCK, Blocks.EMERALD_BLOCK, Blocks.PODZOL,
                    Blocks.NETHERRACK, Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
                    Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA, Blocks.PINK_TERRACOTTA,
                    Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA,
                    Blocks.BLUE_TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA,
                    Blocks.BLACK_TERRACOTTA, Blocks.CRIMSON_NYLIUM, Blocks.CRIMSON_PLANKS, Blocks.CRIMSON_HYPHAE,
                    Blocks.WARPED_NYLIUM, Blocks.WARPED_PLANKS, Blocks.WARPED_HYPHAE, Blocks.WARPED_WART_BLOCK,
                    Blocks.DEEPSLATE, Blocks.RAW_IRON_BLOCK, Blocks.MOSS_BLOCK, Blocks.OBSIDIAN, Blocks.SPRUCE_PLANKS,
                    Blocks.BIRCH_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MUD, Blocks.SANDSTONE, Blocks.COBBLESTONE
            };
            for (Block block : preferred) {
                register(block);
            }
            // Toutes les autres couleurs : premier bloc vanilla plein qui l'utilise
            for (Block block : BuiltInRegistries.BLOCK) {
                if (isVanilla(BuiltInRegistries.BLOCK.getKey(block)) && isFullCube(block.defaultBlockState())) {
                    register(block);
                }
            }
        }

        private void register(Block block) {
            MapColor color = mapColor(block.defaultBlockState());
            if (color != null && color != MapColor.NONE) {
                byMapColor.putIfAbsent(color.id, block);
            }
        }

        BlockState map(BlockState state) {
            Block block = state.getBlock();
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            Block forcedTarget = forced.get(key.toString());
            if (forcedTarget == null) {
                forcedTarget = forced.get(key.getNamespace() + ":*");
            }
            Block target;
            try {
                target = forcedTarget != null ? forcedTarget : guess(state);
            } catch (RuntimeException e) {
                target = defaultBlock;
            }
            return copyProperties(state, target.defaultBlockState());
        }

        private Block guess(BlockState state) {
            Block b = state.getBlock();
            if (state.isAir()) return Blocks.AIR;
            if (b instanceof LiquidBlock) return Blocks.WATER;
            boolean wood = state.getSoundType() == SoundType.WOOD || state.getSoundType() == SoundType.NETHER_WOOD
                    || state.getSoundType() == SoundType.BAMBOO_WOOD || state.getSoundType() == SoundType.CHERRY_WOOD;
            boolean metal = state.getSoundType() == SoundType.METAL || state.getSoundType() == SoundType.COPPER;
            if (b instanceof SlabBlock) return wood ? Blocks.OAK_SLAB : Blocks.SMOOTH_STONE_SLAB;
            if (b instanceof StairBlock) return wood ? Blocks.OAK_STAIRS : Blocks.STONE_STAIRS;
            if (b instanceof WallBlock) return Blocks.COBBLESTONE_WALL;
            if (b instanceof FenceGateBlock) return Blocks.OAK_FENCE_GATE;
            if (b instanceof FenceBlock) return wood ? Blocks.OAK_FENCE : Blocks.NETHER_BRICK_FENCE;
            if (b instanceof DoorBlock) return metal ? Blocks.IRON_DOOR : Blocks.OAK_DOOR;
            if (b instanceof TrapDoorBlock) return metal ? Blocks.IRON_TRAPDOOR : Blocks.OAK_TRAPDOOR;
            if (b instanceof LeavesBlock) return Blocks.OAK_LEAVES;
            if (b instanceof SaplingBlock) return Blocks.OAK_SAPLING;
            if (b instanceof CropBlock) return Blocks.WHEAT;
            if (b instanceof FlowerBlock) return Blocks.POPPY;
            if (b instanceof WallTorchBlock) return Blocks.WALL_TORCH;
            if (b instanceof TorchBlock) return Blocks.TORCH;
            if (b instanceof LanternBlock) return Blocks.LANTERN;
            if (b instanceof CarpetBlock) return Blocks.WHITE_CARPET;
            if (b instanceof IronBarsBlock) return metal ? Blocks.IRON_BARS : Blocks.GLASS_PANE;
            if (b instanceof ButtonBlock) return wood ? Blocks.OAK_BUTTON : Blocks.STONE_BUTTON;
            if (b instanceof BasePressurePlateBlock) return wood ? Blocks.OAK_PRESSURE_PLATE : Blocks.STONE_PRESSURE_PLATE;
            if (b instanceof ChestBlock) return Blocks.CHEST;
            if (b instanceof BarrelBlock) return Blocks.BARREL;
            if (b instanceof LadderBlock) return Blocks.LADDER;
            if (b instanceof BedBlock) return Blocks.RED_BED;
            if (b instanceof WallSignBlock) return Blocks.OAK_WALL_SIGN;
            if (b instanceof StandingSignBlock) return Blocks.OAK_SIGN;
            if (b instanceof BushBlock) return Blocks.SHORT_GRASS;
            if (b instanceof RotatedPillarBlock) return wood ? Blocks.OAK_LOG : Blocks.QUARTZ_PILLAR;
            if (b instanceof TransparentBlock || b instanceof HalfTransparentBlock) return Blocks.GLASS;

            VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (collision.isEmpty()) {
                return Blocks.AIR; // décor sans collision : invisible plutôt qu'un bloc qui bloque
            }
            if (!isFullCube(state) && collision.max(Direction.Axis.Y) <= 0.5) {
                return Blocks.SMOOTH_STONE_SLAB;
            }
            if (!state.canOcclude() && isFullCube(state)) {
                return Blocks.GLASS;
            }
            MapColor color = mapColor(state);
            if (color != null) {
                Block byColor = byMapColor.get(color.id);
                if (byColor != null) return byColor;
            }
            return defaultBlock;
        }

        private static MapColor mapColor(BlockState state) {
            try {
                return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static boolean isFullCube(BlockState state) {
            try {
                return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** Recopie les propriétés de même nom (orientation, moitié haute/basse, eau...). */
        static BlockState copyProperties(BlockState from, BlockState to) {
            BlockState result = to;
            for (Property<?> targetProperty : to.getProperties()) {
                for (Property<?> sourceProperty : from.getProperties()) {
                    if (sourceProperty.getName().equals(targetProperty.getName())) {
                        result = copyOne(from, sourceProperty, result, targetProperty);
                        break;
                    }
                }
            }
            return result;
        }

        private static <S extends Comparable<S>, T extends Comparable<T>> BlockState copyOne(
                BlockState from, Property<S> source, BlockState to, Property<T> target) {
            String valueName = source.getName(from.getValue(source));
            Optional<T> value = target.getValue(valueName);
            return value.map(v -> to.setValue(target, v)).orElse(to);
        }
    }
}
