package com.birditch.escapefromcraftov.api.matchmaking;

/// Stable matchmaking failure reasons that callers may map to localized UI.
public final class MatchmakingFailures {

  public static final String ALREADY_QUEUED = "玩家已在匹配队列中";
  public static final String PLAYER_DATA_NOT_READY = "玩家数据尚未就绪";
  public static final String PARTY_QUEUE_UNSUPPORTED = "当前匹配器不支持队伍整体入队";
  public static final String PARTY_SIZE_INVALID = "队伍人数必须为 1 到 3 人";

  private MatchmakingFailures() {}
}
