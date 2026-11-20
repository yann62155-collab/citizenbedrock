package fr.citizen.bedrock;

import com.mojang.logging.LogUtils;
import fr.citizen.bedrock.bridge.BridgeManager;
import fr.citizen.bedrock.command.BedrockCommand;
import fr.citizen.bedrock.remap.RemapTables;
import fr.citizen.bedrock.selftest.SelfTest;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Citizen Bedrock : permet aux joueurs Bedrock (Xbox, PlayStation, Switch, mobile...) de rejoindre le serveur.
 *
 * Fonctionnement :
 *  - au démarrage, le mod lance en arrière-plan le pont (ViaProxy + Geyser) qui écoute les consoles ;
 *  - le pont se connecte au serveur en se présentant avec des données chiffrées (format Floodgate) ;
 *  - le mod vérifie ces données lui-même (pas besoin de Floodgate) et laisse entrer le joueur ;
 *  - pour chaque joueur console, les blocs, objets, entités et menus moddés sont convertis
 *    en équivalents que la console sait afficher.
 */
@Mod(CitizenBedrock.MODID)
public final class CitizenBedrock {
    public static final String MODID = "citizenbedrock";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CitizenBedrock(IEventBus modBus) {
        BedrockConfig.load();
        if (SelfTest.ENABLED) {
            SelfTest.registerTestContent(modBus);
        }
        modBus.addListener(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class, event ->
                event.registrar("1").optional().playBidirectional(
                        fr.citizen.bedrock.forms.FormPayload.TYPE, fr.citizen.bedrock.forms.FormPayload.CODEC,
                        (payload, context) -> {
                            if (context.player() instanceof ServerPlayer sp) {
                                fr.citizen.bedrock.forms.BedrockForms.handleResponse(sp, payload.data());
                            }
                        }));
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) -> {
            if (e.getEntity() instanceof ServerPlayer sp && BedrockPlayers.isBedrock(sp)) {
                fr.citizen.bedrock.forms.QuickMenu.tick(sp);
                ConsoleMounts.tick(sp);
            }
        });
        NeoForge.EVENT_BUS.addListener(this::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
    }

    private void onServerStarted(ServerStartedEvent event) {
        RemapTables.build();
        BridgeManager.start(event.getServer());
        fr.citizen.bedrock.web.WebServer.start();
        if (SelfTest.ENABLED) {
            SelfTest.run(event.getServer());
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        BridgeManager.stop();
        fr.citizen.bedrock.web.WebServer.stop();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        BedrockCommand.register(event.getDispatcher());
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BedrockSession session = BedrockSession.get(player.connection.getConnection());
            if (session != null) {
                BedrockPlayers.add(player.getUUID(), session);
                LOGGER.info("[Citizen Bedrock] {} s'est connecté depuis {} (gamertag {}).",
                        player.getGameProfile().getName(), session.data().deviceName(), session.data().username());
            }
        }
    }

    /** Affiches : sur PC le clic droit ouvre un écran côté jeu ; pour la console on ouvre un menu Bedrock. */
    private void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!BedrockPlayers.isBedrock(player)) return;
        var block = event.getLevel().getBlockState(event.getPos()).getBlock();
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        if ("citizenaffiches".equals(id.getNamespace()) && player.isShiftKeyDown()) {
            fr.citizen.bedrock.forms.CitizenModsBridge.editPoster(player, event.getPos());
        }
    }

    /** Lettres du hibou : lire une lettre scellée, ou ouvrir la volière avec une lettre vierge. */
    private void onRightClickItem(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!BedrockPlayers.isBedrock(player)) return;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (id.toString().equals("iceandfire:bestiary")) {
            fr.citizen.bedrock.forms.BestiaryBridge.open(player, event.getItemStack());
            return;
        }
        if (!"hiboupostal".equals(id.getNamespace())) return;
        if (id.getPath().equals("sealed_letter")) {
            fr.citizen.bedrock.forms.CitizenModsBridge.readLetter(player, event.getItemStack());
        } else if (id.getPath().equals("blank_letter")) {
            fr.citizen.bedrock.forms.CitizenModsBridge.openOwlery(player);
        }
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        BedrockPlayers.remove(event.getEntity().getUUID());
        fr.citizen.bedrock.forms.BedrockForms.forget(event.getEntity().getUUID());
        fr.citizen.bedrock.forms.QuickMenu.forget(event.getEntity().getUUID());
        ConsoleMounts.forget(event.getEntity().getUUID());
    }
}
