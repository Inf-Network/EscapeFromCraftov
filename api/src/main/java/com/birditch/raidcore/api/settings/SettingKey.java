package com.birditch.raidcore.api.settings;

/// A toggleable player setting with its default value.
public enum SettingKey {
    LOBBY_PLAYER_VISIBILITY(true),
    QUEUE_STATUS_MESSAGES(true);

    private final boolean defaultValue;

    SettingKey(boolean defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean defaultValue() {
        return defaultValue;
    }
}
