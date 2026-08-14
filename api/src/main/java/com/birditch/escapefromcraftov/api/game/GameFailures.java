package com.birditch.escapefromcraftov.api.game;

/// Stable failure reasons produced by [CraftovGameService]. These strings are
/// player-visible (Simplified Chinese per the v4 localization mandate) and are
/// the ONLY values callers may compare against — never match on free prose.
public final class GameFailures {

    public static final String NO_SESSION = "玩家没有会话";
    public static final String PROFILE_NOT_READY = "玩家档案尚未就绪";
    public static final String ALREADY_IN_GAME = "玩家已在一场对局中";
    public static final String GAME_NOT_FOUND = "对局不存在";
    public static final String NOT_ACCEPTING = "对局当前不可加入";
    public static final String GAME_FULL = "对局已满员";
    public static final String NOT_IN_GAME = "玩家不在任何对局中";
    public static final String WAITING_CANCELLATION_UNSUPPORTED = "当前对局服务不支持按房间取消等待成员";
    public static final String MAP_NOT_AVAILABLE = "地图不存在、未启用或验证未通过";
    public static final String ACTION_TIER_NOT_AVAILABLE = "地图不支持所选行动档位";

    private GameFailures() {
    }

    public static String instanceLimitReached(int maxInstances) {
        return "实例数量已达上限(" + maxInstances + ")";
    }

    public static String cannotStartFrom(CraftovGameState state) {
        return "当前状态无法开始对局(" + state + ")";
    }
}
