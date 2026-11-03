package net.infnetwork.snowball.craftov.hud;

import kr.toxicity.hud.api.BetterHud;
import kr.toxicity.hud.api.BetterHudAPI;
import kr.toxicity.hud.api.adapter.LocationWrapper;
import kr.toxicity.hud.api.adapter.WorldWrapper;
import kr.toxicity.hud.api.manager.PlaceholderManager;
import kr.toxicity.hud.api.placeholder.HudPlaceholder;
import kr.toxicity.hud.api.player.HudPlayer;
import kr.toxicity.hud.api.player.PointedLocation;
import kr.toxicity.hud.api.player.PointedLocationSource;
import kr.toxicity.hud.api.popup.Popup;
import kr.toxicity.hud.api.update.UpdateEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.raid.ExtractPoint;
import net.infnetwork.snowball.craftov.raid.Raid;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Feeds Craftov state into BetterHud placeholders, popups and the compass. */
public final class BetterHudBridge implements BetterHudHook {

    private final CraftovPlugin plugin;
    private final HudData data;

    public BetterHudBridge(CraftovPlugin plugin, HudData data) {
        this.plugin = plugin;
        this.data = data;
    }

    public void register() {
        BetterHud hud = BetterHudAPI.inst();
        PlaceholderManager pm = hud.getPlaceholderManager();
        number(pm, "craftov_hp", HudSnapshot::hp);
        number(pm, "craftov_hp_pct", HudSnapshot::hp);
        number(pm, "craftov_ammo", HudSnapshot::ammo);
        number(pm, "craftov_mag", HudSnapshot::magazine);
        number(pm, "craftov_reserve", HudSnapshot::reserve);
        number(pm, "craftov_reload", HudSnapshot::reload);
        number(pm, "craftov_remaining", HudSnapshot::remaining);
        number(pm, "craftov_extract", HudSnapshot::extract);
        number(pm, "craftov_helmet", HudSnapshot::helmet);
        number(pm, "craftov_armor", HudSnapshot::armor);
        number(pm, "craftov_helmet_class", HudSnapshot::helmetClass);
        number(pm, "craftov_armor_class", HudSnapshot::armorClass);
        number(pm, "craftov_downed", HudSnapshot::downedPct);
        number(pm, "craftov_skill_cd", HudSnapshot::skillCooldown);
        number(pm, "craftov_value", HudSnapshot::value);
        number(pm, "craftov_money", HudSnapshot::money);
        string(pm, "craftov_weapon", HudSnapshot::weapon);
        string(pm, "craftov_firemode", HudSnapshot::fireMode);
        string(pm, "craftov_ammo_type", HudSnapshot::ammoType);
        string(pm, "craftov_time", HudSnapshot::time);
        string(pm, "craftov_map", HudSnapshot::map);
        string(pm, "craftov_tier", HudSnapshot::tier);
        string(pm, "craftov_nearest", HudSnapshot::nearest);
        string(pm, "craftov_status", HudSnapshot::status);
        string(pm, "craftov_operator", HudSnapshot::operator);
        string(pm, "craftov_skill", HudSnapshot::skill);
        string(pm, "craftov_value_short", HudSnapshot::valueShort);
        string(pm, "craftov_extract_left", HudSnapshot::extractLeft);
        bool(pm, "craftov_in_raid", HudSnapshot::inRaid);
        bool(pm, "craftov_in_lobby", HudSnapshot::inLobby);
        bool(pm, "craftov_has_gun", HudSnapshot::hasGun);
        bool(pm, "craftov_is_downed", HudSnapshot::downed);
        bool(pm, "craftov_reloading", HudSnapshot::reloading);
        bool(pm, "craftov_extracting", HudSnapshot::extracting);
        bool(pm, "craftov_has_status", s -> !s.status().isEmpty());
        hud.getPlayerManager().addLocationProvider(this::extracts);
    }

    private final java.util.Map<java.util.UUID, List<PointedLocation>> pointers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Main thread: refresh compass markers for every player. */
    public void refreshPointers() {
        java.util.Set<java.util.UUID> online = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            pointers.put(player.getUniqueId(), List.copyOf(buildExtracts(player)));
        }
        pointers.keySet().retainAll(online);
    }

    private List<PointedLocation> extracts(HudPlayer hudPlayer) {
        return pointers.getOrDefault(hudPlayer.uuid(), List.of());
    }

    private List<PointedLocation> buildExtracts(Player player) {
        List<PointedLocation> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        Raid raid = plugin.raids().raidOf(player);
        if (raid == null || !plugin.raids().isInRaid(player) || !player.getWorld().equals(raid.world)) {
            return out;
        }
        for (ExtractPoint point : raid.extracts) {
            if (!point.available) {
                continue;
            }
            boolean open = plugin.raids().extractOpenByTime(raid, point);
            out.add(new PointedLocation(PointedLocationSource.INTERNAL, "craftov_" + point.def.id(),
                    open ? "extract_open" : "extract_closed",
                    new LocationWrapper(new WorldWrapper(raid.world.getName()), point.center.getX(), point.center.getY(),
                            point.center.getZ(), 0f, 0f)));
        }
        return out;
    }

    private void number(PlaceholderManager pm, String name, Function<HudSnapshot, Number> fn) {
        pm.getNumberContainer().addPlaceholder(name, HudPlaceholder.of((args, event) -> hp -> fn.apply(plugin.hud().snapshotOf(hp.uuid()))));
    }

    private void string(PlaceholderManager pm, String name, Function<HudSnapshot, String> fn) {
        pm.getStringContainer().addPlaceholder(name, HudPlaceholder.of((args, event) -> hp -> fn.apply(plugin.hud().snapshotOf(hp.uuid()))));
    }

    private void bool(PlaceholderManager pm, String name, Function<HudSnapshot, Boolean> fn) {
        pm.getBooleanContainer().addPlaceholder(name, HudPlaceholder.of((args, event) -> hp -> fn.apply(plugin.hud().snapshotOf(hp.uuid()))));
    }

    @Override
    public boolean active() {
        return true;
    }

    @Override
    public void popup(Player player, String popupName) {
        try {
            BetterHud hud = BetterHudAPI.inst();
            Popup popup = hud.getPopupManager().getPopup(popupName);
            HudPlayer hudPlayer = hud.getPlayerManager().getHudPlayer(player.getUniqueId());
            if (popup != null && hudPlayer != null) {
                popup.show(UpdateEvent.EMPTY, hudPlayer);
            }
        } catch (RuntimeException ignored) {
            // A HUD hiccup must never break combat.
        }
    }
}
