# Duels_LD API

`net.ledok.duels_ld.api.DuelsApi` gives other mods (for example Eden_LD's hub Duels tab) read access to
ratings, results, leaderboards and match history, plus queue control and events. It is static, server
side only, and must be called on the server thread. Listeners also fire on the server thread.

The API references no other mod: Duels_LD works the same whether or not anyone uses it.

## Setup

Compile against the Duels_LD jar and keep the dependency soft:

```groovy
modCompileOnly files("libs/duels_ld-1.4.0.jar")
```

```json
"suggests": { "duels_ld": "*" }
```

Touch `DuelsApi` only after checking `FabricLoader.getInstance().isModLoaded("duels_ld")`, ideally from
one bridge class, so your mod still loads without Duels.

## Reading never creates data

Nothing in the API stores anything. A player who never finished a match gets `Optional.empty()` from
`stats`, is not on any leaderboard, and has an empty history. Looking them up does not change that.

## Stats

```java
Optional<DuelsApi.Stats> stats = DuelsApi.stats(server, uuid);
stats.ifPresent(s -> {
    DuelsApi.ModeStats oneVsOne = s.oneVsOne();   // rating, wins, losses, draws, played()
    DuelsApi.ModeStats twoVsTwo = s.of(DuelsApi.Mode.TWO_V_TWO);
    long earned = s.earnedToday();                // battle currency earned today (UTC day)
    long cap = s.dailyCap();                      // the win + loss + draw daily caps
    boolean rewards = s.rewardsEnabled();         // false when rewards are off or Economy_LD is missing
});
```

- `rating` is the player's Elo in that mode. A player without a rating gets the configured
  starting rating.
- The per-mode `wins`, `losses` and `draws` were added in 1.4.0 and start at 0. Results from before
  then only count toward the all-time totals that `/duel stats` shows.

## Leaderboards

```java
List<DuelsApi.LeaderboardEntry> top = DuelsApi.leaderboard(server, DuelsApi.Mode.ONE_V_ONE, 10);
int position = DuelsApi.rank(server, DuelsApi.Mode.ONE_V_ONE, uuid);   // 1-based, -1 = unranked
```

- `LeaderboardEntry(UUID player, String name, int rating, int played)`.
- Entries are sorted by rating, then matches played, then name.
- A player is ranked in a mode once they have a match in it. Players from before 1.4.0 are also
  ranked if their rating differs from the starting rating, and their `played` can then be 0.
- Leaderboards are cached and rebuilt after every match, so calling these often is cheap.

## Queue

```java
DuelsApi.QueueResult result = DuelsApi.joinQueue(player, DuelsApi.Mode.TWO_V_TWO);
DuelsApi.leaveQueue(player, DuelsApi.Mode.TWO_V_TWO);   // null = every queue and any pending match
DuelsApi.QueueState state = DuelsApi.queueState(uuid);
int searching = DuelsApi.queued(DuelsApi.Mode.ONE_V_ONE);
```

`joinQueue` uses the same rules as the B screen and `/duel queue`, but sends nothing to the player's
chat. Show the result yourself. The one exception is the leader's partymate, who is still told that
the leader started a party search. `leaveQueue` is silent.

| `QueueResult` | Meaning |
|---|---|
| `QUEUED` | Now searching (for 2v2 with an Eden_LD party, the whole party is queued). |
| `ALREADY_QUEUED` | Already in that queue (or the party already is). |
| `IN_MATCH` | In a duel right now. |
| `PENDING_MATCH` | Already matched and waiting for a free arena. |
| `BUSY` | Busy elsewhere (BusyLib), for example in a dungeon. |
| `NOT_PARTY_LEADER` | 2v2 in an Eden party: only the leader can queue it. |
| `PARTY_WRONG_SIZE` | 2v2 in an Eden party: the party must have exactly 2 members. |
| `PARTY_MEMBER_UNAVAILABLE` | 2v2 in an Eden party: the partymate is offline, busy, in a match or pending. |
| `DISABLED` | No arena has enough spawns for this mode. |

`QueueState(oneVsOne, twoVsTwo, pending, joinedAtMillis, viaParty, searchRange)`:

- `oneVsOne` / `twoVsTwo` are set while the player is searching or pending in that mode. `queued()`
  is true if either is set.
- `pending` means a match was found and is waiting for a free arena.
- `joinedAtMillis` is the earliest join time of the player's active searches, or -1.
- `viaParty` means queued for 2v2 as an Eden_LD party. This is also true for the partymate.
- `searchRange` is the current Elo window, which widens while the player waits. It is 0 when not
  searching.
- `QueueState.NONE` is the state of a player who isn't queued.

## Match history

```java
List<DuelsApi.MatchRecord> recent = DuelsApi.history(server, uuid, 10);   // newest first, max 20 kept
```

`MatchRecord(endedAtMillis, mode, result, ratingBefore, ratingAfter, teammates, opponents, durationMillis)`:

- `result` is `WIN`, `LOSS` or `DRAW` from this player's point of view.
- `teammates` and `opponents` are names as they were at the time.
- `durationMillis` excludes the countdown and paused time (disconnect grace), and is 0 if the match
  ended during the countdown.
- `ratingBefore == ratingAfter` for unranked matches.

History starts with 1.4.0 and keeps a player's last 20 matches.

## Events

```java
DuelsApi.addListener(new DuelsApi.Listener() {
    @Override public void onQueueChanged(UUID player) { /* refresh queue buttons */ }
    @Override public void onMatchStart(DuelsApi.Mode mode, List<UUID> players) { }
    @Override public void onMatchEnd(DuelsApi.Mode mode, List<UUID> winners, List<UUID> losers, boolean draw) { }
});
```

- `onQueueChanged` fires whenever a player's queue state may have changed. That covers a join or
  leave, a match being found or pending, a cleanup (offline, busy, Eden party membership changed)
  and a disconnect. It can fire when nothing visible changed, so re-read `queueState`. The player
  may be offline.
- `onMatchStart` fires once the players are in the arena and the countdown begins.
- `onMatchEnd` fires for every finish: a kill, the win-HP threshold, the timer (decided on points),
  a forfeit after disconnect grace, or a draw. On a draw, `winners` and `losers` hold team 1 and
  team 2. Stats, ratings and history are already updated when it fires.
- Matches cut short by a server shutdown are not recorded and fire no event.

The `fire*` and `invalidateLeaderboards` methods are internal. Don't call them.

## Wager duels

Players stake items and coins on a 1v1. Duels owns the rules, the duel bank and the fights. A host
(Eden_LD's hub) draws the pages from this data. Everything is off unless `wagersEnabled()`: the
config switch is on and Eden_LD and Economy_LD are present.

```java
List<DuelsApi.WagerListing> list = DuelsApi.listings(server, viewer);   // open public ones + personal ones for/by viewer
DuelsApi.MyWagers mine = DuelsApi.myWagers(server, player);              // own listing + offers on it, my offers, challenges to me, bank total
List<DuelsApi.WagerRecord> history = DuelsApi.wagerHistory(server, player, 20);
DuelsApi.WagerStats stats = DuelsApi.wagerStats(player);                 // wins / losses / draws
```

- `WagerListing(number, author, authorName, authorStats, stake, comment, createdAtMillis,
  expiresAtMillis, pausedRemainingMillis, offers, state, target, targetName, mine)`.
  - `number` is the duel number players see (#1042).
  - While `PREPARING` or `FIGHTING`, the timer is paused: `expiresAtMillis` is -1 and
    `pausedRemainingMillis` holds what's left.
  - `target` is set for personal challenges.
- `WagerOffer(id, number, challenger, challengerName, stake, state, online, createdAtMillis)`.
- `WagerRecord(number, startedAtMillis, endedAtMillis, author, …, winner, reportedByViewer)`, where
  `winner` null means a draw.
- `WagerStake(items, coins)`. The stacks are copies. Sorting "by amount" can only use coins.

Actions return a `WagerResult`. `ok()` is true for `OK` and `PENDING` (coins being taken; a chat
line follows). `message()` is the player-facing line, for a toast.

```java
DuelsApi.cancelListing(player);              DuelsApi.withdrawOffer(player, offerId);
DuelsApi.declineOffer(author, offerId);      DuelsApi.declineChallenge(target, number);
DuelsApi.acceptOffer(author, offerId);       // show the loss warning first unless skipsLossWarning(server, uuid)
DuelsApi.report(player, number, text);       // to Eden Moderation with the duel log; once per player per duel
```

Listener additions:
- `onWagerChanged(UUID player)` fires for both sides on every state change: offers, the ready
  check, fights, deliveries.
- `onListingsChanged()` fires when a public listing appears, disappears or changes state.
- Wager fights also fire `onMatchStart` / `onMatchEnd` as `ONE_V_ONE`, so Eden's per-player PvP
  exception covers them.

Opening Duels' screens (since 1.7.0). Each returns OK and replaces whatever screen is open, or
returns a refusal (and opens nothing):

```java
DuelsApi.openCreate(player, null);           // public listing; a UUID makes it a personal challenge (target online)
DuelsApi.openTerms(player, number);          // offer on listing #number
DuelsApi.openReview(author, offerId);        // the author's accept/decline screen for one offer
```
