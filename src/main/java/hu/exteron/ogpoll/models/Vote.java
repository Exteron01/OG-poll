package hu.exteron.ogpoll.models;

import java.util.UUID;

public class Vote {
    private int id;
    private int pollId;
    private int optionId;
    private UUID playerUuid;
    private long votedAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getPollId() {
        return pollId;
    }

    public void setPollId(int pollId) {
        this.pollId = pollId;
    }

    public int getOptionId() {
        return optionId;
    }

    public void setOptionId(int optionId) {
        this.optionId = optionId;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public void setPlayerUuid(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public long getVotedAt() {
        return votedAt;
    }

    public void setVotedAt(long votedAt) {
        this.votedAt = votedAt;
    }
}
