package com.birditch.escapefromcraftov.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class HibernateStarterKitServiceTest {

  @Test
  void starterKitContainsThePlayableBasicLoadout() {
    var items = HibernateStarterKitService.starterItems();

    assertEquals(9, items.size());
    assertEquals(1, items.stream()
        .filter(item -> item.itemId().value().equals("basic_rig"))
        .findFirst().orElseThrow().quantity());
    assertEquals(1, items.stream()
        .filter(item -> item.itemId().value().equals("patrol_backpack"))
        .findFirst().orElseThrow().quantity());
    assertEquals(16, items.stream()
        .filter(item -> item.itemId().value().equals("arrow"))
        .findFirst().orElseThrow().quantity());
    assertEquals(100_000L, HibernateStarterKitService.STARTER_COINS.units());
  }

  @Test
  void starterPhysicalIdentitiesAreStableAndPlayerScoped() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    var item = HibernateStarterKitService.starterItems().getFirst();

    UUID initial = HibernateStarterKitService.itemIdentity(first, 0, item.itemId());

    assertEquals(initial,
        HibernateStarterKitService.itemIdentity(first, 0, item.itemId()));
    assertNotEquals(initial,
        HibernateStarterKitService.itemIdentity(second, 0, item.itemId()));
    assertNotEquals(initial, HibernateStarterKitService.starterAuditId(first));
  }
}
