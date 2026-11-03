package net.infnetwork.snowball.craftov.map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/** World-independent position stored in map files. */
public record Pos(double x, double y, double z, float yaw) {

    public Location at(World world) {
        return new Location(world, x, y, z, yaw, 0f);
    }

    public double distanceSq(Location location) {
        double dx = location.getX() - x;
        double dy = location.getY() - y;
        double dz = location.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static Pos read(ConfigurationSection s) {
        return new Pos(s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw", 0));
    }

    public static Pos read(Map<?, ?> m) {
        return new Pos(num(m.get("x")), num(m.get("y")), num(m.get("z")), (float) num(m.get("yaw")));
    }

    public static Pos of(Location location) {
        return new Pos(round(location.getX()), round(location.getY()), round(location.getZ()), Math.round(location.getYaw()));
    }

    static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : o == null ? 0 : Double.parseDouble(o.toString());
    }

    private static double round(double v) {
        return Math.round(v * 2.0) / 2.0;
    }

    public Map<String, Object> write() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        if (yaw != 0) {
            m.put("yaw", (double) yaw);
        }
        return m;
    }
}
