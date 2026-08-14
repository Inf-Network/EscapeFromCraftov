package com.birditch.escapefromcraftov.api.extraction;

import com.birditch.escapefromcraftov.api.CraftovException;

public class ExtractionException extends CraftovException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
