package fr.citizen.bedrock.selftest;

import fr.citizen.bedrock.BedrockContext;
import fr.citizen.bedrock.BedrockData;
import fr.citizen.bedrock.BedrockPlayers;
import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.CitizenBedrock;
import fr.citizen.bedrock.bridge.BridgeManager;
import fr.citizen.bedrock.floodgate.FloodgateCipher;
import fr.citizen.bedrock.floodgate.FloodgateKey;
import fr.citizen.bedrock.remap.ItemRemapper;
import fr.citizen.bedrock.remap.RemapTables;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Autotest lancé uniquement avec -Dcitizenbedrock.selftest=true (sur GitHub Actions) :
 * démarre un vrai serveur NeoForge, vérifie chaque conversion, simule la connexion d'un joueur console,
 * vérifie que le pont Geyser démarre, puis arrête le serveur avec un code de réussite ou d'échec.
 */
public final class SelfTest {
    public static final boolean ENABLED = Boolean.getBoolean("citizenbedrock.selftest");

    private static DeferredBlock<Block> testBlock;
    private static DeferredItem<Item> testItem;
    private static net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.entity.EntityType<?>, net.minecraft.world.entity.EntityType<net.minecraft.world.entity.monster.Zombie>> testNpc;

    /** Canal moddé « obligatoire » : sans Citizen Bedrock, NeoForge refuserait les clients sans mods. */
    public record TestPayload() implements CustomPacketPayload {
        public static final Type<TestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CitizenBedrock.MODID, "selftest"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TestPayload> CODEC = StreamCodec.unit(new TestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private SelfTest() {
    }

    public static void registerTestContent(IEventBus modBus) {
        DeferredRegister.Blocks blocks = DeferredRegister.createBlocks(CitizenBedrock.MODID);
        DeferredRegister.Items items = DeferredRegister.createItems(CitizenBedrock.MODID);
        testBlock = blocks.registerSimpleBlock("test_block", BlockBehaviour.Properties.of().strength(1.0f));
        items.registerSimpleBlockItem(testBlock);
        testItem = items.registerSimpleItem("test_sword_like_item");
        DeferredRegister<net.minecraft.world.entity.EntityType<?>> entities = DeferredRegister.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, CitizenBedrock.MODID);
        testNpc = entities.register("test_npc", () -> net.minecraft.world.entity.EntityType.Builder
                .<net.minecraft.world.entity.monster.Zombie>of(net.minecraft.world.entity.monster.Zombie::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.6f, 1.95f).build("test_npc"));
        entities.register(modBus);
        modBus.addListener(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent.class,
                event -> event.put(testNpc.get(), net.minecraft.world.entity.monster.Zombie.createAttributes().build()));
        blocks.register(modBus);
        items.register(modBus);
        // Étape de configuration qui ne se termine jamais sans client moddé (comme certains mods réels)
        modBus.addListener(net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent.class, event ->
                event.register(new net.neoforged.neoforge.network.configuration.ICustomConfigurationTask() {
                    @Override
                    public void run(java.util.function.Consumer<CustomPacketPayload> sender) {
                        // attend une réponse qui ne viendra jamais
                    }

                    @Override
                    public Type type() {
                        return new Type(ResourceLocation.fromNamespaceAndPath(CitizenBedrock.MODID, "selftest_blocking_task"));
                    }
                }));
        modBus.addListener(RegisterPayloadHandlersEvent.class, event ->
                event.registrar("1").playToClient(TestPayload.TYPE, TestPayload.CODEC, (payload, context) -> {
                }));
    }

    public static void run(MinecraftServer server) {
        Thread thread = new Thread(() -> {
            int code = 0;
            try {
                execute(server);
                CitizenBedrock.LOGGER.info("[Citizen Bedrock] SELFTEST OK");
            } catch (Throwable t) {
                code = 1;
                CitizenBedrock.LOGGER.error("[Citizen Bedrock] SELFTEST FAILED", t);
            }
            BridgeManager.stop();
            Runtime.getRuntime().halt(code);
        }, "CitizenBedrock-Autotest");
        thread.setDaemon(true);
        thread.start();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        CitizenBedrock.LOGGER.info("[Citizen Bedrock] [test] OK : {}", message);
    }

    private static void execute(MinecraftServer server) throws Exception {
        // 1. Les classes modifiées doivent se charger (une erreur de mixin ferait échouer ici)
        for (String name : new String[]{
                "net.minecraft.server.network.ServerHandshakePacketListenerImpl",
                "net.minecraft.server.network.ServerLoginPacketListenerImpl",
                "net.minecraft.server.network.ServerCommonPacketListenerImpl",
                "net.minecraft.server.network.ServerGamePacketListenerImpl",
                "net.minecraft.network.protocol.handshake.ClientIntentionPacket",
                "net.minecraft.network.PacketEncoder",
                "net.minecraft.world.item.ItemStack$1",
                "net.minecraft.world.item.ItemStack$2",
                "net.minecraft.tags.TagNetworkSerialization$NetworkPayload",
                "net.neoforged.neoforge.network.configuration.CheckExtensibleEnums",
                "net.neoforged.neoforge.network.configuration.CheckFeatureFlags"}) {
            Class.forName(name, true, SelfTest.class.getClassLoader());
        }
        check(true, "toutes les classes modifiées se chargent");

        // 2. Chiffrement compatible Floodgate
        String plain = String.join("\0", "1.21.50", "Test Xbox", "2535411111111111", "11", "fr_FR", "0", "3", "127.0.0.1", "null", "0", "-1", "null");
        String encrypted = FloodgateCipher.encrypt(plain, FloodgateKey.get());
        BedrockData data = BedrockData.parse(FloodgateCipher.decrypt(encrypted, FloodgateKey.get()));
        check("Test Xbox".equals(data.username()) && "Xbox".equals(data.deviceName()), "déchiffrement des données console");

        // 3. Conversions avec une session console simulée
        BedrockSession fake = new BedrockSession(data);
        BedrockContext.forceAny(true);
        try {
            BlockState modded = testBlock.get().defaultBlockState();
            int moddedId = Block.getId(modded);
            check(moddedId >= RemapTables.vanillaStateCount(), "le bloc de test est bien moddé (id " + moddedId + ")");
            int converted = BedrockContext.with(fake, () -> Block.getId(modded));
            check(converted < RemapTables.vanillaStateCount(), "état de bloc moddé converti en vanilla (" + converted + ")");
            check(Block.getId(Blocks.OAK_PLANKS.defaultBlockState()) == BedrockContext.with(fake, () -> Block.getId(Blocks.OAK_PLANKS.defaultBlockState())),
                    "les blocs vanilla ne changent pas");

            int itemId = BedrockContext.with(fake, () -> BuiltInRegistries.ITEM.getId(testItem.get()));
            check(itemId == BuiltInRegistries.ITEM.getId(ItemRemapper.fallbackItem(testItem.get())), "identifiant d'objet moddé converti");

            // Objet : écriture réseau pour console puis relecture
            ItemStack original = new ItemStack(testItem.get(), 3);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess(), ConnectionType.OTHER);
            BedrockContext.with(fake, () -> {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, original);
                return null;
            });
            ItemStack decoded = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            check(ItemRemapper.isVanilla(decoded.getItem()) && decoded.getCount() == 3, "objet moddé envoyé comme " + BuiltInRegistries.ITEM.getKey(decoded.getItem()));
            check(decoded.has(DataComponents.CUSTOM_NAME) && ItemRemapper.isStandIn(decoded), "l'objet garde son nom et sa marque");
            ItemStack vanilla = new ItemStack(Items.DIAMOND, 2);
            RegistryFriendlyByteBuf buf2 = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess(), ConnectionType.OTHER);
            BedrockContext.with(fake, () -> {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf2, vanilla);
                return null;
            });
            check(ItemStack.isSameItemSameComponents(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf2), vanilla), "objet vanilla inchangé");

            // Section de chunk : petite palette, puis palette globale (> 256 états différents)
            checkSection(fake, false);
            checkSection(fake, true);
        } finally {
            BedrockContext.forceAny(false);
        }

        // 4. Connexion d'un faux joueur console sur le vrai serveur (poignée de main, login, configuration, jeu)
        server.submit(() -> {
            BlockPos spawn = server.overworld().getSharedSpawnPos();
            server.overworld().setBlockAndUpdate(spawn.above(2), testBlock.get().defaultBlockState());
            net.minecraft.world.entity.monster.Zombie npc = testNpc.get().create(server.overworld());
            if (npc != null) {
                npc.setNoAi(true);
                npc.setCustomName(net.minecraft.network.chat.Component.literal("Enzo"));
                npc.moveTo(spawn.getX() + 2.5, spawn.getY() + 1, spawn.getZ() + 0.5);
                server.overworld().addFreshEntity(npc);
            }
        }).get(30, TimeUnit.SECONDS);
        UUID expected = new UUID(0L, Long.parseLong(data.xuid()));
        FakeBedrockClient client = new FakeBedrockClient("127.0.0.1", server.getPort(), encrypted, "Test Xbox");
        FakeBedrockClient.Result result = client.connect(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(expected);
            if (player != null) {
                // Un mod qui envoie des données sur son canal à un joueur console ne doit pas planter le serveur
                PacketDistributor.sendToPlayer(player, new TestPayload());
            }
        });
        check(result.reachedPlay(), "le joueur console arrive en jeu (paquets reçus : " + result.summary() + ")");
        check(!result.encryptionRequested(), "pas de vérification Mojang pour le joueur console");
        check(result.chunks() > 0, "chunks reçus et convertis : " + result.chunks());
        check(result.disconnectReason() == null, "pas d'expulsion (" + result.disconnectReason() + ")");
        check(result.summary().contains("play:0x3e"), "PNJ moddé affiché comme personnage (profil envoyé)");
        for (int i = 0; i < 20 && BedrockPlayers.isBedrock(expected); i++) {
            Thread.sleep(500);
        }
        check(!BedrockPlayers.isBedrock(expected), "le joueur est bien retiré après déconnexion");

        // 5. Le pont Geyser + ViaProxy démarre
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(4);
        while (BridgeManager.state() != BridgeManager.State.PRET && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
        }
        check(BridgeManager.state() == BridgeManager.State.PRET, "pont Bedrock prêt (" + BridgeManager.versions() + ")");

        // 6. Le compte Xbox (onglet Amis) démarre et demande sa connexion Microsoft
        deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3);
        while (BridgeManager.xboxLoginCode().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
        }
        check(!BridgeManager.xboxLoginCode().isEmpty(), "compte Xbox démarré, code de connexion affiché");
    }

    private static void checkSection(BedrockSession fake, boolean global) {
        PalettedContainer<BlockState> container = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        BlockState modded = testBlock.get().defaultBlockState();
        if (global) {
            int id = 1;
            for (int i = 0; i < 4096 && id < RemapTables.vanillaStateCount(); i += 3) {
                container.set(i & 15, i >> 8, (i >> 4) & 15, Block.stateById(id));
                id += 7;
            }
        }
        container.set(1, 2, 3, modded);
        container.set(4, 5, 6, Blocks.STONE.defaultBlockState());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY, ConnectionType.OTHER);
        int size = BedrockContext.with(fake, () -> {
            int s = container.getSerializedSize();
            container.write(buf);
            return s;
        });
        check(size == buf.writerIndex(), "taille de section exacte (" + (global ? "palette globale" : "petite palette") + ")");
        PalettedContainer<BlockState> read = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        if (global) {
            // Le serveur moddé a plus de bits que le client vanilla : on relit avec les règles vanilla
            int bits = buf.readByte();
            check(bits == RemapTables.globalBits(), "palette globale sur " + bits + " bits comme un client vanilla");
            return;
        }
        read.read(buf);
        check(read.get(1, 2, 3) == RemapTables.vanillaState(modded), "bloc moddé remplacé dans la section");
        check(read.get(4, 5, 6) == Blocks.STONE.defaultBlockState(), "bloc vanilla conservé dans la section");
    }
}
