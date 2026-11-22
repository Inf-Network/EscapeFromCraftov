package net.infnetwork.snowball.craftov.luck;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.loot.LootService;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.LootContainer;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * </ul>
 *
 * <p>A hand-out is one item more, put hidden into the container being searched so it is revealed
 * like the rest; at most one per container. A container counts once per player, and only when it
 * still had something hidden when they opened it. The proving ground (test maps) counts for
 * nothing, and neither does the range, which is no raid. A raid counts for raid-based pity only
 * when the player spent {@code luck.qualify.min-seconds} in it and searched
 * {@code luck.qualify.min-searches} containers. Every hand-out is logged.
 */
public final class LuckService {

    /** Rarities with a pity count, the rarest first (the order hand-outs are tried in). */
    private static final List<Rarity> PITY = List.of(Rarity.RED, Rarity.GOLD);

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

    /** A raid counts for raid-based pity only with this much time and this many own searches in it. */
    public boolean qualifies(RaidPlayer rp) {
        int seconds = (int) ((Bukkit.getCurrentTick() - rp.startTick) / 20);
        return seconds >= cfg().getInt("luck.qualify.min-seconds", 240) && rp.luck.searches >= cfg().getInt("luck.qualify.min-searches", 3);
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
                return new Choice(pool.get(rng.nextInt(pool.size())), "pity-" + rarity.name());
            }
        }
        return null;
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
            return; // full: nothing handed out
        }
        rp.luck.payouts.add(new LuckSession.Payout(uid, def.id(), choice.source(), def.value()));
        found(rp.luck, luck, def);
        plugin.getLogger().info("[luck] " + player.getName() + " is handed " + def.id() + " (◈" + def.value() + ", "
                + choice.source() + ") in " + container.type.id() + " of raid " + raid.id + " (" + raid.map.id() + " "
                + raid.tier.id() + ")");
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
     * The player's raid is over for them (extracted, dead or missing): a qualifying raid adds one to
     * the raid count of every jackpot they did not find in it. {@code profile} may be an offline one
     * being edited (missing).
     */
    public void settle(Raid raid, RaidPlayer rp, Profile profile) {
        if (!counts(raid) || !qualifies(rp)) {
            return;
        }
        for (String id : plugin.loot().jackpots()) {
            if (!rp.luck.jackpotsFound.contains(id)) {
                profile.luck.jackpots.merge(id, 1, Integer::sum);
            }
        }
    }
}
