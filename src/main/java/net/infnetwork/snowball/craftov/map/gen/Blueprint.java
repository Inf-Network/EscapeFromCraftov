package net.infnetwork.snowball.craftov.map.gen;

import net.infnetwork.snowball.craftov.map.ExtractType;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.map.Pos;

import java.util.ArrayList;
import java.util.List;

/** Collects gameplay markers while a map is generated. */
public final class Blueprint {
    public final List<MapDef.SpawnDef> spawns = new ArrayList<>();
    public final List<MapDef.ExtractDef> extracts = new ArrayList<>();
    public final List<MapDef.ContainerDef> containers = new ArrayList<>();
    public final List<MapDef.AiDef> ai = new ArrayList<>();
    public final List<MapDef.BossDef> bosses = new ArrayList<>();
    public final List<MapDef.DoorDef> doors = new ArrayList<>();
    public final List<MapDef.SwitchDef> switches = new ArrayList<>();

    public void container(String type, double x, double y, double z, float yaw, int tier) {
        containers.add(new MapDef.ContainerDef(type, new Pos(x + 0.5, y, z + 0.5, yaw), tier));
    }

    public void ai(double x, double y, double z, int count, double radius, String kind) {
        ai.add(new MapDef.AiDef(new Pos(x + 0.5, y, z + 0.5, 0), count, radius, kind));
    }

    public void spawn(int group, double x, double y, double z, float yaw) {
        spawns.add(new MapDef.SpawnDef(group, new Pos(x + 0.5, y, z + 0.5, yaw)));
    }

    public void extract(String id, String name, double x, double y, double z, double radius, ExtractType type,
                        int seconds, long cost, int opens, int closes, String switchId, int capacity, double chance) {
        extracts.add(new MapDef.ExtractDef(id, name, new Pos(x + 0.5, y, z + 0.5, 0), radius, type, seconds, cost, opens,
                closes, switchId, capacity, chance));
    }
}
