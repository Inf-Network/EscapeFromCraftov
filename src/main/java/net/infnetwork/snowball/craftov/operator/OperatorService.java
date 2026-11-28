package net.infnetwork.snowball.craftov.operator;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.combat.HealthService;
import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operators and their skills (sneak + F in a raid, or on the private range).
 *
 * <p>On the range an operator can be tried out: one picked there ({@link #select}) is the player's
 * only while they stay on the range, and their own choice is what they have again on leaving. The
 * skill works there too, on the range's targets (the recon's sonar marks the standing silhouettes,
 * the engineer's shield stops rounds), on a cooldown of its own: the raid's carries on untouched, and
 * the range's is shortened ({@code range.skill-cooldown-multiplier}) and forgotten on leaving, with
 * any sprint still running.
 */
public final class OperatorService {

    public static final Map<String, OperatorDef> OPERATORS = new LinkedHashMap<>();

    static {
        OPERATORS.put("vanguard", new OperatorDef("vanguard", "疾风", "突击", "#FF9F43", "战术冲刺", 40,
                List.of("技能：8 秒内移动速度 +35%，换弹速度 +30%。", "被动：冲刺时更难被敌兵发现。"), "craftov:ui/op_vanguard"));
        OPERATORS.put("medic", new OperatorDef("medic", "白鸮", "支援", "#7FD1AE", "急救脉冲", 60,
                List.of("技能：为自己与 8 格内队友回复 30 生命并止血。", "被动：救援速度 +50%，医疗品每点治疗量多回 20% 生命。"), "craftov:ui/op_medic"));
        OPERATORS.put("recon", new OperatorDef("recon", "回声", "侦察", "#8AB4F8", "声呐脉冲", 50,
                List.of("技能：标记 45 格内所有敌人 6 秒（仅小队可见）。", "被动：搜索速度 +20%。"), "craftov:ui/op_recon"));
        OPERATORS.put("engineer", new OperatorDef("engineer", "铁砧", "工程", "#A4AAB3", "部署掩体", 55,
                List.of("技能：在面前展开一面持续 15 秒的防弹掩体。", "被动：护甲耐久损耗 -25%。"), "craftov:ui/op_engineer"));
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Long> sprintUntil = new HashMap<>();
    /** The operator being tried on the range, and the range's own skill cooldown. */
    private final Map<UUID, String> trial = new HashMap<>();
    private final Map<UUID, Long> rangeCooldownUntil = new HashMap<>();

    public OperatorService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    /** The player's operator: on the range the one being tried there, if any. */
    public OperatorDef of(Player player) {
        String tried = trial.get(player.getUniqueId());
        if (tried != null && plugin.range().isIn(player)) {
            return OPERATORS.get(tried);
        }
        return chosen(player);
    }

    /** The operator the player picked in the lobby, whatever they are trying on the range. */
    public OperatorDef chosen(Player player) {
        OperatorDef def = OPERATORS.get(plugin.profiles().get(player).operator);
        return def == null ? OPERATORS.get("vanguard") : def;
    }

    /** Picks an operator: the player's own in the lobby, on the range one to try there only. */
    public void select(Player player, String id) {
        if (!OPERATORS.containsKey(id)) {
            return;
        }
        OperatorDef def = OPERATORS.get(id);
        if (plugin.range().isIn(player)) {
            OperatorDef own = chosen(player);
            if (own.id().equals(id)) {
                trial.remove(player.getUniqueId());
            } else {
                trial.put(player.getUniqueId(), id);
            }
            Notify.ok(player, "靶场试用干员 <" + def.color() + ">" + def.name() + " <muted>(" + def.role() + ") · 离开靶场后仍是 "
                    + own.name());
            return;
        }
        plugin.profiles().get(player).operator = id;
        plugin.profiles().markDirty(player.getUniqueId());
        Text.send(player, "<muted>已选择干员 <" + def.color() + ">" + def.name() + " <muted>(" + def.role() + ")");
    }

    public int cooldownLeft(Player player) {
        Map<UUID, Long> cooldowns = plugin.range().isIn(player) ? rangeCooldownUntil : cooldownUntil;
        long until = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        return (int) Math.max(0, (until - Bukkit.getCurrentTick() + 19) / 20);
    }

    /**
     * The player left the range (RangeService): the operator tried there, the range's cooldown and a
     * sprint begun there are dropped, so nothing of it reaches the lobby or the next raid.
     */
    public void leftRange(Player player) {
        trial.remove(player.getUniqueId());
        rangeCooldownUntil.remove(player.getUniqueId());
        if (sprintUntil.remove(player.getUniqueId()) != null) {
            plugin.health().forceSpeedUpdate(player);
        }
    }

    // ------------------------------------------------------------------ passives

    private boolean is(Player player, String id) {
        return plugin.raids().isInRaid(player) && of(player).id().equals(id);
    }

    public double armorWearMultiplier(Player player) {
        return is(player, "engineer") ? 0.75 : 1.0;
    }

    public double healMultiplier(Player player) {
        return is(player, "medic") ? 1.2 : 1.0;
    }

    public double reviveMultiplier(Player player) {
        return is(player, "medic") ? 1.5 : 1.0;
    }

    public double searchMultiplier(Player player) {
        return is(player, "recon") ? 1.2 : 1.0;
    }

    public double reloadMultiplier(Player player) {
        return sprintUntil.getOrDefault(player.getUniqueId(), 0L) > Bukkit.getCurrentTick() ? 0.7 : 1.0;
    }

    public double speedFactor(Player player) {
        return sprintUntil.getOrDefault(player.getUniqueId(), 0L) > Bukkit.getCurrentTick() ? 1.35 : 1.0;
    }

    // ------------------------------------------------------------------ active skill

    /** Sneak+F (SwapKeyService reads the key), or the skill button in the terminal (on the range, the rack). */
    public void activate(Player player) {
        boolean range = plugin.range().isIn(player);
        RaidPlayer rp = plugin.raids().session(player);
        Raid raid = plugin.raids().raidOf(player);
        if (!range && (rp == null || raid == null || rp.state != RaidPlayer.State.ALIVE)) {
            Notify.hint(player, "技能只能在行动中或靶场里使用");
            return;
        }
        int left = cooldownLeft(player);
        if (left > 0) {
            Notify.error(player, "技能冷却中 " + left + "s");
            return;
        }
        OperatorDef def = of(player);
        switch (def.id()) {
            case "vanguard" -> sprint(player);
            case "medic" -> pulse(player, range ? List.of(player) : mates(player, raid, rp, 8));
            case "recon" -> sonar(player, range ? List.of(player) : squad(raid, rp), range ? rangeTargets(player) : enemies(player, rp));
            case "engineer" -> shield(player);
            default -> {
                return;
            }
        }
        long cooldown = def.cooldownSeconds() * 20L;
        if (range) {
            double scale = plugin.getConfig().getDouble("range.skill-cooldown-multiplier", 0.25);
            rangeCooldownUntil.put(player.getUniqueId(), Bukkit.getCurrentTick() + Math.max(0, Math.round(cooldown * scale)));
        } else {
            cooldownUntil.put(player.getUniqueId(), (long) Bukkit.getCurrentTick() + cooldown);
        }
        Notify.info(player, "<" + def.color() + ">技能 · " + def.skillName());
    }

    /** The player's squad in the raid, alive, here and within {@code radius} blocks (the player too). */
    private List<Player> mates(Player player, Raid raid, RaidPlayer rp, double radius) {
        List<Player> out = new ArrayList<>();
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player p = Bukkit.getPlayer(mate.uuid);
            if (p == null || mate.state != RaidPlayer.State.ALIVE || p.getWorld() != player.getWorld()
                    || p.getLocation().distanceSquared(player.getLocation()) > radius * radius) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /** The player's squad in the raid, online. */
    private List<Player> squad(Raid raid, RaidPlayer rp) {
        List<Player> out = new ArrayList<>();
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player p = Bukkit.getPlayer(mate.uuid);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** The range's standing silhouettes within the sonar's 45 blocks. */
    private List<LivingEntity> rangeTargets(Player player) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity target : plugin.range().targets().standing(player)) {
            if (target.getWorld() == player.getWorld() && target.getLocation().distanceSquared(player.getLocation()) <= 45 * 45) {
                out.add(target);
            }
        }
        return out;
    }

    /**
     * Everything within 45 blocks a raider's sonar marks: soldiers, enemy players, and the stand-ins
     * of disconnected enemies (which show up as the enemy would, or they would give themselves away).
     */
    private List<LivingEntity> enemies(Player player, RaidPlayer rp) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity entity : player.getWorld().getNearbyLivingEntities(player.getLocation(), 45)) {
            if (entity == player || entity.isDead()) {
                continue;
            }
            RaidPlayer standsFor = plugin.raids().standInOwner(entity);
            if (plugin.ai().isAi(entity)
                    || (entity instanceof Player other && plugin.raids().isInRaid(other) && !plugin.raids().sameSquad(player, other))
                    || (standsFor != null && standsFor.squad != rp.squad)) {
                out.add(entity);
            }
        }
        return out;
    }

    private void sprint(Player player) {
        sprintUntil.put(player.getUniqueId(), (long) Bukkit.getCurrentTick() + 160);
        plugin.health().forceSpeedUpdate(player);
        player.getWorld().playSound(player.getLocation(), "craftov:skill.sprint", SoundCategory.PLAYERS, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 12, 0.3, 0.1, 0.3, 0.05);
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.health().forceSpeedUpdate(player), 161L);
    }

    private void pulse(Player player, List<Player> healed) {
        player.getWorld().playSound(player.getLocation(), "craftov:skill.heal", SoundCategory.PLAYERS, 1.2f, 1f);
        for (Player p : healed) {
            plugin.health().heal(p, 30);
            plugin.health().cure(p, Injury.LIGHT_BLEED);
            plugin.health().cure(p, Injury.HEAVY_BLEED);
            p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 14, 0.4, 0.5, 0.4);
            if (p != player) {
                Notify.ok(p, "" + Text.escape(player.getName()) + " 的急救脉冲 +30");
            }
        }
        Location c = player.getLocation();
        for (int i = 0; i < 40; i++) {
            double a = i / 40.0 * Math.PI * 2;
            player.getWorld().spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * 8, c.getY() + 0.3, c.getZ() + Math.sin(a) * 8,
                    1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(90, 220, 120), 1.4f));
        }
    }

    private void sonar(Player player, List<Player> viewers, List<LivingEntity> found) {
        player.getWorld().playSound(player.getLocation(), "craftov:skill.sonar", SoundCategory.PLAYERS, 1.5f, 1f);
        int marked = 0;
        for (LivingEntity entity : found) {
            marked++;
            TextDisplay marker = entity.getWorld().spawn(entity.getLocation().add(0, entity.getHeight() + 0.6, 0), TextDisplay.class, d -> {
                d.text(Text.mm("<danger><b>◆</b>"));
                d.setBillboard(Display.Billboard.CENTER);
                d.setSeeThrough(true);
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.getPersistentDataContainer().set(Keys.ENTITY_ROLE, PersistentDataType.STRING, "marker");
            });
            entity.addPassenger(marker);
            for (Player viewer : viewers) {
                viewer.showEntity(plugin, marker);
            }
            Bukkit.getScheduler().runTaskLater(plugin, marker::remove, 120L);
        }
        Notify.info(player, "<info>声呐：标记了 " + marked + " 个目标");
    }

    private void shield(Player player) {
        Vector dir = player.getLocation().getDirection().setY(0).normalize();
        Location base = player.getLocation().add(dir.clone().multiply(2));
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        BlockData wall = Material.TINTED_GLASS.createBlockData();
        List<Block> placed = new ArrayList<>();
        for (int w = -1; w <= 1; w++) {
            for (int h = 0; h <= 1; h++) {
                Block block = base.clone().add(side.clone().multiply(w)).add(0, h, 0).getBlock();
                if (block.getType().isAir()) {
                    block.setBlockData(wall, false);
                    placed.add(block);
                }
            }
        }
        player.getWorld().playSound(base, "craftov:skill.shield", SoundCategory.PLAYERS, 1.2f, 1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Block block : placed) {
                if (block.getType() == Material.TINTED_GLASS) {
                    block.setType(Material.AIR, false);
                    block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, wall);
                }
            }
        }, 20L * 15);
        if (placed.isEmpty()) {
            Notify.hint(player, "前方空间不足");
        }
    }

    public double maxHp() {
        return HealthService.MAX_HP;
    }
}
