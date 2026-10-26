package net.ledok.duels_ld.network;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Server → client: opens the author's review of one offer — both stakes side by side. Read-only:
 * nothing moves until the author accepts and both pass the ready check.
 */
public record OpenWagerReviewPayload(int offerId, int number, List<ItemStack> authorItems, long authorCoins,
                                     String challengerName, List<ItemStack> challengerItems, long challengerCoins,
                                     boolean challengerOnline, boolean skipLossWarning, boolean canSetSkip,
                                     int fightMinutes) implements CustomPacketPayload {
    public static final Type<OpenWagerReviewPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DuelsLdMod.MOD_ID, "open_wager_review"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenWagerReviewPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.offerId);
            buf.writeVarInt(p.number);
            ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.authorItems);
            buf.writeVarLong(p.authorCoins);
            buf.writeUtf(p.challengerName);
            ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.challengerItems);
            buf.writeVarLong(p.challengerCoins);
            buf.writeBoolean(p.challengerOnline);
            buf.writeBoolean(p.skipLossWarning);
            buf.writeBoolean(p.canSetSkip);
            buf.writeVarInt(p.fightMinutes);
        },
        buf -> new OpenWagerReviewPayload(buf.readVarInt(), buf.readVarInt(), ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf),
            buf.readVarLong(), buf.readUtf(), ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf), buf.readVarLong(),
            buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
