package net.infnetwork.snowball.craftov.hud;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.BodyPart;
import net.infnetwork.snowball.craftov.combat.DownedService;
import net.infnetwork.snowball.craftov.combat.HealthService;
import net.infnetwork.snowball.craftov.combat.Injury;
import net.infnetwork.snowball.craftov.gui.Style;
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

import java.util.Map;
import java.util.Set;

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
            return s.opticName() == null ? s.def().name() : s.def().name() + " · " + s.opticName();
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
        return s == null ? 0 : s.gun().magazine();
    }

    public int reserve(Player p) {
        WeaponService.GunState s = gun(p);
        return s == null ? 0 : plugin.weapons().reserve(p, s);
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

    /** The gun is still coming up after a switch (a reload started meanwhile shows instead). */
    public boolean drawing(Player p) {
        WeaponService.GunState s = gun(p);
        return s != null && s.drawing() && !s.reloading();
    }

    public int drawPercent(Player p) {
        WeaponService.GunState s = gun(p);
        return s == null || !drawing(p) ? 0 : (int) Math.round(s.drawProgress() * 100);
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

    /** Short wound list for the vitals panel, e.g. "左腿骨折 胸部出血 止痛52s". */
    public String injuries(Player p) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<BodyPart, Set<Injury>> e : plugin.health().wounds(p).entrySet()) {
            for (Injury injury : e.getValue()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(e.getKey().displayName()).append(injury == Injury.FRACTURE ? "骨折" : injury == Injury.HEAVY_BLEED ? "大出血" : "出血");
            }
        }
        if (plugin.health().painkilled(p)) {
            sb.append(sb.isEmpty() ? "" : " ").append("止痛").append(plugin.health().painkillerSeconds(p)).append('s');
        }
        if (plugin.stamina().exhausted(p)) {
            sb.append(sb.isEmpty() ? "" : " ").append("力竭");
        }
        if (plugin.stamina().overloaded(p)) {
            sb.append(sb.isEmpty() ? "" : " ").append("过载禁跑");
        } else if (plugin.stamina().overweightPenalty(p) > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append("超重");
        }
        return sb.toString();
    }

    public int[] parts(Player p) {
        BodyPart[] all = BodyPart.values();
        int[] out = new int[all.length];
        for (BodyPart part : all) {
            out[part.ordinal()] = plugin.health().partState(p, part);
        }
        return out;
    }

    /** One short line under the ammo counter: reload or draw progress, what to press, or why it is empty. */
    public String weaponHint(Player p) {
        WeaponService.GunState s = gun(p);
        if (s == null) {
            return "";
        }
        if (s.reloading()) {
            double left = s.reloadSecondsLeft();
            return Style.tag(Style.ACCENT) + "换弹中 " + String.format("%.1f", left) + "s";
        }
        if (s.drawing()) {
            return Style.tag(Style.MUTED) + "拔枪中 " + String.format("%.1f", s.drawSecondsLeft()) + "s";
        }
        int reserve = reserve(p);
        int mag = s.gun().magazine();
        if (s.ammo() == 0 && reserve == 0) {
            return Style.tag(Style.DANGER) + "无弹药";
        }
        if (s.ammo() == 0) {
            return Style.tag(Style.DANGER) + "弹匣空 <white>[Q]" + Style.tag(Style.DANGER) + " 换弹";
        }
        if (s.ammo() <= Math.max(1, mag * 0.3) && reserve > 0) {
            return Style.tag(Style.ACCENT) + "弹药不足 <white>[Q]" + Style.tag(Style.ACCENT) + " 换弹";
        }
        if (plugin.weapons().isAiming(p)) {
            return Style.tag(Style.OK) + "瞄准中";
        }
        return "";
    }

    public int stamina(Player p) {
        return (int) Math.round(plugin.stamina().stamina(p));
    }

    public boolean usingMed(Player p) {
        return plugin.health().medUse(p) != null;
    }

    public String medName(Player p) {
        HealthService.MedUse use = plugin.health().medUse(p);
        return use == null ? "" : "正在使用 " + use.def().name();
    }

    public String medLeft(Player p) {
        HealthService.MedUse use = plugin.health().medUse(p);
        return use == null ? "" : String.format("%.1f", use.secondsLeft(org.bukkit.Bukkit.getCurrentTick()));
    }

    public int medPercent(Player p) {
        HealthService.MedUse use = plugin.health().medUse(p);
        return use == null ? 0 : (int) Math.round(use.progress(org.bukkit.Bukkit.getCurrentTick()) * 100);
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

    /** Gear value (战备值), the melee weapon left out: what is at stake in the raid. */
    public long gearValue(Player p) {
        return plugin.carry().gearValue(p);
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

    /** What a downed player should do: wait for a squadmate, or use their own injector when none is up. */
    public String downedHint(Player p) {
        if (!plugin.downed().isDowned(p)) {
            return "";
        }
        return plugin.downed().mateCanRevive(p) ? "等待队友救援" : "使用自救注射器";
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

    /** 0 healthy, 1 hurt (60 or less), 2 critical (30 or less): the colour of the HP number. */
    public int hpState(Player p) {
        int hp = hp(p);
        return hp > 60 ? 0 : hp > 30 ? 1 : 2;
    }

    /** 0 more than five minutes left, 1 five minutes or less (amber), 2 a minute or less (red). */
    public int timeState(Player p) {
        int left = remaining(p);
        return !inRaid(p) || left > 300 ? 0 : left > 60 ? 1 : 2;
    }

    /** The wound icons of the vitals panel, as HudExtra bits. */
    public int injuryFlags(Player p) {
        int flags = 0;
        for (Set<Injury> set : plugin.health().wounds(p).values()) {
            for (Injury injury : set) {
                flags |= switch (injury) {
                    case LIGHT_BLEED -> HudExtra.BLEED;
                    case HEAVY_BLEED -> HudExtra.BLEED | HudExtra.HEAVY;
                    case FRACTURE -> HudExtra.FRACTURE;
                };
            }
        }
        if (plugin.health().painkilled(p)) {
            flags |= HudExtra.PAIN;
        }
        if (plugin.stamina().overloaded(p)) {
            flags |= HudExtra.OVERLOADED;
        }
        return flags;
    }

    /** One line per squadmate (two at most): name, health or state, in the state's colour. */
    public String[] mates(Player p) {
        String[] out = {"", ""};
        Raid raid = plugin.raids().raidOf(p);
        RaidPlayer self = raid == null ? null : raid.player(p.getUniqueId());
        if (self == null) {
            return out;
        }
        int i = 0;
        for (RaidPlayer mate : raid.squadOf(self)) {
            if (mate == self || i >= out.length) {
                continue;
            }
            Player online = org.bukkit.Bukkit.getPlayer(mate.uuid);
            String name = Text.escape(mate.name);
            out[i++] = switch (mate.state) {
                case ALIVE -> Style.tag(Style.TEXT) + name + " " + Style.tag(online != null && online.getHealth() <= 30 ? Style.DANGER : Style.OK)
                        + "❤" + (online == null ? 0 : (int) Math.ceil(online.getHealth()));
                case DOWNED -> Style.tag(Style.DANGER) + name + " 倒地";
                case DEAD -> Style.tag(Style.DIM) + name + " 阵亡";
                case EXTRACTED -> Style.tag(Style.INFO) + name + " 已撤离";
                case DISCONNECTED -> Style.tag(Style.ACCENT) + name + " 断线";
                default -> Style.tag(Style.MUTED) + name;
            };
        }
        return out;
    }

    /**
     * What right mouse does at what the crosshair is on, within reach: search a container or a body,
     * revive a downed squadmate, use a lobby station. Empty when there is nothing to do.
     */
    public String prompt(Player p) {
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || plugin.downed().isDowned(p)) {
            return "";
        }
        org.bukkit.util.RayTraceResult hit = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(),
                3.0, 0.1, e -> e != p);
        if (hit == null || hit.getHitEntity() == null) {
            return "";
        }
        org.bukkit.entity.Entity entity = hit.getHitEntity();
        String key = Style.tag(Style.ACCENT) + "[右键] ";
        String role = entity.getPersistentDataContainer().get(net.infnetwork.snowball.craftov.item.Keys.ENTITY_ROLE,
                org.bukkit.persistence.PersistentDataType.STRING);
        if ("container".equals(role)) {
            Raid raid = plugin.raids().raidOf(p);
            String ref = entity.getPersistentDataContainer().get(net.infnetwork.snowball.craftov.item.Keys.ENTITY_REF,
                    org.bukkit.persistence.PersistentDataType.STRING);
            net.infnetwork.snowball.craftov.raid.LootContainer container = raid == null || ref == null ? null : raid.containers.get(ref);
            if (container == null) {
                return "";
            }
            boolean searched = container.generated() && container.hiddenCount() == 0;
            return key + Style.tag(Style.TEXT) + (searched ? "打开 " : "搜索 ") + Text.escape(container.title);
        }
        String station = entity.getPersistentDataContainer().get(net.infnetwork.snowball.craftov.item.Keys.key("station"),
                org.bukkit.persistence.PersistentDataType.STRING);
        if (station != null) {
            return key + Style.tag(Style.TEXT) + "使用";
        }
        if (entity instanceof Player other && plugin.downed().isDowned(other)) {
            Raid raid = plugin.raids().raidOf(p);
            RaidPlayer self = raid == null ? null : raid.player(p.getUniqueId());
            RaidPlayer them = raid == null ? null : raid.player(other.getUniqueId());
            if (self != null && them != null && self.squad == them.squad) {
                return Style.tag(Style.ACCENT) + "[按住右键] " + Style.tag(Style.TEXT) + "救援 " + Text.escape(other.getName());
            }
        }
        return "";
    }
}
