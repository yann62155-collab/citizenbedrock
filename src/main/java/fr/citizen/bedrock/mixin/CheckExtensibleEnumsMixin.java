package fr.citizen.bedrock.mixin;

import fr.citizen.bedrock.BedrockSession;
import net.minecraft.network.protocol.Packet;
import net.neoforged.neoforge.network.configuration.CheckExtensibleEnums;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Saute la vérification des « enums étendus » de NeoForge pour les joueurs console. */
@Mixin(value = CheckExtensibleEnums.class, remap = false)
public abstract class CheckExtensibleEnumsMixin {
    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void citizenbedrock$skipForBedrock(Consumer<Packet<?>> sender, CallbackInfo ci) {
        CheckExtensibleEnums self = (CheckExtensibleEnums) (Object) this;
        if (BedrockSession.get(self.listener().getConnection()) != null) {
            self.listener().finishCurrentTask(CheckExtensibleEnums.TYPE);
            ci.cancel();
        }
    }
}
