package com.birditch.raidcore.game.plugin.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.map.MapValidationIssue;
import com.birditch.raidcore.map.MapValidationSeverity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    assertEquals("/raid editor tp spawn_a", payload.value());
  }

  @Test
  void lootZoneIdUsesTheDefaultLootTableWhenNoTableIsTyped() throws IOException {
    Path source = Path.of("src/main/java/com/birditch/raidcore/game/plugin/command/EditorCommands.java");
    String java = Files.readString(source);

    int branch = java.indexOf("Commands.literal(\"lootzone\")");
    int nextBranch = java.indexOf("private static LiteralArgumentBuilder", branch + 1);
    String lootZone = java.substring(branch, nextBranch);
    assertTrue(lootZone.contains("StringArgumentType.getString(ctx, \"id\"), null"),
        "/raid editor lootzone create <id> must execute with the default table");
    assertTrue(java.contains("table == null ? defaultLootTable(context) : table"),
        "the loot-zone factory must resolve the registered default LootTable");
  }
}
