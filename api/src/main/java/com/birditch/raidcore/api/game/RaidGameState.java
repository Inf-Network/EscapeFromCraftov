package com.birditch.raidcore.api.game;

/// Lifecycle of a raid game. Legal transitions are encoded in [#canTransitionTo].
public enum RaidGameState {
    CREATED,
    WAITING,
    PREPARING,
    STARTING,
    RUNNING,
    ENDING,
    FINISHED,
    DESTROYED;

    public boolean canTransitionTo(RaidGameState next) {
        return switch (this) {
            case CREATED -> next == WAITING || next == DESTROYED;
            case WAITING -> next == PREPARING || next == STARTING || next == ENDING
                    || next == DESTROYED;
            case PREPARING -> next == STARTING || next == ENDING || next == DESTROYED;
            case STARTING -> next == RUNNING || next == ENDING;
            case RUNNING -> next == ENDING;
            case ENDING -> next == FINISHED;
            case FINISHED -> next == DESTROYED;
            case DESTROYED -> false;
        };
    }

    public boolean active() {
        return this == STARTING || this == RUNNING;
    }

    public boolean terminal() {
        return this == DESTROYED;
    }
}
