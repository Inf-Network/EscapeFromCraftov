package net.infnetwork.snowball.craftov.operator;

import net.infnetwork.snowball.craftov.CraftovPlugin;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operators and their skills (sneak + F in a raid). */
public final class OperatorService implements Listener {

    public static final Map<String, OperatorDef> OPERATORS = new LinkedHashMap<>();

    static {
        OPERATORS.put("vanguard", new OperatorDef("vanguard", "疾风", "突击", "#FF9F43", "战术冲刺", 40,
                List.of("技能：8 秒内移动速度 +35%，换弹速度 +30%。", "被动：冲刺时更难被敌兵发现。"), "craftov:ui/op_vanguard"));
        OPERATORS.put("medic", new OperatorDef("medic", "白鸮", "支援", "#5BD16B", "急救脉冲", 60,
                List.of("技能：为自己与 8 格内队友回复 30 生命并止血。", "被动：救援速度 +50%，医疗品效果 +20%。"), "craftov:ui/op_medic"));
        OPERATORS.put("recon", new OperatorDef("recon", "回声", "侦察", "#4FA3FF", "声呐脉冲", 50,
                List.of("技能：标记 45 格内所有敌人 6 秒（仅小队可见）。", "被动：搜索速度 +20%。"), "craftov:ui/op_recon"));
        OPERATORS.put("engineer", new OperatorDef("engineer", "铁砧", "工程", "#C8CCD2", "部署掩体", 55,
                List.of("技能：在面前展开一面持续 15 秒的防弹掩体。", "被动：护甲耐久损耗 -25%。"), "craftov:ui/op_engineer"));
    }

    private final CraftovPlugin plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Long> sprintUntil = new HashMap<>();

    public OperatorService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public OperatorDef of(Player player) {
        OperatorDef def = OPERATORS.get(plugin.profiles().get(player).operator);
        return def == null ? OPERATORS.get("vanguard") : def;
    }

    public void select(Player player, String id) {
        if (!OPERATORS.containsKey(id)) {
            return;
        }
        plugin.profiles().get(player).operator = id;
        plugin.profiles().markDirty(player.getUniqueId());
        OperatorDef def = OPERATORS.get(id);
        Text.send(player, "<#9AA0A6>已选择干员 <" + def.color() + ">" + def.name() + " <#8A8F98>(" + def.role() + ")");
    }

    public int cooldownLeft(Player player) {
        long until = cooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        return (int) Math.max(0, (until - Bukkit.getCurrentTick() + 19) / 20);
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSkillKey(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (player.isSneaking()) {
            activate(player);
        }
    }

    public void activate(Player player) {
        RaidPlayer rp = plugin.raids().session(player);
        Raid raid = plugin.raids().raidOf(player);
        if (rp == null || raid == null || rp.state != RaidPlayer.State.ALIVE) {
            player.sendActionBar(Text.mm("<#9AA0A6>技能只能在行动中使用"));
            return;
        }
        int left = cooldownLeft(player);
        if (left > 0) {
            player.sendActionBar(Text.mm("<#FF8A80>技能冷却中 " + left + "s"));
            return;
        }
        OperatorDef def = of(player);
        switch (def.id()) {
            case "vanguard" -> sprint(player);
            case "medic" -> pulse(player, raid, rp);
            case "recon" -> sonar(player, raid, rp);
            case "engineer" -> shield(player);
            default -> {
                return;
            }
        }
        cooldownUntil.put(player.getUniqueId(), (long) Bukkit.getCurrentTick() + def.cooldownSeconds() * 20L);
        player.sendActionBar(Text.mm("<" + def.color() + ">技能 · " + def.skillName()));
    }

    private void sprint(Player player) {
        sprintUntil.put(player.getUniqueId(), (long) Bukkit.getCurrentTick() + 160);
        plugin.health().forceSpeedUpdate(player);
        player.getWorld().playSound(player.getLocation(), "craftov:skill.sprint", SoundCategory.PLAYERS, 1f, 1f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 12, 0.3, 0.1, 0.3, 0.05);
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.health().forceSpeedUpdate(player), 161L);
    }

    private void pulse(Player player, Raid raid, RaidPlayer rp) {
        player.getWorld().playSound(player.getLocation(), "craftov:skill.heal", SoundCategory.PLAYERS, 1.2f, 1f);
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player p = Bukkit.getPlayer(mate.uuid);
            if (p == null || mate.state != RaidPlayer.State.ALIVE || p.getWorld() != player.getWorld()
                    || p.getLocation().distanceSquared(player.getLocation()) > 64) {
                continue;
            }
            plugin.health().heal(p, 30);
            plugin.health().cure(p, Injury.LIGHT_BLEED);
            plugin.health().cure(p, Injury.HEAVY_BLEED);
            p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 14, 0.4, 0.5, 0.4);
            if (p != player) {
                p.sendActionBar(Text.mm("<#5BD16B>" + Text.escape(player.getName()) + " 的急救脉冲 +30"));
            }
        }
        Location c = player.getLocation();
        for (int i = 0; i < 40; i++) {
            double a = i / 40.0 * Math.PI * 2;
            player.getWorld().spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * 8, c.getY() + 0.3, c.getZ() + Math.sin(a) * 8,
                    1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(90, 220, 120), 1.4f));
        }
    }

    private void sonar(Player player, Raid raid, RaidPlayer rp) {
        player.getWorld().playSound(player.getLocation(), "craftov:skill.sonar", SoundCategory.PLAYERS, 1.5f, 1f);
        List<Player> viewers = new ArrayList<>();
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player p = Bukkit.getPlayer(mate.uuid);
            if (p != null) {
                viewers.add(p);
            }
        }
        int marked = 0;
        for (LivingEntity entity : player.getWorld().getNearbyLivingEntities(player.getLocation(), 45)) {
            if (entity == player || entity.isDead()) {
                continue;
            }
            boolean enemy = plugin.ai().isAi(entity)
                    || (entity instanceof Player other && plugin.raids().isInRaid(other) && !plugin.raids().sameSquad(player, other));
            if (!enemy) {
                continue;
            }
            marked++;
            TextDisplay marker = entity.getWorld().spawn(entity.getLocation().add(0, entity.getHeight() + 0.6, 0), TextDisplay.class, d -> {
                d.text(Text.mm("<#FF4D4D><b>◆</b>"));
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
        player.sendActionBar(Text.mm("<#4FA3FF>声呐：标记了 " + marked + " 个目标"));
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
            player.sendActionBar(Text.mm("<#9AA0A6>前方空间不足"));
        }
    }

    public double maxHp() {
        return HealthService.MAX_HP;
    }
}
