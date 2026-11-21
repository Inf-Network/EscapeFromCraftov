package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.item.ItemRecord;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
