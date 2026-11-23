package net.infnetwork.snowball.craftov.economy;

import com.google.gson.Gson;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.raid.LootContainer;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gear insurance. With it switched on, deploying charges a share of the value of your guns, armour,
 * rig and backpack (more for gold and red pieces) and marks them as insured for that raid. Whoever
 * extracts with a marked item keeps it and the mark is gone (insurance used up, or lost to a
 * looter). Insured items still lying in bodies, containers or on the ground when the raid closes
 * go back to their owner, as they were insured: rigs and backpacks come back empty, and guns with
 * only the sight, attachments and rounds they had at deploy. What was fitted or packed in the raid
 * stays there. A player whose raid the server stop voided gets their gear back as it went in, and
 * nothing of theirs is paid out on top.
 *
 * <p>A claim is not a full refund: each item is gone anyway with {@code insurance.loss-chance}
 * (someone else picked it up), so dying in a solo raid among soldiers no longer costs only the
 * premium, and what comes back takes {@code insurance.return-minutes} to reach the mailbox.
 */
public final class InsuranceService {

    private static final Gson GSON = new Gson();

    private final CraftovPlugin plugin;

    public InsuranceService(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::deliver, 20L * 5, 20L * 10);
    }

    /** Premium share for items without a rarity rate of their own (insurance.rate). */
    public double rate() {
        return plugin.getConfig().getDouble("insurance.rate", 0.08);
    }

    /** Premium share for items of {@code rarity}: insurance.rarity-rate for it, else {@link #rate()}. */
    public double rate(Rarity rarity) {
        double fallback = switch (rarity) {
            case GOLD -> 0.12;
            case RED -> 0.16;
            default -> rate();
        };
        return plugin.getConfig().getDouble("insurance.rarity-rate." + rarity.name(), fallback);
    }

    public double rate(ItemDef def) {
        return rate(def.rarity());
    }

    /** Chance each insured item left behind is gone anyway when the raid closes (insurance.loss-chance). */
    public double lossChance() {
        return Math.max(0, Math.min(1, plugin.getConfig().getDouble("insurance.loss-chance", 0.3)));
    }

    /** Minutes a claim takes to reach the mailbox (insurance.return-minutes; 0 = at once). */
    public long returnMinutes() {
        return Math.max(0, plugin.getConfig().getLong("insurance.return-minutes", 30));
    }

    private static boolean insurable(@Nullable ItemDef def) {
        return def != null && (def.isGun() || def.isArmor() || def.isContainer());
    }

    /** What insuring everything the player carries would cost. */
    public long quote(Player player) {
        long fee = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (insurable(def)) {
                fee += Math.round(plugin.items().value(stack) * rate(def));
            }
        }
        return fee;
    }

    /**
     * Called when a player deploys: insures their gear if they have it switched on and can pay.
     * Returns the premium charged (0 when nothing was insured).
     */
    public long onDeploy(Player player, Raid raid) {
        Profile profile = plugin.profiles().get(player);
        if (!profile.autoInsure) {
            return 0;
        }
        long fee = quote(player);
        if (fee <= 0) {
            return 0;
        }
        if (profile.money < fee) {
            // Not the answer to a click, and easy to miss during the fly-in: it goes with the landing
            // briefing in chat, where the player can still read it when they wonder why their gear
            // did not come back.
            net.infnetwork.snowball.craftov.gui.UiSound.ERROR.play(player);
            plugin.raids().brief(player, "<danger>块币不足，本局装备未投保 <muted>（自动投保需要 ◈" + Text.money(fee) + "，余额 ◈"
                    + Text.money(profile.money) + "）");
            return 0;
        }
        profile.money -= fee;
        String mark = player.getUniqueId() + ":" + raid.key;
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (insurable(def)) {
                String json = snapshot(stack);
                stack.editPersistentDataContainer(pdc -> {
                    pdc.set(Keys.INSURED, PersistentDataType.STRING, mark);
                    if (json != null) {
                        pdc.set(Keys.INSURED_AS, PersistentDataType.STRING, json);
                    }
                });
                plugin.items().refreshLore(stack, def);
                count++;
            }
        }
        plugin.raids().brief(player, "<ok>已为 " + count + " 件装备投保 <muted>(◈" + Text.money(fee) + ")。阵亡后没被别人带出的，对局结束时每件有 "
                + Math.round((1 - lossChance()) * 100) + "% 的机会找回" + (returnMinutes() > 0 ? "，" + returnMinutes() + " 分钟后退回邮箱。" : "，退回邮箱。"));
        return fee;
    }

    /**
     * The insured item as it is at deploy, which a claim cuts it back to (see {@link #limitTo}).
     * A worn rig or backpack has its cells laid out in the inventory, so nothing is packed in it.
     */
    private @Nullable String snapshot(ItemStack stack) {
        ItemRecord as = plugin.items().toRecord(stack);
        if (as == null) {
            return null;
        }
        as.contents = null;
        as.uninsure();
        return GSON.toJson(as);
    }

    /** Extracting ends insurance on everything carried out, whoever it belonged to, packed contents included. */
    public void strip(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            boolean changed = false;
            if (stack.getPersistentDataContainer().has(Keys.INSURED) || stack.getPersistentDataContainer().has(Keys.INSURED_AS)) {
                stack.editPersistentDataContainer(pdc -> {
                    pdc.remove(Keys.INSURED);
                    pdc.remove(Keys.INSURED_AS);
                });
                changed = true;
            }
            if (ItemFactory.hasContents(stack)) {
                List<ItemRecord> contents = plugin.items().contents(stack);
                boolean inner = false;
                for (ItemRecord child : contents) {
                    inner |= child.uninsure();
                }
                if (inner) {
                    plugin.items().setContents(stack, contents);
                    changed = true;
                }
            }
            if (changed) {
                plugin.items().refreshLore(stack, plugin.items().defOf(stack));
            }
        }
    }

    /**
     * Raid closing: insured items left behind go home, also when they are packed inside a rig or
     * backpack lying around, each one unless the loss roll takes it. What comes back waits in the
     * profile's claims until {@link #returnMinutes} have passed. Returns the value coming back to
     * each owner (their luck does not count it as lost).
     */
    public Map<UUID, Long> settle(Raid raid) {
        return settle(raid, Set.of(), List.of());
    }

    /**
     * Raid closing, or the server stopping with players still in it. Those players' raid is void
     * ({@code voided}): their gear goes home as it went in, so nothing of theirs is paid out here
     * as well, wherever it lies. What they had on them ({@code carried}, copies) stays in the raid
     * like anything lying around, and someone else's insured gear among it goes home to its owner,
     * with the loss roll and the delay like any other claim. Returns the value coming back to each
     * owner, as {@link #settle(Raid)} does.
     */
    public Map<UUID, Long> settle(Raid raid, Set<UUID> voided, List<ItemStack> carried) {
        Map<UUID, List<ItemRecord>> returns = new HashMap<>();
        Map<UUID, Long> returned = new HashMap<>();
        for (LootContainer container : raid.containers.values()) {
            for (int slot = 0; slot < container.size(); slot++) {
                ItemStack stack = container.get(slot);
                ItemStack left = claim(raid, stack, returns, voided);
                if (left != stack) {
                    container.set(slot, left);
                }
            }
        }
        for (Item item : raid.world.getEntitiesByClass(Item.class)) {
            ItemStack stack = item.getItemStack();
            ItemStack left = claim(raid, stack, returns, voided);
            if (left == null) {
                item.remove();
            } else if (left != stack) {
                item.setItemStack(left);
            }
        }
        for (ItemStack stack : carried) {
            claim(raid, stack, returns, voided);
        }
        double loss = lossChance();
        long minutes = returnMinutes();
        long readyAt = System.currentTimeMillis() + minutes * 60_000L;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        returns.forEach((owner, claimed) -> {
            List<ItemRecord> records = new ArrayList<>();
            List<ItemRecord> lost = new ArrayList<>();
            for (ItemRecord record : claimed) {
                (rng.nextDouble() < loss ? lost : records).add(record);
            }
            for (ItemRecord record : records) {
                returned.merge(owner, plugin.items().value(record), Long::sum);
            }
            if (!records.isEmpty()) {
                if (minutes <= 0) {
                    plugin.profiles().edit(owner, () -> "insurance claim from raid " + raid.id + ": add to mailbox "
                            + ProfileService.json(records), p -> p.mailbox.addAll(records));
                } else {
                    List<Profile.Claim> claims = records.stream().map(r -> new Profile.Claim(r, readyAt)).toList();
                    plugin.profiles().edit(owner, () -> "insurance claim from raid " + raid.id + ": add to claims "
                            + ProfileService.json(claims), p -> p.claims.addAll(claims));
                }
                plugin.profiles().saveNow(owner); // a payout is written at once, like a death report
            }
            plugin.getLogger().info("Insurance claim from raid " + raid.id + " for " + owner + ": " + records.size()
                    + " returned, " + lost.size() + " lost " + lost.stream().map(r -> r.id).toList());
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                String back = records.isEmpty() ? "<danger>投保装备全部没能找回"
                        : "<white>" + records.size() + " 件装备 <muted>" + (minutes <= 0 ? "已退回仓库邮箱" : minutes + " 分钟后退回仓库邮箱");
                Text.send(player, "<ok>保险理赔：" + back
                        + (lost.isEmpty() || records.isEmpty() ? "" : "，<danger>" + lost.size() + " 件没能找回")
                        + "<muted>（按出发时的样子：局内装上的配件、多装的子弹和包里的物品不在理赔范围）");
            }
        });
        return returned;
    }

    /** Moves claims that are ready into their online owners' mailboxes (every ten seconds). */
    private void deliver() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Profile profile = plugin.profiles().get(player);
            List<ItemRecord> ready = new ArrayList<>();
            for (Profile.Claim claim : profile.claims) {
                if (claim.readyAt <= now) {
                    ready.add(claim.item);
                }
            }
            if (ready.isEmpty()) {
                continue;
            }
            plugin.profiles().edit(player.getUniqueId(), () -> "insurance claims ready: move to mailbox " + ProfileService.json(ready),
                    p -> {
                        p.claims.removeIf(c -> c.readyAt <= now);
                        p.mailbox.addAll(ready);
                    });
            plugin.profiles().saveNow(player.getUniqueId());
            Text.send(player, "<ok>保险理赔到账：<white>" + ready.size() + " 件装备 <muted>已放入仓库邮箱");
        }
    }

    /** When the first of the profile's claims reaches the mailbox (epoch millis), 0 when none is on its way. */
    public static long nextClaim(Profile profile) {
        long first = 0;
        for (Profile.Claim claim : profile.claims) {
            if (first == 0 || claim.readyAt < first) {
                first = claim.readyAt;
            }
        }
        return first;
    }

    /**
     * Sends home everything in the stack that is insured for this raid. Returns what stays behind:
     * the stack itself when nothing was, a rebuilt one when only something packed inside it was, and
     * null when all of it went. The stack is read in its in-raid form, so a proving-ground armory
     * rig or backpack (which has no stored form) is searched too, and what stays behind keeps its
     * armory items and marks.
     */
    private @Nullable ItemStack claim(Raid raid, @Nullable ItemStack stack, Map<UUID, List<ItemRecord>> returns, Set<UUID> voided) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        ItemRecord record = plugin.items().packRecord(stack);
        if (record == null || !insuredIn(raid, record, voided)) {
            return stack;
        }
        ItemRecord left = claim(raid, record, returns, voided);
        if (left == null) {
            return null;
        }
        ItemStack rebuilt = plugin.items().fromRecord(left);
        return rebuilt == null ? stack : rebuilt;
    }

    /**
     * Record form of {@link #claim(Raid, ItemStack, Map, Set)}: packed contents first (each insured
     * item in there goes to its own owner), then the record itself, cut back to what was insured.
     */
    private @Nullable ItemRecord claim(Raid raid, ItemRecord record, Map<UUID, List<ItemRecord>> returns, Set<UUID> voided) {
        if (record.contents != null) {
            record.contents.removeIf(child -> claim(raid, child, returns, voided) == null);
        }
        UUID owner = owner(raid, record.insured, voided);
        if (owner == null || record.isTest()) {
            return record; // not insured here; an armory item never leaves its raid, mark or not
        }
        limitTo(record, record.insuredAs);
        record.uninsure(); // no mark goes to the mailbox
        returns.computeIfAbsent(owner, k -> new ArrayList<>()).add(record);
        return null;
    }

    private static boolean insuredIn(Raid raid, ItemRecord record, Set<UUID> voided) {
        if (owner(raid, record.insured, voided) != null) {
            return true;
        }
        if (record.contents != null) {
            for (ItemRecord child : record.contents) {
                if (insuredIn(raid, child, voided)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The owner an insurance mark names for this raid, or null (no mark, another raid, garbage, or
     * an owner whose raid was void).
     */
    private static @Nullable UUID owner(Raid raid, @Nullable String mark, Set<UUID> voided) {
        if (mark == null || !mark.endsWith(":" + raid.key) || mark.indexOf(':') <= 0) {
            return null;
        }
        try {
            UUID owner = UUID.fromString(mark.substring(0, mark.indexOf(':')));
            return voided.contains(owner) ? null : owner;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Cuts a claimed item back to what was insured. A rig or backpack taken off in the raid carries
     * its cells packed inside, and a gun can be fitted at the gunsmith bench in the raid; returning
     * either would let anyone load insured gear with loot, leave it in a box and collect the lot
     * from the mailbox. So nothing packed inside comes back, a sight or attachment only if the item
     * had that same one at deploy (one taken off in the raid is not duplicated), no more rounds of
     * the same ammo than it had then, and no more durability.
     */
    private static void limitTo(ItemRecord record, @Nullable ItemRecord insured) {
        record.contents = null;
        if (insured == null) {
            record.optic = null;
            record.attach = null;
            if (record.ammo != null) {
                record.ammo = 0;
            }
            return;
        }
        if (!Objects.equals(record.optic, insured.optic)) {
            record.optic = null;
        }
        if (record.attach != null) {
            Map<String, String> had = insured.attach == null ? Map.of() : insured.attach;
            record.attach.entrySet().removeIf(e -> !e.getValue().equals(had.get(e.getKey())));
            if (record.attach.isEmpty()) {
                record.attach = null;
            }
        }
        if (record.ammo != null) {
            if (Objects.equals(record.ammoType, insured.ammoType)) {
                record.ammo = Math.min(record.ammo, insured.ammo == null ? 0 : insured.ammo);
            } else {
                record.ammo = 0;
                record.ammoType = insured.ammoType;
            }
        }
        if (insured.dura != null && (record.dura == null || record.dura > insured.dura)) {
            record.dura = insured.dura;
        }
    }
}
