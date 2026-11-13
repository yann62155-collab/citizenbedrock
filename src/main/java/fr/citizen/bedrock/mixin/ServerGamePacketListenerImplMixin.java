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
