package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.ConsoleMounts;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Monture moddée montée par un joueur console : la console ne sait pas la piloter,
 * donc le serveur la déplace lui-même avec les commandes du joueur (au lieu d'attendre le jeu du joueur).
 */
@Mixin(Entity.class)
public abstract class EntityControlMixin {
    @Inject(method = "isControlledByLocalInstance", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$serverDrivesConsoleMount(CallbackInfoReturnable<Boolean> cir) {
        if (ConsoleMounts.serverDriven((Entity) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
