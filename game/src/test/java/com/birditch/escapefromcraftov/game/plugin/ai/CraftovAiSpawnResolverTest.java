package com.birditch.escapefromcraftov.game.plugin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

final class CraftovAiSpawnResolverTest {

  @Test
  void unloadedColumnsAreSkippedWithoutReadingBlocksOrLoadingChunks() {
    AtomicInteger chunkChecks = new AtomicInteger();
    AtomicInteger blockReads = new AtomicInteger();
    World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
        new Class<?>[] {World.class}, (_, method, _) -> {
          if (method.getName().equals("isChunkLoaded")) {
            chunkChecks.incrementAndGet();
            return false;
          }
          if (method.getName().equals("getBlockAt")) {
            blockReads.incrementAndGet();
            throw new AssertionError("resolver read an unloaded chunk");
          }
          return defaultValue(method.getReturnType());
        });
    CraftovAiZone zone = new CraftovAiZone("guards", "scav", 1, 2, 30, 1,
        "scav", -128, 60, -128, 128, 80, 128);

    assertEquals(CraftovAiSpawnResolver.Status.UNLOADED,
        CraftovAiSpawnResolver.resolveResult(world, zone, 42).status());
    assertTrue(CraftovAiSpawnResolver.resolve(world, zone, 42).isEmpty());
    assertTrue(chunkChecks.get() > 0);
    assertEquals(0, blockReads.get());
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0F;
    if (type == double.class) return 0D;
    if (type == char.class) return '\0';
    return null;
  }
}
