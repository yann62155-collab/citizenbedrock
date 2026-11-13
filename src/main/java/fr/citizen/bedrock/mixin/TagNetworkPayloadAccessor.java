package fr.citizen.bedrock.mixin;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagNetworkSerialization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(TagNetworkSerialization.NetworkPayload.class)
public interface TagNetworkPayloadAccessor {
    @Accessor("tags")
    Map<ResourceLocation, IntList> citizenbedrock$tags();

    @Invoker("<init>")
    static TagNetworkSerialization.NetworkPayload citizenbedrock$create(Map<ResourceLocation, IntList> tags) {
        throw new AssertionError();
    }
}
