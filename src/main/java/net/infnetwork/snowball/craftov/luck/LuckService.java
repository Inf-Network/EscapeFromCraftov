package net.infnetwork.snowball.craftov.luck;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.loot.LootService;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.LootContainer;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Personal loot luck (#80). The containers of a raid are rolled alike for everyone (LootService);
 * on top of that each player is owed something for going without:
 *
 * <ul>
 *   <li><b>Pity</b> per rarity (gold and red, {@code luck.pity}), counted in the containers the
 *       player searches themselves that could hold a single item of it. Past the soft mark such a
 *       search may hand one out, more likely the closer it gets to the hard mark, where the next one
 *       does. Jackpot items (loot.yml {@code jackpots}) have their own pity, counted in qualifying
 *       raids ({@code luck.jackpot}): after {@code hard-raids} raids without one, the first container
 *       of the next raid that can hold a red item hands it out. Finding one in any way (searched,
 *       picked up or handed out), carried out or not, starts its count again.</li>
 *   <li>A <b>quota pool</b> (工资, {@code luck.quota}) that every qualifying raid pays into by time
 *       and searches, times the tier's factor and a behaviour multiplier from the last qualifying
 *       raids ({@code luck.behaviour}: profit against losses, kills against deaths and how often a
 *       red came out slow it down, cooperation speeds it up). Once it covers a gold or red item the
 *       container could hold, a search may hand that out and take its value. It is capped and wastes
 *       away while the player stays away.</li>
 *   <li><b>Loss compensation</b> (高爆率局, {@code luck.compensation}): a net loss within the window
 *       (a day) over the tier's threshold boosts the gold and red weights of the containers the
 *       player rolls (opens first) for the next few raids. Matchmaking does not change and nobody
 *       else in the raid is affected; whether the player is told is up to {@code notify}.</li>
 * </ul>
 *
 * <p>A hand-out is one item more, put hidden into the container being searched so it is revealed
 * like the rest; at most one per container. One its player does not carry out themselves (death,
 * missing) pays {@code luck.quota.refund} of its value back into their quota when the raid closes,
 * unless someone else carried it out. A container counts once per player, and only when it still
 * had something hidden when they opened it. The proving ground (test maps) counts for nothing,
 * and neither does the range, which is no raid. A raid pays into the quota, counts for raid-based
 * pity and joins the behaviour history only when the player spent {@code luck.qualify.min-seconds}
 * in it and searched {@code luck.qualify.min-searches} containers. Every hand-out and refund is
 * logged.
 */
public final class LuckService {

    /** Rarities with a pity count, the rarest first (the order hand-outs are tried in). */
    private static final List<Rarity> PITY = List.of(Rarity.RED, Rarity.GOLD);
    private static final long DAY = 24L * 60 * 60 * 1000;

    private final CraftovPlugin plugin;

    public LuckService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    /** Whether this raid counts for anyone's luck: never on a test map (the proving ground). */
    public boolean counts(Raid raid) {
        return cfg().getBoolean("luck.enabled", true) && !raid.map.test();
    }

    // ------------------------------------------------------------------ settings

    public int pitySoft(Rarity rarity) {
        return Math.max(0, Math.min(pityHard(rarity) - 1, cfg().getInt("luck.pity." + rarity.name() + ".soft", rarity == Rarity.RED ? 120 : 15)));
    }

    public int pityHard(Rarity rarity) {
        return Math.max(1, cfg().getInt("luck.pity." + rarity.name() + ".hard", rarity == Rarity.RED ? 240 : 30));
    }

    public int jackpotSoft() {
        return Math.max(0, Math.min(jackpotHard() - 1, cfg().getInt("luck.jackpot.soft-raids", 700)));
    }

    public int jackpotHard() {
        return Math.max(1, cfg().getInt("luck.jackpot.hard-raids", 1000));
    }

    public long quotaCap() {
        return Math.max(0, cfg().getLong("luck.quota.cap", 1500000));
    }

    private long minItemValue() {
        return Math.max(1, cfg().getLong("luck.quota.min-item-value", 100000));
    }

    /**
     * Chance that a search hands out a rarity whose pity count (this search included) is
     * {@code count}: none up to soft, then rising in step to certain at hard.
     */
    public double pityChance(Rarity rarity, int count) {
        int soft = pitySoft(rarity);
        int hard = pityHard(rarity);
        if (count >= hard) {
            return 1;
        }
        return count <= soft ? 0 : (count - soft) / (double) (hard - soft);
    }

    /**
     * Chance that a container able to hold a red item hands out a jackpot the player has gone
     * {@code raids} qualifying raids without: none before soft-raids, then rising to soft-chance just
     * before hard-raids, and certain from hard-raids on.
     */
    public double jackpotChance(int raids) {
        int soft = jackpotSoft();
        int hard = jackpotHard();
        if (raids >= hard) {
            return 1;
        }
        if (raids < soft) {
            return 0;
        }
        return cfg().getDouble("luck.jackpot.soft-chance", 0.02) * (raids - soft + 1) / (hard - soft);
    }

    private int seconds(RaidPlayer rp) {
        return (int) ((Bukkit.getCurrentTick() - rp.startTick) / 20);
    }

    /** A raid counts for the quota, raid-based pity and the behaviour score only with this much in it. */
    public boolean qualifies(RaidPlayer rp) {
        return seconds(rp) >= cfg().getInt("luck.qualify.min-seconds", 240) && rp.luck.searches >= cfg().getInt("luck.qualify.min-searches", 3);
    }

    // ------------------------------------------------------------------ quota

    /** Adds to the quota up to the cap; a pool an admin set above the cap is never cut back by it. */
    public void addQuota(Profile.Luck luck, long amount) {
        luck.quota = Math.max(luck.quota, Math.min(quotaCap(), luck.quota + Math.max(0, amount)));
    }

    /**
     * Takes idle decay off the quota: after luck.quota.decay-after-days without a raid that counts,
     * it loses decay-per-day of itself a day (prorated), up to {@code now}.
     */
    public void decay(Profile.Luck luck, long now) {
        luck.quota = decayed(luck, now);
        luck.quotaAt = Math.max(luck.quotaAt, now);
    }

    /** The quota as {@link #decay} would leave it at {@code now}, without changing anything. */
    public long decayed(Profile.Luck luck, long now) {
        if (luck.quota <= 0 || luck.playedAt <= 0) {
            return luck.quota;
        }
        long idleFrom = luck.playedAt + Math.round(cfg().getDouble("luck.quota.decay-after-days", 3) * DAY);
        long from = Math.max(idleFrom, luck.quotaAt);
        double rate = Math.max(0, Math.min(1, cfg().getDouble("luck.quota.decay-per-day", 0.1)));
        if (now <= from || rate <= 0) {
            return luck.quota;
        }
        return Math.round(luck.quota * Math.pow(1 - rate, (now - from) / (double) DAY));
    }

    /**
     * The behaviour score over the last luck.behaviour.raids qualifying raids and the multiplier it
     * gives the quota income. Each part is within -1..1 (cooperation 0..1); with no raids yet all
     * are 0 and the multiplier is 1.
     */
    public record Behaviour(int raids, long gains, long losses, int kills, int deaths, int reds, int coop,
                            double profit, double kd, double red, double cooperation, double multiplier) {
    }

    public Behaviour behaviour(Profile.Luck luck) {
        int limit = Math.max(1, cfg().getInt("luck.behaviour.raids", 20));
        int raids = 0;
        long gains = 0;
        long losses = 0;
        int kills = 0;
        int deaths = 0;
        int reds = 0;
        int coop = 0;
        for (Profile.LuckRaid raid : luck.raids) {
            if (!raid.qualified) {
                continue;
            }
            if (raids >= limit) {
                break;
            }
            raids++;
            gains += Math.max(0, raid.net);
            losses += Math.max(0, -raid.net);
            kills += raid.kills;
            deaths += raid.deaths;
            reds += raid.red ? 1 : 0;
            coop += raid.coop;
        }
        if (raids == 0) {
            return new Behaviour(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        }
        double base = Math.max(1, cfg().getDouble("luck.behaviour.profit-base", 50000));
        double profit = clamp(log2((gains + base) / (losses + base)) / Math.max(0.01, cfg().getDouble("luck.behaviour.profit-scale", 2.0)), -1, 1);
        double kd = clamp(log2((kills + 1.0) / (deaths + 1.0)) / Math.max(0.01, cfg().getDouble("luck.behaviour.kd-scale", 2.0)), -1, 1);
        double red = clamp((reds / (double) raids - cfg().getDouble("luck.behaviour.red-baseline", 0.15))
                / Math.max(0.01, cfg().getDouble("luck.behaviour.red-scale", 0.3)), -1, 1);
        double cooperation = clamp(coop / (double) raids / Math.max(0.01, cfg().getDouble("luck.behaviour.coop-target", 1.0)), 0, 1);
        double m = 1 - cfg().getDouble("luck.behaviour.profit-weight", 0.4) * profit
                - cfg().getDouble("luck.behaviour.kd-weight", 0.15) * kd
                - cfg().getDouble("luck.behaviour.red-weight", 0.3) * red
                + cfg().getDouble("luck.behaviour.coop-weight", 0.25) * cooperation;
        double min = cfg().getDouble("luck.behaviour.min", 0.3);
        double max = Math.max(min, cfg().getDouble("luck.behaviour.max", 2.0));
        return new Behaviour(raids, gains, losses, kills, deaths, reds, coop, profit, kd, red, cooperation, clamp(m, min, max));
    }

    /**
     * What a qualifying raid pays into the quota: (minutes x per-minute + own searches x per-search),
     * each capped, times the tier's factor (luck.quota.tier, else 1 + 0.5 x its loot shift) and the
     * behaviour multiplier.
     */
    public long income(Raid raid, int seconds, int searches, double multiplier) {
        double minutes = Math.min(seconds / 60.0, cfg().getDouble("luck.quota.max-minutes", 30));
        int counted = Math.min(searches, cfg().getInt("luck.quota.max-searches", 30));
        double tier = cfg().getDouble("luck.quota.tier." + raid.tier.id(), 1.0 + 0.5 * raid.tier.lootShift());
        double base = minutes * cfg().getDouble("luck.quota.per-minute", 1500) + counted * cfg().getDouble("luck.quota.per-search", 1200);
        return Math.max(0, Math.round(base * tier * multiplier));
    }

    // ------------------------------------------------------------------ loss compensation

    /**
     * Net loss of a tier that sets off the compensation (luck.compensation.threshold.TIER); a tier
     * the config does not name gets six times its gear requirement, at least ◈200,000.
     */
    public long lossThreshold(String tierId) {
        long fallback = switch (tierId) {
            case "NORMAL" -> 200000;
            case "CONFIDENTIAL" -> 600000;
            case "SECRET" -> 1500000;
            default -> {
                net.infnetwork.snowball.craftov.raid.Tier tier = plugin.tier(tierId);
                yield Math.max(200000, tier == null ? 0 : tier.minValue() * 6);
            }
        };
        return Math.max(1, cfg().getLong("luck.compensation.threshold." + tierId, fallback));
    }

    /**
     * How far the player's losses within the window (and since the last compensation) have got
     * towards it: each raid's net loss as a share of its tier's threshold, profits taking it back.
     * 1 or more sets it off.
     */
    public double lossScore(Profile.Luck luck, long now) {
        long since = Math.max(luck.lossSince, now - Math.round(cfg().getDouble("luck.compensation.window-hours", 24) * 60 * 60 * 1000));
        double score = 0;
        for (Profile.LuckRaid raid : luck.raids) {
            if (raid.time > since) {
                score += -raid.net / (double) lossThreshold(raid.tier);
            }
        }
        return score;
    }

    /** Gold and red weights of a boosted roll (luck.compensation.multiplier). */
    public Map<Rarity, Double> boostWeights() {
        Map<Rarity, Double> out = new EnumMap<>(Rarity.class);
        out.put(Rarity.GOLD, Math.max(1, cfg().getDouble("luck.compensation.multiplier.GOLD", 2.0)));
        out.put(Rarity.RED, Math.max(1, cfg().getDouble("luck.compensation.multiplier.RED", 2.5)));
        return out;
    }

    /**
     * The rarity boost for a container {@code rp} is the first to open, or null: only while their
     * loss compensation runs, and only for their own rolls, never anyone else's.
     */
    public @Nullable Map<Rarity, Double> boost(Raid raid, RaidPlayer rp) {
        return counts(raid) && rp.luck.boosted ? boostWeights() : null;
    }

    private boolean notifyBoost() {
        return cfg().getBoolean("luck.compensation.notify", false);
    }

    /** {@code player} deploys into {@code raid}: whether their loss compensation applies to it. */
    public void onDeploy(Player player, Raid raid, RaidPlayer rp) {
        if (!counts(raid)) {
            return;
        }
        Profile.Luck luck = plugin.profiles().get(player).luck;
        decay(luck, System.currentTimeMillis());
        rp.luck.boosted = luck.boostRaids > 0;
        if (rp.luck.boosted && notifyBoost()) {
            Text.send(player, "<#F2C94C>补偿局：<#9AA0A6>本局你搜出金色和红色物品的概率提高（含本局还剩 "
                    + luck.boostRaids + " 局）。");
        }
    }

    /** Uses up one compensated raid, or starts the compensation when the losses call for it. */
    private void compensate(Raid raid, RaidPlayer rp, Profile.Luck luck, long now) {
        if (rp.luck.boosted && luck.boostRaids > 0) {
            luck.boostRaids--;
        }
        if (luck.boostRaids > 0) {
            return;
        }
        double score = lossScore(luck, now);
        int raids = Math.max(0, cfg().getInt("luck.compensation.raids", 3));
        if (score < 1 || raids == 0) {
            return;
        }
        luck.boostRaids = raids;
        luck.lossSince = now;
        plugin.getLogger().info("[luck] " + rp.name + " lost enough within the window (" + String.format("%.2f", score)
                + " x the threshold, last raid " + raid.id + " " + raid.tier.id() + "): loss compensation for the next " + raids + " raids");
        Player player = Bukkit.getPlayer(rp.uuid);
        if (player != null && notifyBoost()) {
            Text.send(player, "<#F2C94C>补偿局：<#9AA0A6>接下来 " + raids + " 局你搜出金色和红色物品的概率提高。");
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    // ------------------------------------------------------------------ searching

    /**
     * {@code player} opened {@code container} (just rolled for them when {@code fresh}): counts it
     * towards their pity and may hand out one item more. Called before the search screen opens.
     */
    public void onSearch(Player player, Raid raid, RaidPlayer rp, LootContainer container, boolean fresh) {
        if (!counts(raid) || !container.rolled() || !rp.luck.searched.add(container.id)) {
            return;
        }
        if (!fresh && container.hiddenCount() == 0) {
            return; // searched out by someone else: nothing left to search
        }
        rp.luck.searches++;
        LootService loot = plugin.loot();
        Profile profile = plugin.profiles().get(player);
        Profile.Luck luck = profile.luck;
        Map<Rarity, List<ItemDef>> finds = new EnumMap<>(Rarity.class);
        for (Rarity rarity : PITY) {
            List<ItemDef> pool = loot.finds(container.type, container.shift, rarity);
            if (!pool.isEmpty()) {
                finds.put(rarity, pool);
                luck.pity.merge(rarity.name(), 1, Integer::sum);
            }
        }
        plugin.profiles().markDirty(player.getUniqueId());
        if (finds.isEmpty()) {
            return;
        }
        decay(luck, System.currentTimeMillis());
        Random rng = ThreadLocalRandom.current();
        Choice choice = choose(luck, container, finds, rng);
        if (choice != null) {
            handOut(player, raid, rp, luck, container, choice);
        }
    }

    private record Choice(ItemDef def, String source) {
    }

    private @Nullable Choice choose(Profile.Luck luck, LootContainer container, Map<Rarity, List<ItemDef>> finds, Random rng) {
        LootService loot = plugin.loot();
        if (finds.containsKey(Rarity.RED)) {
            for (String id : loot.jackpots()) {
                ItemDef def = plugin.registry().get(id);
                if (def != null && rng.nextDouble() < jackpotChance(luck.jackpots.getOrDefault(id, 0))) {
                    return new Choice(def, "jackpot");
                }
            }
        }
        for (Rarity rarity : PITY) {
            List<ItemDef> pool = finds.get(rarity);
            // One already in the container is found by searching it, which starts the count again.
            if (pool == null || container.holds(stack -> counted(plugin.items().defOf(stack), rarity))) {
                continue;
            }
            if (rng.nextDouble() < pityChance(rarity, luck.pity.getOrDefault(rarity.name(), 0))) {
                return new Choice(pick(container, pool, rng), "pity-" + rarity.name());
            }
        }
        long min = minItemValue();
        if (luck.quota >= min && rng.nextDouble() < cfg().getDouble("luck.quota.chance", 0.25)) {
            List<ItemDef> affordable = new ArrayList<>();
            for (List<ItemDef> pool : finds.values()) {
                for (ItemDef def : pool) {
                    if (def.value() >= min && def.value() <= luck.quota) {
                        affordable.add(def);
                    }
                }
            }
            if (!affordable.isEmpty()) {
                return new Choice(pick(container, affordable, rng), "quota");
            }
        }
        return null;
    }

    /**
     * One of {@code pool} as the container itself would draw it: a category by the container's
     * weights (among those in the pool), then any of its items. A crate hands out a valuable far
     * more often than a collectible, as it holds them.
     */
    private static ItemDef pick(LootContainer container, List<ItemDef> pool, Random rng) {
        Map<Category, List<ItemDef>> byCategory = new EnumMap<>(Category.class);
        for (ItemDef def : pool) {
            byCategory.computeIfAbsent(def.category(), k -> new ArrayList<>()).add(def);
        }
        double total = 0;
        for (Category category : byCategory.keySet()) {
            total += Math.max(0, container.type.categories().getOrDefault(category, 0.0));
        }
        double roll = rng.nextDouble() * total;
        for (Map.Entry<Category, List<ItemDef>> e : byCategory.entrySet()) {
            roll -= Math.max(0, container.type.categories().getOrDefault(e.getKey(), 0.0));
            if (roll <= 0) {
                return e.getValue().get(rng.nextInt(e.getValue().size()));
            }
        }
        return pool.get(rng.nextInt(pool.size()));
    }

    /** Whether finding {@code def} counts as finding {@code rarity} for pity: rounds never do. */
    private static boolean counted(@Nullable ItemDef def, Rarity rarity) {
        return def != null && def.rarity() == rarity && def.category() != Category.AMMO;
    }

    private void handOut(Player player, Raid raid, RaidPlayer rp, Profile.Luck luck, LootContainer container, Choice choice) {
        ItemDef def = choice.def();
        ItemStack stack = plugin.items().create(def, 1);
        String uid = ItemFactory.uid(stack);
        if (uid == null) {
            String fresh = UUID.randomUUID().toString();
            stack.editPersistentDataContainer(pdc -> pdc.set(Keys.UID, PersistentDataType.STRING, fresh));
            uid = fresh;
        }
        if (!container.addHidden(stack)) {
            return; // full: nothing handed out, nothing spent
        }
        if (choice.source().equals("quota")) {
            luck.quota -= def.value();
        }
        rp.luck.payouts.add(new LuckSession.Payout(uid, def.id(), choice.source(), def.value()));
        found(rp.luck, luck, def);
        plugin.getLogger().info("[luck] " + player.getName() + " is handed " + def.id() + " (◈" + def.value() + ", "
                + choice.source() + (choice.source().equals("quota") ? ", quota left ◈" + luck.quota : "") + ") in "
                + container.type.id() + " of raid " + raid.id + " (" + raid.map.id() + " " + raid.tier.id() + ")");
    }

    /**
     * {@code player} revealed or picked up {@code def} in a raid: a gold or red item starts that
     * rarity's pity count again, a jackpot its own.
     */
    public void onFound(Player player, @Nullable ItemDef def) {
        Raid raid = plugin.raids().raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        if (def == null || rp == null || !counts(raid)) {
            return;
        }
        found(rp.luck, plugin.profiles().get(player).luck, def);
        plugin.profiles().markDirty(player.getUniqueId());
    }

    private void found(LuckSession session, Profile.Luck luck, ItemDef def) {
        if (def.category() == Category.AMMO) {
            return;
        }
        if (PITY.contains(def.rarity())) {
            luck.pity.put(def.rarity().name(), 0);
        }
        if (plugin.loot().isJackpot(def)) {
            luck.jackpots.put(def.id(), 0);
            session.jackpotsFound.add(def.id());
        }
    }

    // ------------------------------------------------------------------ settlement

    /**
     * How the raid went for one player.
     *
     * @param net  change in what they own: what they carried out (or kept, dying) less what they
     *             brought in, fees and a paid extraction
     * @param red  carried out a red item they did not bring in
     * @param coop revives given, extracting beside a squadmate, an escort job
     * @param kept instance ids of everything they carried out or kept (secure container)
     */
    public record Outcome(boolean extracted, long net, boolean red, int coop, Set<String> kept) {
    }

    /**
     * The player's raid is over for them (extracted, dead or missing). It joins their history, uses
     * up a compensated raid or sets off the compensation; a qualifying one pays into the quota and
     * adds one to the raid count of every jackpot they did not find in it. Hand-outs they did not carry out wait for the raid to close ({@link #closeRaid}).
     * {@code profile} may be an offline one being edited (missing).
     */
    public void settle(Raid raid, RaidPlayer rp, Profile profile, Outcome outcome) {
        if (!counts(raid)) {
            return;
        }
        Profile.Luck luck = profile.luck;
        long now = System.currentTimeMillis();
        decay(luck, now);
        raid.luck.kept.addAll(outcome.kept());
        for (LuckSession.Payout payout : rp.luck.payouts) {
            if (!outcome.kept().contains(payout.uid())) {
                raid.luck.pending.add(new RaidLuck.Pending(rp.uuid, rp.name, payout));
            }
        }
        boolean qualified = qualifies(rp);
        Profile.LuckRaid line = new Profile.LuckRaid();
        line.time = now;
        line.tier = raid.tier.id();
        line.net = outcome.net();
        line.kills = rp.kills;
        line.deaths = outcome.extracted() ? 0 : 1;
        line.coop = outcome.coop();
        line.red = outcome.red();
        line.qualified = qualified;
        luck.raids.add(0, line);
        prune(luck, now);
        compensate(raid, rp, luck, now);
        if (qualified) {
            for (String id : plugin.loot().jackpots()) {
                if (!rp.luck.jackpotsFound.contains(id)) {
                    luck.jackpots.merge(id, 1, Integer::sum);
                }
            }
            addQuota(luck, income(raid, seconds(rp), rp.luck.searches, behaviour(luck).multiplier()));
        }
        luck.playedAt = now;
    }

    /** Keeps every raid within the loss window and the last luck.behaviour.raids qualifying ones. */
    private void prune(Profile.Luck luck, long now) {
        long window = Math.round(cfg().getDouble("luck.compensation.window-hours", 24) * 60 * 60 * 1000);
        int keep = Math.max(1, cfg().getInt("luck.behaviour.raids", 20));
        int qualified = 0;
        for (Iterator<Profile.LuckRaid> it = luck.raids.iterator(); it.hasNext(); ) {
            Profile.LuckRaid raid = it.next();
            boolean recent = raid.time >= now - window;
            boolean wanted = raid.qualified && qualified < keep;
            if (raid.qualified) {
                qualified++;
            }
            if (!recent && !wanted) {
                it.remove();
            }
        }
    }

    /** Ids of things carried out: recorded somewhere in {@code records} (rig and backpack contents included). */
    public Set<String> uids(Collection<ItemRecord> records) {
        Set<String> out = new HashSet<>();
        collectUids(records, out);
        return out;
    }

    private static void collectUids(@Nullable Collection<ItemRecord> records, Set<String> out) {
        if (records == null) {
            return;
        }
        for (ItemRecord record : records) {
            if (record.uid != null) {
                out.add(record.uid);
            }
            collectUids(record.contents, out);
        }
    }

    /** Whether {@code out} holds a red item (no rounds) whose instance id was not among {@code broughtIn}. */
    public boolean redOut(Collection<ItemRecord> out, Set<String> broughtIn) {
        for (ItemRecord record : out) {
            ItemDef def = plugin.registry().get(record.id);
            if (counted(def, Rarity.RED) && (record.uid == null || !broughtIn.contains(record.uid))) {
                return true;
            }
            if (record.contents != null && redOut(record.contents, broughtIn)) {
                return true;
            }
        }
        return false;
    }

    /** Things carried home from a voided raid (evacuated, server stop): nobody gets a refund for them. */
    public void keep(Raid raid, Collection<ItemRecord> records) {
        collectUids(records, raid.luck.kept);
    }

    /**
     * The raid is over: hand-outs their player did not carry out and nobody else did either pay
     * luck.quota.refund of their value back into that player's quota.
     */
    public void closeRaid(Raid raid) {
        double share = Math.max(0, cfg().getDouble("luck.quota.refund", 0.5));
        for (RaidLuck.Pending pending : raid.luck.pending) {
            LuckSession.Payout payout = pending.payout();
            long back = Math.round(payout.value() * share);
            if (raid.luck.kept.contains(payout.uid()) || back <= 0) {
                continue;
            }
            plugin.profiles().edit(pending.player(), () -> "luck refund for " + payout.item() + " (" + payout.source()
                    + ") not carried out of raid " + raid.id + ": luck.quota +" + back + " (up to luck.quota.cap)",
                    profile -> addQuota(profile.luck, back));
            plugin.getLogger().info("[luck] " + pending.name() + " did not carry out " + payout.item() + " (" + payout.source()
                    + ") from raid " + raid.id + ": ◈" + back + " back into the quota");
        }
        raid.luck.pending.clear();
    }

    // ------------------------------------------------------------------ admin (/cv luck)

    /** Lines (MiniMessage) showing a player's luck to an admin. Changes nothing. */
    public List<String> describe(Profile profile) {
        Profile.Luck luck = profile.luck;
        long now = System.currentTimeMillis();
        Behaviour b = behaviour(luck);
        List<String> out = new ArrayList<>();
        out.add("<#F2E6C8>运气 · <white>" + Text.escape(profile.name) + (cfg().getBoolean("luck.enabled", true) ? "" : " <#FF8A80>（luck.enabled 已关闭）"));
        out.add("<#9AA0A6>额度池 <#F2C94C>◈" + Text.money(decayed(luck, now)) + " <#8A8F98>/ 上限 ◈" + Text.money(quotaCap())
                + " · <#9AA0A6>行为系数 <white>×" + fmt(b.multiplier()) + " <#8A8F98>（最近 " + b.raids() + " 场有效对局）");
        out.add("<#8A8F98>  赚 ◈" + Text.money(b.gains()) + " / 亏 ◈" + Text.money(b.losses()) + " → " + fmt(b.profit())
                + " · 击败 " + b.kills() + " / 阵亡 " + b.deaths() + " → " + fmt(b.kd())
                + " · 出红 " + b.reds() + " 场 → " + fmt(b.red()) + " · 合作 " + b.coop() + " → " + fmt(b.cooperation()));
        StringBuilder pity = new StringBuilder("<#9AA0A6>保底");
        for (Rarity rarity : List.of(Rarity.GOLD, Rarity.RED)) {
            pity.append(" ").append(rarity.tag()).append(rarity == Rarity.GOLD ? "金 " : "红 ")
                    .append(luck.pity.getOrDefault(rarity.name(), 0)).append("<#8A8F98>（软 ").append(pitySoft(rarity))
                    .append(" / 硬 ").append(pityHard(rarity)).append("）");
        }
        out.add(pity.toString());
        StringBuilder jackpots = new StringBuilder("<#9AA0A6>大奖（有效场次，软 " + jackpotSoft() + " / 硬 " + jackpotHard() + "）");
        for (String id : plugin.loot().jackpots()) {
            ItemDef def = plugin.registry().get(id);
            jackpots.append(" <#FF4D4D>").append(def == null ? id : Text.escape(def.name())).append(" <white>")
                    .append(luck.jackpots.getOrDefault(id, 0));
        }
        out.add(jackpots.toString());
        out.add("<#9AA0A6>补偿局 " + (luck.boostRaids > 0 ? "<#5BD16B>还剩 " + luck.boostRaids + " 局" : "<#8A8F98>未生效")
                + " <#9AA0A6>· 窗口内亏损 <white>" + Math.round(lossScore(luck, now) * 100) + "%<#8A8F98> 的阈值");
        out.add("<#8A8F98>/cv luck " + Text.escape(profile.name) + " quota <数量|+数量|-数量> · pity <GOLD|RED|大奖物品> <数量> · boost <局数> · reset");
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /**
     * Changes a player's luck for an admin ({@code by}): quota (n, +n or -n), pity (GOLD, RED or a
     * jackpot id, then the count), boost (raids left) or reset. Returns null on success, else why not.
     */
    public @Nullable String adjust(UUID uuid, String action, String[] args, String by) {
        java.util.function.Consumer<Profile> change;
        String what;
        try {
            switch (action) {
                case "quota" -> {
                    if (args.length < 1) {
                        return "用法：quota <数量|+数量|-数量>";
                    }
                    String raw = args[0];
                    boolean relative = raw.startsWith("+") || raw.startsWith("-");
                    long amount = Long.parseLong(raw.startsWith("+") ? raw.substring(1) : raw);
                    what = "luck.quota " + (relative ? (amount >= 0 ? "+" : "") + amount : "= " + amount) + " (never below 0)";
                    change = p -> {
                        decay(p.luck, System.currentTimeMillis());
                        p.luck.quota = Math.max(0, relative ? p.luck.quota + amount : amount);
                    };
                }
                case "pity" -> {
                    if (args.length < 2) {
                        return "用法：pity <GOLD|RED|大奖物品> <数量>";
                    }
                    int count = Integer.parseInt(args[1]);
                    if (count < 0) {
                        return "数量不能小于 0。";
                    }
                    String key = args[0];
                    if (key.equalsIgnoreCase("GOLD") || key.equalsIgnoreCase("RED")) {
                        String rarity = key.toUpperCase(java.util.Locale.ROOT);
                        what = "luck.pity." + rarity + " = " + count;
                        change = p -> p.luck.pity.put(rarity, count);
                    } else if (plugin.loot().jackpots().contains(key)) {
                        what = "luck.jackpots." + key + " = " + count;
                        change = p -> p.luck.jackpots.put(key, count);
                    } else {
                        return "保底只有 GOLD、RED 和大奖物品：" + String.join("、", plugin.loot().jackpots());
                    }
                }
                case "boost" -> {
                    if (args.length < 1) {
                        return "用法：boost <局数>";
                    }
                    int raids = Integer.parseInt(args[0]);
                    if (raids < 0) {
                        return "局数不能小于 0。";
                    }
                    what = "luck.boostRaids = " + raids;
                    change = p -> p.luck.boostRaids = raids;
                }
                case "reset" -> {
                    what = "luck = {} (everything back to a new player's)";
                    change = p -> p.luck = new Profile.Luck();
                }
                default -> {
                    return "未知操作：" + action + "（quota、pity、boost、reset）";
                }
            }
        } catch (NumberFormatException ex) {
            return "数量必须是整数。";
        }
        if (!plugin.profiles().canEdit(uuid)) {
            return "这个玩家的档案现在无法修改（读不出来或有待处理的 .unsaved）。";
        }
        String line = what;
        plugin.profiles().edit(uuid, () -> "admin " + by + ": " + line, change);
        plugin.getLogger().info("[luck] " + by + " set " + uuid + ": " + line);
        return null;
    }
}
