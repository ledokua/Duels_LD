package net.ledok.duels_ld.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.ledok.duels_ld.DuelsLdMod;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SyncRequestsPayload(List<RequestData> requests) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SyncRequestsPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "sync_requests"));
    
    public static final StreamCodec<FriendlyByteBuf, SyncRequestsPayload> CODEC = StreamCodec.of(
        (buf, val) -> {
            buf.writeInt(val.requests.size());
            for (RequestData data : val.requests) {
                buf.writeUUID(data.senderId);
                buf.writeUtf(data.senderName);
                buf.writeUtf(data.settingsDesc);
            }
        },
        buf -> {
            int count = buf.readInt();
            List<RequestData> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                list.add(new RequestData(buf.readUUID(), buf.readUtf(), buf.readUtf()));
            }
            return new SyncRequestsPayload(list);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    
    public record RequestData(UUID senderId, String senderName, String settingsDesc) {}
}
