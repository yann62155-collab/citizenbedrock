package fr.citizen.bedrock.forms;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Canal « floodgate:form » : Geyser l'utilise pour afficher des menus natifs Bedrock. Données brutes. */
public record FormPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<FormPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("floodgate", "form"));
    public static final StreamCodec<ByteBuf, FormPayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeBytes(p.data),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new FormPayload(bytes);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
