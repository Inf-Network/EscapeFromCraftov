package net.infnetwork.snowball.craftov.weapon;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gunfire as each listener should hear it. The shooter gets a wide stereo first-person shot; everyone
 * else gets a near, mid or far version picked by distance, a muffled one through walls, arriving
 * after the speed-of-sound delay. Loudness follows our own curve instead of Minecraft's linear
 * roll-off: the sound is placed a few blocks from the listener in the shooter's direction, which
 * keeps the stereo direction while the volume is set exactly. The reports are recordings that carry
 * the outdoor range's own decay; under a roof the room answers every shot as it is fired (a tail
 * that starts with the shot), so a single shot is one sound and automatic fire fills the room.
 *
 * <p>Shots go out on 50 ms server ticks, and a sound sent in a tick plays when the tick's packets
 * arrive, so a gun whose interval does not fit the tick would sound uneven (the M4C's 75 ms comes
 * out as 100, 50, 100, 50). Those guns play one sample every few ticks holding several rounds at
 * their real interval instead; the bullets themselves still fire tick by tick.
 */
public final class GunAudio {

    /** Blocks per tick at 343 m/s. */
    private static final double SOUND_SPEED = 17.15;
    private static final double PROXY = 4.0;
    /** Sound events use Minecraft's default 16-block linear attenuation (see build.py). */
    private static final double ATTENUATION = 16.0;

    /** A sound on its way to a listener; {@code restart} stops what still plays under the same key first. */
    private record Delayed(long tick, UUID listener, Location source, String key, double gain, float pitch, boolean restart) {
    }

    /** Room sound under a roof, as a share of the shot's level (suppressed shots barely excite it). */
    private static final double ROOM = 0.8;
    private static final double ROOM_SUPPRESSED = 0.2;

    /** A multi-round sample: this many rounds, one every ticks / rounds ticks. */
    private record Rounds(int rounds, int ticks) {
    }

    /** Sound sets with multi-round samples for automatic fire (GROUPS in tools/art/gunsamples.py). */
    private static final Map<String, Rounds> SAMPLES = Map.of(
            "m4c", new Rounds(2, 3),
            "sv9", new Rounds(3, 4),
            "v57", new Rounds(3, 4));

    private final CraftovPlugin plugin;
    private final List<Delayed> queue = new ArrayList<>();
    private final Map<UUID, long[]> indoorCache = new HashMap<>();
    /** Per listener, which of the two room keys the next tail takes (see {@link #roomKey}). */
    private final Map<UUID, Integer> roomTurns = new HashMap<>();

    public GunAudio(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------ shots

    /**
     * Rounds in one automatic-fire sample of this gun: more than 1 only when the pack has a sample made
     * for exactly its rate (a server owner may have changed the rpm in items.yml).
     */
    public static int roundsPerSample(GunSpec gun) {
        Rounds sample = SAMPLES.get(gun.audio());
        return sample != null && gun.rpm() * sample.ticks() == sample.rounds() * 1200 ? sample.rounds() : 1;
    }

    /** One shot from {@code shooter}'s gun at {@code muzzle}. */
    public void shot(LivingEntity shooter, Location muzzle, GunSpec gun, boolean suppressed) {
        shot(shooter, muzzle, gun, suppressed, 1);
    }

    /**
     * A shot whose report holds {@code rounds} rounds (see {@link #roundsPerSample}); 0 for a round
     * that a sample started by an earlier shot already played: nothing more to hear.
     */
    public void shot(LivingEntity shooter, Location muzzle, GunSpec gun, boolean suppressed, int rounds) {
        if (rounds <= 0) {
            return;
        }
        World world = muzzle.getWorld();
        long now = Bukkit.getCurrentTick();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float pitch = 0.97f + rng.nextFloat() * 0.06f;
        String set = "craftov:gun." + gun.audio() + (rounds > 1 ? ".x" + rounds : "");
        double loudness = gun.soundVolume() / 5.0;
        // Under a roof the room answers in the same tick as the shot, for everyone who hears it.
        @Nullable String room = indoor(shooter, muzzle) ? "craftov:gun.tail." + tailFamily(gun) + ".in" : null;
        double roomShare = suppressed ? ROOM * ROOM_SUPPRESSED : ROOM;
        float roomPitch = 0.98f + rng.nextFloat() * 0.04f;
        if (shooter instanceof Player player) {
            player.playSound(player, set + (suppressed ? ".fp_s" : ".fp"), SoundCategory.PLAYERS, suppressed ? 0.8f : 1f, pitch);
            if (room != null) {
                schedule(now, player, muzzle, roomKey(player, room), roomShare * 0.8, roomPitch, true);
            }
        }
        // Private range plots share one world; nobody else should hear another plot's shots.
        boolean range = plugin.range().isRangeWorld(world);
        for (Player listener : world.getPlayers()) {
            if (listener == shooter || range) {
                continue;
            }
            Location ear = listener.getEyeLocation();
            double d = ear.distance(muzzle);
            double gain = gain(d, loudness, suppressed);
            if (gain < 0.012) {
                continue;
            }
            long arrives = now + Math.round(d / SOUND_SPEED);
            // Beyond 90 blocks the far report already carries its echoes.
            if (room != null && d < 90 && gain * roomShare >= 0.012) {
                schedule(arrives, listener, muzzle, roomKey(listener, room), gain * roomShare, roomPitch, true);
            }
            String band;
            if (suppressed) {
                band = ".near_s";
            } else {
                band = d < 26 ? ".near" : d < 90 ? ".mid" : ".far";
            }
            if (d > 2 && d < 90 && occluded(ear, muzzle)) {
                if (suppressed) {
                    gain *= 0.5;
                } else {
                    band = ".muff";
                    gain *= 0.8;
                }
            }
            schedule(arrives, listener, muzzle, set + band, gain, pitch, false);
        }
    }

    /** Level at distance d: loud guns carry far, suppressed ones die out within ~50 blocks. */
    private static double gain(double d, double loudness, boolean suppressed) {
        double ref = suppressed ? 6 : 30 * loudness;
        double level = suppressed ? 0.6 : Math.min(1.2, loudness);
        return level / (1 + Math.pow(d / Math.max(4, ref), 1.5));
    }

    private static String tailFamily(GunSpec gun) {
        return switch (gun.action()) {
            case "pistol", "smg", "pdw" -> "light";
            case "ar", "pistol_heavy" -> "rifle";
            case "bolt" -> "sniper";
            default -> "heavy";
        };
    }

    /**
     * The room key for this listener's next tail. A room tail lasts a second or more, so automatic
     * fire would stack a dozen of them: each listener alternates between two keys for the same sounds
     * and every tail stops the one two shots back (still ringing under the new shot, which hides the
     * cut), so no more than two play at once.
     */
    private String roomKey(Player listener, String room) {
        int turn = roomTurns.merge(listener.getUniqueId(), 1, (a, b) -> (a + b) % 2);
        return turn == 0 ? room : room + "2";
    }

    // ------------------------------------------------------------------ bullets

    /** Crack or whiz for players a bullet passed close to (but did not hit). */
    public void flyby(LivingEntity shooter, Location from, Location to, @Nullable Entity hit, GunSpec gun) {
        Vector a = from.toVector();
        Vector ab = to.toVector().subtract(a);
        double len2 = ab.lengthSquared();
        if (len2 < 9) {
            return;
        }
        String key = gun.supersonic() ? "craftov:bullet.crack" : "craftov:bullet.whiz";
        for (Player player : from.getWorld().getPlayers()) {
            if (player == shooter || player == hit || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Vector ear = player.getEyeLocation().toVector();
            double t = ear.clone().subtract(a).dot(ab) / len2;
            if (t <= 0.04 || t >= 1) {
                continue;
            }
            Vector closest = a.clone().add(ab.clone().multiply(t));
            double miss = closest.distance(ear);
            if (miss > 3.5) {
                continue;
            }
            Location at = closest.toLocation(from.getWorld());
            player.playSound(at, key, SoundCategory.PLAYERS, (float) Math.min(1.0, 1.15 - miss / 3.5),
                    0.92f + ThreadLocalRandom.current().nextFloat() * 0.16f);
            plugin.hud().suppressed(player, from);
        }
    }

    /** Bullet hitting a block: the surface decides the sound. */
    public void impact(Block block, Location at) {
        String material = material(block.getType());
        at.getWorld().playSound(at, "craftov:impact." + material, SoundCategory.BLOCKS, 0.85f,
                0.92f + ThreadLocalRandom.current().nextFloat() * 0.16f);
        if (ThreadLocalRandom.current().nextInt(9) == 0 && !material.equals("soft") && !material.equals("glass")) {
            at.getWorld().playSound(at, "craftov:impact.ricochet", SoundCategory.BLOCKS, 0.6f,
                    0.9f + ThreadLocalRandom.current().nextFloat() * 0.25f);
        }
    }

    /** Bullet hitting a body: plate, helmet or flesh. */
    public void bodyImpact(LivingEntity victim, boolean head, boolean armored, boolean penetrated) {
        Location at = victim.getLocation().add(0, head ? victim.getHeight() * 0.9 : victim.getHeight() * 0.6, 0);
        float pitch = 0.94f + ThreadLocalRandom.current().nextFloat() * 0.12f;
        if (plugin.drones().isDrone(victim)) {
            victim.getWorld().playSound(at, "craftov:impact.metal", SoundCategory.PLAYERS, 1f, pitch * 1.2f);
            return;
        }
        if (armored) {
            victim.getWorld().playSound(at, head ? "craftov:impact.helmet" : "craftov:impact.armor", SoundCategory.PLAYERS,
                    1f, penetrated ? pitch * 0.92f : pitch);
        }
        if (!armored || penetrated) {
            victim.getWorld().playSound(at, "craftov:impact.flesh", SoundCategory.PLAYERS, 0.9f, pitch);
        }
    }

    public static String material(Material type) {
        String name = type.name();
        if (name.contains("GLASS")) {
            return "glass";
        }
        if (Tag.PLANKS.isTagged(type) || Tag.LOGS.isTagged(type) || Tag.WOODEN_DOORS.isTagged(type)
                || Tag.WOODEN_TRAPDOORS.isTagged(type) || Tag.WOODEN_FENCES.isTagged(type) || Tag.WOODEN_SLABS.isTagged(type)
                || Tag.WOODEN_STAIRS.isTagged(type) || name.contains("BARREL") || name.contains("CHEST")
                || name.contains("BOOKSHELF") || name.contains("CRAFTING") || name.contains("LADDER") || name.contains("SCAFFOLD")
                || name.contains("COMPOSTER") || name.contains("LECTERN")) {
            return "wood";
        }
        if (name.contains("IRON") || name.contains("COPPER") || name.contains("CHAIN") || name.contains("ANVIL")
                || name.contains("CAULDRON") || name.contains("HOPPER") || name.contains("LANTERN") || name.contains("RAIL")
                || name.contains("GOLD") || name.contains("NETHERITE") || name.contains("LIGHTNING_ROD") || name.contains("BELL")) {
            return "metal";
        }
        if (Tag.DIRT.isTagged(type) || Tag.SAND.isTagged(type) || Tag.LEAVES.isTagged(type) || Tag.WOOL.isTagged(type)
                || Tag.WOOL_CARPETS.isTagged(type) || name.contains("GRAVEL") || name.contains("SNOW") || name.contains("CLAY")
                || name.contains("MUD") || name.contains("HAY") || name.contains("MOSS") || name.contains("SPONGE")) {
            return "soft";
        }
        return "concrete";
    }

    // ------------------------------------------------------------------ casings and foley

    /** A spent case hitting the floor next to the shooter. */
    public void casing(LivingEntity shooter, GunSpec gun, long delayTicks) {
        String action = gun.action();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!shooter.isValid()) {
                return;
            }
            Location feet = shooter.getLocation();
            Block below = feet.clone().subtract(0, 0.2, 0).getBlock();
            String kind = action.equals("pump") ? "shell" : material(below.getType()).equals("soft") ? "brass_soft" : "brass";
            feet.getWorld().playSound(feet.add(0, 0.1, 0), "craftov:casing." + kind, SoundCategory.PLAYERS, 0.45f,
                    0.92f + ThreadLocalRandom.current().nextFloat() * 0.16f);
        }, delayTicks);
    }

    /** Handling noise (magazine, bolt, pump) audible to everyone close by. */
    public void foley(LivingEntity source, GunSpec gun, String part, float volume) {
        source.getWorld().playSound(source.getLocation().add(0, 1.2, 0), "craftov:gun.foley." + gun.action() + "." + part,
                SoundCategory.PLAYERS, volume, 0.97f + ThreadLocalRandom.current().nextFloat() * 0.06f);
    }

    // ------------------------------------------------------------------ helpers

    private void schedule(long tick, Player listener, Location source, String key, double gain, float pitch, boolean restart) {
        if (tick <= Bukkit.getCurrentTick()) {
            emit(listener, source, key, gain, pitch, restart);
            return;
        }
        queue.add(new Delayed(tick, listener.getUniqueId(), source.clone(), key, gain, pitch, restart));
    }

    /** Plays key at gain for this listener only, keeping the direction of source. */
    private static void emit(Player listener, Location source, String key, double gain, float pitch, boolean restart) {
        Location ear = listener.getEyeLocation();
        if (ear.getWorld() != source.getWorld()) {
            return;
        }
        if (restart) {
            listener.stopSound(key, SoundCategory.PLAYERS);
        }
        Vector to = source.toVector().subtract(ear.toVector());
        double d = to.length();
        Location at;
        double attenuation;
        if (d <= PROXY) {
            at = source;
            attenuation = 1 - d / ATTENUATION;
        } else {
            at = ear.clone().add(to.multiply(PROXY / d));
            attenuation = 1 - PROXY / ATTENUATION;
        }
        float volume = (float) Math.min(1.0, gain / Math.max(0.05, attenuation));
        listener.playSound(at, key, SoundCategory.PLAYERS, volume, pitch);
    }

    private static boolean occluded(Location ear, Location source) {
        Vector dir = source.toVector().subtract(ear.toVector());
        double d = dir.length();
        if (d < 0.5) {
            return false;
        }
        var hit = ear.getWorld().rayTraceBlocks(ear, dir.multiply(1 / d), d - 0.6, FluidCollisionMode.NEVER, true);
        return hit != null && hit.getHitBlock() != null && hit.getHitBlock().getType().isOccluding();
    }

    /** Under a roof with walls on most sides. Cached briefly per shooter. */
    private boolean indoor(Entity shooter, Location at) {
        long now = Bukkit.getCurrentTick();
        long[] cached = indoorCache.get(shooter.getUniqueId());
        long key = ((long) at.getBlockX() << 40) ^ ((long) at.getBlockY() << 20) ^ at.getBlockZ();
        if (cached != null && cached[0] == key && now - cached[1] < 20) {
            return cached[2] == 1;
        }
        World world = at.getWorld();
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        boolean roof = false;
        for (int dy = 1; dy <= 14 && !roof; dy++) {
            roof = world.getBlockAt(x, y + dy, z).getType().isOccluding();
        }
        boolean inside = false;
        if (roof) {
            int walls = 0;
            for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int k = 1; k <= 12; k++) {
                    if (world.getBlockAt(x + dir[0] * k, y, z + dir[1] * k).getType().isOccluding()) {
                        walls++;
                        break;
                    }
                }
            }
            inside = walls >= 3;
        }
        indoorCache.put(shooter.getUniqueId(), new long[]{key, now, inside ? 1 : 0});
        return inside;
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        if (!queue.isEmpty()) {
            Iterator<Delayed> it = queue.iterator();
            while (it.hasNext()) {
                Delayed delayed = it.next();
                if (delayed.tick() > now) {
                    continue;
                }
                it.remove();
                Player listener = Bukkit.getPlayer(delayed.listener());
                if (listener != null) {
                    emit(listener, delayed.source(), delayed.key(), delayed.gain(), delayed.pitch(), delayed.restart());
                }
            }
        }
        if (now % 200 == 0) {
            indoorCache.entrySet().removeIf(e -> now - e.getValue()[1] > 200);
            roomTurns.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        }
    }
}
