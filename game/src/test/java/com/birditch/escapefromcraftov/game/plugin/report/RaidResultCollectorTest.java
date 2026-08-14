package com.birditch.escapefromcraftov.game.plugin.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.api.report.RaidReportService;
import com.birditch.escapefromcraftov.api.report.RaidResult;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import com.birditch.escapefromcraftov.game.plugin.instance.FakeCraftovGame;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class RaidResultCollectorTest {

  @Test
  void freezesOneIdempotentReportWithKillsQuantityValueAndReward() {
    InMemoryReports reports = new InMemoryReports();
    SimpleItemRegistry registry = new SimpleItemRegistry();
    ItemId itemId = ItemId.of("military_cpu");
    registry.register(ItemDefinition.builder(itemId, "军用处理器", ItemCategory.MATERIAL,
            ItemRarity.RARE).basePrice(Money.of(2_500)).stackSize(16).build());
    MutableTimeSource time = new MutableTimeSource(300_000);
    RaidResultCollector collector = new RaidResultCollector(reports, registry, time);
    FakeCraftovGame game = new FakeCraftovGame(CraftovGameId.of("raid-7"),
        CraftovMapId.of("test_flat"), RaidMode.CONFIDENTIAL, RaidDifficulty.HARD);
    UUID playerId = UUID.randomUUID();
    collector.track(game, playerId);
    collector.recordKill(game.id(), playerId);
    collector.recordKill(game.id(), playerId);
    collector.recordPveKill(game.id(), playerId);
    List<ItemInstance> loot = List.of(new ItemInstance(UUID.randomUUID(), itemId, 3));

    RaidResult first = collector.complete(game.id(), playerId, RaidOutcome.EXTRACTED,
        loot, 800).join();
    RaidResult retried = collector.complete(game.id(), playerId, RaidOutcome.KILLED,
        List.of(), 0).join();

    assertSame(first, retried);
    assertEquals(3, first.kills());
    assertEquals(2, first.pvpKills());
    assertEquals(1, first.pveKills());
    assertEquals(3, first.extractedItemCount());
    assertEquals(7_500, first.extractedValue());
    assertEquals(7_500, first.earnings());
    assertEquals(2, reports.records.size());
    assertSame(reports.records.get(0), reports.records.get(1));
  }

  private static final class InMemoryReports implements RaidReportService {
    private final List<RaidResult> records = new ArrayList<>();

    @Override
    public CompletableFuture<RaidResult> record(RaidResult result) {
      records.add(result);
      return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<List<RaidResult>> recent(UUID playerId, int limit) {
      return CompletableFuture.completedFuture(List.copyOf(records));
    }
  }
}
