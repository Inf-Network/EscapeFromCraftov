package com.birditch.escapefromcraftov.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HibernatePlayerMarketServiceTest {

  @Test
  void bothProfilesAlwaysUseOneDeterministicLockOrder() {
    UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    assertEquals(List.of(low, high),
        HibernatePlayerMarketService.profileLockOrder(low, high));
    assertEquals(List.of(low, high),
        HibernatePlayerMarketService.profileLockOrder(high, low));
  }

  @Test
  void buyerAndSellerAuditsHaveStableSeparateIdentitiesAndBoundPayloads() {
    UUID operationId = UUID.randomUUID();
    UUID listingId = UUID.randomUUID();

    assertEquals(HibernatePlayerMarketService.sellerAuditId(operationId),
        HibernatePlayerMarketService.sellerAuditId(operationId));
    assertNotEquals(operationId, HibernatePlayerMarketService.sellerAuditId(operationId));
    assertEquals("market:buy:" + listingId,
        HibernatePlayerMarketService.buyDescription(listingId));
    assertEquals("market:sell:" + listingId + ':' + operationId,
        HibernatePlayerMarketService.sellDescription(listingId, operationId));
  }
}
