package com.birditch.raidcore.api;

/// The permission nodes shared by every RaidCore plugin — declared once so
/// game and lobby never drift apart.
public final class RaidPermissions {

    public static final String ADMIN = "raid.admin";
    public static final String COMMAND_INFO = "raid.command.info";
    public static final String LOBBY_PLAY = "raid.lobby.play";

    private RaidPermissions() {
    }
}
