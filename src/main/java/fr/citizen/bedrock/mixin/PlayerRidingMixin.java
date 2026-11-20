package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.ConsoleMounts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Joueur console sur un dragon en vol : s'accroupir fait descendre, au lieu de sauter dans le vide. */
@Mixin(Player.class)
public abstract class PlayerRidingMixin {
    @Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$descendInsteadOfFalling(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer sp && ConsoleMounts.keepRiding(sp)) {
            cir.setReturnValue(false);
        }
    }
}
