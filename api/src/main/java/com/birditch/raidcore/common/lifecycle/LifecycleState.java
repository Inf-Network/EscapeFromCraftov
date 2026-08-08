package com.birditch.raidcore.common.lifecycle;

/// Coarse lifecycle phases for modules and long-lived services.
public enum LifecycleState {
    CREATED,
    INITIALIZING,
    INITIALIZED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
