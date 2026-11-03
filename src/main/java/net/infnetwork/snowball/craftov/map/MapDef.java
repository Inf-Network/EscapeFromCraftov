package net.infnetwork.snowball.craftov.map;

import java.util.List;

/** Immutable description of a raid map. Coordinates are in the template world. */
public record MapDef(
        String id,
        String name,
        String description,
        String template,
        int raidSeconds,
        Pos camera,
        List<SpawnDef> spawns,
        List<ExtractDef> extracts,
        List<ContainerDef> containers,
        List<AiDef> ai,
        List<BossDef> bosses,
        List<DoorDef> doors,
        List<SwitchDef> switches,
        double minX, double minZ, double maxX, double maxZ,
        String timeOfDay
) {
    public record SpawnDef(int group, Pos pos) {
    }

    /**
     * @param opensAt  seconds after raid start (TIMED)
     * @param closesAt seconds after raid start, 0 = never (TIMED)
     * @param chance   probability the extract is available in a given raid
     */
    public record ExtractDef(String id, String name, Pos pos, double radius, ExtractType type, int seconds,
                             long cost, int opensAt, int closesAt, String switchId, int capacity, double chance) {
    }

    /** @param tier loot quality boost 0..2 for hot zones */
    public record ContainerDef(String type, Pos pos, int tier) {
    }

    public record AiDef(Pos pos, int count, double radius, String kind) {
    }

    public record BossDef(String id, String name, Pos pos, int guards, double chance, int delaySeconds) {
    }

    public record DoorDef(String lock, Pos pos) {
    }

    public record SwitchDef(String id, String name, Pos pos, int activeSeconds) {
    }

    public boolean inside(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
