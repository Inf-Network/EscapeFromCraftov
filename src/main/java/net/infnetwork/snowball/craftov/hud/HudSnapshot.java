package net.infnetwork.snowball.craftov.hud;

import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

/**
 * Immutable per-player HUD values computed on the main thread. BetterHud evaluates placeholders on
 * its own threads, so it must only ever read these snapshots.
 */
public record HudSnapshot(
        boolean inRaid, boolean inLobby, boolean hasGun, boolean downed, boolean reloading, boolean extracting,
        int hp, int ammo, int magazine, int reserve, int reload, int remaining, int extract, int helmet, int armor,
        int helmetClass, int armorClass, int downedPct, int skillCooldown, long value, long money,
        String weapon, String fireMode, String ammoType, String time, String map, String tier, String nearest,
        String status, String operator, String skill, String valueShort, String extractLeft
) {
    public static final HudSnapshot EMPTY = new HudSnapshot(false, false, false, false, false, false,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "", "", "", "", "", "", "", "", "", "", "");

    static HudSnapshot of(HudData d, Player p, boolean inLobby) {
        boolean inRaid = d.inRaid(p);
        long value = d.carried(p);
        return new HudSnapshot(inRaid, inLobby, d.gun(p) != null, d.downed(p), d.reloadPercent(p) > 0, d.extractPercent(p) > 0,
                d.hp(p), d.ammo(p), d.magazine(p), d.reserve(p), d.reloadPercent(p), d.remaining(p), d.extractPercent(p),
                d.armorPercent(p, CarryService.HEAD), d.armorPercent(p, CarryService.CHEST),
                d.armorClass(p, CarryService.HEAD), d.armorClass(p, CarryService.CHEST), d.downedPercent(p), d.skillCooldown(p),
                value, d.money(p), d.weaponName(p), d.fireMode(p), d.ammoType(p), d.time(p), d.mapName(p), d.tierName(p),
                d.nearestExtract(p), d.injuries(p), d.operator(p), d.skill(p), Text.moneyShort(value),
                String.format("%.1f", d.extractSecondsLeft(p)));
    }
}
