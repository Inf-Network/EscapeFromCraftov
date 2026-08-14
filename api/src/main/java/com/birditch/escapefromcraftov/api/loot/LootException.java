package com.birditch.escapefromcraftov.api.loot;

import com.birditch.escapefromcraftov.api.CraftovException;

public class LootException extends CraftovException {

    public LootException(String message) {
        super(message);
    }

    public LootException(String message, Throwable cause) {
        super(message, cause);
    }
}
