package com.birditch.escapefromcraftov.api;

/// The permission nodes shared by every EscapeFromCraftov plugin — declared once so
/// game and lobby never drift apart.
public final class CraftovPermissions {

    public static final String ADMIN = "craftov.admin";
    public static final String COMMAND_INFO = "craftov.command.info";
    public static final String LOBBY_PLAY = "craftov.lobby.play";
    public static final String EDITOR = "craftov.editor";
    public static final String EDITOR_MAP = "craftov.editor.map";
    public static final String EDITOR_SPAWN = "craftov.editor.spawn";
    public static final String EDITOR_LOOT = "craftov.editor.loot";
    public static final String EDITOR_AI = "craftov.editor.ai";
    public static final String EDITOR_EXTRACTION = "craftov.editor.extraction";
    public static final String EDITOR_POI = "craftov.editor.poi";
    public static final String EDITOR_DELETE = "craftov.editor.delete";
    public static final String EDITOR_SAVE = "craftov.editor.save";
    public static final String EDITOR_ROLLBACK = "craftov.editor.rollback";
    private CraftovPermissions() {
    }
}
