package com.birditch.escapefromcraftov.game.plugin.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import net.luckperms.api.util.Tristate;
import org.junit.jupiter.api.Test;

class LuckPermsPermissionProviderTest {

  @Test
  void explicitLuckPermsValuesRemainAuthoritative() {
    AtomicBoolean fallbackCalled = new AtomicBoolean();

    assertTrue(LuckPermsPermissionProvider.resolve(Tristate.TRUE, () -> {
      fallbackCalled.set(true);
      return false;
    }));
    assertFalse(LuckPermsPermissionProvider.resolve(Tristate.FALSE, () -> {
      fallbackCalled.set(true);
      return true;
    }));
    assertFalse(fallbackCalled.get());
  }

  @Test
  void undefinedFallsBackToRegisteredBukkitDefaults() {
    assertTrue(LuckPermsPermissionProvider.resolve(Tristate.UNDEFINED, () -> true));
    assertFalse(LuckPermsPermissionProvider.resolve(Tristate.UNDEFINED, () -> false));
  }
}
