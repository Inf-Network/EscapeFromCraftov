package com.birditch.raidcore.common.lifecycle;

/// Owner of resources that must be released when no longer needed.
public interface Disposable {

    void dispose();
}
