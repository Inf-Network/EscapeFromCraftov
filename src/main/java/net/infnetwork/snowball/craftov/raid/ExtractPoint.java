package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.map.MapDef;
import org.bukkit.Location;

public final class ExtractPoint {
    public final MapDef.ExtractDef def;
    public final Location center;
    public final boolean available;
    public int used;
    public boolean announcedOpen;

    public ExtractPoint(MapDef.ExtractDef def, Location center, boolean available) {
        this.def = def;
        this.center = center;
        this.available = available;
    }
}
