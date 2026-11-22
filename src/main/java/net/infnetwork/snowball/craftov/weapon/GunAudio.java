package net.infnetwork.snowball.craftov.weapon;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gunfire as each listener should hear it. The shooter gets a wide stereo first-person shot, as loud
 * as the gun is (a pistol well under a sniper rifle); everyone else gets the near, mid and far
 * versions crossfaded by distance (both of a pair play across a band edge, their weights adding up
 * to one), and behind a wall a muffled version with a little of the open one under it, arriving
 * after the speed-of-sound delay. Loudness follows our own curve instead of Minecraft's linear
 * roll-off: the sound is placed a few blocks from the listener in the shooter's direction, which
 * keeps the stereo direction while the volume is set exactly. The reports are recordings that carry
 * the outdoor range's own decay; under a roof the room answers every shot as it is fired (a tail
 * that starts with the shot), so a single shot is one sound and automatic fire fills the room.
 *
 * <p>Each event has several recorded variants. The client picks one from the seed in the sound
 * packet, so the server deals them like cards per shooter (every variant once before any repeats,
 * never the same twice in a row) and sends the seed that picks the dealt one; each shot also varies
 * by up to 1 dB.
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
    private record Delayed(long tick, UUID listener, Location source, String key, double gain, float pitch, long seed,
                           boolean restart) {
    }

    /** The variants a shooter has not heard yet, in the order they come. */
    private static final class Deck {
        private final int[] order;
        private int next;
        private int last = -1;
        private long used;

        Deck(int size) {
            order = new int[size];
            next = size;
        }

        int draw(Random rng, long now) {
            used = now;
            if (next >= order.length) {
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                for (int i = order.length - 1; i > 0; i--) {
                    int j = rng.nextInt(i + 1);
                    int t = order[i];
                    order[i] = order[j];
                    order[j] = t;
                }
                // A new round starts with something other than what the last one ended on.
                if (order.length > 1 && order[0] == last) {
                    int j = 1 + rng.nextInt(order.length - 1);
                    order[0] = order[j];
                    order[j] = last;
                }
                next = 0;
            }
            last = order[next++];
            return last;
        }
    }

    /** Room sound under a roof, as a share of the shot's level (suppressed shots barely excite it). */
    private static final double ROOM = 0.8;
    private static final double ROOM_SUPPRESSED = 0.2;
    /** Quietest sound worth a packet. */
    private static final double AUDIBLE = 0.012;
    /** items.yml volumes that map to the ends of weapons.audio.shooter-volume. */
    private static final double QUIETEST_GUN = 3.5;
    private static final double LOUDEST_GUN = 7.0;
    private static final String[] OPEN = {".near", ".mid", ".far"};
    private static final String[] SUPPRESSED = {".near_s", ".mid_s"};

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
    /** Variants of each sound event in the pack (sounds.json in this jar), by full key. */
    private final Map<String, Integer> variants;
    /** Per shooter and sound set, the variants still to come. */
    private final Map<String, Deck> decks = new HashMap<>();
    /** Packet seeds that make the client pick variant i of n: SEEDS.get(n)[i]. */
    private static final Map<Integer, long[]> SEEDS = new HashMap<>();

    public GunAudio(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.variants = readVariants(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Variant counts from the pack's sounds.json, which the plugin jar carries. */
    private static Map<String, Integer> readVariants(CraftovPlugin plugin) {
        Map<String, Integer> out = new HashMap<>();
        try (InputStream in = plugin.getResource("pack/craftov/resourcepack/assets/craftov/sounds.json")) {
            if (in == null) {
                plugin.getLogger().warning("No sounds.json in the jar: gun sound variants are left to the client");
                return out;
            }
            var root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> event : root.entrySet()) {
                var sounds = event.getValue().getAsJsonObject().getAsJsonArray("sounds");
                out.put("craftov:" + event.getKey(), sounds == null ? 1 : sounds.size());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read sounds.json from the jar: " + e.getMessage());
        }
        return out;
    }

    /**
     * The packet seed for variant {@code index} of a sound with {@code count} variants. The vanilla
     * client seeds a java.util.Random-compatible generator with it and picks the variant with
     * nextInt(total weight); every variant of ours weighs 1.
     */
    static long seedFor(int count, int index) {
        if (count <= 1) {
            return ThreadLocalRandom.current().nextLong();
        }
        long[] seeds = SEEDS.computeIfAbsent(count, n -> {
            long[] found = new long[n];
            java.util.Arrays.fill(found, -1);
            int missing = n;
            for (long seed = 0; missing > 0; seed++) {
                int i = new Random(seed).nextInt(n);
                if (found[i] < 0) {
                    found[i] = seed;
                    missing--;
                }
            }
            return found;
        });
        return seeds[Math.floorMod(index, count)];
    }

    /** The seed that plays the dealt variant of {@code key}, whatever its count. */
    private long seed(String key, int pick) {
        int count = variants.getOrDefault(key, 1);
        return seedFor(count, pick % Math.max(1, count));
    }

    /** The next variant of {@code set} for this shooter, out of as many as its near report has. */
    private int deal(Entity shooter, String set, Random rng, long now) {
        String key = shooter.getUniqueId() + set;
        Deck deck = decks.get(key);
        int size = Math.max(1, variants.getOrDefault(set, 1));
        if (deck == null || deck.order.length != size) {
            deck = new Deck(size);
            decks.put(key, deck);
        }
        return deck.draw(rng, now);
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
        var config = plugin.getConfig();
        // One variant for this shot in every band, and a level that varies a little from shot to shot.
        int pick = deal(shooter, set + (suppressed ? ".near_s" : ".near"), rng, now);
        double jitterDb = config.getDouble("weapons.audio.level-jitter-db", 1.0);
        double jitter = Math.pow(10, (rng.nextDouble() * 2 - 1) * jitterDb / 20);
        // Under a roof the room answers in the same tick as the shot, for everyone who hears it.
        @Nullable String room = indoor(shooter, muzzle) ? "craftov:gun.tail." + tailFamily(gun) + ".in" : null;
        double roomShare = suppressed ? ROOM * ROOM_SUPPRESSED : ROOM;
        float roomPitch = 0.98f + rng.nextFloat() * 0.04f;
        if (shooter instanceof Player player) {
            // The suppressed recordings are already that much quieter: the level is the gun's own.
            double own = shooterVolume(gun, pair(config, "weapons.audio.shooter-volume", 0.55, 1.0)) * jitter;
            String fp = set + (suppressed ? ".fp_s" : ".fp");
            player.playSound(player, fp, SoundCategory.PLAYERS, (float) Math.min(1.0, own), pitch, seed(fp, pick));
            if (room != null) {
                schedule(now, player, muzzle, roomKey(player, room), own * roomShare * 0.8, roomPitch, pick, true);
            }
        }
        double[] nearMid = pair(config, "weapons.audio.near-mid", 20, 32);
        double[] midFar = pair(config, "weapons.audio.mid-far", 70, 110);
        double muffled = config.getDouble("weapons.audio.muffled", 0.75);
        double muffledDry = config.getDouble("weapons.audio.muffled-dry", 0.2);
        String[] bands = suppressed ? SUPPRESSED : OPEN;
        // Private range plots share one world; nobody else should hear another plot's shots.
        boolean range = plugin.range().isRangeWorld(world);
        for (Player listener : world.getPlayers()) {
            if (listener == shooter || range) {
                continue;
            }
            Location ear = listener.getEyeLocation();
            double d = ear.distance(muzzle);
            double gain = gain(d, loudness, suppressed) * jitter;
            if (gain < AUDIBLE) {
                continue;
            }
            long arrives = now + Math.round(d / SOUND_SPEED);
            // Beyond 90 blocks the far report already carries its echoes.
            if (room != null && d < 90 && gain * roomShare >= AUDIBLE) {
                schedule(arrives, listener, muzzle, roomKey(listener, room), gain * roomShare, roomPitch, pick, true);
            }
            double open = 1.0;
            if (d > 2 && d < 90 && occluded(ear, muzzle)) {
                // Through a wall: dull, but with a little of the open report under it so it is still
                // a gunshot. Suppressed reports are dull already and only lose level.
                open = suppressed ? 0.4 : muffledDry;
                if (!suppressed) {
                    schedule(arrives, listener, muzzle, set + ".muff", gain * muffled, pitch, pick, false);
                }
            }
            double[] weights = bandWeights(d, nearMid, midFar);
            for (int i = 0; i < bands.length; i++) {
                // Suppressed reports have no far band: mid_s takes the far weight too.
                double w = suppressed && i == bands.length - 1 ? 1 - weights[0] : weights[i];
                // Equal power: two different recordings played together add up in energy.
                double level = gain * open * Math.sqrt(w);
                if (w > 0 && level >= AUDIBLE / 2) {
                    schedule(arrives, listener, muzzle, set + bands[i], level, pitch, pick, false);
                }
            }
        }
    }

    /**
     * Share of the near, mid and far reports at distance d (they add up to 1 and change continuously):
     * near fades into mid between nearMid[0] and nearMid[1] blocks, mid into far between midFar[0]
     * and midFar[1].
     */
    static double[] bandWeights(double d, double[] nearMid, double[] midFar) {
        double toMid = ramp(d, nearMid[0], nearMid[1]);
        double toFar = ramp(d, Math.max(midFar[0], nearMid[1]), Math.max(midFar[1], nearMid[1]));
        return new double[]{1 - toMid, toMid * (1 - toFar), toMid * toFar};
    }

    private static double ramp(double d, double from, double to) {
        if (to <= from) {
            return d < from ? 0 : 1;
        }
        return Math.clamp((d - from) / (to - from), 0.0, 1.0);
    }

    /**
     * How loud the shooter hears their own gun: the items.yml volume from 3.5 (pistols) to 7 (the
     * AWR), linear onto the configured range, so a pistol is a pop next to a rifle.
     */
    static double shooterVolume(GunSpec gun, double[] range) {
        double t = Math.clamp((gun.soundVolume() - QUIETEST_GUN) / (LOUDEST_GUN - QUIETEST_GUN), 0.0, 1.0);
        return Math.clamp(range[0] + t * (range[1] - range[0]), 0.0, 1.0);
    }

    private static double[] pair(org.bukkit.configuration.Configuration config, String path, double a, double b) {
        List<Double> values = config.getDoubleList(path);
        return values.size() >= 2 ? new double[]{values.get(0), values.get(1)} : new double[]{a, b};
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

    /** Plays variant {@code pick} of key to the listener at {@code tick} (see {@link #emit}). */
    private void schedule(long tick, Player listener, Location source, String key, double gain, float pitch, int pick,
                          boolean restart) {
        long seed = seed(key, pick);
        if (tick <= Bukkit.getCurrentTick()) {
            emit(listener, source, key, gain, pitch, seed, restart);
            return;
        }
        queue.add(new Delayed(tick, listener.getUniqueId(), source.clone(), key, gain, pitch, seed, restart));
    }

    /** Plays key at gain for this listener only, keeping the direction of source. */
    private static void emit(Player listener, Location source, String key, double gain, float pitch, long seed, boolean restart) {
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
        listener.playSound(at, key, SoundCategory.PLAYERS, volume, pitch, seed);
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
                    emit(listener, delayed.source(), delayed.key(), delayed.gain(), delayed.pitch(), delayed.seed(), delayed.restart());
                }
            }
        }
        if (now % 200 == 0) {
            indoorCache.entrySet().removeIf(e -> now - e.getValue()[1] > 200);
            roomTurns.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
            decks.values().removeIf(deck -> now - deck.used > 6000);
        }
    }
}
