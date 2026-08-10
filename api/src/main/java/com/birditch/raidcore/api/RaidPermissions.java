package com.birditch.raidcore.api;

/// The permission nodes shared by every RaidCore plugin — declared once so
/// game and lobby never drift apart.
public final class RaidPermissions {

    public static final String ADMIN = "raid.admin";
    public static final String COMMAND_INFO = "raid.command.info";
    public static final String LOBBY_PLAY = "raid.lobby.play";
    public static final String EDITOR = "raid.editor";
    public static final String EDITOR_MAP = "raid.editor.map";
    public static final String EDITOR_SPAWN = "raid.editor.spawn";
    public static final String EDITOR_LOOT = "raid.editor.loot";
    public static final String EDITOR_AI = "raid.editor.ai";
    public static final String EDITOR_EXTRACTION = "raid.editor.extraction";
    public static final String EDITOR_POI = "raid.editor.poi";
    public static final String EDITOR_DELETE = "raid.editor.delete";
    public static final String EDITOR_SAVE = "raid.editor.save";
    public static final String EDITOR_ROLLBACK = "raid.editor.rollback";
    private RaidPermissions() {
    }
}
