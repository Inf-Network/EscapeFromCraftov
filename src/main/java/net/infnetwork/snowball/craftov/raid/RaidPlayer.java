package net.infnetwork.snowball.craftov.raid;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.luck.LuckSession;
import org.bukkit.Location;
import org.bukkit.entity.Mannequin;
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
    /** Rounds fired, and of those the ones fired standing still or stopped in time (moving cost no accuracy). */
    public int shots;
    public int stillShots;
    /** Value carried in and in the secure container at deploy (raid profit is measured against it). */
    public long startValue;
    /**
     * Gear value carried in at deploy (the deploy screen's 战备值): secure container and melee weapon
     * excluded. Extraction experience and the house bounty go by it.
     */
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
    /**
     * Stacking items this player has used up in the raid (rounds fired, grenades thrown, meds
     * used): item id -> count. No longer in hand, yet not handed to anyone (RaidManager.leftWith).
     */
    public Map<String, Long> used = new HashMap<>();
    /** Entry fee and insurance premium paid on deploy, handed back if the raid is called off before landing. */
    public long fees;
    /**
     * The record saved with the deploy (Profile.deployment; the same object while the player is
     * online). A server stop voids the raid with it, and describes the change from it should the
     * profile not be readable then.
     */
    public net.infnetwork.snowball.craftov.profile.Profile.@Nullable Deployment deployment;
    public long startTick;
    public List<ItemRecord> secure = new ArrayList<>();
    public @Nullable String extracting;
    public int extractTicks;
    /** Until this tick the player takes no damage and cannot shoot, stab or throw (landing, rejoining). */
    public long protectedUntil;
    /**
     * Soldiers leave a freshly landed player alone until this tick (ai.insertion-grace-ticks), or
     * until the player fires or hurts someone. Only landing sets it; rejoining does not.
     */
    public long aiGraceUntil;
    /**
     * Tick of the last hit this player (or their stand-in) took from, or dealt to, another player
     * or a soldier (-1: none yet). Leaving the game within raid.combat-logout-seconds of it counts
     * as death.
     */
    public long lastCombatTick = -1;
    /**
     * The last hit another player or a soldier dealt this player or their stand-in. Kept apart
     * from DamageService's last hit, which bleeding overwrites every 1.5 s, so leaving mid-fight
     * credits the attacker.
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
    /** A disconnected player's skin, taken when they left, for their stand-in and their body. */
    public @Nullable ResolvableProfile look;
    /**
     * What stays of a disconnected player where they stood (RaidManager.spawnStandIn): it can be
     * shot, and a rejoin puts the player in its place. Null during the entry camera and once settled.
     */
    public @Nullable Mannequin standIn;
    /**
     * A disconnected player's health when they left. The stand-in carries it on from there; this
     * is what counts whenever there is none.
     */
    public double offlineHealth;
    /**
     * A disconnected player's operator armour wear multiplier (OperatorService) as they left, for
     * bullets their stand-in takes.
     */
    public double armorWear = 1.0;
    public @Nullable String escortContract;
    public int containersSearched;
    /** Personal luck in this raid: containers searched, hand-outs (LuckService). */
    public final LuckSession luck = new LuckSession();
    /** Squadmates this player revived (the luck behaviour score counts it as cooperation). */
    public int revives;
    /** Deployed as an escort on someone else's contract (not as its client). */
    public boolean escorting;

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
