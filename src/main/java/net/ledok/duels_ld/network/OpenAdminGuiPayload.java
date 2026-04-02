package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenAdminGuiPayload() implements CustomPacketPayload {
    public static final Type<OpenAdminGuiPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "open_admin_gui"));

    public static final StreamCodec<FriendlyByteBuf, OpenAdminGuiPayload> CODEC = StreamCodec.of(
        (buf, val) -> {},
        buf -> new OpenAdminGuiPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
