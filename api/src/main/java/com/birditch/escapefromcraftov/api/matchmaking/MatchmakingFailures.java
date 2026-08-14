package com.birditch.escapefromcraftov.api.matchmaking;

/// Stable matchmaking failure reasons that callers may map to localized UI.
public final class MatchmakingFailures {

  public static final String ALREADY_QUEUED = "玩家已在匹配队列中";
  public static final String PLAYER_DATA_NOT_READY = "玩家数据尚未就绪";

  private MatchmakingFailures() {}
}
