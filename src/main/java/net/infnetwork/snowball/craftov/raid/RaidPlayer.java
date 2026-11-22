package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
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
    /** Queued for ranked: this player wins or loses rank points. Ranked and casual players share raids. */
    public final boolean ranked;
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
    /** Entry fee and insurance premium paid on deploy, handed back if the raid is called off before landing. */
    public long fees;
    public long startTick;
    public List<ItemRecord> secure = new ArrayList<>();
    public @Nullable String extracting;
    public int extractTicks;
    /** Until this tick the player takes no damage and cannot fire (landing, rejoining). */
    public long protectedUntil;
    /**
     * Soldiers leave a freshly landed player alone until this tick (ai.insertion-grace-ticks), or
     * until the player fires or hurts someone. Only landing sets it; rejoining does not.
     */
    public long aiGraceUntil;
    /**
     * Tick of the last hit this player took from, or dealt to, another player or a soldier (-1:
     * none yet). Leaving the game within raid.combat-logout-seconds of it counts as death.
     */
    public long lastCombatTick = -1;
    /**
     * The last hit another player or a soldier dealt this player. Kept apart from DamageService's
     * last hit, which bleeding overwrites every 1.5 s, so leaving mid-fight credits the attacker.
     */
    public DamageService.@Nullable LastHit lastHostile;
    public @Nullable Location lastLocation;
    public long disconnectedAt;
    /**
     * From deployment until the squad's entry camera ends. A player who drops out and comes back
     * in this window goes back on the camera and lands with the squad.
     */
    public boolean inFlight;
    /**
     * What a disconnected player carried, by slot. Stacks rather than records: a rejoin gets them back
     * exactly as they were (insurance mark, fire mode, armory items).
     */
    public @Nullable Map<Integer, ItemStack> disconnectedInventory;
    public @Nullable String escortContract;
    public int containersSearched;

    public RaidPlayer(UUID uuid, String name, int squad, boolean ranked) {
        this.uuid = uuid;
        this.name = name;
        this.squad = squad;
        this.ranked = ranked;
    }

    public boolean active() {
        return state == State.ALIVE || state == State.DOWNED;
    }
}
