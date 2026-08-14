package com.birditch.escapefromcraftov.game.plugin.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class RaidEntryStateTest {

  @Test
  void resetsEditorAndTransientCombatStateForAConfirmedRaid() {
    RecordingPlayer recording = new RecordingPlayer(400);

    RaidEntryState.reset(recording.player(), 36.0D);

    assertEquals(GameMode.SURVIVAL, recording.value("setGameMode"));
    assertEquals(false, recording.value("setFlying"));
    assertEquals(false, recording.value("setAllowFlight"));
    assertEquals(true, recording.effectsRead);
    assertEquals(36.0D, recording.value("setHealth"));
    assertEquals(20, recording.value("setFoodLevel"));
    assertEquals(5.0F, recording.value("setSaturation"));
    assertEquals(0.0F, recording.value("setExhaustion"));
    assertEquals(0.0D, recording.value("setAbsorptionAmount"));
    assertEquals(0, recording.value("setFireTicks"));
    assertEquals(0, recording.value("setFreezeTicks"));
    assertEquals(0.0F, recording.value("setFallDistance"));
    assertEquals(0, recording.value("setNoDamageTicks"));
    assertEquals(400, recording.value("setRemainingAir"));
    assertEquals(false, recording.value("setGliding"));
    assertEquals(false, recording.value("setSprinting"));
    assertEquals(false, recording.value("setSneaking"));
    assertEquals(new Vector(), recording.value("setVelocity"));
    assertFalse(recording.values.isEmpty());
  }

  private static final class RecordingPlayer {

    private final Map<String, Object> values = new HashMap<>();
    private final Player player;
    private boolean effectsRead;

    private RecordingPlayer(int maximumAir) {
      player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
          new Class<?>[] {Player.class}, (_, method, arguments) -> {
            return switch (method.getName()) {
              case "getActivePotionEffects" -> {
                effectsRead = true;
                yield Set.of();
              }
              case "getMaximumAir" -> maximumAir;
              case "toString" -> "RecordingPlayer";
              default -> {
                if (method.getName().startsWith("set") && arguments != null
                    && arguments.length > 0) {
                  values.put(method.getName(), arguments[0]);
                }
                yield defaultValue(method.getReturnType());
              }
            };
          });
    }

    private Player player() {
      return player;
    }

    private Object value(String method) {
      return values.get(method);
    }

    private static Object defaultValue(Class<?> type) {
      if (!type.isPrimitive() || type == void.class) return null;
      if (type == boolean.class) return false;
      if (type == byte.class) return (byte) 0;
      if (type == short.class) return (short) 0;
      if (type == int.class) return 0;
      if (type == long.class) return 0L;
      if (type == float.class) return 0.0F;
      if (type == double.class) return 0.0D;
      if (type == char.class) return '\0';
      throw new AssertionError("Unhandled primitive " + type);
    }
  }
}
