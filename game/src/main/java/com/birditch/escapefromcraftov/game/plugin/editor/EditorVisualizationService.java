package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import com.birditch.escapefromcraftov.map.EditorSession;
import com.birditch.escapefromcraftov.map.EditorSessionService;
import com.birditch.escapefromcraftov.map.EditorTool;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.MapObject;
import com.birditch.escapefromcraftov.map.MapObjectIndex;
import com.birditch.escapefromcraftov.map.MapObjectType;
import com.birditch.escapefromcraftov.map.MapValidationIssue;
import com.birditch.escapefromcraftov.map.MapValidationSeverity;
import com.birditch.escapefromcraftov.map.SpawnDefinition;
import com.birditch.escapefromcraftov.game.plugin.integration.HologramProvider;
import com.birditch.escapefromcraftov.game.plugin.integration.EditorHologramView;
import com.birditch.escapefromcraftov.game.plugin.integration.SelectionProvider;
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

  private static final Color MAP_COLOR = Color.ORANGE;
  private static final Map<MapObjectType, Color> COLORS = Map.of(
      MapObjectType.SPAWN, Color.LIME,
      MapObjectType.LOOT, Color.YELLOW,
      MapObjectType.LOOT_ZONE, Color.YELLOW,
      MapObjectType.AI_ZONE, Color.RED,
      MapObjectType.EXTRACTION, Color.AQUA,
      MapObjectType.POI, Color.PURPLE);
  private static final int MAXIMUM_LINE_SAMPLES = 48;

  private final EditorSessionService sessions;
  private final int maximumObjectsPerFrame;
  private final int maximumHologramsPerPlayer;
  private final HologramProvider holograms;
  private final SelectionProvider selections;
  private final Map<UUID, Cache> indexes = new HashMap<>();
  private final BukkitTask task;
  private boolean closed;

  public EditorVisualizationService(Plugin plugin, EditorSessionService sessions,
      int refreshTicks, int maximumObjectsPerFrame, int maximumHologramsPerPlayer,
      HologramProvider holograms, SelectionProvider selections) {
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
    this.selections = Preconditions.checkNotNull(selections, "selections");
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
    ManualCraftovMap map = session.draft();
    // CraftovSidebarService owns the detailed editor Action Bar and Sidebar;
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
      drawBounds(player, map.bounds(), MAP_COLOR, false);
    }
    selections.selection(player, map.worldName()).ifPresent(bounds ->
        drawBounds(player, bounds, color(session.tool()), true));
  }

  private static void draw(Player player, MapObject object, boolean selected,
      List<MapValidationIssue> issues) {
    boolean error = issues.stream().anyMatch(issue ->
        issue.severity() == MapValidationSeverity.ERROR);
    Color color = error ? Color.RED : COLORS.get(object.type());
    object.region().ifPresentOrElse(bounds -> drawBounds(player, bounds, color, selected), () -> {
      Location location = location(player, object.anchor()).add(0.5, 0.5, 0.5);
      for (int index = 0; index <= 5; index++) {
        dust(player, location.clone().add(0, index * 0.4, 0), color,
            selected ? 1.8f : 1.1f);
      }
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
    double minX = bounds.minX() + 0.5;
    double maxX = bounds.maxX() + 0.5;
    double minZ = bounds.minZ() + 0.5;
    double maxZ = bounds.maxZ() + 0.5;
    // A full-height map selection can span from the build floor to build ceiling.
    // Project its horizontal outline onto the editor's current Y so the border
    // remains visible instead of being drawn hundreds of blocks above/below them.
    double displayY = clamp(player.getLocation().getY() + 0.2,
        bounds.minY() + 0.2, bounds.maxY() + 1.2);
    line(player, minX, displayY, minZ, maxX, displayY, minZ, color, selected);
    line(player, minX, displayY, maxZ, maxX, displayY, maxZ, color, selected);
    line(player, minX, displayY, minZ, minX, displayY, maxZ, color, selected);
    line(player, maxX, displayY, minZ, maxX, displayY, maxZ, color, selected);

    double minY = bounds.minY() + 0.2;
    double maxY = bounds.maxY() + 1.2;
    line(player, minX, minY, minZ, minX, maxY, minZ, color, selected);
    line(player, maxX, minY, minZ, maxX, maxY, minZ, color, selected);
    line(player, minX, minY, maxZ, minX, maxY, maxZ, color, selected);
    line(player, maxX, minY, maxZ, maxX, maxY, maxZ, color, selected);
  }

  private static void line(Player player, double fromX, double fromY, double fromZ,
      double toX, double toY, double toZ, Color color, boolean selected) {
    double length = Math.sqrt(square(toX - fromX) + square(toY - fromY)
        + square(toZ - fromZ));
    int samples = Math.max(1, Math.min(MAXIMUM_LINE_SAMPLES, (int) Math.ceil(length / 2)));
    for (int index = 0; index <= samples; index++) {
      double progress = index / (double) samples;
      dust(player, new Location(player.getWorld(),
          interpolate(fromX, toX, progress), interpolate(fromY, toY, progress),
          interpolate(fromZ, toZ, progress)), color, selected ? 1.55f : 0.95f);
    }
  }

  static Color color(EditorTool tool) {
    return switch (tool) {
      case MAP -> MAP_COLOR;
      case SPAWN -> COLORS.get(MapObjectType.SPAWN);
      case LOOT -> COLORS.get(MapObjectType.LOOT);
      case LOOT_ZONE -> COLORS.get(MapObjectType.LOOT_ZONE);
      case AI_ZONE -> COLORS.get(MapObjectType.AI_ZONE);
      case EXTRACTION -> COLORS.get(MapObjectType.EXTRACTION);
      case POI -> COLORS.get(MapObjectType.POI);
    };
  }

  private static double interpolate(double from, double to, double progress) {
    return from + (to - from) * progress;
  }

  private static double square(double value) {
    return value * value;
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
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

  private record Cache(ManualCraftovMap map, MapObjectIndex index) {}
}
