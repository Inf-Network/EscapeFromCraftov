package com.birditch.raidcore.api.profile;

import com.birditch.raidcore.api.RaidException;

public class ProfileException extends RaidException {

    public ProfileException(String message) {
        super(message);
    }

    public ProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
