package net.ledok.duels_ld.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.ledok.duels_ld.DuelsLdMod;
import java.util.UUID;

public record DeclineRequestPayload(UUID senderId) implements CustomPacketPayload {
    public static final Type<DeclineRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "decline_request"));
    
    public static final StreamCodec<FriendlyByteBuf, DeclineRequestPayload> CODEC = StreamCodec.of(
        (buf, val) -> buf.writeUUID(val.senderId),
        buf -> new DeclineRequestPayload(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
