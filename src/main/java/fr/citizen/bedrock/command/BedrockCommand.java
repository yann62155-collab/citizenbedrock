package fr.citizen.bedrock.command;

import com.mojang.brigadier.CommandDispatcher;
import fr.citizen.bedrock.BedrockConfig;
import fr.citizen.bedrock.BedrockPlayers;
import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.bridge.BridgeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /citizenbedrock statut   : état du pont et joueurs console connectés
 * /citizenbedrock relancer : redémarre le pont sans redémarrer le serveur
 */
public final class BedrockCommand {
    private BedrockCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("citizenbedrock")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("statut").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("relancer").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("§6[Citizen Bedrock] §eRelance du pont..."), true);
                    Thread t = new Thread(BridgeManager::restart, "CitizenBedrock-Relance");
                    t.setDaemon(true);
                    t.start();
                    return 1;
                }))
                .executes(ctx -> status(ctx.getSource())));
    }

    private static int status(CommandSourceStack source) {
        BedrockConfig.Values cfg = BedrockConfig.VALUES;
        source.sendSuccess(() -> Component.literal("§6[Citizen Bedrock] §ePont : §f" + BridgeManager.state()
                + (BridgeManager.lastError().isEmpty() ? "" : " §c(" + BridgeManager.lastError() + ")")), false);
        source.sendSuccess(() -> Component.literal("§eAdresse console : §f" + cfg.adressePublique + " §eport §f" + cfg.portBedrock), false);
        source.sendSuccess(() -> Component.literal("§eVersions : §f" + BridgeManager.versions()), false);
        int count = BedrockPlayers.all().size();
        source.sendSuccess(() -> Component.literal("§eJoueurs console connectés : §f" + count), false);
        for (BedrockSession session : BedrockPlayers.all()) {
            source.sendSuccess(() -> Component.literal(" §7- §f" + session.profile().getName()
                    + " §7(" + session.data().deviceName() + ", gamertag " + session.data().username() + ")"), false);
        }
        return count;
    }
}
