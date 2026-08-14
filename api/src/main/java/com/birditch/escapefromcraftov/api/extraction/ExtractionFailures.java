package com.birditch.escapefromcraftov.api.extraction;

/// Stable failure reasons produced by [ExtractionService#begin]. These strings
/// are player-visible (Simplified Chinese per the v4 localization mandate) and
/// are the ONLY values callers may compare against — never match on free prose.
public final class ExtractionFailures {

    public static final String GAME_NOT_FOUND = "对局不存在";
    public static final String GAME_NOT_RUNNING = "对局未在进行中";
    public static final String PLAYER_NOT_IN_GAME = "玩家不在该对局中";
    public static final String POINT_NOT_FOUND = "撤离点不存在";
    public static final String POINT_LOCKED = "撤离点已锁定";
    public static final String ALREADY_EXTRACTING = "撤离正在进行中";
    public static final String ROUTE_NOT_ASSIGNED = "该撤离点不属于本队路线";

    private ExtractionFailures() {
    }

    public static String blocked(String describe) {
        return "撤离受阻:" + describe;
    }
}
