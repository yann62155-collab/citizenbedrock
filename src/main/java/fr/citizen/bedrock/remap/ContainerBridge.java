package fr.citizen.bedrock.remap;

import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.CitizenBedrock;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Menus moddés « à cases » (sacs, machines, fours...) affichés aux consoles comme un coffre de la bonne taille.
 * Les cases du mod sont rangées dans le coffre, l'inventaire du joueur en dessous ; chaque clic est
 * retraduit vers la vraie case côté serveur, donc la machine fonctionne normalement.
 */
public final class ContainerBridge {
    private static final MenuType<?>[] CHESTS = {
            MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
            MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6
    };

    /** Correspondance des cases pour un menu ouvert. */
    public static final class Mapping {
        final int[] clientToServer;
        final int[] serverToClient;
        final int clientSize;

        Mapping(int[] clientToServer, int serverSize) {
            this.clientToServer = clientToServer;
            this.clientSize = clientToServer.length;
            this.serverToClient = new int[serverSize];
            java.util.Arrays.fill(serverToClient, -1);
            for (int c = 0; c < clientToServer.length; c++) {
                int s = clientToServer[c];
                if (s >= 0 && s < serverSize) serverToClient[s] = c;
            }
        }
    }

    private ContainerBridge() {
    }

    /**
     * Le serveur ouvre un menu moddé : on attend qu'il soit réellement ouvert (fin du tick),
     * puis on envoie un coffre et son contenu réorganisé.
     */
    public static void scheduleOpen(ServerPlayer player, BedrockSession session, ClientboundOpenScreenPacket open) {
        session.pendingContainers.add(open.getContainerId());
        player.server.execute(() -> {
            session.pendingContainers.remove(open.getContainerId());
            AbstractContainerMenu menu = player.containerMenu;
            if (menu == null || menu.containerId != open.getContainerId()) {
                return; // menu déjà fermé
            }
            // Dialogue de PNJ Easy NPC : menu Bedrock avec le texte et les boutons du PNJ
            if (fr.citizen.bedrock.forms.EasyNpcBridge.tryShow(player, menu)) {
                session.hiddenContainers.add(menu.containerId);
                return;
            }
            // Menus Citizen remplacés par un menu Bedrock (établi de menuiserie...)
            if (fr.citizen.bedrock.forms.CitizenModsBridge.replaceMenu(player, menu)) {
                session.hiddenContainers.add(menu.containerId);
                return;
            }
            Mapping mapping = build(player, menu);
            if (mapping == null) {
                session.hiddenContainers.add(open.getContainerId());
                player.closeContainer();
                player.sendSystemMessage(Component.literal(fr.citizen.bedrock.BedrockConfig.VALUES.messageMenuNonAdapte));
                return;
            }
            session.containerMappings.put(menu.containerId, mapping);
            Runnable onClose = fr.citizen.bedrock.forms.CitizenModsBridge.closeAction(player, menu);
            if (onClose != null) session.closeActions.put(menu.containerId, onClose);
            int rows = (mapping.clientSize - 36) / 9;
            player.connection.send(new ClientboundOpenScreenPacket(menu.containerId, CHESTS[rows - 1], open.getTitle()));
            menu.sendAllDataToRemote();
        });
    }

    @Nullable
    private static Mapping build(ServerPlayer player, AbstractContainerMenu menu) {
        List<Integer> modSlots = new ArrayList<>();
        int[] playerSlots = new int[36]; // index d'inventaire joueur -> case serveur
        java.util.Arrays.fill(playerSlots, -1);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory()) {
                int inv = slot.getContainerSlot();
                if (inv >= 0 && inv < 36) playerSlots[inv] = i;
            } else {
                modSlots.add(i);
            }
        }
        if (modSlots.isEmpty()) return null;
        int rows = Math.min(6, Math.max(1, (modSlots.size() + 8) / 9));
        if (modSlots.size() > 54) {
            CitizenBedrock.LOGGER.debug("[Citizen Bedrock] Menu de {} cases : seules les 54 premières sont affichées sur console.", modSlots.size());
        }
        int[] clientToServer = new int[rows * 9 + 36];
        java.util.Arrays.fill(clientToServer, -1);
        for (int c = 0; c < rows * 9 && c < modSlots.size(); c++) {
            clientToServer[c] = modSlots.get(c);
        }
        // Inventaire principal (index 9 à 35) puis barre rapide (0 à 8), comme dans un coffre vanilla
        int base = rows * 9;
        for (int k = 0; k < 27; k++) clientToServer[base + k] = playerSlots[9 + k];
        for (int k = 0; k < 9; k++) clientToServer[base + 27 + k] = playerSlots[k];
        return new Mapping(clientToServer, menu.slots.size());
    }

    // ------------------------------------------------------------------ paquets sortants

    public static ClientboundContainerSetContentPacket content(Mapping m, ClientboundContainerSetContentPacket p) {
        NonNullList<ItemStack> items = NonNullList.withSize(m.clientSize, ItemStack.EMPTY);
        List<ItemStack> server = p.getItems();
        for (int c = 0; c < m.clientSize; c++) {
            int s = m.clientToServer[c];
            if (s >= 0 && s < server.size()) items.set(c, server.get(s));
        }
        return new ClientboundContainerSetContentPacket(p.getContainerId(), p.getStateId(), items, p.getCarriedItem());
    }

    @Nullable
    public static ClientboundContainerSetSlotPacket slot(Mapping m, ClientboundContainerSetSlotPacket p) {
        int s = p.getSlot();
        if (s < 0) return p; // objet tenu par la souris
        int c = s < m.serverToClient.length ? m.serverToClient[s] : -1;
        if (c < 0) return null;
        return new ClientboundContainerSetSlotPacket(p.getContainerId(), p.getStateId(), c, p.getItem());
    }

    // ------------------------------------------------------------------ clics de la console

    /** Retraduit un clic du coffre vers la vraie case du menu moddé. null = clic impossible. */
    @Nullable
    public static ServerboundContainerClickPacket click(Mapping m, ServerboundContainerClickPacket p) {
        int slot = p.getSlotNum();
        int serverSlot = slot;
        if (slot >= 0) {
            serverSlot = slot < m.clientSize ? m.clientToServer[slot] : -1;
            if (serverSlot < 0) return null;
        }
        Int2ObjectMap<ItemStack> changed = new Int2ObjectOpenHashMap<>();
        for (Int2ObjectMap.Entry<ItemStack> e : p.getChangedSlots().int2ObjectEntrySet()) {
            int c = e.getIntKey();
            int s = c >= 0 && c < m.clientSize ? m.clientToServer[c] : -1;
            if (s >= 0) changed.put(s, e.getValue());
        }
        return new ServerboundContainerClickPacket(p.getContainerId(), p.getStateId(), serverSlot, p.getButtonNum(),
                p.getClickType(), p.getCarriedItem(), changed);
    }
}
