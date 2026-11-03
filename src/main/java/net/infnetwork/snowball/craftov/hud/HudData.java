package net.infnetwork.snowball.craftov.hud;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.DownedService;
import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.raid.ExtractPoint;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import net.infnetwork.snowball.craftov.weapon.WeaponService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Read-only view of everything the HUD shows, shared by the vanilla HUD and BetterHud. */
public final class HudData {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private final CraftovPlugin plugin;

    public HudData(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean inRaid(Player p) {
        return plugin.raids().isInRaid(p);
    }

    public int hp(Player p) {
        return (int) Math.ceil(p.getHealth());
    }

    public WeaponService.@Nullable GunState gun(Player p) {
        return plugin.weapons().state(p);
    }

    public String weaponName(Player p) {
        WeaponService.GunState s = gun(p);
        if (s != null) {
            return s.def().name();
        }
        ItemDef def = plugin.items().defOf(p.getInventory().getItemInMainHand());
        return def == null ? "" : def.name();
    }

    public int ammo(Player p) {
        WeaponService.GunState s = gun(p);
        return s == null ? 0 : s.ammo();
    }

    public int magazine(Player p) {
        WeaponService.GunState s = gun(p);
        return s == null ? 0 : s.def().gun().magazine();
    }

    public int reserve(Player p) {
        WeaponService.GunState s = gun(p);
        return s == null ? 0 : plugin.weapons().reserve(p, s.def().gun(), null);
    }

    public String fireMode(Player p) {
        WeaponService.GunState s = gun(p);
        return s == null ? "" : s.fireMode().displayName();
    }

    public String ammoType(Player p) {
        WeaponService.GunState s = gun(p);
        ItemDef def = s == null ? null : plugin.registry().get(s.ammoType());
        return def == null ? "" : def.name();
    }

    public int reloadPercent(Player p) {
        WeaponService.GunState s = gun(p);
        return s == null || !s.reloading() ? 0 : (int) Math.round(s.reloadProgress() * 100);
    }

    public int armorPercent(Player p, int slot) {
        ItemStack stack = p.getInventory().getItem(slot);
        ItemDef def = plugin.items().defOf(stack);
        if (def == null || !def.isArmor() || def.durability() <= 0) {
            return 0;
        }
        return (int) Math.round(ItemFactory.dura(stack, def.durability()) / def.durability() * 100);
    }

    public int armorClass(Player p, int slot) {
        ItemDef def = plugin.items().defOf(p.getInventory().getItem(slot));
        return def == null || !def.isArmor() ? 0 : def.armorClass();
    }

    public String injuries(Player p) {
        StringBuilder sb = new StringBuilder();
        for (Injury injury : plugin.health().injuries(p)) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(injury.displayName());
        }
        if (plugin.health().painkilled(p)) {
            sb.append(sb.isEmpty() ? "" : " ").append("止痛");
        }
        return sb.toString();
    }

    public String time(Player p) {
        Raid raid = plugin.raids().raidOf(p);
        return raid == null ? "--:--" : Text.duration(raid.remainingSeconds());
    }

    public int remaining(Player p) {
        Raid raid = plugin.raids().raidOf(p);
        return raid == null ? 0 : raid.remainingSeconds();
    }

    public String mapName(Player p) {
        Raid raid = plugin.raids().raidOf(p);
        return raid == null ? "" : raid.map.name();
    }

    public String tierName(Player p) {
        Raid raid = plugin.raids().raidOf(p);
        return raid == null ? "" : raid.tier.name();
    }

    public long carried(Player p) {
        return plugin.carry().carriedValue(p);
    }

    public int extractPercent(Player p) {
        RaidPlayer rp = plugin.raids().session(p);
        Raid raid = plugin.raids().raidOf(p);
        if (rp == null || raid == null || rp.extracting == null) {
            return 0;
        }
        for (ExtractPoint point : raid.extracts) {
            if (point.def.id().equals(rp.extracting)) {
                return (int) Math.min(100, rp.extractTicks * 100L / Math.max(1, point.def.seconds() * 20L));
            }
        }
        return 0;
    }

    public double extractSecondsLeft(Player p) {
        RaidPlayer rp = plugin.raids().session(p);
        Raid raid = plugin.raids().raidOf(p);
        if (rp == null || raid == null || rp.extracting == null) {
            return 0;
        }
        for (ExtractPoint point : raid.extracts) {
            if (point.def.id().equals(rp.extracting)) {
                return Math.max(0, (point.def.seconds() * 20 - rp.extractTicks) / 20.0);
            }
        }
        return 0;
    }

    /** "北门检查站 ↗ 85m" for the nearest usable extract. */
    public String nearestExtract(Player p) {
        Raid raid = plugin.raids().raidOf(p);
        if (raid == null || p.getWorld() != raid.world) {
            return "";
        }
        ExtractPoint best = null;
        double bestD = Double.MAX_VALUE;
        for (ExtractPoint point : raid.extracts) {
            if (!point.available || !plugin.raids().extractOpenByTime(raid, point)) {
                continue;
            }
            double d = point.center.distanceSquared(p.getLocation());
            if (d < bestD) {
                bestD = d;
                best = point;
            }
        }
        if (best == null) {
            return "暂无可用撤离点";
        }
        return best.def.name() + " " + arrow(p, best.center) + " " + (int) Math.sqrt(bestD) + "m";
    }

    public String arrow(Player p, Location target) {
        Location from = p.getLocation();
        if (from.getWorld() != target.getWorld()) {
            return "";
        }
        double angle = Math.toDegrees(Math.atan2(-(target.getX() - from.getX()), target.getZ() - from.getZ()));
        double rel = ((angle - from.getYaw()) % 360 + 360 + 22.5) % 360;
        return ARROWS[(int) (rel / 45) % 8];
    }

    public int downedPercent(Player p) {
        DownedService.Downed d = plugin.downed().state(p);
        return d == null ? 0 : (int) Math.round(d.fraction() * 100);
    }

    public boolean downed(Player p) {
        return plugin.downed().isDowned(p);
    }

    public String operator(Player p) {
        return plugin.operators().of(p).name();
    }

    public String skill(Player p) {
        int cd = plugin.operators().cooldownLeft(p);
        return plugin.operators().of(p).skillName() + (cd > 0 ? " " + cd + "s" : " 就绪");
    }

    public int skillCooldown(Player p) {
        return plugin.operators().cooldownLeft(p);
    }

    public long money(Player p) {
        return plugin.profiles().get(p).money;
    }

    public int helmetSlot() {
        return CarryService.HEAD;
    }

    public int armorSlot() {
        return CarryService.CHEST;
    }
}
