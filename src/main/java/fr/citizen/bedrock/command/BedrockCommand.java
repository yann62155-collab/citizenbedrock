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
        // /menu : menu rapide des joueurs console
        dispatcher.register(Commands.literal("menu").executes(ctx -> {
            var player = ctx.getSource().getPlayerOrException();
            if (!BedrockPlayers.isBedrock(player)) {
                ctx.getSource().sendFailure(Component.literal("Menu rapide réservé aux joueurs console."));
                return 0;
            }
            fr.citizen.bedrock.forms.QuickMenu.open(player);
            return 1;
        }));
        // /historique : relire les derniers messages du chat (joueurs console)
        dispatcher.register(Commands.literal("historique").executes(ctx -> {
            var player = ctx.getSource().getPlayerOrException();
            BedrockSession session = BedrockPlayers.get(player.getUUID());
            if (session == null) {
                ctx.getSource().sendFailure(Component.literal("Commande réservée aux joueurs console : sur PC, appuie sur T."));
                return 0;
            }
            java.util.List<String> lines = new java.util.ArrayList<>(session.chatHistory);
            int from = Math.max(0, lines.size() - 100);
            String text = lines.isEmpty() ? "§7Aucun message pour l'instant." : String.join("\n", lines.subList(from, lines.size()));
            fr.citizen.bedrock.forms.BedrockForms.simple("Historique du chat", text).button("Fermer").send(player);
            return 1;
        }));
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
        if (cfg.compteXboxAmis) {
            if (!BridgeManager.xboxLoginCode().isEmpty()) {
                source.sendSuccess(() -> Component.literal("§eCompte Xbox : §cà connecter §e- va sur §fmicrosoft.com/link§e, code §f§l" + BridgeManager.xboxLoginCode()), false);
            } else if (!BridgeManager.xboxAccount().isEmpty()) {
                source.sendSuccess(() -> Component.literal("§eCompte Xbox (onglet Amis) : §a" + BridgeManager.xboxAccount()), false);
            } else {
                source.sendSuccess(() -> Component.literal("§eCompte Xbox : §7démarrage..."), false);
            }
        }
        int count = BedrockPlayers.all().size();
        source.sendSuccess(() -> Component.literal("§eJoueurs console connectés : §f" + count), false);
        for (BedrockSession session : BedrockPlayers.all()) {
            source.sendSuccess(() -> Component.literal(" §7- §f" + session.profile().getName()
                    + " §7(" + session.data().deviceName() + ", gamertag " + session.data().username() + ")"), false);
        }
        return count;
    }
}
