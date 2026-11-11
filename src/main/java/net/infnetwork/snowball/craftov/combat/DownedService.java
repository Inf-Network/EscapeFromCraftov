package net.infnetwork.snowball.craftov.combat;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Knock-down and rescue. The first down gives a long bleed-out, the second a short one, and a third
 * lethal hit kills outright. Teammates revive by crouching next to the downed player.
 */
public final class DownedService implements Listener {

    public static final class Downed {
        public final int number;
        public double pool;
        public final double maxPool;
        public @Nullable UUID reviver;
        public int reviveTicks;
        public final int reviveNeeded;
        public DamageService.@Nullable LastHit cause;
        Location fakeBlock;

        Downed(int number, double pool, int reviveNeeded) {
            this.number = number;
            this.pool = pool;
            this.maxPool = pool;
            this.reviveNeeded = reviveNeeded;
        }

        public double fraction() {
            return Math.max(0, pool / maxPool);
        }
    }

    private static final BlockData BARRIER = Material.BARRIER.createBlockData();
    private final CraftovPlugin plugin;
    private final Map<UUID, Downed> downed = new HashMap<>();
    /** reviver -> (downed player, tick of last right-click). */
    private final Map<UUID, Map.Entry<UUID, Long>> clicks = new HashMap<>();

    public DownedService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public boolean isDowned(Player player) {
        return downed.containsKey(player.getUniqueId());
    }

    public @Nullable Downed state(Player player) {
        return downed.get(player.getUniqueId());
    }

    public boolean canBeDowned(Raid raid, RaidPlayer rp) {
        int maxDowns = plugin.getConfig().getInt("downed.max-downs", 2);
        if (rp.downs >= maxDowns) {
            return false;
        }
        if (raid.squadOf(rp).size() <= 1) {
            return plugin.getConfig().getBoolean("downed.solo", false);
        }
        for (RaidPlayer mate : raid.squadOf(rp)) {
            if (mate != rp && mate.state == RaidPlayer.State.ALIVE) {
                return true;
            }
        }
        return false;
    }

    public void down(Raid raid, RaidPlayer rp, Player player, DamageService.@Nullable LastHit cause) {
        rp.downs++;
        rp.state = RaidPlayer.State.DOWNED;
        boolean first = rp.downs == 1;
        double pool = first ? plugin.getConfig().getDouble("downed.first-seconds", 60) : plugin.getConfig().getDouble("downed.second-seconds", 30);
        int revive = (int) Math.round(plugin.getConfig().getDouble("downed.revive-seconds", 5) * 20);
        Downed state = new Downed(rp.downs, pool, revive);
        state.cause = cause;
        downed.put(player.getUniqueId(), state);
        plugin.weapons().reset(player);
        player.setHealth(1.0);
        player.setSprinting(false);
        plugin.health().forceSpeedUpdate(player);
        player.showTitle(Title.title(Text.mm("<#FF4D4D><b>" + (first ? "第一次倒地" : "第二次倒地")),
                Text.mm("<#9AA0A6>等待队友救援 · " + (first ? "再次倒地将更加危险" : "<#FF8A80>下一次将直接阵亡")),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofMillis(400))));
        player.playSound(player.getLocation(), "craftov:status.downed", SoundCategory.MASTER, 1f, 1f);
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player p = Bukkit.getPlayer(mate.uuid);
            if (p != null && p != player) {
                p.sendMessage(Text.mm(Text.PREFIX + "<#FF8A80>队友 " + Text.escape(player.getName()) + " 倒地了！靠近后潜行或按住右键救援。"));
                p.playSound(p.getLocation(), "craftov:status.mate_down", SoundCategory.MASTER, 1f, 1f);
            }
        }
        if (cause != null && cause.attacker() != null) {
            Player attacker = Bukkit.getPlayer(cause.attacker());
            if (attacker != null) {
                attacker.sendActionBar(Text.mm("<#FFC23D>击倒 <white>" + Text.escape(player.getName())));
            }
        }
        crawl(player, state);
    }

    /** Damage taken while downed eats into the bleed-out pool. */
    public void damageDowned(Raid raid, RaidPlayer rp, Player player, double amount) {
        Downed state = downed.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.pool -= amount * 0.6;
        state.reviveTicks = 0;
        state.reviver = null;
        if (state.pool <= 0) {
            finish(raid, rp, player, state);
        }
    }

    public void revive(Player player, @Nullable Player reviver) {
        Downed state = downed.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        RaidPlayer rp = plugin.raids().session(player);
        if (rp != null) {
            rp.state = RaidPlayer.State.ALIVE;
        }
        uncrawl(player, state);
        player.setHealth(Math.min(HealthService.MAX_HP, state.number == 1 ? 35 : 20));
        plugin.health().forceSpeedUpdate(player);
        player.showTitle(Title.title(Text.mm("<#5BD16B>已被救起"), Text.mm(reviver == null ? "<#9AA0A6>自我救援成功" : "<#9AA0A6>" + Text.escape(reviver.getName()) + " 救起了你"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(300))));
        player.playSound(player.getLocation(), "craftov:status.revived", SoundCategory.MASTER, 1f, 1f);
        if (reviver != null) {
            reviver.sendActionBar(Text.mm("<#5BD16B>救援成功"));
        }
    }

    public void clear(Player player) {
        Downed state = downed.remove(player.getUniqueId());
        if (state != null) {
            uncrawl(player, state);
            plugin.health().forceSpeedUpdate(player);
        }
    }

    private void finish(Raid raid, RaidPlayer rp, Player player, Downed state) {
        downed.remove(player.getUniqueId());
        uncrawl(player, state);
        rp.state = RaidPlayer.State.ALIVE;
        DamageService.LastHit hit = plugin.damage().lastHit(player.getUniqueId());
        if (hit == null) {
            hit = state.cause;
        }
        plugin.raids().die(raid, player, rp, hit == null || hit.attacker() == null ? "倒地后失血过多"
                : net.infnetwork.snowball.craftov.raid.RaidManager.deathReason(hit), hit);
    }

    private void tick() {
        for (Map.Entry<UUID, Downed> entry : new HashMap<>(downed).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Downed state = entry.getValue();
            if (player == null) {
                downed.remove(entry.getKey());
                continue;
            }
            Raid raid = plugin.raids().raidOf(player);
            RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
            if (raid == null || rp == null) {
                clear(player);
                continue;
            }
            crawl(player, state);
            if (!hasStandingMate(raid, rp)) {
                // Nobody left who could revive: bleeding out would only make everyone wait.
                finish(raid, rp, player, state);
                continue;
            }
            Player reviver = findReviver(raid, rp, player);
            if (reviver != null) {
                if (!reviver.getUniqueId().equals(state.reviver)) {
                    state.reviver = reviver.getUniqueId();
                    state.reviveTicks = 0;
                }
                state.reviveTicks += Math.round(5 * plugin.operators().reviveMultiplier(reviver));
                reviveEffects(reviver, player);
                double progress = Math.min(1, state.reviveTicks / (double) state.reviveNeeded);
                reviver.sendActionBar(Text.mm("<#5BD16B>正在救援 " + Text.escape(player.getName()) + "  " + Text.bar(progress, 20, "#5BD16B", "#3A3F47")));
                player.sendActionBar(Text.mm("<#5BD16B>" + Text.escape(reviver.getName()) + " 正在救援你  " + Text.bar(progress, 20, "#5BD16B", "#3A3F47")));
                if (state.reviveTicks >= state.reviveNeeded) {
                    stopReviving(reviver);
                    revive(player, reviver);
                }
                continue;
            }
            if (state.reviver != null) {
                Player old = Bukkit.getPlayer(state.reviver);
                if (old != null) {
                    stopReviving(old);
                }
            }
            state.reviver = null;
            state.reviveTicks = 0;
            state.pool -= 0.25;
            if (Bukkit.getCurrentTick() % 20 < 5) {
                player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation().add(0, 0.2, 0), 4, 0.3, 0.05, 0.3,
                        Material.REDSTONE_BLOCK.createBlockData());
                player.playSound(player.getLocation(), "craftov:status.heartbeat", SoundCategory.PLAYERS, 0.6f, 1f);
            }
            player.sendActionBar(Text.mm("<#FF4D4D>倒地 " + Text.bar(state.fraction(), 24, "#FF4D4D", "#3A3F47")
                    + " <#9AA0A6>" + (int) Math.ceil(state.pool) + "s"));
            if (state.pool <= 0) {
                finish(raid, rp, player, state);
            }
        }
    }

    private boolean hasStandingMate(Raid raid, RaidPlayer rp) {
        for (RaidPlayer mate : raid.squadOf(rp)) {
            if (mate != rp && mate.state == RaidPlayer.State.ALIVE && Bukkit.getPlayer(mate.uuid) != null) {
                return true;
            }
        }
        return false;
    }

    /** The reviver kneels over the downed player and works on them; everyone nearby sees it. */
    private void reviveEffects(Player reviver, Player patient) {
        if (reviver.getPose() != Pose.SNEAKING) {
            reviver.setPose(Pose.SNEAKING, true);
        }
        Location at = patient.getLocation().add(0, 0.4, 0);
        reviver.swingMainHand();
        patient.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 3, 0.35, 0.15, 0.35, 0);
        if (Bukkit.getCurrentTick() % 20 < 5) {
            patient.getWorld().playSound(at, "minecraft:item.armor.equip_leather", SoundCategory.PLAYERS, 0.6f, 1.2f);
        }
    }

    private void stopReviving(Player reviver) {
        if (reviver.hasFixedPose()) {
            reviver.setPose(Pose.STANDING, false);
        }
    }

    private @Nullable Player findReviver(Raid raid, RaidPlayer rp, Player downedPlayer) {
        for (RaidPlayer mate : raid.squadOf(rp)) {
            if (mate == rp || mate.state != RaidPlayer.State.ALIVE) {
                continue;
            }
            Player p = Bukkit.getPlayer(mate.uuid);
            Map.Entry<UUID, Long> click = p == null ? null : clicks.get(p.getUniqueId());
            boolean holding = click != null && click.getKey().equals(downedPlayer.getUniqueId())
                    && Bukkit.getCurrentTick() - click.getValue() <= 8;
            if (p != null && (p.isSneaking() || holding) && p.getWorld() == downedPlayer.getWorld()
                    && p.getLocation().distanceSquared(downedPlayer.getLocation()) <= 2.2 * 2.2) {
                return p;
            }
        }
        return null;
    }

    /** Forces the crawling pose by placing a client-side barrier above the player's head. */
    private void crawl(Player player, Downed state) {
        // Server-side pose so other players see the crawl; the fake barrier keeps the own client down.
        if (player.getPose() != Pose.SWIMMING || !player.hasFixedPose()) {
            player.setPose(Pose.SWIMMING, true);
        }
        Location head = player.getLocation().add(0, 1.5, 0);
        Block block = head.getBlock();
        if (state.fakeBlock != null && !state.fakeBlock.getBlock().equals(block)) {
            player.sendBlockChange(state.fakeBlock, state.fakeBlock.getBlock().getBlockData());
            state.fakeBlock = null;
        }
        if (block.getType().isAir()) {
            player.sendBlockChange(block.getLocation(), BARRIER);
            state.fakeBlock = block.getLocation();
        }
    }

    private void uncrawl(Player player, Downed state) {
        if (player.hasFixedPose()) {
            player.setPose(Pose.STANDING, false);
        }
        if (state.reviver != null) {
            Player reviver = Bukkit.getPlayer(state.reviver);
            if (reviver != null) {
                stopReviving(reviver);
            }
        }
        if (state.fakeBlock != null && player.isOnline()) {
            player.sendBlockChange(state.fakeBlock, state.fakeBlock.getBlock().getBlockData());
            state.fakeBlock = null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!isDowned(event.getPlayer())) {
            return;
        }
        net.infnetwork.snowball.craftov.item.ItemDef def = plugin.items().defOf(event.getItem());
        if (def != null && def.med() != null && def.med().effect() == MedSpec.Effect.SELF_REVIVE) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isDowned(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (event.getRightClicked() instanceof Player target && isDowned(target)
                && plugin.raids().sameSquad(event.getPlayer(), target)) {
            event.setCancelled(true);
            clicks.put(event.getPlayer().getUniqueId(), Map.entry(target.getUniqueId(), (long) Bukkit.getCurrentTick()));
        }
    }
}
