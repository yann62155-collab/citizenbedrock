package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.BedrockSession;
import fr.citizen.bedrock.remap.ItemRemapper;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * En mode créatif, la console renvoie l'objet de remplacement (ex : papier) au lieu de l'objet moddé :
 * on refuse ce remplacement pour ne pas détruire l'objet réel.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    /** Clic dans un menu moddé affiché en coffre : on retraduit la case vers le vrai menu. */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "handleContainerClick", at = @At("HEAD"), argsOnly = true)
    private net.minecraft.network.protocol.game.ServerboundContainerClickPacket citizenbedrock$mapClick(
            net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
        if (!player.server.isSameThread()) {
            return packet; // Minecraft relance la méthode sur le fil principal : on traduit à ce moment-là
        }
        BedrockSession session = BedrockSession.get(player.connection.getConnection());
        if (session == null) return packet;
        fr.citizen.bedrock.remap.ContainerBridge.Mapping m = session.containerMappings.get(packet.getContainerId());
        if (m == null) return packet;
        net.minecraft.network.protocol.game.ServerboundContainerClickPacket mapped = fr.citizen.bedrock.remap.ContainerBridge.click(m, packet);
        if (mapped == null) {
            // case vide du coffre (hors du menu) : on renvoie l'état réel et on ignore le clic
            player.containerMenu.sendAllDataToRemote();
            return new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(-1, packet.getStateId(), -999, 0,
                    net.minecraft.world.inventory.ClickType.PICKUP, net.minecraft.world.item.ItemStack.EMPTY,
                    new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>());
        }
        return mapped;
    }

    /**
     * PNJ affichés en personnage : sur console, frapper le PNJ ouvre son dialogue (comme un clic droit),
     * pour que ça marche aussi sur les écrans tactiles où le bouton « interagir » n'apparaît pas.
     */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "handleInteract", at = @At("HEAD"), argsOnly = true)
    private net.minecraft.network.protocol.game.ServerboundInteractPacket citizenbedrock$npcClick(
            net.minecraft.network.protocol.game.ServerboundInteractPacket packet) {
        if (!player.server.isSameThread()) return packet;
        BedrockSession session = BedrockSession.get(player.connection.getConnection());
        if (session == null) return packet;
        int id = ((InteractPacketAccessor) packet).citizenbedrock$entityId();
        if (!session.fakePlayers.containsKey(id)) return packet;
        boolean[] attack = {false};
        packet.dispatch(new net.minecraft.network.protocol.game.ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(net.minecraft.world.InteractionHand hand) {
            }

            @Override
            public void onInteraction(net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.Vec3 pos) {
            }

            @Override
            public void onAttack() {
                attack[0] = true;
            }
        });
        net.minecraft.world.entity.Entity target = player.serverLevel().getEntity(id);
        if (!attack[0] || target == null) return packet;
        return net.minecraft.network.protocol.game.ServerboundInteractPacket.createInteractionPacket(target, false,
                net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** Fermeture d'un coffre avec action (colis à envoyer, machine à clés) : on propose d'abord l'action. */
    /** Joueur console sur une monture moddée : frapper = souffle de la monture (tâche principale uniquement). */
    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void citizenbedrock$mountBreath(net.minecraft.network.protocol.game.ServerboundSwingPacket packet, CallbackInfo ci) {
        fr.citizen.bedrock.ConsoleMounts.swing(((ServerGamePacketListenerImpl) (Object) this).player);
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$closeAction(net.minecraft.network.protocol.game.ServerboundContainerClosePacket packet, CallbackInfo ci) {
        if (!player.server.isSameThread()) return;
        BedrockSession session = BedrockSession.get(player.connection.getConnection());
        if (session == null) return;
        Runnable action = session.closeActions.remove(packet.getContainerId());
        if (action != null && player.containerMenu.containerId == packet.getContainerId()) {
            ci.cancel();
            action.run();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$protectModdedItems(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (!player.server.isSameThread()) {
            return; // Minecraft relance la méthode sur le fil principal
        }
        if (BedrockSession.get(player.connection.getConnection()) == null) {
            return;
        }
        if (ItemRemapper.isStandIn(packet.itemStack())) {
            ci.cancel();
            player.inventoryMenu.sendAllDataToRemote();
        }
    }
}
