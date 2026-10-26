package net.ledok.duels_ld.wager;

import net.ledok.duels_ld.DuelsLdMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The duel bank: every listing, offer, pending delivery, finished duel and action log, in world
 * data ({@code data/duels_ld/wager_bank.dat}). Unlike other SavedData it is never left for the next
 * world save: {@link #flush} writes it (atomically) on every change, before any item or coin leaves
 * a player and before a delivery is attempted, so a crash can't lose or duplicate a stake.
 */
public final class WagerBank extends SavedData {
    private static final String DATA_ID = "duels_ld/wager_bank";
    private static final DateTimeFormatter LOG_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final Factory<WagerBank> FACTORY = new Factory<>(WagerBank::new, WagerBank::load, null);

    public enum ListingStatus { ACTIVE, PREPARING, FIGHTING, FINISHED, CANCELLED, EXPIRED }

    public enum OfferStatus { PENDING, READY_CHECK, FIGHTING, WON, LOST, DRAW, DECLINED, WITHDRAWN, CLOSED }

    /** An announcement (or personal challenge); its number is the duel number (#1042). */
    public static final class Listing {
        public int number;
        public UUID author;
        public String authorName;
        public Stake stake = Stake.EMPTY;
        public String comment = "";
        public long createdAt;
        public long expiresAt;
        /** Time left when the timer was paused (PREPARING/FIGHTING), else -1. */
        public long pausedRemaining = -1;
        @Nullable public UUID target;
        public String targetName = "";
        public ListingStatus status = ListingStatus.ACTIVE;
        /** The offer being fought, once accepted; 0 = none. */
        public int acceptedOffer;

        public boolean open() {
            return status == ListingStatus.ACTIVE || status == ListingStatus.PREPARING || status == ListingStatus.FIGHTING;
        }
    }

    public static final class Offer {
        public int id;
        public int number;
        public UUID challenger;
        public String challengerName;
        public Stake stake = Stake.EMPTY;
        public long createdAt;
        public OfferStatus status = OfferStatus.PENDING;

        public boolean open() {
            return status == OfferStatus.PENDING || status == OfferStatus.READY_CHECK || status == OfferStatus.FIGHTING;
        }
    }

    /** A finished duel, for History. */
    public static final class Finished {
        public int number;
        public long startedAt;
        public long endedAt;
        public UUID author;
        public String authorName;
        public UUID challenger;
        public String challengerName;
        public Stake authorStake = Stake.EMPTY;
        public Stake challengerStake = Stake.EMPTY;
        /** Null = draw. */
        @Nullable public UUID winner;
        public final Set<UUID> reportedBy = new HashSet<>();
    }

    /**
     * Coins and items owed to one player (a refund or winnings). Processed in steps, each written
     * down first, so a restart resumes instead of repeating: coins, then the inventory, then mail.
     */
    public static final class Delivery {
        public String nonce;
        public UUID recipient;
        public String recipientName;
        public Stake stake = Stake.EMPTY;
        /** Lang keys for the mail and chat line, and their arguments (strings). */
        public String titleKey;
        public String bodyKey;
        public List<String> args = new ArrayList<>();
        /** 0 = coins not yet given; 1 = giving (unknown outcome after a crash); 2 = done. */
        public int coinStage;
        /** Items still to deliver (the rest went into the inventory). */
        public List<net.minecraft.world.item.ItemStack> pendingItems = new ArrayList<>();
        public boolean itemsSplit;
        /** Coins the mail must carry (when giving them directly failed). */
        public long mailCoins;
        /** Mails confirmed sent; the next one may be in flight. */
        public int mailsSent;
        public boolean mailInFlight;
    }

    public int nextNumber = 1;
    public int nextOffer = 1;
    public final Map<Integer, Listing> listings = new LinkedHashMap<>();
    public final Map<Integer, Offer> offers = new LinkedHashMap<>();
    public final Map<Integer, Finished> finished = new LinkedHashMap<>();
    public final Map<Integer, List<String>> logs = new LinkedHashMap<>();
    public final Map<String, Delivery> deliveries = new LinkedHashMap<>();
    /** Wager wins / losses / draws per player. */
    public final Map<UUID, int[]> stats = new LinkedHashMap<>();

    private Path file;
    private HolderLookup.Provider registries;

    public static WagerBank get(MinecraftServer server) {
        WagerBank bank = server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
        bank.file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_ID + ".dat");
        bank.registries = server.registryAccess();
        return bank;
    }

    /** Adds a timestamped line to a duel's log (staff and complaints read it). Not flushed by itself. */
    public void log(int number, String line) {
        logs.computeIfAbsent(number, k -> new ArrayList<>())
            .add(LOG_TIME.format(Instant.now()) + " " + line);
    }

    public int[] statsOf(UUID player) {
        return stats.getOrDefault(player, new int[3]);
    }

    /** Writes the bank to disk now: a temp file, then an atomic replace. */
    public void flush() {
        if (file == null || registries == null) {
            setDirty();
            return;
        }
        try {
            CompoundTag root = new CompoundTag();
            root.put("data", save(new CompoundTag(), registries));
            NbtUtils.addCurrentDataVersion(root);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            setDirty(false);
        } catch (Exception e) {
            // Keep it dirty so the world save still writes it.
            DuelsLdMod.LOGGER.error("Failed to write the duel bank.", e);
            setDirty();
        }
    }

    // ── NBT ──────────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        tag.putInt("nextNumber", nextNumber);
        tag.putInt("nextOffer", nextOffer);

        ListTag listingTags = new ListTag();
        for (Listing l : listings.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("number", l.number);
            t.putUUID("author", l.author);
            t.putString("authorName", l.authorName);
            t.put("stake", stake(ops, l.stake));
            t.putString("comment", l.comment);
            t.putLong("createdAt", l.createdAt);
            t.putLong("expiresAt", l.expiresAt);
            t.putLong("pausedRemaining", l.pausedRemaining);
            if (l.target != null) {
                t.putUUID("target", l.target);
                t.putString("targetName", l.targetName);
            }
            t.putString("status", l.status.name());
            t.putInt("acceptedOffer", l.acceptedOffer);
            listingTags.add(t);
        }
        tag.put("listings", listingTags);

        ListTag offerTags = new ListTag();
        for (Offer o : offers.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("id", o.id);
            t.putInt("number", o.number);
            t.putUUID("challenger", o.challenger);
            t.putString("challengerName", o.challengerName);
            t.put("stake", stake(ops, o.stake));
            t.putLong("createdAt", o.createdAt);
            t.putString("status", o.status.name());
            offerTags.add(t);
        }
        tag.put("offers", offerTags);

        ListTag finishedTags = new ListTag();
        for (Finished f : finished.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("number", f.number);
            t.putLong("startedAt", f.startedAt);
            t.putLong("endedAt", f.endedAt);
            t.putUUID("author", f.author);
            t.putString("authorName", f.authorName);
            t.putUUID("challenger", f.challenger);
            t.putString("challengerName", f.challengerName);
            t.put("authorStake", stake(ops, f.authorStake));
            t.put("challengerStake", stake(ops, f.challengerStake));
            if (f.winner != null) {
                t.putUUID("winner", f.winner);
            }
            ListTag reported = new ListTag();
            for (UUID id : f.reportedBy) {
                reported.add(NbtUtils.createUUID(id));
            }
            t.put("reportedBy", reported);
            finishedTags.add(t);
        }
        tag.put("finished", finishedTags);

        CompoundTag logTags = new CompoundTag();
        logs.forEach((number, lines) -> {
            ListTag list = new ListTag();
            lines.forEach(line -> list.add(StringTag.valueOf(line)));
            logTags.put(Integer.toString(number), list);
        });
        tag.put("logs", logTags);

        ListTag deliveryTags = new ListTag();
        for (Delivery d : deliveries.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("nonce", d.nonce);
            t.putUUID("recipient", d.recipient);
            t.putString("recipientName", d.recipientName);
            t.put("stake", stake(ops, d.stake));
            t.putString("titleKey", d.titleKey);
            t.putString("bodyKey", d.bodyKey);
            ListTag args = new ListTag();
            d.args.forEach(a -> args.add(StringTag.valueOf(a)));
            t.put("args", args);
            t.putInt("coinStage", d.coinStage);
            t.put("pendingItems", stake(ops, new Stake(d.pendingItems, 0)));
            t.putBoolean("itemsSplit", d.itemsSplit);
            t.putLong("mailCoins", d.mailCoins);
            t.putInt("mailsSent", d.mailsSent);
            t.putBoolean("mailInFlight", d.mailInFlight);
            deliveryTags.add(t);
        }
        tag.put("deliveries", deliveryTags);

        CompoundTag statTags = new CompoundTag();
        stats.forEach((id, s) -> statTags.putIntArray(id.toString(), s));
        tag.put("stats", statTags);
        return tag;
    }

    private static WagerBank load(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        WagerBank bank = new WagerBank();
        bank.nextNumber = Math.max(1, tag.getInt("nextNumber"));
        bank.nextOffer = Math.max(1, tag.getInt("nextOffer"));

        for (Tag raw : tag.getList("listings", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Listing l = new Listing();
            l.number = t.getInt("number");
            l.author = t.getUUID("author");
            l.authorName = t.getString("authorName");
            l.stake = stake(ops, t.get("stake"));
            l.comment = t.getString("comment");
            l.createdAt = t.getLong("createdAt");
            l.expiresAt = t.getLong("expiresAt");
            l.pausedRemaining = t.getLong("pausedRemaining");
            if (t.hasUUID("target")) {
                l.target = t.getUUID("target");
                l.targetName = t.getString("targetName");
            }
            l.status = enumOf(ListingStatus.class, t.getString("status"), ListingStatus.CANCELLED);
            l.acceptedOffer = t.getInt("acceptedOffer");
            bank.listings.put(l.number, l);
        }
        for (Tag raw : tag.getList("offers", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Offer o = new Offer();
            o.id = t.getInt("id");
            o.number = t.getInt("number");
            o.challenger = t.getUUID("challenger");
            o.challengerName = t.getString("challengerName");
            o.stake = stake(ops, t.get("stake"));
            o.createdAt = t.getLong("createdAt");
            o.status = enumOf(OfferStatus.class, t.getString("status"), OfferStatus.CLOSED);
            bank.offers.put(o.id, o);
        }
        for (Tag raw : tag.getList("finished", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Finished f = new Finished();
            f.number = t.getInt("number");
            f.startedAt = t.getLong("startedAt");
            f.endedAt = t.getLong("endedAt");
            f.author = t.getUUID("author");
            f.authorName = t.getString("authorName");
            f.challenger = t.getUUID("challenger");
            f.challengerName = t.getString("challengerName");
            f.authorStake = stake(ops, t.get("authorStake"));
            f.challengerStake = stake(ops, t.get("challengerStake"));
            f.winner = t.hasUUID("winner") ? t.getUUID("winner") : null;
            for (Tag id : t.getList("reportedBy", Tag.TAG_INT_ARRAY)) {
                f.reportedBy.add(NbtUtils.loadUUID(id));
            }
            bank.finished.put(f.number, f);
        }
        CompoundTag logTags = tag.getCompound("logs");
        for (String key : logTags.getAllKeys()) {
            List<String> lines = new ArrayList<>();
            for (Tag line : logTags.getList(key, Tag.TAG_STRING)) {
                lines.add(line.getAsString());
            }
            try {
                bank.logs.put(Integer.parseInt(key), lines);
            } catch (NumberFormatException ignored) {
            }
        }
        for (Tag raw : tag.getList("deliveries", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Delivery d = new Delivery();
            d.nonce = t.getString("nonce");
            d.recipient = t.getUUID("recipient");
            d.recipientName = t.getString("recipientName");
            d.stake = stake(ops, t.get("stake"));
            d.titleKey = t.getString("titleKey");
            d.bodyKey = t.getString("bodyKey");
            for (Tag a : t.getList("args", Tag.TAG_STRING)) {
                d.args.add(a.getAsString());
            }
            d.coinStage = t.getInt("coinStage");
            d.pendingItems = new ArrayList<>(stake(ops, t.get("pendingItems")).items());
            d.itemsSplit = t.getBoolean("itemsSplit");
            d.mailCoins = t.getLong("mailCoins");
            d.mailsSent = t.getInt("mailsSent");
            d.mailInFlight = t.getBoolean("mailInFlight");
            bank.deliveries.put(d.nonce, d);
        }
        CompoundTag statTags = tag.getCompound("stats");
        for (String key : statTags.getAllKeys()) {
            try {
                int[] s = statTags.getIntArray(key);
                bank.stats.put(UUID.fromString(key), s.length == 3 ? s : new int[3]);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return bank;
    }

    private static Tag stake(RegistryOps<Tag> ops, Stake stake) {
        return Stake.CODEC.encodeStart(ops, stake).result().orElseGet(CompoundTag::new);
    }

    private static Stake stake(RegistryOps<Tag> ops, @Nullable Tag tag) {
        if (tag == null) {
            return Stake.EMPTY;
        }
        return Stake.CODEC.parse(ops, tag).resultOrPartial(error ->
            DuelsLdMod.LOGGER.error("Duel bank: unreadable stake ({}): {}", error, tag)).orElse(Stake.EMPTY);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
