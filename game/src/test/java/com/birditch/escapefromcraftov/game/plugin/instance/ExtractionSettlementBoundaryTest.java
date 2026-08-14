package com.birditch.escapefromcraftov.game.plugin.instance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExtractionSettlementBoundaryTest {

  @Test
  void inventorySnapshotAndInteractionLockStartAtTheLockedEvent() throws Exception {
    String source = Files.readString(projectRoot().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/instance/InstanceWorldBridge.java"));
    String locked = method(source, "onPlayerExtractionLocked", "onPlayerExtractionAuthorizationFailed");
    String ready = method(source, "onPlayerExtractionReady", "onSettlingMove");

    assertTrue(locked.contains("settlementItems(player, event.gameId())"),
        "locked snapshot must merge ordinary inventory with the secure container");
    assertTrue(source.contains("deps.carry().settlementSnapshot(player, gameId).settlementItems()"),
        "durable extraction must use the authoritative carry snapshot");
    assertTrue(locked.contains("pendingExtractions.put"));
    assertTrue(locked.contains("freeze(event.playerId())"));
    assertTrue(ready.indexOf("pendingExtractions.get(key)")
        < ready.indexOf("settlementItems(player, event.gameId())"),
        "ready must consume the locked snapshot before its defensive recovery path");
  }

  @Test
  void retryAndTerminalCompletionCannotBeBlockedByPresentation() throws Exception {
    String bridge = Files.readString(projectRoot().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/instance/InstanceWorldBridge.java"));
    String settle = methodText(bridge, "private void settleExtraction",
        "private void awardExtractionProgression");
    assertTrue(settle.indexOf("runLater(Duration.ofSeconds(5)")
        < settle.indexOf("present(\"撤离结算失败提示\""));

    String finish = methodText(bridge, "private void finishExtractionSettlement",
        "private void leaveOrReturnToLobby");
    assertTrue(finish.contains("critical(\"清理已撤离玩家物品\""));
    assertTrue(finish.indexOf("pending.completion().complete(CraftovInventoryOutcome.EXTRACTED)")
        < finish.indexOf("present(\"撤离成功提示\""));
    assertTrue(finish.contains("leaveOrReturnToLobby(pending.playerId()"));

    String combat = Files.readString(projectRoot().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/listener/GameCombatListener.java"));
    String death = methodText(combat, "private void settleDeath", "private void present");
    assertTrue(death.indexOf("runLater(Duration.ofSeconds(5)")
        < death.indexOf("present(\"死亡结算失败提示\""));
    assertTrue(death.contains("critical(\"关闭死亡安全箱结算\""));
    assertTrue(death.contains("critical(\n                \"写入死亡战报\""));
    assertTrue(death.indexOf("finally") < death.indexOf("games.leave(victimId)"));
  }

  @Test
  void deathStartsDurableSettlementBeforeReportsProgressionOrPresentation() throws Exception {
    String combat = Files.readString(projectRoot().resolve(
        "game/src/main/java/com/birditch/escapefromcraftov/game/plugin/listener/GameCombatListener.java"));
    String death = methodText(combat, "public void onDeath", "public void onQuit");
    int claim = death.indexOf("lootOwnership.recordPlayerDeath");
    int secure = death.indexOf("secureContainers.beginSettlement", claim);
    int settle = death.indexOf("settleDeath(victim", secure);
    int killReport = death.indexOf("raidReports.recordKill", settle);
    int progression = death.indexOf("awardProgression(\"kill\"", settle);
    int outcome = death.indexOf("raidReports.declareOutcome", settle);

    assertTrue(claim >= 0 && secure > claim && settle > secure);
    assertTrue(killReport > settle && progression > settle && outcome > settle,
        "secondary report/progression work must follow durable settlement startup");
    assertTrue(death.contains("bestEffort(\"记录击杀战报\""));
    assertTrue(death.contains("present(\"击杀播报\""));
    assertTrue(death.contains("bestEffort(\"声明死亡战报\""));
  }

  private static String method(String source, String startName, String nextName) {
    int start = source.indexOf("void " + startName);
    int end = source.indexOf("void " + nextName, start + 1);
    if (start < 0 || end < 0) throw new IllegalStateException("method boundary not found");
    return source.substring(start, end);
  }

  private static String methodText(String source, String startText, String nextText) {
    int start = source.indexOf(startText);
    int end = source.indexOf(nextText, start + startText.length());
    if (start < 0 || end < 0) throw new IllegalStateException("method boundary not found");
    return source.substring(start, end);
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
      current = current.getParent();
    }
    if (current == null) throw new IllegalStateException("project root not found");
    return current;
  }
}
