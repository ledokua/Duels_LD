package net.ledok.duels_ld.manager;

import java.util.UUID;

/** A 1v1 match: two players, ended by a winning player's UUID (or a draw). */
public class ActiveDuel extends ActiveMatch {
    private final UUID player1;
    private final UUID player2;
    private final String teamName;
    private volatile EndRequest pendingEndRequest;

    public ActiveDuel(UUID player1, UUID player2, DuelSettings settings, String teamName, float p1Hp, float p2Hp) {
        super(settings);
        this.player1 = player1;
        this.player2 = player2;
        this.teamName = teamName;
        addParticipant(player1, 1, p1Hp);
        addParticipant(player2, 2, p2Hp);
    }

    public UUID getPlayer1() {
        return player1;
    }

    public UUID getPlayer2() {
        return player2;
    }

    public String getTeamName() {
        return teamName;
    }

    public void markForEnd(boolean draw, UUID winnerUUID) {
        pendingEndRequest = new EndRequest(draw, winnerUUID);
    }

    public EndRequest consumePendingEndRequest() {
        EndRequest request = pendingEndRequest;
        pendingEndRequest = null;
        return request;
    }

    public static final class EndRequest {
        private final boolean draw;
        private final UUID winnerUUID;

        public EndRequest(boolean draw, UUID winnerUUID) {
            this.draw = draw;
            this.winnerUUID = winnerUUID;
        }

        public boolean isDraw() {
            return draw;
        }

        public UUID getWinnerUUID() {
            return winnerUUID;
        }
    }
}
