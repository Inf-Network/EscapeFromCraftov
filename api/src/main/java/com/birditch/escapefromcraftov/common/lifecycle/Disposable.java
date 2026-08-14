package com.birditch.escapefromcraftov.common.lifecycle;

/// Owner of resources that must be released when no longer needed.
public interface Disposable {

    void dispose();
}
