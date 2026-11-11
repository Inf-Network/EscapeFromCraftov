package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.HitZone;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.loot.ContainerType;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Things that happen during a raid so no two play the same. Some raids are fought at night; every
 * raid rolls two events at random times: a blackout (lever extracts dead for a while), an artillery
 * barrage on an announced area, reinforcements arriving from the map's edge, a supply drop marked by
 * red smoke, or the boss moving its post. Everyone hears about it, with a bearing and distance.
 */
public final class RaidEvents {

    /** OPERATORS and SHIELDS are only started by hand (/cv event), for testing. */
    public enum Kind { BLACKOUT, BARRAGE, REINFORCEMENTS, SUPPLY_DROP, BOSS_PATROL, DRONE, OPERATORS, SHIELDS }

    private record Planned(Kind kind, long at) {
    }

    /** An event in progress that needs ticking. */
    private abstract static class Running {
        final Raid raid;
        final long started = Bukkit.getCurrentTick();

        Running(Raid raid) {
            this.raid = raid;
        }

        /** False when finished. */
        abstract boolean tick(long now);
    }

    private static final Particle.DustOptions WARN = new Particle.DustOptions(Color.fromRGB(230, 40, 30), 2.2f);

    private final CraftovPlugin plugin;
    private final Map<Raid, List<Planned>> plans = new IdentityHashMap<>();
    private final List<Running> running = new ArrayList<>();

    public RaidEvents(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 5L);
    }

    // ------------------------------------------------------------------ planning

    /** Called when a raid begins: picks night or day and schedules its events. */
    public void begin(Raid raid) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        boolean nightAllowed = !plugin.getConfig().getStringList("events.day-only-maps").contains(raid.map.id());
        if (nightAllowed && rng.nextDouble() < plugin.getConfig().getDouble("events.night-chance", 0.3)) {
            raid.night = true;
            raid.world.setTime(18000);
            for (Player player : raid.online()) {
                player.sendMessage(Text.mm("<#9AA0A6>🌙 <#C8CCD2>夜间行动：<#9AA0A6>敌兵视野变短，但开着的战术手电会让你从更远处暴露。"));
            }
        }
        int count = plugin.getConfig().getInt("events.per-raid", 2);
        if (count <= 0) {
            return;
        }
        List<Kind> pool = new ArrayList<>(List.of(Kind.BLACKOUT, Kind.BARRAGE, Kind.REINFORCEMENTS, Kind.SUPPLY_DROP, Kind.DRONE));
        if (!raid.map.bosses().isEmpty()) {
            pool.add(Kind.BOSS_PATROL);
        }
        if (raid.map.switches().isEmpty()) {
            pool.remove(Kind.BLACKOUT);
        }
        Collections.shuffle(pool);
        long length = raid.map.raidSeconds() * 20L;
        List<Planned> plan = new ArrayList<>();
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            // Spread over the middle of the raid, at least two minutes apart.
            double from = 0.2 + 0.55 * i / count;
            double to = from + 0.5 / count;
            long at = raid.startTick + (long) (length * rng.nextDouble(from, to));
            plan.add(new Planned(pool.get(i), at));
        }
        plans.put(raid, plan);
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        for (Iterator<Map.Entry<Raid, List<Planned>>> it = plans.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Raid, List<Planned>> entry = it.next();
            Raid raid = entry.getKey();
            if (raid.state != Raid.State.RUNNING) {
                it.remove();
                continue;
            }
            entry.getValue().removeIf(p -> {
                if (p.at() > now) {
                    return false;
                }
                // A planned event that cannot happen (no boss alive, nobody to aim at) gives way to another.
                if (!fire(raid, p.kind())) {
                    fire(raid, ThreadLocalRandom.current().nextBoolean() ? Kind.REINFORCEMENTS : Kind.SUPPLY_DROP);
                }
                return true;
            });
        }
        running.removeIf(event -> event.raid.state != Raid.State.RUNNING || !event.tick(now));
    }

    /** Starts an event now (also used by the admin command); false if it could not happen. */
    public boolean fire(Raid raid, Kind kind) {
        return switch (kind) {
            case BLACKOUT -> blackout(raid);
            case BARRAGE -> barrage(raid);
            case REINFORCEMENTS -> reinforcements(raid);
            case SUPPLY_DROP -> supplyDrop(raid);
            case BOSS_PATROL -> bossPatrol(raid);
            case DRONE -> {
                Location from = plugin.drones().launch(raid);
                if (from != null) {
                    for (Player player : raid.online()) {
                        player.sendMessage(Text.mm("<#FF8A80>✈ 敌方侦察无人机已升空：<#C8CCD2>" + bearing(player.getLocation(), from)
                                + "<#9AA0A6>。被它看见会引来附近的敌兵，可以把它打下来。"));
                    }
                }
                yield from != null;
            }
            case OPERATORS -> {
                Location at = nearPlayers(raid, 25, 40);
                if (at != null) {
                    plugin.ai().operatorSquad(raid, at);
                }
                yield at != null;
            }
            case SHIELDS -> {
                Location at = nearPlayers(raid, 12, 18);
                yield at != null && plugin.ai().shieldTeam(raid, at, nearPlayers(raid, 0, 2)) > 0;
            }
        };
    }

    // ------------------------------------------------------------------ events

    private boolean blackout(Raid raid) {
        int seconds = plugin.getConfig().getInt("events.blackout-seconds", 180);
        raid.blackoutUntil = Bukkit.getCurrentTick() + seconds * 20L;
        broadcast(raid, "<#FFC23D>⚡ 电网故障！<#9AA0A6>需拉闸的撤离点在 " + seconds / 60 + " 分钟内无法启用。", "minecraft:block.beacon.deactivate");
        return true;
    }

    private boolean barrage(Raid raid) {
        Location centre = nearPlayers(raid, 10, 22);
        if (centre == null) {
            return false;
        }
        double radius = 16;
        long warn = 20L * 20;
        long fire = 20L * 30;
        raid.world.playSound(centre, "craftov:event.siren", SoundCategory.MASTER, 6f, 1f);
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm("<#FF4D4D>☢ 炮击预警！<#C8CCD2>" + bearing(player.getLocation(), centre)
                    + " <#9AA0A6>半径 " + (int) radius + " 米的区域 20 秒后遭到炮击，红色烟圈内立刻撤出。"));
        }
        running.add(new Running(raid) {
            long nextShell;

            @Override
            boolean tick(long now) {
                long t = now - started;
                if (t % 10 < 5) {
                    ring(raid.world, centre, radius);
                }
                if (t < warn) {
                    return true;
                }
                if (t > warn + fire) {
                    return false;
                }
                if (now >= nextShell) {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    nextShell = now + rng.nextInt(8, 16);
                    double angle = rng.nextDouble(Math.PI * 2);
                    double r = radius * Math.sqrt(rng.nextDouble());
                    Location hit = ground(centre.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r));
                    raid.world.playSound(hit, "craftov:event.whistle", SoundCategory.MASTER, 4f, rng.nextFloat(0.9f, 1.1f));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> shell(raid, hit), 22L);
                }
                return true;
            }
        });
        return true;
    }

    private void shell(Raid raid, Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        world.spawnParticle(Particle.LARGE_SMOKE, at, 40, 1.5, 1.0, 1.5, 0.06);
        world.spawnParticle(Particle.BLOCK, at, 40, 1.2, 0.4, 1.2, 0.2, at.clone().subtract(0, 1, 0).getBlock().getBlockData());
        world.playSound(at, "craftov:throw.explode", SoundCategory.MASTER, 8f, 0.85f);
        double radius = 5.5;
        for (LivingEntity entity : world.getNearbyLivingEntities(at, radius)) {
            if (entity.getPersistentDataContainer().has(Keys.ENTITY_ROLE)) {
                continue;
            }
            if (entity instanceof Player p && !plugin.raids().canBeShot(p)) {
                continue;
            }
            double distance = entity.getLocation().distance(at);
            double damage = 85 * Math.max(0.15, 1 - distance / radius);
            plugin.damage().recordHit(entity, null, "炮击", HitZone.TORSO);
            plugin.damage().deal(entity, null, damage);
            Vector push = entity.getLocation().toVector().subtract(at.toVector()).setY(0.4);
            if (push.lengthSquared() > 1e-4) {
                entity.setVelocity(entity.getVelocity().add(push.normalize().multiply(0.6)));
            }
        }
        plugin.ai().alert(at, 30, null);
    }

    private boolean reinforcements(Raid raid) {
        Location target = nearPlayers(raid, 0, 12);
        if (target == null) {
            return false;
        }
        // From the player spawn point farthest from the action, so they walk in.
        Location from = null;
        double best = -1;
        for (MapDef.SpawnDef spawn : raid.map.spawns()) {
            Location at = spawn.pos().at(raid.world);
            double d = at.distanceSquared(target);
            if (d > best) {
                best = d;
                from = at;
            }
        }
        if (from == null) {
            return false;
        }
        int count = 3 + ThreadLocalRandom.current().nextInt(2);
        if (plugin.ai().reinforce(raid, from, target, count) > 0) {
            for (Player player : raid.online()) {
                player.sendMessage(Text.mm("<#FF8A80>⚑ 敌方增援已部署：<#C8CCD2>" + count + " 名士兵从" + bearing(player.getLocation(), from)
                        + "<#9AA0A6>赶来。"));
            }
            raid.world.playSound(from, "minecraft:event.raid.horn", SoundCategory.MASTER, 4f, 1f);
            return true;
        }
        return false;
    }

    private boolean supplyDrop(Raid raid) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        MapDef map = raid.map;
        Location at = ground(new Location(raid.world, rng.nextDouble(map.minX() + 0.3 * (map.maxX() - map.minX()), map.maxX() - 0.3 * (map.maxX() - map.minX())),
                0, rng.nextDouble(map.minZ() + 0.3 * (map.maxZ() - map.minZ()), map.maxZ() - 0.3 * (map.maxZ() - map.minZ()))));
        ContainerType type = plugin.loot().type("airdrop");
        if (type == null) {
            return false;
        }
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm("<#F2C94C>✈ 空投正在投放：<#C8CCD2>" + bearing(player.getLocation(), at)
                    + "<#9AA0A6>，红烟处 20 秒后落地。"));
        }
        raid.world.playSound(at, "craftov:event.siren", SoundCategory.MASTER, 3f, 1.4f);
        running.add(new Running(raid) {
            boolean landed;

            @Override
            boolean tick(long now) {
                long t = now - started;
                raid.world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, at.clone().add(0, 0.5, 0), 3, 0.2, 0.1, 0.2, 0.03, null, true);
                raid.world.spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 6, 0.5, 0.6, 0.5, 0, WARN, true);
                if (!landed && t >= 20L * 20) {
                    landed = true;
                    LootContainer crate = new LootContainer(LootContainer.newId(), type, at, raid.tier.lootShift() + 1, type.name());
                    crate.spawn(rng.nextFloat() * 360);
                    raid.containers.put(crate.id, crate);
                    raid.world.playSound(at, "minecraft:block.anvil.land", SoundCategory.MASTER, 3f, 0.6f);
                    raid.world.spawnParticle(Particle.EXPLOSION, at, 3, 0.6, 0.2, 0.6, 0);
                    plugin.ai().alert(at, 40, null);
                }
                return t < 20L * 80;
            }
        });
        return true;
    }

    private boolean bossPatrol(Raid raid) {
        List<MapDef.ExtractDef> extracts = raid.map.extracts();
        List<MapDef.ContainerDef> containers = raid.map.containers();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location to;
        if (!containers.isEmpty() && rng.nextBoolean()) {
            to = containers.get(rng.nextInt(containers.size())).pos().at(raid.world);
        } else if (!extracts.isEmpty()) {
            to = extracts.get(rng.nextInt(extracts.size())).pos().at(raid.world);
        } else {
            return false;
        }
        String boss = plugin.ai().relocateBoss(raid, to);
        if (boss == null) {
            return false;
        }
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm("<#FF4D4D>☠ 首领 " + Text.escape(boss) + " <#C8CCD2>正带着护卫转移，目的地在" + bearing(player.getLocation(), to) + "。"));
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** A point near a random living player (offset between min and max blocks), or null. */
    private static @Nullable Location nearPlayers(Raid raid, double min, double max) {
        List<Player> alive = new ArrayList<>();
        for (Player player : raid.online()) {
            RaidPlayer rp = raid.player(player.getUniqueId());
            if (rp != null && rp.state == RaidPlayer.State.ALIVE && player.getWorld() == raid.world) {
                alive.add(player);
            }
        }
        if (alive.isEmpty()) {
            return null;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location base = alive.get(rng.nextInt(alive.size())).getLocation();
        double angle = rng.nextDouble(Math.PI * 2);
        double r = min + rng.nextDouble() * (max - min);
        MapDef map = raid.map;
        double x = Math.max(map.minX() + 4, Math.min(map.maxX() - 4, base.getX() + Math.cos(angle) * r));
        double z = Math.max(map.minZ() + 4, Math.min(map.maxZ() - 4, base.getZ() + Math.sin(angle) * r));
        return ground(new Location(raid.world, x, base.getY(), z));
    }

    private static Location ground(Location at) {
        World world = at.getWorld();
        int y = world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ());
        return new Location(world, at.getBlockX() + 0.5, y + 1, at.getBlockZ() + 0.5);
    }

    private static void ring(World world, Location centre, double radius) {
        for (int i = 0; i < 36; i++) {
            double a = Math.PI * 2 * i / 36;
            Location p = centre.clone().add(Math.cos(a) * radius, 0.3, Math.sin(a) * radius);
            world.spawnParticle(Particle.DUST, p, 2, 0.2, 0.4, 0.2, 0, WARN, true);
        }
    }

    /** "东北方向 60 米" from the player's position to a point. */
    public static String bearing(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        int distance = (int) Math.round(Math.hypot(dx, dz));
        if (distance < 6) {
            return "你所在的位置";
        }
        // Minecraft: north is -Z, east is +X.
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        int index = Math.floorMod((int) Math.round(angle / 45), 8);
        return "你" + names[index] + "方向 " + distance + " 米处";
    }

    private void broadcast(Raid raid, String message, String sound) {
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm(message));
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 0.8f, 1f);
        }
    }
}
