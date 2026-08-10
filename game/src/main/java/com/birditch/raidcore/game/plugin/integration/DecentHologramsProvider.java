package com.birditch.raidcore.game.plugin.integration;

import com.birditch.raidcore.map.MapObject;
import com.birditch.raidcore.map.AiZoneDefinition;
import com.birditch.raidcore.map.ExtractionDefinition;
import com.birditch.raidcore.map.LootPointDefinition;
import com.birditch.raidcore.map.LootZoneDefinition;
import com.birditch.raidcore.map.PoiDefinition;
import com.birditch.raidcore.map.SpawnDefinition;
import com.birditch.raidcore.map.MapValidationIssue;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/// DecentHolograms public-API adapter with per-player visibility.
public final class DecentHologramsProvider implements HologramProvider {

  private static final String NAME_PREFIX = "raidcore_editor_";
  private final Map<UUID, Map<String, Entry>> entries = new HashMap<>();
  private boolean closed;

  public DecentHologramsProvider() {
    // Probe the public API during plugin startup and clear only RaidCore's
    // unsaved labels left by an interrupted reload.
    Hologram.getCachedHolograms().stream()
        .filter(hologram -> hologram.getName().startsWith(NAME_PREFIX))
        .map(Hologram::getName).toList().forEach(DHAPI::removeHologram);
  }

  @Override
  public void render(Player player, List<EditorHologramView> views) {
    if (closed) return;
    Map<String, Entry> currentEntries = entries.computeIfAbsent(player.getUniqueId(),
        _ -> new HashMap<>());
    HashSet<String> wanted = new HashSet<>();
    for (EditorHologramView view : views) {
      wanted.add(view.key());
      reconcile(player, currentEntries, view);
    }
    currentEntries.entrySet().removeIf(entry -> {
      if (wanted.contains(entry.getKey())) return false;
      DHAPI.removeHologram(entry.getValue().hologram().getName());
      return true;
    });
    if (currentEntries.isEmpty()) entries.remove(player.getUniqueId());
  }

  private void reconcile(Player player, Map<String, Entry> currentEntries,
      EditorHologramView view) {
    List<String> renderedLines = lines(view);
    Location renderedLocation = view.location();
    Entry current = currentEntries.get(view.key());
    if (current != null && current.objectId().equals(view.object().id())) {
      boolean moved = !current.location().equals(renderedLocation);
      boolean changed = !current.lines().equals(renderedLines);
      if (moved) DHAPI.moveHologram(current.hologram(), renderedLocation);
      if (changed) DHAPI.setHologramLines(current.hologram(), renderedLines);
      if (moved || changed) {
        currentEntries.put(view.key(), new Entry(view.object().id(), current.hologram(),
            renderedLocation.clone(), renderedLines));
      }
      return;
    }
    if (current != null) DHAPI.removeHologram(current.hologram().getName());
    String name = NAME_PREFIX + player.getUniqueId().toString().replace("-", "")
        + "_" + view.key();
    Hologram stale = Hologram.getCachedHologram(name);
    if (stale != null) DHAPI.removeHologram(stale.getName());
    // DHAPI.createHologram shows non-empty pages to every online player before
    // returning. Create an empty page first so no editor label packet can leak
    // to ordinary players, then opt only this administrator into visibility.
    Hologram hologram = DHAPI.createHologram(name, renderedLocation, false, List.of());
    hologram.setDefaultVisibleState(false);
    hologram.setShowPlayer(player);
    DHAPI.setHologramLines(hologram, renderedLines);
    hologram.show(player, 0);
    currentEntries.put(view.key(), new Entry(view.object().id(), hologram,
        renderedLocation.clone(), renderedLines));
  }

  @Override
  public void clear(Player player) {
    if (closed) return;
    Map<String, Entry> values = entries.remove(player.getUniqueId());
    if (values != null) values.values().forEach(value ->
        DHAPI.removeHologram(value.hologram().getName()));
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    entries.values().forEach(values -> values.values().forEach(value ->
        DHAPI.removeHologram(value.hologram().getName())));
    entries.clear();
  }

  private static List<String> lines(EditorHologramView view) {
    MapObject object = view.object();
    List<MapValidationIssue> validationIssues = view.validationIssues();
    if (view.style() == EditorHologramView.Style.ERROR) {
      java.util.ArrayList<String> errors = new java.util.ArrayList<>(List.of(
          "&c&lERROR", "&f" + object.type() + " · " + object.id()));
      validationIssues.stream()
          .filter(issue -> issue.severity()
              == com.birditch.raidcore.map.MapValidationSeverity.ERROR)
          .limit(3).forEach(issue -> errors.add("&c" + issue.message()));
      return List.copyOf(errors);
    }
    if (view.style() == EditorHologramView.Style.NEARBY) {
      return List.of("&f&l" + displayType(object) + " &7· " + object.id(),
          (object.enabled() ? "&a可用" : "&c已停用") + " &7· " + shortDetails(object));
    }
    String details = switch (object) {
      case SpawnDefinition spawn -> "队伍=" + spawn.team() + " 权重=" + spawn.weight();
      case LootPointDefinition loot -> "掉落=" + loot.lootTable() + " Tier=" + loot.tier()
          + " 容器=" + loot.containerType();
      case LootZoneDefinition zone -> "掉落=" + zone.lootTable() + " Tier=" + zone.tier()
          + " 密度=" + zone.density() + " 危险=" + zone.danger();
      case AiZoneDefinition ai -> "类型=" + ai.aiType() + " 数量=" + ai.minimum() + "-"
          + ai.maximum() + " 重生=" + ai.respawnSeconds() + "s";
      case ExtractionDefinition extraction -> "名称=" + extraction.displayName() + " 类型="
          + extraction.extractionType() + " 引导=" + extraction.durationSeconds() + "s";
      case PoiDefinition poi -> "名称=" + poi.displayName() + " Tier=" + poi.tier();
    };
    String bounds = object.region().map(value -> "区域=" + value.width() + "x"
        + value.height() + "x" + value.depth()).orElse("");
    java.util.ArrayList<String> lines = new java.util.ArrayList<>(List.of(
        "&6&lRAID EDITOR", "&f" + object.type() + " · " + object.id(),
        "&7" + object.anchor().x() + ", " + object.anchor().y() + ", " + object.anchor().z(),
        "&b" + details, "&a" + (object.enabled() ? "启用" : "停用")
            + (bounds.isBlank() ? "" : " · " + bounds)));
    validationIssues.stream().limit(3).forEach(issue -> lines.add(
        (issue.severity() == com.birditch.raidcore.map.MapValidationSeverity.ERROR
            ? "&c" : "&e") + issue.severity() + " · " + issue.message()));
    return List.copyOf(lines);
  }

  private static String displayType(MapObject object) {
    return switch (object.type()) {
      case SPAWN -> "出生点";
      case LOOT -> "战利品点";
      case LOOT_ZONE -> "战利品区域";
      case AI_ZONE -> "生物区域";
      case EXTRACTION -> "撤离点";
      case POI -> "兴趣点";
    };
  }

  private static String shortDetails(MapObject object) {
    return switch (object) {
      case SpawnDefinition spawn -> spawn.team();
      case LootPointDefinition loot -> loot.lootTable() + " · Tier " + loot.tier();
      case LootZoneDefinition zone -> zone.lootTable() + " · Tier " + zone.tier();
      case AiZoneDefinition ai -> ai.aiType() + " · " + ai.minimum() + "-" + ai.maximum();
      case ExtractionDefinition extraction -> extraction.displayName();
      case PoiDefinition poi -> poi.displayName() + " · Tier " + poi.tier();
    };
  }

  private record Entry(String objectId, Hologram hologram, Location location,
                       List<String> lines) {}
}
