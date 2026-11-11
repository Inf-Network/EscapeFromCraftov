package net.infnetwork.snowball.craftov.weapon;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Camera kick. Uses the server's relative rotation packet when available so the kick adds to the
 * player's current aim instead of snapping it; falls back to an absolute rotation.
 */
public final class Recoil {

    private static Method handle;
    private static Method forceRotation;
    private static boolean unavailable;

    private Recoil() {
    }

    public static void kick(Player player, float yaw, float pitch) {
        if (!unavailable) {
            try {
                if (forceRotation == null) {
                    handle = player.getClass().getMethod("getHandle");
                    Object nms = handle.invoke(player);
                    forceRotation = findRotation(nms.getClass());
                }
                Object nms = handle.invoke(player);
                forceRotation.invoke(nms, yaw, true, pitch, true);
                return;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                unavailable = true;
            }
        }
        float newPitch = Math.max(-90f, Math.min(90f, player.getPitch() + pitch));
        player.setRotation(player.getYaw() + yaw, newPitch);
    }

    private static Method findRotation(Class<?> type) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("forceSetRotation", float.class, boolean.class, float.class, boolean.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchMethodException("forceSetRotation");
    }
}
