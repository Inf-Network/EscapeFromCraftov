package com.birditch.escapefromcraftov.api.matchmaking;

import java.util.Locale;

/// Whether a solo queue ticket may be grouped with other solo players as one squad.
public enum SquadFillMode {
    AUTO_FILL("auto_fill"),
    NO_FILL("no_fill");

    private final String id;

    SquadFillMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static SquadFillMode parse(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "auto", "auto_fill", "fill", "on" -> AUTO_FILL;
            case "none", "no_fill", "nofill", "off" -> NO_FILL;
            default -> throw new IllegalArgumentException(
                    "fill mode must be auto_fill or no_fill but was: " + raw);
        };
    }
}
