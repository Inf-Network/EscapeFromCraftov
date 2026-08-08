package com.birditch.raidcore.api.loot;

import com.birditch.raidcore.api.RaidException;

public class LootException extends RaidException {

    public LootException(String message) {
        super(message);
    }

    public LootException(String message, Throwable cause) {
        super(message, cause);
    }
}
