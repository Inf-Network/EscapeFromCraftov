package com.birditch.raidcore.game.plugin.instance;

/// Read-only gate used by automatic instance provisioning.
@FunctionalInterface
public interface MapAvailability {

  boolean isEnabled(String mapId);
}
