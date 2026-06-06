package net.ledok.duels_ld.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A team match (e.g. 2v2): two teams, ended by a winning team number (or a draw). */
public class ActiveTeamDuel extends ActiveMatch {
    private final List<UUID> team1;
    private final List<UUID> team2;
    private final String teamNameBase;
    private volatile EndRequest pendingEndRequest;

    public ActiveTeamDuel(List<UUID> team1, List<UUID> team2, DuelSettings settings, String teamNameBase,
                          Map<UUID, Float> startHealth) {
        super(settings);
        this.team1 = new ArrayList<>(team1);
        this.team2 = new ArrayList<>(team2);
        this.teamNameBase = teamNameBase;
        for (UUID id : this.team1) {
            addParticipant(id, 1, startHealth.getOrDefault(id, 20.0f));
        }
        for (UUID id : this.team2) {
            addParticipant(id, 2, startHealth.getOrDefault(id, 20.0f));
        }
    }

    public List<UUID> getTeam1() {
        return team1;
    }

    public List<UUID> getTeam2() {
        return team2;
    }

    public List<UUID> allPlayers() {
        List<UUID> players = new ArrayList<>(team1.size() + team2.size());
        players.addAll(team1);
        players.addAll(team2);
        return players;
    }

    public int getTeam(UUID player) {
        if (team1.contains(player)) {
            return 1;
        }
        if (team2.contains(player)) {
            return 2;
        }
        return 0;
    }

    public boolean isEliminated(UUID player) {
        Participant p = participants.get(player);
        return p != null && p.eliminated;
    }

    public void eliminate(UUID player) {
        participant(player).eliminated = true;
    }

    public boolean hasAlivePlayersInTeam(int team) {
        List<UUID> ids = team == 1 ? team1 : team2;
        for (UUID id : ids) {
            if (!isEliminated(id)) {
                return true;
            }
        }
        return false;
    }

    public String getTeamNameBase() {
        return teamNameBase;
    }

    public void markForEnd(boolean draw, int winningTeam) {
        pendingEndRequest = new EndRequest(draw, winningTeam);
    }

    public EndRequest consumePendingEndRequest() {
        EndRequest request = pendingEndRequest;
        pendingEndRequest = null;
        return request;
    }

    public static final class EndRequest {
        private final boolean draw;
        private final int winningTeam;

        public EndRequest(boolean draw, int winningTeam) {
            this.draw = draw;
            this.winningTeam = winningTeam;
        }

        public boolean isDraw() {
            return draw;
        }

        public int getWinningTeam() {
            return winningTeam;
        }
    }
}
