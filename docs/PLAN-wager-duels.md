# Wager duels: plan (agreed 2026-10-26)

Source: the moderator's plan, "Дуелі на ставку — план механіки" (12 pages, Ukrainian). This file
records what the owner decided, the split between Duels_LD and Eden_LD, and the build order. Nothing
here is built yet.

## Owner decisions

| Question | Decision |
|---|---|
| Who draws the screens | **Split.** Eden's hub draws Announcements / My duels / History from `DuelsApi` data, in its own themes. Duels owns the screens that move items: create and offer (9 stake slots plus coins) and the "You stake / Opponent stakes / Winner gets" review. Eden's buttons open them. Items never pass through Eden's UI. |
| Without Eden_LD | **Wager duels require Eden.** Returns and winnings that don't fit go to Eden Mail. Money stakes also need Economy_LD; without it, stakes are items only. Ranked duels work everywhere. |
| Arenas | **Shared pool.** The same arenas as ranked 1v1. Wager fights and ranked matches wait in one arena queue. |
| Complaints | **Eden Moderation.** History's "Report" sends the duel number and its full action log to Eden's Moderation screen through an Eden API. |

Everything else follows the moderator's plan: the 8 stages, personal challenges, the statuses,
the refund table, the notifications, the anti-troll limits, and every number in the config.

## Split

**Duels_LD owns:**
- the rules: the state machine, the limits, the refunds;
- the duel bank (escrow);
- fights, numbering, history and the action log;
- the stake and review screens;
- the ready check;
- the commands `/duel`, `/duel <nick>`, `/duel list|cancel|ready|surrender`;
- the config.

**Eden_LD owns:**
- the hub pages Activities → Duels (Announcements / My duels / History);
- renaming today's tab to "Ranked PvP";
- Mail;
- Moderation reports;
- the two hub settings;
- per-player PvP (wager fights count as Duels matches).

## Duels side

- **Duel bank:** world SavedData `duels_ld/wager_bank`.
  - It holds listings, offers, stakes (item stacks plus coins), the next duel number and each
    duel's action log.
  - Every change is flushed to disk on its own, before any item or coin leaves a player. Order on
    staking: write the bank → take from the player. Order on payout or refund: mark it paying in
    the bank → deliver → write.
  - On server start, every listing or offer that isn't finished is refunded to Eden Mail. A fight
    that was running counts as a draw.
- **Money:** `EconomyManager.take` (async) must succeed before a stake counts. Refunds and winnings
  go back with `give`, or `deliverCurrency` for offline players.
- **Banned items:**
  - anything that holds items, checked through components: a non-empty `container`, non-empty
    `bundle_contents`, or a modded component that stores stacks;
  - plus the item tag `duels_ld:wager_banned`;
  - plus a config list.
  - Banning an item only affects new stakes.
- **Stake screens:** a server menu with 9 stake slots, shown with VectorLib (`CanvasHandledScreen`
  plus slot widgets). Offers can't be edited, only withdrawn and made again.
- **Ready check:** chat lines with `[Ready]` / `[Decline]` (run `/duel ready` / `/duel decline`),
  plus a 30 s countdown bar.
  - The start waits up to 15 s if a player took damage in the last 15 s (Duels tracks this
    itself).
  - 3 failed ready checks in 10 min block offering and accepting for 10 min.
- **Fight:** the existing 1v1 path.
  - Players fight in their own gear and drop nothing.
  - 5 min limit; time running out is a draw (not decided on points).
  - `/duel surrender` gives the win to the opponent.
  - The disconnect grace is 60 s, and both players offline is a draw.
  - BusyLib busy covers the ready check and the fight.
  - It fires `onMatchStart` / `onMatchEnd` like a ranked 1v1, so Eden's PvP exception applies.
    It never touches ratings or ranked stats.
- **History:** a global number (#1042), separate wager win/loss/draw counters, and an action log
  with times per duel.

## `DuelsApi` additions (draft, for Eden's hub pages)

```java
record Stake(List<ItemStack> items, long coins) {}
record WagerStats(int wins, int losses, int draws) {}
enum ListingState { ACTIVE, PREPARING, FIGHTING, FINISHED, CANCELLED, EXPIRED }
enum OfferState { PENDING, READY_CHECK, FIGHTING, WON, LOST, DRAW, DECLINED, WITHDRAWN, CLOSED }
record Listing(int id, UUID author, String authorName, WagerStats authorStats, Stake stake, String comment,
               long createdAtMillis, long expiresAtMillis, int offers, ListingState state,
               @Nullable UUID target /* personal */, boolean mine /* author == viewer */) {}
record Offer(int id, int listingId, UUID challenger, String challengerName, Stake stake, OfferState state,
             boolean online, long createdAtMillis) {}
record MyWagers(@Nullable Listing listing, List<Offer> offersOnMine, List<Offer> myOffers,
                List<Listing> challengesToMe, Stake inBank) {}
record WagerRecord(int number, long endedAtMillis, UUID author, String authorName, UUID challenger, String challengerName,
                   Stake authorStake, Stake challengerStake, @Nullable UUID winner /* null = draw */, long durationMillis,
                   boolean reportedByViewer) {}
// WagerResult: enum with Component message() for toasts; report() refuses a repeat (ALREADY_REPORTED)
// and text over the limit (config, default 300 chars).

static boolean wagersEnabled();                                   // Eden present + config switch
static List<Listing> listings(MinecraftServer, UUID viewer);      // public ones + personal ones for the viewer
static MyWagers myWagers(MinecraftServer, UUID player);
static List<WagerRecord> wagerHistory(MinecraftServer, UUID player, int limit);
static WagerStats wagerStats(MinecraftServer, UUID player);
// actions: open Duels screens, or act directly; results say why not
static WagerResult openCreate(ServerPlayer, @Nullable UUID target);
static WagerResult openTerms(ServerPlayer, int listingId);        // the offer screen
static WagerResult openReview(ServerPlayer, int offerId);         // the author's accept/decline screen
static WagerResult cancelListing(ServerPlayer);
static WagerResult withdrawOffer(ServerPlayer, int offerId);
static WagerResult declineOffer(ServerPlayer, int offerId);
static WagerResult report(ServerPlayer, int duelNumber, String text);
// Listener: default void onWagerChanged(UUID player) {}           // both sides, on every state change
//           default void onListingsChanged() {}                    // a public listing appeared or disappeared
```

## Mail, moderation and settings (answered by Eden, 2026-10-26)

1. **Mail is Economy_LD's**, not Eden's. Eden's Mail tab shows `net.ledok.economy_ld.mail.EconomyMail`
   (Economy_LD 1.0.2).
   - Send with `EconomyMail.send(MailDraft.builder(MailKind.SYSTEM|REWARD,
     MailContent.translated(titleKey, bodyKey, args)).sender(...).to(uuid).items(...).coins(n)
     .expiresInDays(d))`.
   - It's async: `CompletableFuture<Long>`, and an id ≥ 0 means delivered (-1 = nobody). Only a
     completed id counts as delivered.
   - **Idempotency is ours.** Every payout or refund gets a nonce in the mail args (`#1042/r3`).
     The mails use a fixed Duels sender UUID. At startup, an entry still in "paying" is first looked
     up in `EconomyMail.sent(duelsSender, limit)` by its nonce, and only re-sent if it's missing.
   - **Limit: `maxItems` per mail (default 12).** A winner gets up to 18 stacks, so attachments are
     split across several mails.
   - **Expiry deletes unclaimed items** (`systemExpiryDays` 7 by default, `maxExpiryDays` 30). Duels
     mails use `maxExpiryDays`. **Owner, 2026-10-26: 30 days is fine.** The mail text says
     unclaimed items are lost after that.
   - Sender: "duels_ld.mail.sender", which Eden will translate (it already does "economy_ld.").
   - Eden adds a generic join line: "You have N unread letters [Open mail]".
2. **Moderation:** `EdenModerationApi.report(MinecraftServer, UUID reporter, String category,
   String reference, String text, List<String> log)` returns a report id, or -1 if refused (rate
   limit). Eden is adding a Reports section to Moderation. Duels sends category `duels_ld:wager` and
   reference `#1042`.
3. **Settings:** `EdenSettingsApi.getBoolean(MinecraftServer, UUID, String key, boolean def)` (works
   for offline players).
   - Keys: `duels_ld:wager.skip_loss_warning` (default false) and
     `duels_ld:wager.personal_challenges` (default true).
   - Eden shows them in a "Duels" settings group with its own labels.
4. **Teleports:** the pack has no /home or /tpa mod. Eden's hub teleports already refuse while
   busy, but in-world waystones and warp items don't. Duels keeps the "leaving the arena puts you
   back" rule.
5. **Hub pages and the tab rename are Eden's.** Sorting by amount uses coins only.

## Build order

1. Bank + state machine + config + commands, with no screens (testable with commands).
2. Stake and review screens; the ready check.
3. Fight hookup, payout, history, log.
4. The `DuelsApi` wager surface, then Eden's hub pages.

**Status (2026-10-26, 1.6.0):**
- Steps 1 and 3 (bank, rules, ready check, arena wait, fight, payout, history, log, recovery,
  complaints) and step 4 (the `DuelsApi` surface) are built.
- Tested on a dev server with two clients:
  - escrow and coin matching;
  - the arena wait and leaving it;
  - startup recovery to mail;
  - a full fight with surrender and payout;
  - a declined personal challenge;
  - the filled-shulker ban.
- Step 2 (stake screens, the loss warning, `openCreate`/`openTerms`/`openReview`) is done in 1.7.0.
  It was tested in the dev client: create, offer, review and accept through the screens, the
  ready-check timeout refund, red refused items, and returning items when the screen is closed.

Previously waiting on Eden (now done in Eden 1.3.11): `EdenSettingsApi` (needed in step 2) and `EdenModerationApi` (needed in step 3).
5. Crash and restart tests, dupe tests (full inventory, disconnects at every step, a server kill
   mid-payout).

## Frozen signatures for step 2 (agreed with Eden, 2026-10-26)

Eden looks these up by reflection; when they exist, its Create / Offer / Review buttons go live.
They must not be added as stubs: they ship only together with the working screens.

```java
public static WagerResult openCreate(ServerPlayer player, @Nullable UUID target);   // null = public listing
public static WagerResult openTerms(ServerPlayer player, int number);              // duel (listing) number
public static WagerResult openReview(ServerPlayer author, int offerId);            // offer ids are int
```

They're in `net.ledok.duels_ld.api.DuelsApi`, all static, and return `net.ledok.duels_ld.api.WagerResult`.

