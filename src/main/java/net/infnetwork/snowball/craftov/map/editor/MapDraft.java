package net.infnetwork.snowball.craftov.map.editor;

import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.map.Pos;

import java.util.ArrayList;
import java.util.List;

/** Mutable working copy of a map while it is being edited. */
final class MapDraft {
    String id;
    String name;
    String description = "";
    String template;
    int raidMinutes = 25;
    String time = "day";
    Pos camera;
    double minX = Double.NaN, minZ = Double.NaN, maxX = Double.NaN, maxZ = Double.NaN;
    final List<MapDef.SpawnDef> spawns = new ArrayList<>();
    final List<MapDef.ExtractDef> extracts = new ArrayList<>();
    final List<MapDef.ContainerDef> containers = new ArrayList<>();
    final List<MapDef.AiDef> ai = new ArrayList<>();
    final List<MapDef.BossDef> bosses = new ArrayList<>();
    final List<MapDef.DoorDef> doors = new ArrayList<>();
    final List<MapDef.SwitchDef> switches = new ArrayList<>();

    static MapDraft of(MapDef def) {
        MapDraft d = new MapDraft();
        d.id = def.id();
        d.name = def.name();
        d.description = def.description();
        d.template = def.template();
        d.raidMinutes = Math.max(5, def.raidSeconds() / 60);
        d.time = def.timeOfDay();
        d.camera = def.camera();
        if (def.minX() > -9000) {
            d.minX = def.minX();
            d.minZ = def.minZ();
            d.maxX = def.maxX();
            d.maxZ = def.maxZ();
        }
        d.spawns.addAll(def.spawns());
        d.extracts.addAll(def.extracts());
        d.containers.addAll(def.containers());
        d.ai.addAll(def.ai());
        d.bosses.addAll(def.bosses());
        d.doors.addAll(def.doors());
        d.switches.addAll(def.switches());
        return d;
    }

    List<Pos> allPositions() {
        List<Pos> out = new ArrayList<>();
        spawns.forEach(s -> out.add(s.pos()));
        extracts.forEach(e -> out.add(e.pos()));
        containers.forEach(c -> out.add(c.pos()));
        ai.forEach(a -> out.add(a.pos()));
        bosses.forEach(b -> out.add(b.pos()));
        doors.forEach(d -> out.add(d.pos()));
        switches.forEach(s -> out.add(s.pos()));
        return out;
    }

    MapDef build() {
        double x0 = minX, z0 = minZ, x1 = maxX, z1 = maxZ;
        if (Double.isNaN(x0)) {
            x0 = Double.MAX_VALUE;
            z0 = Double.MAX_VALUE;
            x1 = -Double.MAX_VALUE;
            z1 = -Double.MAX_VALUE;
            for (Pos p : allPositions()) {
                x0 = Math.min(x0, p.x() - 32);
                z0 = Math.min(z0, p.z() - 32);
                x1 = Math.max(x1, p.x() + 32);
                z1 = Math.max(z1, p.z() + 32);
            }
            if (x0 == Double.MAX_VALUE) {
                x0 = -64;
                z0 = -64;
                x1 = 64;
                z1 = 64;
            }
        }
        Pos cam = camera != null ? camera : new Pos((x0 + x1) / 2, 120, (z0 + z1) / 2, 0);
        return new MapDef(id, name, description, template, raidMinutes * 60, cam, List.copyOf(spawns), List.copyOf(extracts),
                List.copyOf(containers), List.copyOf(ai), List.copyOf(bosses), List.copyOf(doors), List.copyOf(switches),
                x0, z0, x1, z1, time);
    }
}
