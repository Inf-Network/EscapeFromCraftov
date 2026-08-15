package com.birditch.escapefromcraftov.bukkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrMenuBridgeCacheResetTest {

  @Test
  void updatesArgumentsThenResetsEveryMenuIconAndRefreshesOnlyActiveIcons() throws Exception {
    FakeIcon first = new FakeIcon(17);
    FakeIcon second = new FakeIcon(17);
    FakeSession session = new FakeSession(17, Set.of(first));
    FakeMenu menu = new FakeMenu(new LinkedHashSet<>(Set.of(first, second)));

    TrMenuBridge.refreshIconCaches(
        menu, session, FakeSession.class, FakeIcon.class, new String[] {"new"});

    assertEquals("fresh:new", first.cachedValue(session.id()));
    assertNull(second.cachedValue(session.id()));
    assertEquals(1, first.resetCount());
    assertEquals(1, second.resetCount());
    assertEquals(1, first.refreshCount());
    assertEquals(0, second.refreshCount());
    assertArrayEquals(new String[] {"new"}, session.arguments());
  }

  @Test
  void rejectsAnUnexpectedSessionTypeBeforeTouchingIcons() {
    FakeSession session = new FakeSession(21, Set.of());
    FakeIcon icon = new FakeIcon(session.id());
    FakeMenu menu = new FakeMenu(Set.of(icon));

    assertThrows(IllegalArgumentException.class,
        () -> TrMenuBridge.refreshIconCaches(
            menu, session, String.class, FakeIcon.class, new String[] {"new"}));
    assertEquals(0, icon.resetCount());
    assertArrayEquals(new String[] {"old"}, session.arguments());
  }

  @Test
  void validatesEveryIconTypeBeforeResettingAnyCache() {
    FakeIcon valid = new FakeIcon(23);
    FakeSession session = new FakeSession(23, Set.of(valid));
    FakeMenu menu = new FakeMenu(new LinkedHashSet<>(Set.of(valid, "wrong")));

    assertThrows(IllegalStateException.class,
        () -> TrMenuBridge.refreshIconCaches(
            menu, session, FakeSession.class, FakeIcon.class, new String[] {"new"}));
    assertEquals(0, valid.resetCount());
    assertArrayEquals(new String[] {"old"}, session.arguments());
  }

  @Test
  void validatesEveryActiveIconTypeBeforeUpdatingArgumentsOrResetting() {
    FakeIcon valid = new FakeIcon(24);
    FakeSession session = new FakeSession(24, new LinkedHashSet<>(Set.of(valid, "wrong")));
    FakeMenu menu = new FakeMenu(Set.of(valid));

    assertThrows(IllegalStateException.class,
        () -> TrMenuBridge.refreshIconCaches(
            menu, session, FakeSession.class, FakeIcon.class, new String[] {"new"}));
    assertEquals(0, valid.resetCount());
    assertEquals(0, valid.refreshCount());
    assertArrayEquals(new String[] {"old"}, session.arguments());
  }

  @Test
  void rejectsAMenuWithoutASetOfIcons() {
    FakeSession session = new FakeSession(25, Set.of());

    assertThrows(IllegalStateException.class,
        () -> TrMenuBridge.refreshIconCaches(new InvalidMenu(), session,
            FakeSession.class, FakeIcon.class, new String[] {"new"}));
    assertArrayEquals(new String[] {"old"}, session.arguments());
  }

  @Test
  void resolvesTheCompleteRefreshApiBeforeMutatingTheSession() {
    ResetOnlyIcon icon = new ResetOnlyIcon();
    FakeSession session = new FakeSession(26, Set.of(icon));
    FakeMenu menu = new FakeMenu(Set.of(icon));

    assertThrows(NoSuchMethodException.class,
        () -> TrMenuBridge.refreshIconCaches(menu, session,
            FakeSession.class, ResetOnlyIcon.class, new String[] {"new"}));
    assertEquals(0, icon.resetCount);
    assertArrayEquals(new String[] {"old"}, session.arguments());
  }

  public static final class FakeSession {
    private final int id;
    private final Set<?> activeIcons;
    private String[] arguments = {"old"};

    private FakeSession(int id, Set<?> activeIcons) {
      this.id = id;
      this.activeIcons = activeIcons;
    }

    public int id() {
      return id;
    }

    public Set<?> getActiveIcons() {
      return activeIcons;
    }

    public void setArguments(String[] arguments) {
      this.arguments = arguments.clone();
    }

    private String argument(int index) {
      return arguments[index];
    }

    private String[] arguments() {
      return arguments.clone();
    }
  }

  public static final class FakeMenu {
    private final Set<?> icons;

    private FakeMenu(Set<?> icons) {
      this.icons = icons;
    }

    public Set<?> getIcons() {
      return icons;
    }
  }

  public static final class InvalidMenu {
    public Object getIcons() {
      return new Object();
    }
  }

  public static final class FakeIcon {
    private final Map<Integer, String> cache = new LinkedHashMap<>();
    private int resetCount;
    private int refreshCount;

    private FakeIcon(int sessionId) {
      cache.put(sessionId, "stale");
    }

    public void onReset(FakeSession session) {
      cache.remove(session.id());
      resetCount++;
    }

    public void onRefresh(FakeSession session) {
      cache.put(session.id(), "fresh:" + session.argument(0));
      refreshCount++;
    }

    private String cachedValue(int sessionId) {
      return cache.get(sessionId);
    }

    private int resetCount() {
      return resetCount;
    }

    private int refreshCount() {
      return refreshCount;
    }
  }

  public static final class ResetOnlyIcon {
    private int resetCount;

    public void onReset(FakeSession session) {
      resetCount++;
    }
  }
}
