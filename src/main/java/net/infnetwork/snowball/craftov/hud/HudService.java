package net.infnetwork.snowball.craftov.hud;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla HUD (boss bars) used when BetterHud is not installed, plus effects that work either way:
 * hit markers and the camera overlays (scope, suppression, strobe and flashbang white-outs).
 */
@SuppressWarnings("UnstableApiUsage")
public final class HudService {

    private final CraftovPlugin plugin;
    private final HudData data;
    private final Map<UUID, BossBar> info = new HashMap<>();
    private final Map<UUID, BossBar> status = new HashMap<>();
    private final Map<UUID, HudSnapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean betterHud;
    /**
     * Camera overlays share one client-side helmet, so the strongest wins: a flashbang white-out, a
     * strobe flash, then a scope picture, then the suppression vignette, otherwise the real helmet.
     */
    private final Map<UUID, String> scopes = new HashMap<>();
    private final Map<UUID, Long> suppressedUntil = new HashMap<>();
    private final Map<UUID, Long> dazzledUntil = new HashMap<>();
    private final Map<UUID, Flash> flashed = new HashMap<>();
    /** Players blinded by {@link #flash} whose blindness has not been lifted yet. */
    private final java.util.Set<UUID> flashBlinded = new java.util.HashSet<>();
    private final Map<UUID, String> shownOverlay = new HashMap<>();
    private final Map<UUID, Long> lastShake = new HashMap<>();

    public HudService(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.data = new HudData(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 4L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::snapshot, 20L, 2L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickOverlays, 20L, 1L);
    }

    private void snapshot() {
        if (!betterHud) {
            return;
        }
        java.util.Set<UUID> online = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            try {
                snapshots.put(player.getUniqueId(), HudSnapshot.of(data, player, plugin.lobby().isLobby(player.getWorld()) || plugin.range().isIn(player)));
            } catch (RuntimeException ex) {
                snapshots.put(player.getUniqueId(), HudSnapshot.EMPTY);
            }
        }
        snapshots.keySet().retainAll(online);
    }

    /** Thread-safe: safe to call from BetterHud worker threads. */
    public HudSnapshot snapshotOf(UUID uuid) {
        return snapshots.getOrDefault(uuid, HudSnapshot.EMPTY);
    }

    public HudData data() {
        return data;
    }

    public void setBetterHud(boolean active) {
        this.betterHud = active;
        if (active && !plugin.getConfig().getBoolean("hud.vanilla-bars-with-betterhud", false)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                hide(player);
            }
        }
    }

    public boolean betterHud() {
        return betterHud;
    }

    private boolean vanilla() {
        return !betterHud || plugin.getConfig().getBoolean("hud.vanilla-bars-with-betterhud", false);
    }

    public void onJoin(Player player) {
        if (vanilla()) {
            player.showBossBar(bar(info, player));
        }
    }

    public void onRaidStart(Player player) {
        if (vanilla()) {
            player.showBossBar(bar(info, player));
            player.showBossBar(bar(status, player));
        }
    }

    public void onLobby(Player player) {
        BossBar s = status.get(player.getUniqueId());
        if (s != null) {
            player.hideBossBar(s);
        }
        scopeOverlay(player, null);
        if (vanilla()) {
            player.showBossBar(bar(info, player));
        }
    }

    private void hide(Player player) {
        BossBar a = info.get(player.getUniqueId());
        BossBar b = status.get(player.getUniqueId());
        if (a != null) {
            player.hideBossBar(a);
        }
        if (b != null) {
            player.hideBossBar(b);
        }
    }

    private static BossBar bar(Map<UUID, BossBar> map, Player player) {
        return map.computeIfAbsent(player.getUniqueId(), k -> BossBar.bossBar(Text.mm(""), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));
    }

    private void tick() {
        if (!vanilla()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar infoBar = bar(info, player);
            if (data.inRaid(player)) {
                int extract = data.extractPercent(player);
                if (extract > 0) {
                    infoBar.name(Text.mm("<#5BD16B><b>撤离中</b> <white>" + String.format("%.1f", data.extractSecondsLeft(player)) + "s"));
                    infoBar.progress(extract / 100f);
                    infoBar.color(BossBar.Color.GREEN);
                } else {
                    int total = Math.max(1, plugin.raids().raidOf(player).map.raidSeconds());
                    infoBar.name(Text.mm("<#F2E6C8>" + Text.escape(data.mapName(player)) + " <#8A8F98>" + data.tierName(player)
                            + "  <white>⏱ " + data.time(player) + "  <#F2C94C>◈ " + Text.moneyShort(data.carried(player))
                            + "  <#9AA0A6>撤离 " + Text.escape(data.nearestExtract(player))));
                    infoBar.progress(Math.max(0f, Math.min(1f, data.remaining(player) / (float) total)));
                    infoBar.color(data.remaining(player) < 300 ? BossBar.Color.RED : BossBar.Color.YELLOW);
                }
                BossBar statusBar = bar(status, player);
                StringBuilder sb = new StringBuilder();
                int hp = data.hp(player);
                String hpColor = hp > 60 ? "#5BD16B" : hp > 30 ? "#FFC23D" : "#FF4D4D";
                if (data.downed(player)) {
                    sb.append("<#FF4D4D><b>倒地</b> ").append(data.downedPercent(player)).append('%');
                } else {
                    sb.append('<').append(hpColor).append(">❤ ").append(hp);
                }
                int helmet = data.armorClass(player, CarryService.HEAD);
                int armor = data.armorClass(player, CarryService.CHEST);
                if (helmet > 0) {
                    sb.append("  <#9AA0A6>⛑").append(roman(helmet)).append(' ').append(data.armorPercent(player, CarryService.HEAD)).append('%');
                }
                if (armor > 0) {
                    sb.append("  <#9AA0A6>🛡").append(roman(armor)).append(' ').append(data.armorPercent(player, CarryService.CHEST)).append('%');
                }
                String weapon = data.weaponName(player);
                if (data.gun(player) != null) {
                    int reload = data.reloadPercent(player);
                    sb.append("  <white>").append(Text.escape(weapon)).append(' ')
                            .append(reload > 0 ? "<#FFC23D>换弹 " + reload + "%" : (data.ammo(player) == 0 ? "<#FF4D4D>" : "<white>") + data.ammo(player)
                                    + "<#8A8F98>/" + data.reserve(player) + " " + data.fireMode(player));
                }
                String injuries = data.injuries(player);
                if (!injuries.isEmpty()) {
                    sb.append("  <#FF8A80>").append(injuries);
                }
                sb.append("  <#8A8F98>").append(Text.escape(data.skill(player)));
                statusBar.name(Text.mm(sb.toString()));
                statusBar.progress(Math.max(0f, Math.min(1f, (float) (player.getHealth() / 100.0))));
                statusBar.color(hp > 60 ? BossBar.Color.GREEN : hp > 30 ? BossBar.Color.YELLOW : BossBar.Color.RED);
            } else if (plugin.lobby().isLobby(player.getWorld())) {
                var profile = plugin.profiles().get(player);
                infoBar.name(Text.mm("<#F2C94C>◈ " + Text.money(profile.money) + "  <#9AA0A6>Lv." + profile.level + "  "
                        + plugin.ranks().tierName(profile.rankPoints) + "  <#9AA0A6>战备值 <white>◈" + Text.moneyShort(plugin.carry().carriedValue(player))
                        + "  <#8A8F98>干员 " + plugin.operators().of(player).name()));
                infoBar.progress(1f);
                infoBar.color(BossBar.Color.WHITE);
            }
        }
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            default -> "";
        };
    }

    public void hitMarker(Player shooter, boolean head, boolean kill) {
        if (betterHud) {
            plugin.betterHud().popup(shooter, kill ? "craftov_hit_kill" : head ? "craftov_hit_head" : "craftov_hit");
            return;
        }
        String mark = kill ? "<#FF4D4D><b>✖</b>" : head ? "<#FFC23D>✕" : "<white>✕";
        shooter.sendTitlePart(TitlePart.TIMES, Title.Times.times(Duration.ZERO, Duration.ofMillis(150), Duration.ofMillis(100)));
        shooter.sendTitlePart(TitlePart.SUBTITLE, Text.mm(mark));
        shooter.sendTitlePart(TitlePart.TITLE, Text.mm(""));
    }

    /** Sniper scope: a client-side helmet with a camera overlay, never placed in the real inventory. */
    /** Shows a scope picture (craftov:misc/&lt;overlay&gt;) through a client-only helmet, or removes it when null. */
    public void scopeOverlay(Player player, @org.jspecify.annotations.Nullable String overlay) {
        if (overlay != null) {
            scopes.put(player.getUniqueId(), overlay);
        } else {
            scopes.remove(player.getUniqueId());
        }
        refreshOverlay(player);
    }

    private @org.jspecify.annotations.Nullable String wantedOverlay(UUID id, long now) {
        Flash flash = flashed.get(id);
        if (flash != null && flash.until() > now) {
            double left = flash.until() - now;
            return "flash_" + (int) Math.max(1, Math.min(FLASH_STEPS, Math.ceil(left * FLASH_STEPS / flash.fade())));
        }
        Long dazzled = dazzledUntil.get(id);
        if (dazzled != null && dazzled > now && (now / 2) % 2 == 0) {
            return "strobe";
        }
        String scope = scopes.get(id);
        if (scope != null) {
            return scope;
        }
        Long suppressed = suppressedUntil.get(id);
        return suppressed != null && suppressed > now ? "suppressed" : null;
    }

    private void refreshOverlay(Player player) {
        String want = wantedOverlay(player.getUniqueId(), Bukkit.getCurrentTick());
        String key = want == null ? "" : want;
        if (key.equals(shownOverlay.getOrDefault(player.getUniqueId(), ""))) {
            return;
        }
        if (want == null) {
            shownOverlay.remove(player.getUniqueId());
            restoreHelmet(player);
        } else {
            shownOverlay.put(player.getUniqueId(), want);
            cameraOverlay(player, want);
        }
    }

    private static void cameraOverlay(Player player, String texture) {
        ItemStack overlay = ItemStack.of(Material.PAPER);
        overlay.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD)
                .cameraOverlay(Key.key("craftov", "misc/" + texture)).build());
        player.sendEquipmentChange(player, EquipmentSlot.HEAD, overlay);
    }

    private static void restoreHelmet(Player player) {
        ItemStack real = player.getInventory().getHelmet();
        player.sendEquipmentChange(player, EquipmentSlot.HEAD, real == null ? ItemStack.empty() : real);
    }

    /**
     * A bullet snapped past this player: the screen edges darken for about a second and the view
     * jolts a little. A scope picture, if one is up, stays on top.
     */
    public void suppressed(Player player, org.bukkit.Location from) {
        long now = Bukkit.getCurrentTick();
        suppressedUntil.put(player.getUniqueId(), now + 24);
        refreshOverlay(player);
        Long last = lastShake.get(player.getUniqueId());
        if (last == null || now - last >= 4) {
            lastShake.put(player.getUniqueId(), now);
            java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
            net.infnetwork.snowball.craftov.weapon.Recoil.kick(player, (float) (rng.nextDouble() - 0.5) * 0.9f,
                    (float) (rng.nextDouble() - 0.5) * 0.7f);
        }
    }

    /** Blinded by a strobe light: the screen flashes white every other tick for a moment. */
    public void dazzle(Player player, int ticks) {
        long until = Bukkit.getCurrentTick() + ticks;
        dazzledUntil.merge(player.getUniqueId(), until, Math::max);
        refreshOverlay(player);
    }

    /** Flashbang white-out (misc/flash_1..4, 4 = fully white): when it ends and how many ticks it takes to fade. */
    private record Flash(long until, int fade) {
    }

    private static final int FLASH_STEPS = 4;

    /**
     * Vanilla blends blindness fog in and out over 22 ticks and starts the fade-out that long before the
     * effect runs out, so the effect is given this much longer than the white part and lifted by hand.
     */
    private static final int BLINDNESS_BLEND_TICKS = 23;

    /**
     * Flashbanged: the screen goes fully white, then steps down through fainter overlays over the last
     * half of {@code ticks} (10 to 30 ticks of fade). It covers a scope picture, which comes back after.
     * <p>
     * Camera overlays are only drawn in first person with the HUD shown, so pressing F5 (or F1) would
     * clear a flash completely. While the screen is fully white the player is also blinded
     * (combat.flashbang.blind-while-white): in first person the opaque white hides the fog, and the
     * effect is lifted (tickOverlays) before the white starts to fade, so the fog never shows through.
     */
    public void flash(Player player, int ticks) {
        if (ticks <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = Bukkit.getCurrentTick();
        long until = now + ticks;
        Flash current = flashed.get(id);
        if (current == null || current.until() < until) {
            current = new Flash(until, Math.max(10, Math.min(30, ticks / 2)));
            flashed.put(id, current);
        }
        long white = current.until() - current.fade() - now;
        if (white > 0 && plugin.getConfig().getBoolean("combat.flashbang.blind-while-white", true)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) white + BLINDNESS_BLEND_TICKS,
                    0, false, false, false));
            flashBlinded.add(id);
        }
        refreshOverlay(player);
    }

    /** Leaving the server mid-flash: the blindness that goes with the white-out must not be saved with the player. */
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();
        flashed.remove(id);
        if (flashBlinded.remove(id)) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    /**
     * How often an overlay that is up gets sent again. The client-side helmet only lasts until the
     * server next syncs the real head slot: a helmet losing durability to a headshot, a helmet swap or
     * an inventory refresh puts the real helmet back on the client (a Bukkit setHelmet even sends it
     * twice, once at once and once from the next container sync), so resending only on a change of
     * overlay would leave the screen clear until the next fade step.
     */
    private static final int OVERLAY_RESEND_TICKS = 2;

    private void tickOverlays() {
        if (shownOverlay.isEmpty() && suppressedUntil.isEmpty() && dazzledUntil.isEmpty() && flashed.isEmpty()
                && flashBlinded.isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        java.util.Set<UUID> touched = new java.util.HashSet<>(shownOverlay.keySet());
        touched.addAll(suppressedUntil.keySet());
        touched.addAll(dazzledUntil.keySet());
        touched.addAll(flashed.keySet());
        touched.addAll(flashBlinded);
        suppressedUntil.values().removeIf(until -> until <= now);
        dazzledUntil.values().removeIf(until -> until <= now);
        flashed.values().removeIf(flash -> flash.until() <= now);
        boolean resend = now % OVERLAY_RESEND_TICKS == 0;
        for (UUID id : touched) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                shownOverlay.remove(id);
                lastShake.remove(id);
                flashBlinded.remove(id);
                continue;
            }
            Flash flash = flashed.get(id);
            if ((flash == null || now >= flash.until() - flash.fade()) && flashBlinded.remove(id)) {
                // The white is about to fade (it stays fully white for a quarter of the fade yet).
                player.removePotionEffect(PotionEffectType.BLINDNESS);
            }
            String before = shownOverlay.get(id);
            refreshOverlay(player);
            String shown = shownOverlay.get(id);
            if (resend && shown != null && shown.equals(before)) {
                cameraOverlay(player, shown);
            }
        }
    }

    /** Red arc around the crosshair pointing at where a hit came from (front = top). */
    public void damageFrom(Player victim, org.bukkit.Location source) {
        if (!betterHud || source.getWorld() != victim.getWorld()) {
            return;
        }
        org.bukkit.util.Vector to = source.toVector().subtract(victim.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 0.01) {
            return;
        }
        double yaw = Math.toRadians(victim.getLocation().getYaw());
        double forward = to.getX() * -Math.sin(yaw) + to.getZ() * Math.cos(yaw);
        double right = to.getX() * -Math.cos(yaw) + to.getZ() * -Math.sin(yaw);
        int index = Math.floorMod((int) Math.round(Math.atan2(right, forward) / (Math.PI / 4)), 8);
        plugin.betterHud().popup(victim, "craftov_damage_" + index);
    }
}
