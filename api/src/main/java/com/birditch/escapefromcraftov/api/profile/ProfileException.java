package com.birditch.escapefromcraftov.api.profile;

import com.birditch.escapefromcraftov.api.CraftovException;

public class ProfileException extends CraftovException {

    public ProfileException(String message) {
        super(message);
    }

    public ProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
