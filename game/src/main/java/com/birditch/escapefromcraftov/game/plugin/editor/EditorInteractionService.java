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
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/// Maintains a small private interaction layer around each active editor.
/// Markers are non-persistent, invisible by default and removed outside the
/// nearby radius, so large maps never create a full-world entity set.
public final class EditorInteractionService implements Listener, AutoCloseable {

  private static final int INTERACTION_RADIUS = 48;
  private static final int MAXIMUM_MARKERS = 48;

  private final Plugin plugin;
  private final EditorSessionService sessions;
  private final EditorObjectMenuController objectMenus;
  private final NamespacedKey ownerKey;
  private final NamespacedKey objectKey;
  private final Map<UUID, PlayerMarkers> markers = new HashMap<>();
  private final Map<UUID, Cache> indexes = new HashMap<>();
  private final BukkitTask task;
  private boolean closed;

  public EditorInteractionService(Plugin plugin, EditorSessionService sessions,
      EditorObjectMenuController objectMenus, int refreshTicks) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.sessions = Preconditions.checkNotNull(sessions, "sessions");
    this.objectMenus = Preconditions.checkNotNull(objectMenus, "objectMenus");
    Preconditions.checkArgument(refreshTicks > 0, "refreshTicks must be positive");
    ownerKey = new NamespacedKey(plugin, "editor_marker_owner");
    objectKey = new NamespacedKey(plugin, "editor_marker_object");
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::syncAll, 1L, refreshTicks);
  }

  @EventHandler(ignoreCancelled = false)
  public void onAttack(PrePlayerAttackEntityEvent event) {
    if (!(event.getAttacked() instanceof Interaction interaction) || !isMarker(interaction)) return;
    event.setCancelled(true);
    select(event.getPlayer(), interaction, false);
  }

  @EventHandler(ignoreCancelled = false)
  public void onInteract(PlayerInteractEntityEvent event) {
    if (!(event.getRightClicked() instanceof Interaction interaction) || !isMarker(interaction)) {
      return;
    }
    event.setCancelled(true);
    if (event.getHand() != EquipmentSlot.HAND) return;
    select(event.getPlayer(), interaction, event.getPlayer().isSneaking());
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    clear(event.getPlayer().getUniqueId());
  }

  private void select(Player player, Interaction interaction, boolean openProperties) {
    String owner = interaction.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
    if (!player.getUniqueId().toString().equals(owner)) return;
    String objectId = interaction.getPersistentDataContainer().get(objectKey,
        PersistentDataType.STRING);
    if (objectId == null) return;
    EditorSession session = sessions.find(player.getUniqueId()).orElse(null);
    if (session == null || session.draft().findObject(objectId).isEmpty()) {
      interaction.remove();
      return;
    }
    MapObject object = session.draft().findObject(objectId).orElseThrow();
    session.select(objectId);
    session.setTool(tool(object.type()));
    player.sendActionBar(Component.text("已选择 " + object.type() + " · " + object.id(),
        NamedTextColor.AQUA));
    if (openProperties) objectMenus.open(player);
  }

  private void syncAll() {
    if (closed) return;
    Set<UUID> active = new HashSet<>();
    for (EditorSession session : sessions.all()) {
      Player player = Bukkit.getPlayer(session.playerId());
      if (player == null || !player.isOnline()
          || !player.getWorld().getName().equals(session.draft().worldName())) {
        clear(session.playerId());
        continue;
      }
      active.add(session.playerId());
      sync(player, session);
    }
    new HashSet<>(markers.keySet()).stream()
        .filter(playerId -> !active.contains(playerId)).forEach(this::clear);
  }

  private void sync(Player player, EditorSession session) {
    ManualCraftovMap map = session.draft();
    Cache cache = indexes.get(player.getUniqueId());
    if (cache == null || cache.map() != map) {
      cache = new Cache(map, new MapObjectIndex(map));
      indexes.put(player.getUniqueId(), cache);
    }
    BlockPosition playerPosition = position(player.getLocation());
    int radius = Math.min(INTERACTION_RADIUS, session.visibilityRadius());
    List<MapObject> nearby = cache.index().nearby(playerPosition, radius, session.visibleTypes())
        .stream().sorted(Comparator.comparingDouble(object -> distanceSquared(
            playerPosition, object.anchor()))).limit(MAXIMUM_MARKERS).toList();
    PlayerMarkers playerMarkers = markers.computeIfAbsent(player.getUniqueId(),
        _ -> new PlayerMarkers());
    Set<String> wanted = nearby.stream().map(MapObject::id)
        .collect(java.util.stream.Collectors.toSet());
    playerMarkers.values.entrySet().removeIf(entry -> {
      if (wanted.contains(entry.getKey()) && entry.getValue().isValid()) return false;
      if (entry.getValue().isValid()) entry.getValue().remove();
      return true;
    });
    for (MapObject object : nearby) {
      Location location = markerLocation(player, object);
      Interaction marker = playerMarkers.values.get(object.id());
      if (marker == null || !marker.isValid()) {
        marker = spawn(player, object.id(), location);
        playerMarkers.values.put(object.id(), marker);
      } else if (marker.getLocation().distanceSquared(location) > 0.04) {
        marker.teleport(location);
      }
    }
  }

  private Interaction spawn(Player owner, String objectId, Location location) {
    Interaction interaction = location.getWorld().spawn(location, Interaction.class, entity -> {
      entity.setPersistent(false);
      entity.setVisibleByDefault(false);
      entity.setResponsive(true);
      entity.setInteractionWidth(0.9f);
      entity.setInteractionHeight(1.8f);
      entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
          owner.getUniqueId().toString());
      entity.getPersistentDataContainer().set(objectKey, PersistentDataType.STRING, objectId);
    });
    owner.showEntity(plugin, interaction);
    return interaction;
  }

  private Location markerLocation(Player player, MapObject object) {
    BlockPosition target = object.region().map(bounds -> closest(bounds, position(player.getLocation())))
        .orElse(object.anchor());
    return new Location(player.getWorld(), target.x() + 0.5, target.y(), target.z() + 0.5);
  }

  private static BlockPosition closest(CuboidBounds bounds, BlockPosition player) {
    return new BlockPosition(clamp(player.x(), bounds.minX(), bounds.maxX()),
        clamp(player.y(), bounds.minY(), bounds.maxY()),
        clamp(player.z(), bounds.minZ(), bounds.maxZ()));
  }

  private boolean isMarker(Interaction interaction) {
    return interaction.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)
        && interaction.getPersistentDataContainer().has(objectKey, PersistentDataType.STRING);
  }

  /** Removes every private click marker owned by this editor immediately. */
  public void clear(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    indexes.remove(playerId);
    PlayerMarkers removed = markers.remove(playerId);
    if (removed != null) removed.values.values().forEach(entity -> {
      if (entity.isValid()) entity.remove();
    });
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    task.cancel();
    new HashSet<>(markers.keySet()).forEach(this::clear);
    indexes.clear();
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private static double distanceSquared(BlockPosition first, BlockPosition second) {
    long dx = (long) first.x() - second.x();
    long dy = (long) first.y() - second.y();
    long dz = (long) first.z() - second.z();
    return (double) dx * dx + (double) dy * dy + (double) dz * dz;
  }

  private static BlockPosition position(Location location) {
    return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  private static EditorTool tool(MapObjectType type) {
    return switch (type) {
      case SPAWN -> EditorTool.SPAWN;
      case LOOT -> EditorTool.LOOT;
      case LOOT_ZONE -> EditorTool.LOOT_ZONE;
      case AI_ZONE -> EditorTool.AI_ZONE;
      case EXTRACTION -> EditorTool.EXTRACTION;
      case POI -> EditorTool.POI;
    };
  }

  private static final class PlayerMarkers {
    private final Map<String, Interaction> values = new HashMap<>();
  }

  private record Cache(ManualCraftovMap map, MapObjectIndex index) {}
}
