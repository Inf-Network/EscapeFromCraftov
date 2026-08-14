package com.birditch.escapefromcraftov.game.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.escapefromcraftov.map.MapValidationIssue;
import com.birditch.escapefromcraftov.map.MapValidationSeverity;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.MapEventDefinition;
import com.birditch.escapefromcraftov.map.MapEventType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards command shortcuts that are intentionally executable before optional arguments. */
class EditorCommandBoundaryTest {

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
    ManualCraftovMap map = ManualCraftovMap.create("test", "world").withEvents(List.of(
        event("enabled_target", true), event("disabled_target", false)));

    assertEquals(List.of("enabled_target"), EditorCommands.enabledEventIds(map));
    assertEquals("enabled_target",
        EditorCommands.enabledEventRule(map, "enabled_target").eventId());
    assertThrows(IllegalArgumentException.class,
        () -> EditorCommands.enabledEventRule(map, "disabled_target"));
    assertThrows(IllegalArgumentException.class,
        () -> EditorCommands.enabledEventRule(map, "missing_target"));
  }

  private static MapEventDefinition event(String id, boolean enabled) {
    return new MapEventDefinition(id, id, MapEventType.HIGH_VALUE_TARGET,
        "ai_zone", "loot_cache", 0, 1, 1, 0, enabled);
  }
}
