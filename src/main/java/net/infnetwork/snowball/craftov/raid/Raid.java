package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.map.MapDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One running raid instance in its own world copy. */
public final class Raid {

    public enum State { LOADING, RUNNING, ENDING, CLOSED }

    public final String id;
    public final MapDef map;
    public final Tier tier;
    public final RaidMode mode;
    public final boolean ranked;
    public final World world;
    public State state = State.LOADING;
    public long startTick;
    public final Map<UUID, RaidPlayer> players = new LinkedHashMap<>();
    public final List<List<UUID>> squads = new ArrayList<>();
    public final Map<Integer, Integer> squadSpawnGroup = new HashMap<>();
    public final Map<String, LootContainer> containers = new HashMap<>();
    public final List<ExtractPoint> extracts = new ArrayList<>();
    public final Map<String, Long> switchUntil = new HashMap<>();
    public final Map<String, String> lockedDoors = new HashMap<>();
    public final Map<String, MapDef.SwitchDef> switchBlocks = new HashMap<>();
    public boolean bossSpawned;
    public boolean warned5;
    public boolean warned1;
    public long endTick;

    public Raid(String id, MapDef map, Tier tier, RaidMode mode, boolean ranked, World world) {
        this.id = id;
        this.map = map;
        this.tier = tier;
        this.mode = mode;
        this.ranked = ranked;
        this.world = world;
    }

    public int elapsedSeconds() {
        if (startTick <= 0) {
            return 0;
        }
        return (int) ((Bukkit.getCurrentTick() - startTick) / 20);
    }

    public int remainingSeconds() {
        return Math.max(0, map.raidSeconds() - elapsedSeconds());
    }

    public @Nullable RaidPlayer player(UUID uuid) {
        return players.get(uuid);
    }

    public List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (RaidPlayer rp : players.values()) {
            Player p = Bukkit.getPlayer(rp.uuid);
            if (p != null && p.getWorld() == world) {
                out.add(p);
            }
        }
        return out;
    }

    public List<RaidPlayer> squadOf(RaidPlayer rp) {
        List<RaidPlayer> out = new ArrayList<>();
        for (RaidPlayer other : players.values()) {
            if (other.squad == rp.squad) {
                out.add(other);
            }
        }
        return out;
    }

    public boolean switchActive(String id) {
        return switchUntil.getOrDefault(id, 0L) > Bukkit.getCurrentTick();
    }

    public static String blockKey(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    public long activeCount() {
        return players.values().stream().filter(p -> p.active() || p.state == RaidPlayer.State.DEPLOYING
                || p.state == RaidPlayer.State.DISCONNECTED).count();
    }
}
