package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// Main-thread-confined collection of independent administrator sessions.
public final class EditorSessionService {

  private final Map<UUID, EditorSession> sessions = new LinkedHashMap<>();

  public EditorSession enter(UUID playerId, ManualRaidMap map) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(map, "map");
    EditorSession existing = sessions.get(playerId);
    if (existing != null && existing.dirty()) {
      if (existing.mapId().equals(map.mapId())) return existing;
      throw new IllegalStateException("当前编辑会话有未保存修改");
    }
    EditorSession session = new EditorSession(playerId, map);
    sessions.put(playerId, session);
    return session;
  }

  public Optional<EditorSession> find(UUID playerId) {
    return Optional.ofNullable(sessions.get(Preconditions.checkNotNull(playerId, "playerId")));
  }

  public EditorSession require(UUID playerId) {
    return find(playerId).orElseThrow(() -> new IllegalStateException("你尚未进入地图编辑模式"));
  }

  public Optional<EditorSession> exit(UUID playerId, boolean discardChanges) {
    EditorSession session = sessions.get(Preconditions.checkNotNull(playerId, "playerId"));
    if (session == null) return Optional.empty();
    if (session.dirty() && !discardChanges) {
      throw new IllegalStateException("存在未保存修改；请先保存或使用 exit discard");
    }
    sessions.remove(playerId);
    return Optional.of(session);
  }

  public Collection<EditorSession> all() {
    return List.copyOf(sessions.values());
  }
}
