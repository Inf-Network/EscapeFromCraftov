package com.birditch.raidcore.game.plugin.editor;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.map.BlockPosition;
import com.birditch.raidcore.map.CuboidBounds;
import com.birditch.raidcore.map.EditorSession;
import com.birditch.raidcore.map.EditorSessionService;
import com.birditch.raidcore.map.ManualRaidMap;
import com.birditch.raidcore.map.MapObject;
import com.birditch.raidcore.map.MapObjectIndex;
import com.birditch.raidcore.map.MapObjectType;
import com.birditch.raidcore.map.MapValidationIssue;
import com.birditch.raidcore.map.MapValidationSeverity;
import com.birditch.raidcore.map.SpawnDefinition;
import com.birditch.raidcore.game.plugin.integration.HologramProvider;
import com.birditch.raidcore.game.plugin.integration.EditorHologramView;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/// Low-frequency, player-only editor overlay backed by chunk spatial queries.
public final class EditorVisualizationService implements Listener {

  private static final Map<MapObjectType, Color> COLORS = Map.of(
      MapObjectType.SPAWN, Color.LIME,
      MapObjectType.LOOT, Color.YELLOW,
      MapObjectType.LOOT_ZONE, Color.YELLOW,
      MapObjectType.AI_ZONE, Color.RED,
      MapObjectType.EXTRACTION, Color.AQUA,
      MapObjectType.POI, Color.PURPLE);

  private final EditorSessionService sessions;
  private final int maximumObjectsPerFrame;
  private final int maximumHologramsPerPlayer;
  private final HologramProvider holograms;
  private final Map<UUID, Cache> indexes = new HashMap<>();
  private final BukkitTask task;
  private boolean closed;

  public EditorVisualizationService(Plugin plugin, EditorSessionService sessions,
      int refreshTicks, int maximumObjectsPerFrame, int maximumHologramsPerPlayer,
      HologramProvider holograms) {
    Preconditions.checkNotNull(plugin, "plugin");
    this.sessions = Preconditions.checkNotNull(sessions, "sessions");
    Preconditions.checkArgument(refreshTicks > 0, "refreshTicks must be positive");
    Preconditions.checkArgument(maximumObjectsPerFrame > 0,
        "maximumObjectsPerFrame must be positive");
    Preconditions.checkArgument(maximumHologramsPerPlayer > 0,
        "maximumHologramsPerPlayer must be positive");
    this.maximumObjectsPerFrame = maximumObjectsPerFrame;
    this.maximumHologramsPerPlayer = maximumHologramsPerPlayer;
    this.holograms = Preconditions.checkNotNull(holograms, "holograms");
    this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::renderAll, 0L, refreshTicks);
  }

  public void close() {
    if (closed) return;
    closed = true;
    task.cancel();
    indexes.clear();
    holograms.close();
  }

  /** Releases the player's private DecentHolograms label as soon as the session ends. */
  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    clear(event.getPlayer());
  }

  /** Removes cached spatial state and every personal label immediately. */
  public void clear(Player player) {
    Preconditions.checkNotNull(player, "player");
    indexes.remove(player.getUniqueId());
    holograms.clear(player);
  }

  private void renderAll() {
    indexes.keySet().removeIf(playerId -> {
      if (sessions.find(playerId).isPresent()) return false;
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) holograms.clear(player);
      return true;
    });
    for (EditorSession session : sessions.all()) {
      Player player = Bukkit.getPlayer(session.playerId());
      if (player == null || !player.isOnline()) continue;
      render(player, session);
    }
  }

  private void render(Player player, EditorSession session) {
    ManualRaidMap map = session.draft();
    // RaidSidebarService owns the detailed editor Action Bar and Sidebar;
    // this renderer is deliberately limited to particles and Holograms.
    if (!player.getWorld().getName().equals(map.worldName())) {
      holograms.clear(player);
      return;
    }

    Cache cache = indexes.get(player.getUniqueId());
    if (cache == null || cache.map() != map) {
      cache = new Cache(map, new MapObjectIndex(map));
      indexes.put(player.getUniqueId(), cache);
    }
    BlockPosition center = position(player.getLocation());
    Map<String, List<MapValidationIssue>> validation = session.validationResult().stream()
        .flatMap(result -> result.issues().stream())
        .filter(issue -> issue.affectedObjectId().isPresent())
        .collect(Collectors.groupingBy(issue -> issue.affectedObjectId().orElseThrow()));
    List<MapObject> nearby = cache.index()
        .nearby(center, session.visibilityRadius(), session.visibleTypes()).stream()
        .limit(maximumObjectsPerFrame).toList();
    nearby.forEach(object -> draw(player, object,
            session.selectedObjectId().filter(object.id()::equals).isPresent(),
            validation.getOrDefault(object.id(), List.of())));
    List<EditorHologramView> labels = EditorHologramPlanner.plan(map, nearby, validation,
            session.selectedObjectId().orElse(null), maximumHologramsPerPlayer).stream()
        .map(label -> new EditorHologramView(label.key(), label.object(),
            location(player, label.object().anchor()).add(0.5, 2.4, 0.5),
            label.validationIssues(), label.style()))
        .toList();
    holograms.render(player, labels);
    if (map.bounds() != null && session.boundsVisible()) {
      drawBounds(player, map.bounds(), Color.WHITE, false);
    }
  }

  private static void draw(Player player, MapObject object, boolean selected,
      List<MapValidationIssue> issues) {
    boolean error = issues.stream().anyMatch(issue ->
        issue.severity() == MapValidationSeverity.ERROR);
    Color color = selected ? Color.WHITE : error ? Color.RED : COLORS.get(object.type());
    object.region().ifPresentOrElse(bounds -> drawBounds(player, bounds, color, selected), () -> {
      Location location = location(player, object.anchor()).add(0.5, 0.5, 0.5);
      dust(player, location, color, selected ? 1.8f : 1.1f);
      dust(player, location.clone().add(0, 1, 0), color, selected ? 1.8f : 1.1f);
    });
    if (object instanceof SpawnDefinition spawn) drawSpawnDirection(player, spawn);
    if (error) drawError(player, object.anchor());
  }

  private static void drawSpawnDirection(Player player, SpawnDefinition spawn) {
    double radians = Math.toRadians(spawn.yaw());
    double dx = -Math.sin(radians);
    double dz = Math.cos(radians);
    Location origin = location(player, spawn.position()).add(0.5, 1.1, 0.5);
    for (int step = 1; step <= 6; step++) {
      dust(player, origin.clone().add(dx * step * 0.35, 0, dz * step * 0.35),
          Color.LIME, 0.9f);
    }
    Location tip = origin.clone().add(dx * 2.1, 0, dz * 2.1);
    dust(player, tip.clone().add(-dx * 0.55 + dz * 0.4, 0, -dz * 0.55 - dx * 0.4),
        Color.LIME, 0.9f);
    dust(player, tip.clone().add(-dx * 0.55 - dz * 0.4, 0, -dz * 0.55 + dx * 0.4),
        Color.LIME, 0.9f);
  }

  private static void drawError(Player player, BlockPosition position) {
    Location center = location(player, position).add(0.5, 2.1, 0.5);
    for (int step = -3; step <= 3; step++) {
      double offset = step * 0.16;
      dust(player, center.clone().add(offset, offset, 0), Color.RED, 1.3f);
      dust(player, center.clone().add(offset, -offset, 0), Color.RED, 1.3f);
    }
  }

  private static void drawBounds(Player player, CuboidBounds bounds, Color color,
      boolean selected) {
    int samples = 6;
    for (int index = 0; index <= samples; index++) {
      double progress = index / (double) samples;
      double x = bounds.minX() + (bounds.maxX() - bounds.minX()) * progress + 0.5;
      double z = bounds.minZ() + (bounds.maxZ() - bounds.minZ()) * progress + 0.5;
      edge(player, x, bounds.minY(), bounds.minZ(), color, selected);
      edge(player, x, bounds.minY(), bounds.maxZ(), color, selected);
      edge(player, bounds.minX(), bounds.minY(), z, color, selected);
      edge(player, bounds.maxX(), bounds.minY(), z, color, selected);
    }
    for (int y : new int[] {bounds.minY(), bounds.maxY() + 1}) {
      edge(player, bounds.minX(), y, bounds.minZ(), color, selected);
      edge(player, bounds.maxX(), y, bounds.minZ(), color, selected);
      edge(player, bounds.minX(), y, bounds.maxZ(), color, selected);
      edge(player, bounds.maxX(), y, bounds.maxZ(), color, selected);
    }
  }

  private static void edge(Player player, double x, double y, double z, Color color,
      boolean selected) {
    dust(player, new Location(player.getWorld(), x + 0.5, y + 0.2, z + 0.5), color,
        selected ? 1.5f : 0.85f);
  }

  private static void dust(Player player, Location location, Color color, float size) {
    if (player.getLocation().distanceSquared(location) > 512.0 * 512.0) return;
    player.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0,
        new Particle.DustOptions(color, size));
  }

  private static Location location(Player player, BlockPosition position) {
    return new Location(player.getWorld(), position.x(), position.y(), position.z());
  }

  private static BlockPosition position(Location location) {
    return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  private record Cache(ManualRaidMap map, MapObjectIndex index) {}
}
