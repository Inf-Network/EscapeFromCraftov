package com.birditch.escapefromcraftov.game.plugin.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovMapEventType;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.core.service.ServiceRegistry;
import com.birditch.escapefromcraftov.game.CraftovMapRuntime;
import com.birditch.escapefromcraftov.game.plugin.extraction.BindableEventCompletionLookup;
import com.birditch.escapefromcraftov.game.plugin.extraction.EventCompletedExtractionCondition;
import com.birditch.escapefromcraftov.game.plugin.extraction.EventCompletionLookup;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.MapEventDefinition;
import com.birditch.escapefromcraftov.map.MapEventType;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualCraftovMapProviderTest {

  @TempDir Path temporaryDirectory;

  @Test
  void compilesOnlyEnabledMapEventsIntoImmutableRuntimeData() {
    ManualMapStore store = new ManualMapStore(temporaryDirectory);
    ManualCraftovMap source = ManualMapStoreTest.map();
    MapEventDefinition enabled = source.events().getFirst();
    MapEventDefinition disabled = new MapEventDefinition("disabled_target", "Disabled Target",
        MapEventType.HIGH_VALUE_TARGET, "ai_zone_a", "loot_a", 30, 2, 1, 0, false);
    store.save(source.withEvents(List.of(enabled, disabled)).withEnabled(true));
    ServiceRegistry services = new ServiceRegistry();
    BindableEventCompletionLookup lookup = new BindableEventCompletionLookup();
    services.register(EventCompletionLookup.class, lookup);
    ManualCraftovMapProvider provider = new ManualCraftovMapProvider(
        new ManualMapRegistry(store), services);

    CraftovMapRuntime runtime = provider.runtime(CraftovMapId.of("factory")).orElseThrow();

    assertEquals(1, runtime.mapEvents().size());
    var event = runtime.mapEvents().getFirst();
    assertEquals("factory_overseer", event.id());
    assertEquals(CraftovMapEventType.HIGH_VALUE_TARGET, event.type());
    assertEquals("ai_zone_a", event.aiZoneId());
    assertEquals("loot_a", event.cacheLootPointId());
    assertTrue(event.enabled());
    assertThrows(UnsupportedOperationException.class, runtime.mapEvents()::clear);
    EventCompletedExtractionCondition condition = (EventCompletedExtractionCondition)
        runtime.extractionPoints().getFirst().conditions().stream()
            .filter(EventCompletedExtractionCondition.class::isInstance).findFirst()
            .orElseThrow();
    CraftovGameId gameId = CraftovGameId.of("runtime-snapshot");
    ExtractionContext context = new ExtractionContext(UUID.randomUUID(), gameId);
    assertEquals("factory_overseer", condition.eventId());
    assertEquals("Factory Overseer", condition.displayName());
    assertFalse(condition.isSatisfied(context));
    lookup.bind((candidateGame, candidateEvent) ->
        candidateGame.equals(gameId) && candidateEvent.equals("factory_overseer"));
    assertTrue(condition.isSatisfied(context));
    lookup.unbind();
    assertFalse(condition.isSatisfied(context));
  }
}
