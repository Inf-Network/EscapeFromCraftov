package net.infnetwork.snowball.craftov.hud;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.DamageService;
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
     * strobe flash, a scope picture, the red flash of a hit (6 ticks), the blood edge of low health
     * (three steps under 35, 24 and 12 HP), then the suppression vignette, otherwise the real helmet.
     * Camera overlays are drawn at every graphics setting; the world-border tint of HealthService is
     * only a supplement to the blood edge.
     */
    private final Map<UUID, String> scopes = new HashMap<>();
    private final Map<UUID, Long> suppressedUntil = new HashMap<>();
    private final Map<UUID, Long> hitUntil = new HashMap<>();
    private static final int HIT_TICKS = 6;
    /** Health below which the blood edge shows, one overlay step each (misc/blood_1..3). */
    private static final double[] BLOOD_STEPS = {35, 24, 12};
    private final Map<UUID, Long> dazzledUntil = new HashMap<>();
    private final Map<UUID, Flash> flashed = new HashMap<>();
    /** Players blinded by {@link #flash} whose blindness has not been lifted yet. */
    private final java.util.Set<UUID> flashBlinded = new java.util.HashSet<>();
    private final Map<UUID, String> shownOverlay = new HashMap<>();
    private final Map<UUID, Long> lastShake = new HashMap<>();

    /** A line shown until a tick: the alert banner (BetterHud only). */
    private record Timed(String text, long until) {
    }

    private final Map<UUID, Timed> alerts = new HashMap<>();

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
                boolean inLobby = plugin.lobby().isLobby(player.getWorld()) || plugin.range().isIn(player);
                snapshots.put(player.getUniqueId(), HudSnapshot.of(data, player, inLobby, extra(player, inLobby)));
            } catch (RuntimeException ex) {
                snapshots.put(player.getUniqueId(), HudSnapshot.EMPTY);
            }
        }
        snapshots.keySet().retainAll(online);
        alerts.keySet().retainAll(online);
    }

    private HudExtra extra(Player player, boolean inLobby) {
        long now = Bukkit.getCurrentTick();
        Timed alert = alerts.get(player.getUniqueId());
        if (alert != null && alert.until() <= now) {
            alerts.remove(player.getUniqueId());
            alert = null;
        }
        String[] feed = data.feed(player);
        boolean inRaid = data.inRaid(player);
        var gun = data.gun(player);
        var objective = plugin.tutorial().objective(player);
        // The objective's own marker is on the compass outside raids and on the training course
        // (BetterHudBridge); a raid's compass shows its extracts instead.
        var raid = plugin.raids().raidOf(player);
        boolean marked = objective != null && objective.target() != null && (!inRaid || raid != null && raid.training());
        return new HudExtra(data.hpState(player), data.timeState(player), inRaid ? data.injuryFlags(player) : 0,
                gun != null && gun.ammo() == 0, inRaid ? data.mates(player) : new String[]{"", ""}, feed,
                alert == null ? "" : alert.text(), inRaid || inLobby ? data.prompt(player) : "",
                data.objective(player, objective), marked);
    }

    /**
     * The alert banner under the compass (raid clock warnings, extracts opening, random events,
     * intel): one line for {@code ticks}, with its sound. Without BetterHud it goes to chat. The text
     * is MiniMessage with only {@code <#RRGGBB>} and named colours, which BetterHud reads as well.
     */
    public void alert(Player player, String text, int ticks, net.infnetwork.snowball.craftov.gui.@org.jspecify.annotations.Nullable UiSound sound) {
        if (sound != null) {
            sound.play(player);
        }
        if (!betterHud) {
            player.sendMessage(Text.mm(text));
            return;
        }
        alerts.put(player.getUniqueId(), new Timed(net.infnetwork.snowball.craftov.gui.Style.hex(text), Bukkit.getCurrentTick() + ticks));
    }

    /** A plain kill-feed line (squad news; KillFeedService#note): top right, chat without BetterHud. */
    public void feed(Player player, String text) {
        plugin.killFeed().note(player, text);
    }

    /** What the HUD shows besides the panels, for `cv hud` and the bots (they cannot see BetterHud). */
    public String describe(Player player) {
        HudSnapshot s = snapshotOf(player.getUniqueId());
        HudExtra e = s.extra();
        return "alert=" + Text.plain(Text.mm(e.alert())) + " | feed=" + String.join(" / ", java.util.Arrays.stream(e.feed())
                .filter(line -> !line.isEmpty()).map(line -> Text.plain(Text.mm(line))).toList())
                + " | prompt=" + Text.plain(Text.mm(e.prompt())) + " | objective=" + Text.plain(Text.mm(e.objective()))
                + (e.pointer() ? " (compass)" : "") + " | value=" + s.value() + " | time=" + s.time() + "@" + e.timeState()
                + " | hp=" + s.hp() + "@" + e.hpState() + " | wounds=" + e.injuryFlags()
                + " | mates=" + String.join(" / ", java.util.Arrays.stream(e.mates()).filter(m -> !m.isEmpty())
                .map(m -> Text.plain(Text.mm(m))).toList())
                + " | feedlog=" + String.join(" / ", plugin.killFeed().allLines(player).stream()
                .map(line -> Text.plain(Text.mm(line))).toList());
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
        clearCameraEffects(player);
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
                    infoBar.name(Text.mm("<ok><b>撤离中</b> <white>" + String.format("%.1f", data.extractSecondsLeft(player)) + "s"));
                    infoBar.progress(extract / 100f);
                    infoBar.color(BossBar.Color.GREEN);
                } else {
                    int total = Math.max(1, plugin.raids().raidOf(player).map.raidSeconds());
                    infoBar.name(Text.mm("<text>" + Text.escape(data.mapName(player)) + " <muted>" + data.tierName(player)
                            + "  <white>⏱ " + data.time(player) + "  <accent>◈ " + Text.moneyShort(data.gearValue(player))
                            + "  <muted>撤离 " + Text.escape(data.nearestExtract(player))));
                    infoBar.progress(Math.max(0f, Math.min(1f, data.remaining(player) / (float) total)));
                    infoBar.color(data.remaining(player) < 300 ? BossBar.Color.RED : BossBar.Color.YELLOW);
                }
                BossBar statusBar = bar(status, player);
                StringBuilder sb = new StringBuilder();
                int hp = data.hp(player);
                String hpColor = hp > 60 ? "ok" : hp > 30 ? "accent" : "danger";
                if (data.downed(player)) {
                    sb.append("<danger><b>倒地</b> ").append(data.downedPercent(player)).append('%');
                } else {
                    sb.append('<').append(hpColor).append(">❤ ").append(hp);
                }
                int helmet = data.armorClass(player, CarryService.HEAD);
                int armor = data.armorClass(player, CarryService.CHEST);
                if (helmet > 0) {
                    sb.append("  <muted>⛑").append(roman(helmet)).append(' ').append(data.armorPercent(player, CarryService.HEAD)).append('%');
                }
                if (armor > 0) {
                    sb.append("  <muted>🛡").append(roman(armor)).append(' ').append(data.armorPercent(player, CarryService.CHEST)).append('%');
                }
                String weapon = data.weaponName(player);
                if (data.gun(player) != null) {
                    int reload = data.reloadPercent(player);
                    sb.append("  <white>").append(Text.escape(weapon)).append(' ')
                            .append(reload > 0 ? "<accent>换弹 " + reload + "%"
                                    : data.drawing(player) ? "<text>拔枪 " + data.drawPercent(player) + "%"
                                    : (data.ammo(player) == 0 ? "<danger>" : "<white>") + data.ammo(player)
                                    + "<muted>/" + data.reserve(player) + " " + data.fireMode(player));
                }
                String injuries = data.injuries(player);
                if (!injuries.isEmpty()) {
                    sb.append("  <danger>").append(injuries);
                }
                sb.append("  <muted>").append(Text.escape(data.skill(player)));
                statusBar.name(Text.mm(sb.toString()));
                statusBar.progress(Math.max(0f, Math.min(1f, (float) (player.getHealth() / 100.0))));
                statusBar.color(hp > 60 ? BossBar.Color.GREEN : hp > 30 ? BossBar.Color.YELLOW : BossBar.Color.RED);
            } else if (plugin.lobby().isLobby(player.getWorld())) {
                var profile = plugin.profiles().get(player);
                infoBar.name(Text.mm("<accent>◈ " + Text.money(profile.money) + "  <muted>Lv." + profile.level + "  "
                        + plugin.ranks().tierName(profile.rankPoints) + "  <muted>战备值 <white>◈" + Text.moneyShort(plugin.carry().gearValue(player))
                        + "  <muted>干员 " + plugin.operators().of(player).name()
                        + objectiveLine(player)));
                infoBar.progress(1f);
                infoBar.color(BossBar.Color.WHITE);
            }
        }
    }

    /** The guide's goal after the vanilla lobby bar, for players without BetterHud. */
    private String objectiveLine(Player player) {
        String line = data.objective(player, plugin.tutorial().objective(player));
        return line.isEmpty() ? "" : "  " + line;
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

    /**
     * The marker at the crosshair for one hit: white, yellow for the head, an orange ring for a
     * knock-down and a bold red cross for a kill.
     */
    public void hitMarker(Player shooter, boolean head, DamageService.Outcome outcome) {
        if (betterHud) {
            plugin.betterHud().popup(shooter, switch (outcome) {
                case KILLED -> "craftov_hit_kill";
                case DOWNED -> "craftov_hit_knock";
                case HIT -> head ? "craftov_hit_head" : "craftov_hit";
            });
            return;
        }
        String mark = switch (outcome) {
            case KILLED -> "<danger><b>✖</b>";
            case DOWNED -> "<accent><b>⊗</b>";
            case HIT -> head ? "<accent>✕" : "<white>✕";
        };
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

    /** The red flash of being hit (a shot or a cut): the screen rim goes red for a few ticks. */
    public void hurt(Player player) {
        hitUntil.put(player.getUniqueId(), (long) Bukkit.getCurrentTick() + HIT_TICKS);
        refreshOverlay(player);
    }

    /** 0 for none, else the blood edge step for this player's health (raids only). */
    private int bloodLevel(@org.jspecify.annotations.Nullable Player player) {
        if (player == null || player.isDead() || !data.inRaid(player)) {
            return 0;
        }
        double hp = player.getHealth();
        int level = 0;
        for (double step : BLOOD_STEPS) {
            if (hp < step) {
                level++;
            }
        }
        return level;
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
        Long hit = hitUntil.get(id);
        if (hit != null && hit > now) {
            return "hit";
        }
        int blood = bloodLevel(Bukkit.getPlayer(id));
        if (blood > 0) {
            return "blood_" + blood;
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

    /**
     * Puts a camera overlay on the player's own client through the head slot. The network handler of
     * their own view (Nms.SelfView) then dresses every later update of their head slot the same way
     * until it is taken off, so a headshot that damages the helmet, a helmet swap or an inventory
     * resync shows the new helmet with the picture still up instead of clearing it for a moment.
     */
    private void cameraOverlay(Player player, String texture) {
        net.infnetwork.snowball.craftov.util.Nms.SelfView view = plugin.fov().selfView(player);
        if (view != null) {
            net.infnetwork.snowball.craftov.util.Nms.selfHelmet(view, real -> withOverlay(real, texture));
        }
        player.sendEquipmentChange(player, EquipmentSlot.HEAD, withOverlay(player.getInventory().getHelmet(), texture));
    }

    /**
     * The client-only head item that carries a camera overlay: a copy of the real helmet with the
     * overlay added, so the helmet slot (the blood edge stays up for as long as health is low,
     * inventory open or not) and the third-person view still show the helmet; with no helmet it is an
     * invisible item without a tooltip. Also called on the network thread, on a copy.
     */
    private static ItemStack withOverlay(@org.jspecify.annotations.Nullable ItemStack real, String texture) {
        ItemStack overlay;
        Equippable.Builder equippable;
        if (real != null && !real.isEmpty()) {
            overlay = real.clone();
            Equippable worn = real.getData(DataComponentTypes.EQUIPPABLE);
            equippable = worn != null && worn.slot() == EquipmentSlot.HEAD ? worn.toBuilder() : Equippable.equippable(EquipmentSlot.HEAD);
        } else {
            overlay = ItemStack.of(Material.PAPER);
            overlay.setData(DataComponentTypes.ITEM_MODEL, Key.key("craftov", "ui/blank"));
            overlay.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                    io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
            equippable = Equippable.equippable(EquipmentSlot.HEAD);
        }
        overlay.setData(DataComponentTypes.EQUIPPABLE, equippable.cameraOverlay(Key.key("craftov", "misc/" + texture)).build());
        return overlay;
    }

    private void restoreHelmet(Player player) {
        net.infnetwork.snowball.craftov.util.Nms.SelfView view = plugin.fov().selfView(player);
        if (view != null) {
            net.infnetwork.snowball.craftov.util.Nms.selfHelmet(view, null);
        }
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
        endFlash(player);
    }

    /** Drops a flashbang white-out at once, with the blindness that goes with it. */
    private void endFlash(Player player) {
        UUID id = player.getUniqueId();
        flashed.remove(id);
        if (flashBlinded.remove(id)) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    /**
     * Takes every raid camera effect off at once: a flashbang white-out and its blindness, a strobe,
     * the suppression vignette, the red flash of a hit and a scope picture; the real helmet goes back
     * on the client. For going back to the lobby and for dying: a white-out used to fade out over
     * the lobby for up to four seconds after an extract, a voided raid or /cv lobby, and over the
     * death camera and the kill replay (blind too) after dying mid-flash. Spectators cannot be
     * flashed, so the squadmate camera after a death stays clear.
     */
    public void clearCameraEffects(Player player) {
        UUID id = player.getUniqueId();
        endFlash(player);
        dazzledUntil.remove(id);
        suppressedUntil.remove(id);
        hitUntil.remove(id);
        scopes.remove(id);
        refreshOverlay(player);
    }

    /**
     * How often an overlay that is up gets sent again when the server's internals cannot be reached
     * (see {@link #cameraOverlay}; otherwise every update of the head slot already carries it, and it
     * is resent only once a second as a precaution). The client-side helmet only lasts until the
     * server next syncs the real head slot: a helmet losing durability to a headshot, a helmet swap or
     * an inventory refresh puts the real helmet back on the client (a Bukkit setHelmet even sends it
     * twice, once at once and once from the next container sync), so resending only on a change of
     * overlay would leave the screen clear until the next fade step.
     */
    private static final int OVERLAY_RESEND_TICKS = 2;
    private static final int OVERLAY_RESEND_TICKS_HOOKED = 20;

    private void tickOverlays() {
        long now = Bukkit.getCurrentTick();
        java.util.Set<UUID> touched = new java.util.HashSet<>(shownOverlay.keySet());
        // Low health shows the blood edge without any event to start it.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getHealth() < BLOOD_STEPS[0] && bloodLevel(player) > 0) {
                touched.add(player.getUniqueId());
            }
        }
        if (touched.isEmpty() && suppressedUntil.isEmpty() && dazzledUntil.isEmpty() && flashed.isEmpty()
                && flashBlinded.isEmpty() && hitUntil.isEmpty()) {
            return;
        }
        touched.addAll(suppressedUntil.keySet());
        touched.addAll(hitUntil.keySet());
        hitUntil.values().removeIf(until -> until <= now);
        touched.addAll(dazzledUntil.keySet());
        touched.addAll(flashed.keySet());
        touched.addAll(flashBlinded);
        suppressedUntil.values().removeIf(until -> until <= now);
        dazzledUntil.values().removeIf(until -> until <= now);
        flashed.values().removeIf(flash -> flash.until() <= now);
        boolean resend = now % (net.infnetwork.snowball.craftov.util.Nms.selfReady() ? OVERLAY_RESEND_TICKS_HOOKED : OVERLAY_RESEND_TICKS) == 0;
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
