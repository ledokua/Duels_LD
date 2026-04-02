package net.ledok.duels_ld.manager;

import java.util.UUID;

public class DuelRequest {
    private final UUID sender;
    private final DuelSettings settings;
    private final long timestamp;

    public DuelRequest(UUID sender, DuelSettings settings) {
        this.sender = sender;
        this.settings = settings;
        this.timestamp = System.currentTimeMillis();
    }

    public UUID getSender() {
        return sender;
    }

    public DuelSettings getSettings() {
        return settings;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
}
