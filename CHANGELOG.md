# Changelog

## 1.7.2 — EMI off the stake screen

- Fixed: with EMI installed, clicking a stake slot could open an EMI recipe instead of placing the
  item. EMI hooks every container screen for input, and without bounds it laid its (invisible)
  favorites and index panels out under the stake screen. A small EMI plugin
  (`client/compat/DuelsEmiPlugin`, the "emi" entrypoint) now claims the whole stake screen, so EMI
  neither shows nor handles anything there (no EMI on player screens). EMI stays optional.

## 1.7.1 — themes follow the Eden hub

Deployed 2026-10-26 (Prism, pack repo, live) with Eden_LD 1.3.13; live at the next restart.

- The player screens (matchmaking, stake, offer, review) have four looks:
  - **Hub theme:** the theme picked in the Eden hub (parchment, dark, overgrown, stone tablet,
    and any resource-pack theme). It's read from Eden's `EdenHubThemeApi` (Eden 1.3.13+) when a
    screen opens, and an open screen rebuilds when the hub theme changes. This is the default
    when Eden has the API.
  - **Parchment:** Duels' own copy of the family parchment look. It works without Eden, and it's
    the default when Eden doesn't have the API.
  - **Modern** and **Vanilla**, as before.
- The header button cycles through them and shows the next one. The choice is still kept in
  `config/duels_ld_client.json`.
- Staff screens (matchmaking settings, arena manager) stay Modern.

## 1.7.0 — wager stake screens

Deployed 2026-10-26 to the Fallen Eden pack and live server, with Eden_LD 1.3.12; live at the next restart.

- **Create screen** (`/duel`, `/duel <player>` for a personal challenge, or Eden's "Create duel"):
  - 9 stake slots, coins with your balance shown, and an optional comment;
  - the fight rules and the honesty warning;
  - your inventory: items that can't be staked show red with the reason, and can't go into the slots.
  - "Post listing" opens a summary first. The stake moves into the duel bank only after that.
- **Offer screen** (`/duel terms <#>`, the [Terms] buttons, Eden's "Offer a stake"):
  - the author's stake with full item tooltips, their comment and a live countdown;
  - your stake slots;
  - coins locked to the author's amount when the listing has coins.
  - "Offer stake" shows the loss warning first.
- **Review screen** (`/duel review <offer>`, the [Review] button, Eden's "Review"):
  - both stakes side by side, what the winner gets, and whether the challenger is online.
  - Accept goes through the loss warning, unless the Eden setting turns it off. Decline returns the
    challenger's stake.
  - A "Don't ask again" box appears once Eden can store the setting.
- Closing a stake screen gives back whatever is in its slots. While coins are being taken the slots
  are locked, and the server checks everything again.
- **`DuelsApi`:** `openCreate(ServerPlayer, @Nullable UUID)`, `openTerms(ServerPlayer, int)` and
  `openReview(ServerPlayer, int)`.
- Chat buttons now open the screens: [Review] on new offers, [Terms] on challenges and in `/duel list`.
- Wager actions refuse a dead player, instead of opening a screen that closes at once.
- "1 item" instead of "1 items".

## 1.6.0 — wager duels, server side (in progress)

Players stake items and/or Economy_LD coins on a 1v1. The winner takes both stakes; every other
ending (cancel, timeout, decline, draw, restart) returns them. Plan: docs/PLAN-wager-duels.md.
This release has the server side; the stake screens came in 1.7.0. Staff can still stake the
held item with `/duel wager test …`.

- **Duel bank** (world data `duels_ld/wager_bank`):
  - It holds every listing, offer, stake, finished duel and timestamped action log, and the
    deliveries still owed.
  - It's written to disk (atomically) before anything leaves a player.
  - Player data is saved right after items enter or leave an inventory, so a crash can't leave a
    stake both in the bank and with the player.
  - On startup every open listing and offer is refunded by mail. Deliveries that were cut off
    resume, and a mail that may already have been sent is looked up by its nonce first.
- **Rules (all numbers in `config/duels_ld/wager.json`):**
  - Listings last 30 min; personal challenges via a target.
  - Up to 5 offers per listing and 3 active offers per player.
  - The challenger's coins match the author's.
  - Minimum stake: 1 item or 50 coins.
  - 30 s ready check, with clickable [Ready]/[Decline] and a countdown bar.
  - The start waits if a player was hurt in the last 15 s.
  - 3 failed ready checks in 10 min block offering and accepting for 10 min.
  - Relisting waits 1 min; re-challenging the same player waits 2 min.
  - Players offline for more than 2 min lose their waiting listing or offer (refunded).
  - Arenas are shared with ranked 1v1, and wager fights wait in line behind each other.
  - Fights last 5 min in the player's own gear. Nothing drops, a timeout is a draw, and
    `/duel surrender` gives the win away. The reconnect window is 60 s.
  - Wager fights never touch ratings, ranked stats or ranked history.
- **Payouts:** coins go straight to the balance (by mail when offline). Items go to the inventory,
  and the rest goes by Economy_LD mail; every refund or win also leaves a letter.
- **Banned items:** anything that hides items inside it (filled shulker boxes, bundles, modded
  backpacks, loaded crossbows, unrolled loot), the tag `duels_ld:wager_banned`, and the config list.
- **Eden_LD 1.3.11:**
  - Complaints go to Eden's Moderation with the duel log (`/duel report <#> <text>`).
  - The hub settings "personal challenges" and "skip loss warning" are respected.
- **Commands:**
  - `/duel cancel|ready|decline [#]|surrender|accept <offer>|reject <offer>|withdraw <offer>|list|report`
  - staff: `/duel wager list|log <#>|cancel <#>`
- **`DuelsApi` wager surface** for Eden's hub pages: `listings`, `myWagers`, `wagerHistory`,
  `wagerStats`, the actions, `WagerResult` with `message()`, and the `onWagerChanged` and
  `onListingsChanged` listeners.
- **Requirements:** Eden_LD 1.3.11+ and Economy_LD 1.0.2+ for wagers. The bundled VectorLib is now 0.7.2.

## 1.5.0 — VectorLib instead of owo-lib

- The matchmaking screen (B), the admin settings screen (`/duel admingui`) and the arena manager
  (`/duel arena gui`) are rebuilt on VectorLib. owo-lib is no longer a dependency.
- VectorLib 0.6.2 is bundled inside the jar (jar-in-jar), so there is nothing extra to install.
- The screens look the same as before, and the Modern/Vanilla theme switch is kept. The vanilla
  look now draws vanilla's own panel and slot textures instead of owo's.
- Pressing Enter in the create and rename fields of the arena manager now confirms.
- Typed admin values survive a window resize.

## 1.4.0 — API for Eden's Duels tab

- **`DuelsApi`** (`net.ledok.duels_ld.api`, documented in API.md) exposes:
  - stats (rating and W/L/D per mode, today's rewards);
  - leaderboards and rank;
  - match history;
  - queue join, leave and state, with a `QueueResult` instead of chat;
  - listeners for queue changes and match start and end.
  The API doesn't depend on Eden_LD.
- Wins, losses and draws are now counted **per mode**. The old totals stay as all-time totals, and
  the per-mode counters start at 0.
- **Match history:** the last 20 matches per player are saved in world data (`duels_ld/history`).
- Reading a rating no longer stores it. Before, opening the matchmaking screen put a player on the
  leaderboard at the starting rating. Leaderboards now list only players with a match in that mode,
  plus older players whose rating has moved.
- Reading today's reward total no longer creates an entry either.
- `/duel stats` shows rating, rank and W/L/D per mode plus all-time totals. The matchmaking screen
  shows W/L/D under each rating.
- Queueing for a mode that has no arena set up is refused ("No arena is set up for this mode yet")
  instead of waiting forever. Queueing twice now says you're already queued.
- Draws are recorded for offline players too. Before, a 1v1 draw only counted for players who were
  online when it ended.

## 1.3.0 — parties come from Eden_LD

- **Parties now come from Eden_LD.** Players form parties in Eden's hub (F4). Duels' own party system
  is gone, and Eden is required for 2v2 parties. Without Eden, 2v2 is solo-queue only.
- 2v2 party queue: only the Eden party's leader can queue, and the party must have exactly 2 members.
  Bigger parties are refused rather than having two members picked. The queue is keyed by Eden
  party id, so a change of leader doesn't matter. Any membership change (join, leave, kick, disband)
  drops the party from the queue. Parties are no longer disbanded when a match starts.
- **Friendly fire:** Eden blocks damage between partymates and guildmates. Every match participant
  now has that protection suspended for the whole match (reason `duels_ld:match`), so Eden
  partymates or guildmates matched against each other can fight. The suspension is restored when
  the match ends or the server stops, and re-applied when a player reconnects mid-match. Duels' own
  team rules still decide who may hit whom.
- Matchmaking screen: the party panel (invite field, invite banner, accept/decline) is replaced by a
  read-only "Party: <name> (n/2)" line with a hint to open the hub.
- Removed: `/duel party create|invite|accept|leave|disband` and the `party_invite`,
  `party_accept`, `party_decline` and `sync_party` packets.
- Build: Eden_LD (and VectorLib for the dev runtime) from `libs/`. Loom 1.18, Gradle 9.7.1 and
  Fabric Loader 0.19.3, to match Eden.
