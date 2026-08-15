package com.birditch.escapefromcraftov.game.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.escapefromcraftov.map.MapValidationIssue;
import com.birditch.escapefromcraftov.map.MapValidationSeverity;
import com.birditch.escapefromcraftov.map.AiZoneDefinition;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import com.birditch.escapefromcraftov.map.LootPointDefinition;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.MapEventDefinition;
import com.birditch.escapefromcraftov.map.MapEventType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards command shortcuts that are intentionally executable before optional arguments. */
class EditorCommandBoundaryTest {

  @Test
  void readableNameHandlesLeadingAndRepeatedUnderscores() {
    assertEquals("Boss Target", EditorCommands.readableName("_boss__target_"));
    assertEquals("__", EditorCommands.readableName("__"));
  }

  @Test
  void validationIssueLinkTeleportsDirectlyToTheAffectedObject() {
    var component = EditorCommands.issueComponent(new MapValidationIssue(
        MapValidationSeverity.ERROR, "spawn_invalid", "spawn_a", "出生点不可用"));

    var click = component.children().getFirst().clickEvent();
    assertNotNull(click);
    var payload = org.junit.jupiter.api.Assertions.assertInstanceOf(
        net.kyori.adventure.text.event.ClickEvent.Payload.Text.class, click.payload());
    assertEquals("/craftov editor tp spawn_a", payload.value());
  }

  @Test
  void validationIssueLinkListsNonSpatialEventsInsteadOfTeleporting() {
    var component = EditorCommands.issueComponent(new MapValidationIssue(
        MapValidationSeverity.ERROR, "event_ai_zone_missing", "boss_a", "AI 区域不存在"));

    var click = component.children().getFirst().clickEvent();
    assertNotNull(click);
    var payload = org.junit.jupiter.api.Assertions.assertInstanceOf(
        net.kyori.adventure.text.event.ClickEvent.Payload.Text.class, click.payload());
    assertEquals("/craftov editor event list", payload.value());
  }

  @Test
  void lootZoneIdUsesTheDefaultLootTableWhenNoTableIsTyped() throws IOException {
    Path source = Path.of("src/main/java/com/birditch/escapefromcraftov/game/plugin/command/EditorCommands.java");
    String java = Files.readString(source);

    int branch = java.indexOf("Commands.literal(\"lootzone\")");
    int nextBranch = java.indexOf("private static LiteralArgumentBuilder", branch + 1);
    String lootZone = java.substring(branch, nextBranch);
    assertTrue(lootZone.contains("StringArgumentType.getString(ctx, \"id\"), null"),
        "/craftov editor lootzone create <id> must execute with the default table");
    assertTrue(java.contains("table == null ? defaultLootTable(context) : table"),
        "the loot-zone factory must resolve the registered default LootTable");
  }

  @Test
  void eventRuleAcceptsAndSuggestsOnlyEnabledEventsFromTheCurrentDraft() {
    ManualCraftovMap map = eventMap().withEvents(List.of(
        event("enabled_target", true), event("disabled_target", false)));

    assertEquals(List.of("enabled_target"), EditorCommands.enabledEventIds(map));
    assertEquals(List.of("enabled_target", "disabled_target"), EditorCommands.eventIds(map));
    assertEquals(List.of("ai_zone_b"), EditorCommands.availableEventAiZoneIds(map, null));
    assertEquals(List.of("loot_b"), EditorCommands.availableEventLootPointIds(map, null));
    assertEquals(List.of("ai_zone_a", "ai_zone_b"),
        EditorCommands.availableEventAiZoneIds(map, "enabled_target"));
    assertEquals("enabled_target",
        EditorCommands.enabledEventRule(map, "enabled_target").eventId());
    assertThrows(IllegalArgumentException.class,
        () -> EditorCommands.enabledEventRule(map, "disabled_target"));
    assertThrows(IllegalArgumentException.class,
        () -> EditorCommands.enabledEventRule(map, "missing_target"));
  }

  @Test
  void eventDeleteConfirmationBindsTheExactDraftEventAndRechecksPermissions() throws IOException {
    String java = Files.readString(Path.of(
        "src/main/java/com/birditch/escapefromcraftov/game/plugin/command/EditorCommands.java"));

    assertTrue(java.contains("ManualCraftovMap expectedDraft = expectedSession.draft()"));
    assertTrue(java.contains("live != expectedSession || live.draft() != expectedDraft"));
    assertTrue(java.contains("current != expectedEvent"));
    assertTrue(java.contains("CraftovPermissions.EDITOR_DELETE"));
    assertTrue(java.contains("live.apply(map -> EVENT_EDITOR.remove(map, eventId))"));
  }

  private static ManualCraftovMap eventMap() {
    return new ManualCraftovMap(ManualCraftovMap.SCHEMA_VERSION, "test", "Test", "world",
        null, 0, false, Map.of(), List.of(),
        List.of(new LootPointDefinition("loot_a", new BlockPosition(0, 64, 0), "virtual",
                "default", 1, true, true),
            new LootPointDefinition("loot_b", new BlockPosition(10, 64, 0), "virtual",
                "default", 1, true, true)),
        List.of(),
        List.of(new AiZoneDefinition("ai_zone_a", new CuboidBounds(0, 60, 0, 5, 70, 5),
                "scav", 1, 1, 60, 1, "scav", true),
            new AiZoneDefinition("ai_zone_b", new CuboidBounds(10, 60, 0, 15, 70, 5),
                "scav", 1, 1, 60, 1, "scav", true)),
        List.of(), List.of());
  }

  private static MapEventDefinition event(String id, boolean enabled) {
    return new MapEventDefinition(id, id, MapEventType.HIGH_VALUE_TARGET,
        enabled ? "ai_zone_a" : "ai_zone_b", enabled ? "loot_a" : "loot_b",
        0, 1, 1, 0, enabled);
  }
}
