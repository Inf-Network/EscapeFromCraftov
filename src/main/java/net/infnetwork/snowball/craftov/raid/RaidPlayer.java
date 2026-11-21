package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.item.ItemRecord;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Per-player raid session state. */
public final class RaidPlayer {

    public enum State { DEPLOYING, ALIVE, DOWNED, DEAD, EXTRACTED, DISCONNECTED, LEFT }

    public final UUID uuid;
    public final String name;
    public final int squad;
    public State state = State.DEPLOYING;
    public int kills;
    public int aiKills;
    public int downs;
    /** Value carried in and in the secure container at deploy (raid profit is measured against it). */
    public long startValue;
    /** Value of the gear carried in at deploy, secure container excluded (the deploy screen's 战备值). */
    public long gearValue;
    /** Instance ids of everything brought in at deploy, so other players' profit can leave it out. */
    public Set<String> gearUids = new HashSet<>();
    /**
     * Stacking items (ammo, meds, valuables) brought in at deploy, secure container and rounds in
     * guns included: item id -> count. Stacks have no instance id, so handing them over is caught
     * by count instead (RaidManager.othersGear).
     */
    public Map<String, Long> broughtIn = new HashMap<>();
    /** The same counts for what this player carried out, once they have extracted (null before). */
    public @Nullable Map<String, Long> carriedOut;
    public long startTick;
    public List<ItemRecord> secure = new ArrayList<>();
    public @Nullable String extracting;
    public int extractTicks;
    public long protectedUntil;
    public @Nullable Location lastLocation;
    public long disconnectedAt;
    public @Nullable Map<Integer, ItemRecord> disconnectedInventory;
    public @Nullable String escortContract;
    public int containersSearched;

    public RaidPlayer(UUID uuid, String name, int squad) {
        this.uuid = uuid;
        this.name = name;
        this.squad = squad;
    }

    public boolean active() {
        return state == State.ALIVE || state == State.DOWNED;
    }
}
