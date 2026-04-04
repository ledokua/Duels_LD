# Duels_LD

Fabric mod that adds duels and matchmaking battles with arenas, Elo, parties, leaderboards, and GUI screens.

## Features

### Matchmaking
1. 1v1 and 2v2 queues.
2. Players can be in both queues; once a match is found they are removed from all queues.
3. Elo-based matching with expanding range over time (configurable).
4. Draws use points to decide the winner.
5. Queue wait handling when no arena is free.
6. `/duel leavequeue` to leave all queues.

### Arenas
1. WorldEdit-style `pos1`/`pos2` selection per arena.
2. Team spawns (multiple per team); random spawn selection.
3. Arena locked while active; players teleported back after match.
4. Players leaving arena bounds are returned to their spawn.

### Duels/Battles
1. Countdown, then fight.
2. Friendly fire disabled, colored teams, name tags hidden from other teams.
3. Health/hunger/saturation restored at start.
4. Death is intercepted in arena matches; players are eliminated and moved to spectator (battles) or duel ends immediately (duels).
5. Points system: offense, defense, support.

### Parties
1. Party system for 2v2 queue.
2. Leader starts queue; party members are notified.
3. Invites can be accepted from GUI or command.

### Elo
1. Separate Elo for 1v1 and 2v2.
2. Elo changes announced after match.
3. Leaderboards via `/duel leaderboard 1v1|2v2`.

### GUI
1. Lobby screen (`B` keybind).
2. Matchmaking screen shows Elo, queue timers, party invite/accept fields.
3. Admin GUI for matchmaking settings (`/duel admingui`).

## Commands

1. `/duel invite <player> [settings]` - send duel request.
2. `/duel accept` - accept pending duel request.
3. `/duel decline <player>` - decline duel request.
4. `/duel stats` - show duel stats.
5. `/duel leaderboard 1v1|2v2` - show Elo top 10.
6. `/duel queue 1v1|2v2` - join matchmaking queue.
7. `/duel leavequeue` - leave all queues.
8. `/duel admingui` - open matchmaking admin GUI.
9. `/duel party create|invite|accept|leave|disband` - party commands.
10. `/duel arena create|remove|pos1|pos2|addspawn|remspawn|list` - arena management.

## Arena Commands

1. `/duel arena create <name>`
2. `/duel arena remove <name>`
3. `/duel arena pos1 <name>`
4. `/duel arena pos2 <name>`
5. `/duel arena addspawn <name> <team> <pos>`
6. `/duel arena remspawn <name> <team> <pos>`
7. `/duel arena list`

## Data Files

1. `world/duels_ld/duel_stats.json`
2. `world/duels_ld/mmr.json`
3. `world/duel_arenas.json`
4. `config/duels_ld/matchmaking_config.json`

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

## Notes

1. Placeholder API is optional. If not installed, placeholders are disabled.
2. HoloDisplays is suggested for holograms.
3. Queue counts include pending matches (waiting for free arena).
