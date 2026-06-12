# Duels_LD

Fabric (1.21.1) mod that adds matchmade PvP battles in arenas: 1v1/2v2 queues, Elo, parties, currency rewards, leaderboards, and owo-lib GUI screens.

## Features

### Matchmaking
1. 1v1 and 2v2 queues.
2. Players can be in both queues; once a match is found they are removed from all queues.
3. Elo-based matching with a range that expands over time (configurable).
4. Draws are decided by accumulated points.
5. If no arena is free, the match waits in a pending state until one opens.
6. `/duel leavequeue` leaves all queues.

### Arenas
1. WorldEdit-style `pos1`/`pos2` bounds per arena.
2. Multiple team spawns per team with captured facing (yaw/pitch); a random spawn is chosen per match.
3. Arena is locked while active; players are teleported back after the match.
4. Players who leave the arena bounds are returned to their spawn.
5. Selection **wand** with a live block overlay and shift+scroll mode switching (bounds / spawns), plus a shift+RMB arena selection/rename screen.
6. `/duel arena gui` opens an arena manager screen; `/duel arena info <name>` prints a readiness summary.

### Battles
1. Configurable pre-fight countdown, then FIGHT.
2. Friendly fire disabled, colored teams, name tags hidden from other teams.
3. Players are healed and fed at the start (configurable).
4. Each player's HP is stored before the battle and restored afterward, so a battle is not a free heal.
5. Configurable in-battle game mode (default ADVENTURE).
6. Death is intercepted: the player is eliminated and moved to spectator; the last team standing wins.
7. Points system: offense (damage), defense (blocked), support (healing), and a kill bonus — all weighted in config.
8. Disconnect grace: a disconnected player has a configurable window to reconnect before forfeiting (the timer pauses meanwhile).

### Parties
1. Party system for the 2v2 queue.
2. Leader starts the queue; members are notified.
3. Invites can be accepted from the GUI or via command.

### Elo & Leaderboards
1. Separate Elo for 1v1 and 2v2.
2. Elo changes announced after each match.
3. `/duel leaderboard 1v1|2v2` shows the top 10.

### Rewards (optional, via Economy_LD)
1. Currency paid out for win/loss/draw outcomes.
2. Reward is quality-scaled by match duration (AFK / instant-forfeit matches pay little to nothing).
3. Anti-farm limits: per-outcome daily caps, repeat-opponent decay, and an Elo gate that scales down rewards for stomping much weaker opponents.
4. A drawn match where nothing happened (a 0-0 stalemate below `drawMinPoints`) pays nothing.
5. Today's earned amount and remaining cap are shown on the matchmaking screen.
6. If Economy_LD is not installed, rewards are silently disabled.

### GUI
1. Lobby/matchmaking screen (`B` keybind) — shows Elo, queue timers, party invite/accept, and coins earned today.
2. Admin GUI for matchmaking settings (`/duel admingui`).
3. Arena manager screen (`/duel arena gui`).

## Commands

1. `/duel stats` — show your win/loss/draw stats.
2. `/duel leaderboard 1v1|2v2` — show the Elo top 10.
3. `/duel queue 1v1|2v2` — join a matchmaking queue.
4. `/duel leavequeue` — leave all queues.
5. `/duel admingui` — open the matchmaking admin GUI. *(op)*
6. `/duel reload` — reload the config from disk. *(op)*
7. `/duel party create|invite <player>|accept <leader>|leave|disband` — party commands.
8. `/duel arena ...` — arena management *(op)*, see below.

## Arena Commands *(op)*

1. `/duel arena create <name>`
2. `/duel arena remove <name>`
3. `/duel arena pos1 <name>` — set bound 1 to your position.
4. `/duel arena pos2 <name>` — set bound 2 to your position.
5. `/duel arena addspawn <name> <team>` — add a spawn at your position/facing.
6. `/duel arena addspawn <name> <team> <pos>` — add a spawn at coordinates.
7. `/duel arena remspawn <name> <team> <pos>`
8. `/duel arena list`
9. `/duel arena info <name>` — bounds/spawns/ready/active summary.
10. `/duel arena wand [name]` — get the selection wand (optionally bound to an arena).
11. `/duel arena gui` — open the arena manager screen.

## Data Files

Persistence uses Minecraft's SavedData, stored under the world folder:

1. `world/data/duels_ld/arenas.dat`
2. `world/data/duels_ld/stats.dat`
3. `world/data/duels_ld/mmr.dat`
4. `world/data/duels_ld/backups.dat` — pending player restores (HP/position/game mode).
5. `world/data/duels_ld/rewards.dat` — daily reward tallies.
6. `config/duels_ld/matchmaking_config.json` — config (JSON; `/duel reload` re-reads it).

The config is grouped into sections: match formats (`oneVOne`, `twoVTwo`), `battle` (countdown, reconnect grace, heal-at-start, in-battle game mode, item blacklist, post-match teleport guard), rating/matchmaking (`elo`, MMR range), scoring `weights`, and `rewards` (amounts, quality ramp, daily caps, anti-farm).

The `battle.arenaItemBlacklist` blocks listed items from being used during a match (and for `itemBlacklistGraceSeconds` after). Entries are item ids (e.g. `minecraft:ender_pearl`) or item tags prefixed with `#`. A delayed teleport (e.g. an ender pearl thrown right before the match ends) that would pull a player back into the arena is undone for `postMatchTeleportGuardSeconds` after the match.

## Placeholders (Placeholder API + HoloDisplays)

All placeholders use namespace `duels_ld`.

### Leaderboards
1. `%duels_ld:leaderboard_1v1_header%`
2. `%duels_ld:leaderboard_2v2_header%`
3. `%duels_ld:leaderboard_1v1 1%` ... `%duels_ld:leaderboard_1v1 10%`
4. `%duels_ld:leaderboard_2v2 1%` ... `%duels_ld:leaderboard_2v2 10%`

### Arena Status
1. `%duels_ld:arena_status_header%`
2. `%duels_ld:arena_status 1%` ... `%duels_ld:arena_status N%`
3. `%duels_ld:arena_status_all%` (multi-line)

### Queue Counts
1. `%duels_ld:queue_1v1%`
2. `%duels_ld:queue_2v2%`
3. `%duels_ld:queue_total%`

## Dependencies

1. **owo-lib** — required (GUI screens).
2. **Economy_LD** — optional; enables currency rewards.
3. **Placeholder API** — optional; enables the placeholders above (HoloDisplays suggested for holograms).

## Notes

1. Queue counts include pending matches (waiting for a free arena).
2. Cross-dimension teleports are deferred off the join event to avoid a chunk-manager crash on rejoin.
</content>
</invoke>
