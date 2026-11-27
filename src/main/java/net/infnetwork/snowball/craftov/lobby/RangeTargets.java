package net.infnetwork.snowball.craftov.lobby;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.BodyPart;
import net.infnetwork.snowball.craftov.combat.HealthService;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The private range's targets (#165): steel silhouettes that fall when shot down and stand up again.
 *
 * <p>A silhouette is an invisible zombie without AI (the hitbox, a player's size, so rounds, knives,
 * grenades, hit zones and lag compensation treat it as they treat a soldier) carrying the picture as
 * passengers: a steel plate (an item display), the base it is hinged to (a block display) and its
 * label. The plate turns on its hinge through the display's own interpolation, so the vanilla client
 * draws the fall and the rise smoothly, and riding the hitbox it moves exactly where the hitbox is
 * drawn. It takes damage like a player without armour ({@code range.targets.hp}); at none left it
 * falls back flat, rounds and knives pass over it, and after {@code range.targets.down-seconds} it
 * stands up again with its health back.
 */
public final class RangeTargets {

    /** How high above a zombie's feet its passengers ride (vanilla's passenger attachment for a zombie). */
    static final float SEAT = 2.0125f;
    /** The hinge the plate turns on: the top of its base. */
    private static final float HINGE = 0.12f;
    /** The plate model is drawn at half size (an item model has to fit in 3 blocks) and shown doubled. */
    private static final float PLATE_SCALE = 2f;

    private enum State { STANDING, DOWN, RISING }

    private record Frame(long at, float angle, int ticks, @Nullable String sound, float pitch) {
    }

    /** A silhouette and where it is in its fall. */
    private static final class Silhouette {
        final int plot;
        final Zombie body;
        final ItemDisplay plate;
        final TextDisplay label;
        final String name;
        State state = State.STANDING;
        double hp;
        long downAt = -1;
        long riseAt;
        long standAt;
        int hits;
        long firstHit = -1;
        @Nullable String last;
        long flashUntil;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();

        Silhouette(int plot, Zombie body, ItemDisplay plate, TextDisplay label, String name) {
            this.plot = plot;
            this.body = body;
            this.plate = plate;
            this.label = label;
            this.name = name;
        }
    }

    private static final class Plot {
        final List<Silhouette> silhouettes = new ArrayList<>();
    }

    private final CraftovPlugin plugin;
    private final Map<Integer, Plot> plots = new HashMap<>();
    private final Map<UUID, Silhouette> silhouettes = new HashMap<>();

    RangeTargets(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        // Every tick: the fall's keyframes are a few ticks apart.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private double maxHp() {
        return Math.max(1, plugin.getConfig().getDouble("range.targets.hp", HealthService.MAX_HP));
    }

    private long downTicks() {
        return Math.max(1, Math.round(plugin.getConfig().getDouble("range.targets.down-seconds", 3) * 20));
    }

    // ------------------------------------------------------------------ building

    /** Puts up the targets of plot {@code plot}, {@code dx} blocks east of the hangar; what it spawns goes into {@code spawned}. */
    void spawn(World world, int plot, int dx, List<Entity> spawned) {
        Plot p = new Plot();
        plots.put(plot, p);
        double floor = RangeBuilder.Y + 1;
        for (int[] at : RangeBuilder.SILHOUETTES) {
            int meters = RangeBuilder.RAIL_Z - at[1];
            Silhouette s = silhouette(world, plot, new Location(world, dx + at[0] + 0.5, floor, at[1] + 0.5, 0, 0),
                    "人物靶 " + meters + " m", meters, spawned);
            p.silhouettes.add(s);
        }
        String[] signs = {
                "<b><text>人物靶</b>\n<muted>4 / 7 / 11 m · 打倒后 " + trim(downTicks() / 20.0) + " 秒升起"};
        for (int i = 0; i < signs.length; i++) {
            String text = signs[i];
            // Over the lane, above the line of sight down it.
            spawned.add(world.spawn(new Location(world, dx + RangeBuilder.LANE_SIGN_X[i], floor + 3.0, RangeBuilder.RAIL_Z - 1.5),
                    TextDisplay.class, d -> {
                        d.text(Text.mm(text));
                        d.setBillboard(Display.Billboard.VERTICAL);
                        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.8f, 0.8f, 0.8f),
                                new Quaternionf()));
                        d.setPersistent(false);
                        d.setBackgroundColor(Color.fromARGB(120, 10, 12, 16));
                    }));
        }
    }

    private Silhouette silhouette(World world, int plot, Location feet, String name, int meters, List<Entity> spawned) {
        // Readable from the firing line: larger the farther the target stands.
        float scale = (float) Math.max(0.7, Math.min(1.8, 0.07 * meters));
        Zombie body = world.spawn(feet, Zombie.class, false, z -> {
            z.setAI(false);
            z.setAdult();
            z.setSilent(true);
            z.setInvisible(true);
            z.setGravity(false);
            z.setCollidable(false);
            z.setCanPickupItems(false);
            z.setPersistent(false);
            // Never despawned for being far from players (see the training course's targets);
            // RangeService#release removes it with the plot.
            z.setRemoveWhenFarAway(false);
            z.setShouldBurnInDay(false);
            z.getPersistentDataContainer().set(Keys.key("target"), PersistentDataType.BYTE, (byte) 1);
        });
        ItemStack picture = ItemStack.of(Material.PAPER);
        picture.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov", "fx/target_silhouette"));
        ItemDisplay plate = world.spawn(facingShooter(feet), ItemDisplay.class, d -> {
            d.setItemStack(picture);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(plate(0));
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
        });
        BlockDisplay base = world.spawn(feet, BlockDisplay.class, d -> {
            d.setBlock(Material.POLISHED_BLACKSTONE.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(-0.34f, -SEAT, -0.16f), new Quaternionf(),
                    new Vector3f(0.68f, HINGE, 0.32f), new Quaternionf()));
            d.setPersistent(false);
        });
        TextDisplay label = world.spawn(feet, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(new Vector3f(0, 2.15f - SEAT, 0), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            d.setPersistent(false);
            d.setBackgroundColor(Color.fromARGB(120, 10, 12, 16));
        });
        body.addPassenger(base);
        body.addPassenger(plate);
        body.addPassenger(label);
        spawned.add(body);
        spawned.add(plate);
        spawned.add(base);
        spawned.add(label);
        Silhouette s = new Silhouette(plot, body, plate, label, name);
        s.hp = maxHp();
        silhouettes.put(body.getUniqueId(), s);
        relabel(s);
        return s;
    }

    /**
     * Where an item display stands to show a model's south face (+Z) to the firing line, south of it:
     * the client draws item displays turned half round, so it is turned back with a yaw of 180.
     */
    private static Location facingShooter(Location at) {
        Location out = at.clone();
        out.setYaw(180);
        out.setPitch(0);
        return out;
    }

    /**
     * The plate turned back by {@code angle} degrees (0 upright, -90 flat on its back) on the hinge:
     * the model's origin is the hinge, so turning about it and then dropping it from the seat to the
     * hinge keeps the bottom edge in place all the way down.
     */
    private static Transformation plate(float angle) {
        return new Transformation(new Vector3f(0, HINGE - SEAT, 0), new Quaternionf().rotateX((float) Math.toRadians(angle)),
                new Vector3f(PLATE_SCALE, PLATE_SCALE, PLATE_SCALE), new Quaternionf());
    }

    /** Forgets plot {@code plot}'s targets; RangeService removes their entities with the plot. */
    void clear(int plot) {
        Plot p = plots.remove(plot);
        if (p == null) {
            return;
        }
        for (Silhouette s : p.silhouettes) {
            silhouettes.remove(s.body.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ queries

    /** A silhouette that is down or still getting up: rounds, knives and blasts pass it by. */
    public boolean isDown(Entity entity) {
        Silhouette s = silhouettes.get(entity.getUniqueId());
        return s != null && s.state != State.STANDING;
    }

    /** True when the hit {@code victim} just took knocked it down (the knock-down marker and sound). */
    public boolean knockedDownNow(LivingEntity victim) {
        Silhouette s = silhouettes.get(victim.getUniqueId());
        return s != null && s.state == State.DOWN && s.downAt == Bukkit.getCurrentTick();
    }

    /** The sound of a round striking a range target (steel), or null for anything else. */
    public @Nullable String impactSound(Entity entity) {
        return silhouettes.containsKey(entity.getUniqueId()) ? "craftov:impact.metal" : null;
    }

    /** Sparks off steel instead of blood. False for anything else. */
    public boolean impact(LivingEntity entity, Location at) {
        World world = at.getWorld();
        if (silhouettes.containsKey(entity.getUniqueId())) {
            world.spawnParticle(Particle.ELECTRIC_SPARK, at, 6, 0.05, 0.05, 0.05, 0.25);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ hits

    /**
     * Damage dealt to a range target by a round, a knife or a blast (DamageService#deal, before
     * LobbyService cancels it): a silhouette loses as much health and falls at none.
     */
    void damaged(Entity victim, double amount) {
        Silhouette s = silhouettes.get(victim.getUniqueId());
        if (s == null || s.state != State.STANDING || amount <= 0) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        if (s.firstHit < 0) {
            s.firstHit = now;
        }
        s.hits++;
        s.hp -= amount;
        // A short outline flash on the plate, seen from the firing line however far it is.
        s.plate.setGlowColorOverride(Color.fromRGB(255, 214, 120));
        s.plate.setGlowing(true);
        s.flashUntil = now + 3;
        if (s.hp <= 0) {
            knockDown(s, now);
        }
        relabel(s);
    }

    /**
     * A round fired by {@code shooter} on the range hit {@code hit} ({@code part}, {@code dealt}
     * damage): a silhouette's label says where and how much.
     */
    public void shot(Player shooter, @Nullable LivingEntity hit, BodyPart part, double dealt) {
        Integer plot = plugin.range().plotOf(shooter);
        Silhouette s = hit == null ? null : silhouettes.get(hit.getUniqueId());
        if (plot == null || s == null || s.plot != plot) {
            return;
        }
        s.last = part.displayName() + " <white>-" + Math.round(dealt);
        relabel(s);
        Notify.status(shooter, "range_target", "<muted>" + s.name + " · " + s.last
                + (s.state == State.STANDING ? " <muted>· 剩余 <white>" + (int) Math.ceil(s.hp) : " <ok>· 击倒"));
    }

    // ------------------------------------------------------------------ falling and rising

    private void knockDown(Silhouette s, long now) {
        s.state = State.DOWN;
        s.downAt = now;
        s.riseAt = now + downTicks();
        s.frames.clear();
        // Tipping slowly, then quicker, a small bounce on landing: about a third of a second.
        s.frames.add(new Frame(now, -24, 3, "minecraft:block.iron_trapdoor.close", 0.7f));
        s.frames.add(new Frame(now + 3, -90, 3, null, 1));
        s.frames.add(new Frame(now + 6, -80, 2, "minecraft:block.anvil.land", 1.9f));
        s.frames.add(new Frame(now + 8, -90, 2, null, 1));
    }

    private void rise(Silhouette s, long now) {
        s.state = State.RISING;
        s.frames.clear();
        // Pushed back up by its motor, a little past upright, then settling.
        s.frames.add(new Frame(now, 6, 10, "minecraft:block.piston.extend", 1.3f));
        s.frames.add(new Frame(now + 10, 0, 3, null, 1));
        // Hittable again once it is most of the way up.
        s.standAt = now + 8;
    }

    private void stand(Silhouette s) {
        s.state = State.STANDING;
        s.hp = maxHp();
        s.hits = 0;
        s.firstHit = -1;
        s.last = null;
        relabel(s);
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Plot p : plots.values()) {
            for (Silhouette s : p.silhouettes) {
                if (!s.body.isValid()) {
                    continue;
                }
                if (s.state == State.DOWN && now >= s.riseAt) {
                    rise(s, now);
                }
                if (s.state == State.RISING && now >= s.standAt) {
                    stand(s);
                }
                while (!s.frames.isEmpty() && s.frames.peekFirst().at() <= now) {
                    Frame f = s.frames.pollFirst();
                    s.plate.setInterpolationDelay(0);
                    s.plate.setInterpolationDuration(f.ticks());
                    s.plate.setTransformation(plate(f.angle()));
                    if (f.sound() != null) {
                        s.body.getWorld().playSound(s.body.getLocation(), f.sound(), SoundCategory.BLOCKS, 0.6f, f.pitch());
                    }
                }
                if (s.flashUntil > 0 && now >= s.flashUntil) {
                    s.flashUntil = 0;
                    s.plate.setGlowing(false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ labels

    private void relabel(Silhouette s) {
        StringBuilder sb = new StringBuilder("<b><text>").append(s.name).append("</b>\n");
        if (s.state == State.STANDING) {
            double share = s.hp / maxHp();
            String color = share > 0.6 ? "ok" : share > 0.3 ? "accent" : "danger";
            sb.append(Text.bar(share, 20, color, "dim")).append(" <white>").append((int) Math.ceil(Math.max(0, s.hp)));
            if (s.last != null) {
                sb.append("\n<muted>").append(s.last);
            }
        } else if (s.hits <= 1) {
            sb.append("<ok>一发击倒");
        } else {
            // From the first hit to the one that knocked it down: the time to kill.
            double seconds = (s.downAt - s.firstHit) / 20.0;
            sb.append("<ok>击倒 <white>").append(s.hits).append(" <muted>发 · <white>").append(String.format("%.2f", seconds))
                    .append(" <muted>秒");
            if (s.last != null) {
                sb.append("\n<muted>").append(s.last);
            }
        }
        s.label.text(Text.mm(sb.toString()));
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
