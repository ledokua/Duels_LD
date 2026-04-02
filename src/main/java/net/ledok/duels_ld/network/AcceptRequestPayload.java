package net.ledok.duels_ld.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.ledok.duels_ld.DuelsLdMod;
import java.util.UUID;

public record AcceptRequestPayload(UUID senderId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AcceptRequestPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "accept_request"));
    
    public static final StreamCodec<FriendlyByteBuf, AcceptRequestPayload> CODEC = StreamCodec.of(
        (buf, val) -> buf.writeUUID(val.senderId),
        buf -> new AcceptRequestPayload(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
