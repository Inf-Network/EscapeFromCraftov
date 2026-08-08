package com.birditch.raidcore.api.extraction;

import com.birditch.raidcore.api.RaidException;

public class ExtractionException extends RaidException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
