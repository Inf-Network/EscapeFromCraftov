package net.infnetwork.snowball.craftov.replay;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import net.infnetwork.snowball.craftov.weapon.GunSpec;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kill replay. Every raid keeps the last ten seconds of everyone's position, view, stance and held
 * weapon (10 samples a second) plus every shot fired. When a player is killed, they watch the last
 * six seconds again through the killer's eyes: stand-ins (mannequins wearing the killer's, the
 * victim's and nearby soldiers' looks, visible only to the dead player) re-enact the fight with
 * tracers and gunfire, the final second in slow motion. Shift skips it.
 */
public final class ReplayService implements Listener {

    private static final int STEP = 2;
    private static final int KEEP_TICKS = 20 * 10;
    private static final int WINDOW_TICKS = 20 * 6;
    private static final int SLOW_TICKS = 20;
    private static final double SHOW_RADIUS = 48;
    private static final Color TRACER = Color.fromRGB(255, 206, 128);

    /** One actor at one moment. */
    private record Sample(UUID id, double x, double y, double z, float yaw, float pitch, Pose pose, boolean raised,
                          @Nullable ItemStack hand) {
    }

    private record Frame(long tick, Map<UUID, Sample> samples) {
    }

    private record Shot(long tick, UUID shooter, Vector from, Vector to, String audio) {
    }

    /** How an actor looks: whose skin, or which uniform, and what they wear. */
    private record Look(String name, @Nullable ResolvableProfile profile, boolean elite, @Nullable ItemStack helmet,
                        @Nullable ItemStack chest) {
    }

    private static final class Track {
        final Deque<Frame> frames = new ArrayDeque<>();
        final Deque<Shot> shots = new ArrayDeque<>();
        final Map<UUID, Look> looks = new HashMap<>();
        final Map<UUID, Long> lookAt = new HashMap<>();
        final Map<UUID, ItemStack> hands = new HashMap<>();
        final Map<UUID, Integer> handKeys = new HashMap<>();
    }

    /** What a dead player is shown. */
    private record Clip(UUID victim, UUID killer, String killerName, String weapon, int distance, Location deathAt,
                        List<Frame> frames, List<Shot> shots, Map<UUID, Look> looks) {
    }

    private final class Playback {
        final Player viewer;
        final Clip clip;
        final Runnable after;
        final Map<UUID, Mannequin> standIns = new HashMap<>();
        final Set<Entity> hidden = new HashSet<>();
        final long startTick;
        final long endTick;
        double recorded;
        long lastShotTick;
        int ticks;
        @Nullable Mannequin camera;
        int task = -1;

        Playback(Player viewer, Clip clip, Runnable after) {
            this.viewer = viewer;
            this.clip = clip;
            this.after = after;
            this.startTick = clip.frames().get(0).tick();
            this.endTick = clip.frames().get(clip.frames().size() - 1).tick();
            this.recorded = startTick;
            this.lastShotTick = startTick - 1;
        }
    }

    private final CraftovPlugin plugin;
    private final Map<Raid, Track> tracks = new IdentityHashMap<>();
    private final Map<UUID, Playback> playing = new HashMap<>();

    public ReplayService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::record, 40L, STEP);
    }

    // ------------------------------------------------------------------ recording

    private void record() {
        long now = Bukkit.getCurrentTick();
        Set<Raid> live = new HashSet<>();
        for (Raid raid : plugin.raids().raids()) {
            if (raid.state != Raid.State.RUNNING) {
                continue;
            }
            live.add(raid);
            Track track = tracks.computeIfAbsent(raid, r -> new Track());
            Map<UUID, Sample> samples = new HashMap<>();
            for (RaidPlayer rp : raid.players.values()) {
                Player player = Bukkit.getPlayer(rp.uuid);
                if (player == null || !rp.active() || player.getWorld() != raid.world || player.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                boolean downed = plugin.downed().isDowned(player);
                Pose pose = downed || player.isSwimming() ? Pose.SWIMMING : player.isSneaking() ? Pose.SNEAKING : Pose.STANDING;
                ItemStack hand = hand(track, player.getUniqueId(), player.getInventory().getItemInMainHand());
                boolean raised = hand != null && plugin.stance().takesPick(player.getEntityId()) && !player.isSprinting() && !downed;
                samples.put(player.getUniqueId(), sample(player, pose, raised, hand));
                look(track, player, now, () -> new Look(player.getName(), ResolvableProfile.resolvableProfile(player.getPlayerProfile()),
                        false, copy(player.getInventory().getHelmet()), copy(player.getInventory().getChestplate())));
            }
            for (Zombie mob : plugin.ai().mobsIn(raid)) {
                ItemStack hand = hand(track, mob.getUniqueId(), mob.getEquipment().getItemInMainHand());
                samples.put(mob.getUniqueId(), sample(mob, Pose.STANDING, true, hand));
                EntityEquipment eq = mob.getEquipment();
                look(track, mob, now, () -> new Look(DamageService.displayName(mob), null, mob instanceof Husk,
                        copy(eq.getHelmet()), copy(eq.getChestplate())));
            }
            track.frames.addLast(new Frame(now, samples));
            while (!track.frames.isEmpty() && now - track.frames.peekFirst().tick() > KEEP_TICKS) {
                track.frames.removeFirst();
            }
            while (!track.shots.isEmpty() && now - track.shots.peekFirst().tick() > KEEP_TICKS) {
                track.shots.removeFirst();
            }
        }
        tracks.keySet().retainAll(live);
    }

    private static Sample sample(LivingEntity entity, Pose pose, boolean raised, @Nullable ItemStack hand) {
        Location at = entity.getLocation();
        return new Sample(entity.getUniqueId(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), pose, raised, hand);
    }

    /** The held item, cloned only when it changes so frames share one copy. */
    private static @Nullable ItemStack hand(Track track, UUID id, @Nullable ItemStack held) {
        if (held == null || held.isEmpty()) {
            track.hands.remove(id);
            track.handKeys.remove(id);
            return null;
        }
        int key = held.getType().hashCode() * 31 + String.valueOf(held.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL)).hashCode()
                + 17 * String.valueOf(held.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA)).hashCode();
        Integer known = track.handKeys.get(id);
        if (known == null || known != key) {
            track.handKeys.put(id, key);
            track.hands.put(id, held.clone());
        }
        return track.hands.get(id);
    }

    private static void look(Track track, Entity entity, long now, java.util.function.Supplier<Look> make) {
        Long at = track.lookAt.get(entity.getUniqueId());
        if (at == null || now - at > 40) {
            track.looks.put(entity.getUniqueId(), make.get());
            track.lookAt.put(entity.getUniqueId(), now);
        }
    }

    private static @Nullable ItemStack copy(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.clone();
    }

    /** A shot to replay later (Ballistics calls this for players and soldiers alike). */
    public void shot(LivingEntity shooter, Location from, Location to, GunSpec gun) {
        for (Map.Entry<Raid, Track> entry : tracks.entrySet()) {
            if (entry.getKey().world == from.getWorld()) {
                entry.getValue().shots.addLast(new Shot(Bukkit.getCurrentTick(), shooter.getUniqueId(), from.toVector(),
                        to.toVector(), gun.audio()));
                return;
            }
        }
    }

    // ------------------------------------------------------------------ after a death

    /**
     * Called when a raid player dies. Plays the kill replay first if there is a killer to follow,
     * then {@code after} (death report, spectating); without one, {@code after} runs as before.
     */
    public void afterDeath(Player victim, Raid raid, DamageService.@Nullable LastHit hit, Runnable after) {
        Clip clip = hit == null || hit.attacker() == null || !plugin.getConfig().getBoolean("replay.enabled", true)
                ? null : clip(victim, raid, hit);
        if (clip == null) {
            Bukkit.getScheduler().runTaskLater(plugin, after, 20L * 5);
            return;
        }
        UUID id = victim.getUniqueId();
        // The last death ends the raid; keep its world open until the replay is over.
        long length = clip.frames().get(clip.frames().size() - 1).tick() - clip.frames().get(0).tick() + SLOW_TICKS + 40;
        raid.endTick = Math.max(raid.endTick, Bukkit.getCurrentTick() + 30 + length + 40);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null || viewer.getWorld() != raid.world
                    || (raid.state != Raid.State.RUNNING && raid.state != Raid.State.ENDING)) {
                after.run();
                return;
            }
            start(viewer, clip, after);
        }, 30L);
    }

    private @Nullable Clip clip(Player victim, Raid raid, DamageService.LastHit hit) {
        Track track = tracks.get(raid);
        if (track == null || track.frames.isEmpty()) {
            return null;
        }
        long now = Bukkit.getCurrentTick();
        List<Frame> frames = new ArrayList<>();
        for (Frame frame : track.frames) {
            if (now - frame.tick() <= WINDOW_TICKS) {
                frames.add(frame);
            }
        }
        if (frames.size() < 5 || frames.stream().noneMatch(f -> f.samples().containsKey(hit.attacker()))) {
            return null;
        }
        List<Shot> shots = new ArrayList<>();
        for (Shot shot : track.shots) {
            if (now - shot.tick() <= WINDOW_TICKS) {
                shots.add(shot);
            }
        }
        Location deathAt = victim.getLocation();
        Sample killer = null;
        for (int i = frames.size() - 1; i >= 0 && killer == null; i--) {
            killer = frames.get(i).samples().get(hit.attacker());
        }
        int distance = killer == null ? 0 : (int) Math.round(Math.sqrt(Math.pow(killer.x() - deathAt.getX(), 2)
                + Math.pow(killer.y() - deathAt.getY(), 2) + Math.pow(killer.z() - deathAt.getZ(), 2)));
        return new Clip(victim.getUniqueId(), hit.attacker(), hit.attackerName(), hit.weapon(), distance, deathAt, frames, shots,
                new HashMap<>(track.looks));
    }

    // ------------------------------------------------------------------ playback

    private void start(Player viewer, Clip clip, Runnable after) {
        Playback playback = new Playback(viewer, clip, after);
        playing.put(viewer.getUniqueId(), playback);
        World world = viewer.getWorld();
        // Only the stand-ins are seen: the live raid goes on, hidden from the replay.
        for (Player other : world.getPlayers()) {
            if (other != viewer) {
                viewer.hidePlayer(plugin, other);
                playback.hidden.add(other);
            }
        }
        for (Raid raid : plugin.raids().raids()) {
            if (raid.world == world) {
                for (Zombie mob : plugin.ai().mobsIn(raid)) {
                    viewer.hideEntity(plugin, mob);
                    playback.hidden.add(mob);
                }
            }
        }
        Set<UUID> cast = new HashSet<>();
        for (Frame frame : clip.frames()) {
            for (Sample sample : frame.samples().values()) {
                if (sample.id().equals(clip.killer()) || sample.id().equals(clip.victim())
                        || clip.deathAt().distanceSquared(new Location(world, sample.x(), sample.y(), sample.z())) < SHOW_RADIUS * SHOW_RADIUS) {
                    cast.add(sample.id());
                }
            }
        }
        Frame first = clip.frames().get(0);
        for (UUID id : cast) {
            Sample sample = first.samples().get(id);
            if (sample == null) {
                sample = firstSample(clip, id);
            }
            if (sample != null) {
                playback.standIns.put(id, standIn(viewer, world, sample, clip.looks().get(id)));
            }
        }
        // The camera follows the killer's stand-in once the client has it (see step).
        playback.camera = playback.standIns.get(clip.killer());
        viewer.showTitle(Title.title(Text.mm("<#FF4D4D><b>击杀回放"), Text.mm("<white>" + Text.escape(clip.killerName())
                        + " <#8A8F98>· " + Text.escape(clip.weapon()) + " · " + clip.distance() + "m"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(400))));
        playback.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(playback), 1L, 1L).getTaskId();
    }

    private static @Nullable Sample firstSample(Clip clip, UUID id) {
        for (Frame frame : clip.frames()) {
            Sample sample = frame.samples().get(id);
            if (sample != null) {
                return sample;
            }
        }
        return null;
    }

    private Mannequin standIn(Player viewer, World world, Sample sample, @Nullable Look look) {
        Location at = new Location(world, sample.x(), sample.y(), sample.z(), sample.yaw(), sample.pitch());
        Mannequin mannequin = world.spawn(at, Mannequin.class, m -> {
            m.setVisibleByDefault(false);
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setGravity(false);
            m.setImmovable(true);
            if (look != null) {
                if (look.profile() != null) {
                    m.setProfile(look.profile());
                } else {
                    m.setProfile(ResolvableProfile.resolvableProfile().skinPatch(p -> p.body(Key.key("craftov", "entity/soldier/"
                            + (look.elite() ? "elite" : "patrol"))).model(PlayerTextures.SkinModel.CLASSIC)).build());
                }
                m.getEquipment().setHelmet(look.helmet());
                m.getEquipment().setChestplate(look.chest());
                m.customName(Text.mm("<#C8CCD2>" + Text.escape(look.name())));
                m.setCustomNameVisible(false);
            }
        });
        viewer.showEntity(plugin, mannequin);
        apply(mannequin, sample, null);
        return mannequin;
    }

    /** Puts a stand-in where the sample says, between two samples by {@code mix} if a next one is given. */
    private void apply(Mannequin mannequin, Sample sample, @Nullable Sample next, double mix) {
        double x = sample.x();
        double y = sample.y();
        double z = sample.z();
        float yaw = sample.yaw();
        float pitch = sample.pitch();
        if (next != null && mix > 0) {
            x += (next.x() - x) * mix;
            y += (next.y() - y) * mix;
            z += (next.z() - z) * mix;
            float dy = ((next.yaw() - yaw) % 360 + 540) % 360 - 180;
            yaw += (float) (dy * mix);
            pitch += (float) ((next.pitch() - pitch) * mix);
        }
        Location at = new Location(mannequin.getWorld(), x, y, z, yaw, pitch);
        mannequin.teleport(at);
        mannequin.setRotation(yaw, pitch);
        mannequin.setBodyYaw(yaw);
        if (mannequin.getPose() != sample.pose()) {
            mannequin.setPose(sample.pose(), true);
        }
        ItemStack hand = sample.hand() == null ? null : sample.raised() ? plugin.stance().raisedCopy(sample.hand()) : sample.hand();
        ItemStack current = mannequin.getEquipment().getItemInMainHand();
        if (hand == null ? !current.isEmpty() : !hand.isSimilar(current)) {
            mannequin.getEquipment().setItemInMainHand(hand);
        }
    }

    private void apply(Mannequin mannequin, Sample sample, @Nullable Sample next) {
        apply(mannequin, sample, next, 0);
    }

    private void step(Playback playback) {
        Player viewer = playback.viewer;
        if (!viewer.isOnline() || viewer.getWorld() != playback.clip.deathAt().getWorld()) {
            finish(playback);
            return;
        }
        playback.ticks++;
        Mannequin camera = playback.camera;
        if (camera != null && (playback.ticks == 2 || playback.ticks % 10 == 0) && viewer.getSpectatorTarget() != camera) {
            viewer.setSpectatorTarget(camera);
        }
        // Real time until the last second, which plays at half speed.
        playback.recorded += playback.endTick - playback.recorded <= SLOW_TICKS ? 0.5 : 1.0;
        if (playback.recorded > playback.endTick + 10) {
            finish(playback);
            return;
        }
        double t = Math.min(playback.recorded, playback.endTick);
        List<Frame> frames = playback.clip.frames();
        int index = (int) Math.min(frames.size() - 1, Math.max(0, Math.floor((t - playback.startTick) / STEP)));
        Frame frame = frames.get(index);
        Frame next = index + 1 < frames.size() ? frames.get(index + 1) : null;
        double mix = next == null ? 0 : Math.max(0, Math.min(1, (t - frame.tick()) / (double) Math.max(1, next.tick() - frame.tick())));
        for (Map.Entry<UUID, Mannequin> entry : playback.standIns.entrySet()) {
            Sample sample = frame.samples().get(entry.getKey());
            if (sample == null) {
                continue;
            }
            Sample after = next == null ? null : next.samples().get(entry.getKey());
            apply(entry.getValue(), sample, after, mix);
        }
        long now = (long) Math.floor(t);
        for (Shot shot : playback.clip.shots()) {
            if (shot.tick() > playback.lastShotTick && shot.tick() <= now) {
                replayShot(playback, shot);
            }
        }
        playback.lastShotTick = Math.max(playback.lastShotTick, now);
        if ((long) playback.recorded % 20 == 0) {
            viewer.sendActionBar(Text.mm("<#FF8A80>击杀回放 <#8A8F98>· 按 <white>Shift</white> 跳过"));
        }
    }

    private void replayShot(Playback playback, Shot shot) {
        Player viewer = playback.viewer;
        World world = viewer.getWorld();
        Location from = shot.from().toLocation(world);
        Location to = shot.to().toLocation(world);
        Vector dir = to.toVector().subtract(from.toVector());
        if (dir.lengthSquared() > 1e-4) {
            Location start = from.clone().add(dir.clone().normalize().multiply(0.9));
            int ticks = (int) Math.max(1, Math.min(6, Math.round(dir.length() / 28)));
            viewer.spawnParticle(Particle.TRAIL, start, 1, 0, 0, 0, 0, new Particle.Trail(to, TRACER, ticks), true);
        }
        boolean killer = shot.shooter().equals(playback.clip.killer());
        String key = "craftov:gun." + shot.audio() + (killer ? ".fp" : ".near");
        if (killer) {
            viewer.playSound(viewer, key, SoundCategory.PLAYERS, 0.9f, 1f);
        } else {
            viewer.playSound(from, key, SoundCategory.PLAYERS, 0.8f, 1f);
        }
    }

    private void finish(Playback playback) {
        if (playing.remove(playback.viewer.getUniqueId()) == null) {
            return;
        }
        if (playback.task != -1) {
            Bukkit.getScheduler().cancelTask(playback.task);
        }
        Player viewer = playback.viewer;
        if (viewer.isOnline() && viewer.getSpectatorTarget() instanceof Mannequin) {
            viewer.setSpectatorTarget(null);
            viewer.teleport(playback.clip.deathAt());
        }
        for (Mannequin mannequin : playback.standIns.values()) {
            mannequin.remove();
        }
        for (Entity entity : playback.hidden) {
            if (entity instanceof Player other) {
                viewer.showPlayer(plugin, other);
            } else if (entity.isValid()) {
                viewer.showEntity(plugin, entity);
            }
        }
        playback.after.run();
    }

    public boolean watching(Player player) {
        return playing.containsKey(player.getUniqueId());
    }

    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        Playback playback = playing.get(event.getPlayer().getUniqueId());
        if (playback != null && event.getPlayer().getCurrentInput().isSneak()) {
            // Shift skips the replay. A camera lost any other way is picked up again by step().
            Bukkit.getScheduler().runTask(plugin, () -> finish(playback));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Playback playback = playing.get(event.getPlayer().getUniqueId());
        if (playback != null) {
            finish(playback);
        }
    }

    public void shutdown() {
        for (Playback playback : new ArrayList<>(playing.values())) {
            finish(playback);
        }
    }
}
