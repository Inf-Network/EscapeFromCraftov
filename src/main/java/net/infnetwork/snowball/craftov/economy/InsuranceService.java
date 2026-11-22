package net.infnetwork.snowball.craftov.economy;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
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
import java.util.UUID;

/**
 * Gear insurance. With it switched on, deploying charges a share of the value of your guns, armour,
 * rig and backpack and marks them as insured for that raid. Whoever extracts with a marked item
 * keeps it and the mark is gone (insurance used up, or lost to a looter). Insured items still lying
 * in bodies, containers or on the ground when the raid closes go back to their owner's mailbox.
 */
public final class InsuranceService {

    private final CraftovPlugin plugin;

    public InsuranceService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public double rate() {
        return plugin.getConfig().getDouble("insurance.rate", 0.08);
    }

    private static boolean insurable(@Nullable ItemDef def) {
        return def != null && (def.isGun() || def.isArmor() || def.isContainer());
    }

    /** What insuring everything the player carries would cost. */
    public long quote(Player player) {
        long value = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (insurable(plugin.items().defOf(stack))) {
                value += plugin.items().value(stack);
            }
        }
        return Math.round(value * rate());
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
            Text.send(player, "<#FF8A80>块币不足，本局装备未投保（需要 ◈" + Text.money(fee) + "）");
            return 0;
        }
        profile.money -= fee;
        String mark = player.getUniqueId() + ":" + raid.key;
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (insurable(def)) {
                stack.editPersistentDataContainer(pdc -> pdc.set(Keys.INSURED, PersistentDataType.STRING, mark));
                plugin.items().refreshLore(stack, def);
                count++;
            }
        }
        Text.send(player, "<#5BD16B>已为 " + count + " 件装备投保 <#9AA0A6>(◈" + Text.money(fee) + ")。阵亡后若没被别人带出，对局结束时退回邮箱。");
        return fee;
    }

    /** Extracting ends insurance on everything carried out, whoever it belonged to, packed contents included. */
    public void strip(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            boolean changed = false;
            if (stack.getPersistentDataContainer().has(Keys.INSURED)) {
                stack.editPersistentDataContainer(pdc -> pdc.remove(Keys.INSURED));
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
     * Raid closing (or the server stopping): insured items left behind go home, also when they are
     * packed inside a rig or backpack lying around.
     */
    public void settle(Raid raid) {
        Map<UUID, List<ItemRecord>> returns = new HashMap<>();
        for (LootContainer container : raid.containers.values()) {
            for (int slot = 0; slot < container.size(); slot++) {
                ItemStack stack = container.get(slot);
                ItemStack left = claim(raid, stack, returns);
                if (left != stack) {
                    container.set(slot, left);
                }
            }
        }
        for (Item item : raid.world.getEntitiesByClass(Item.class)) {
            ItemStack stack = item.getItemStack();
            ItemStack left = claim(raid, stack, returns);
            if (left == null) {
                item.remove();
            } else if (left != stack) {
                item.setItemStack(left);
            }
        }
        returns.forEach((owner, records) -> {
            plugin.profiles().edit(owner, () -> "insurance claim from raid " + raid.id + ": add to mailbox "
                    + ProfileService.json(records), p -> p.mailbox.addAll(records));
            plugin.profiles().saveNow(owner); // a payout is written at once, like a death report
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                Text.send(player, "<#5BD16B>保险理赔：<white>" + records.size() + " 件装备 <#9AA0A6>已退回仓库邮箱。");
            }
        });
    }

    /**
     * Sends home everything in the stack that is insured for this raid. Returns what stays behind:
     * the stack itself when nothing was, a rebuilt one when only something packed inside it was, and
     * null when all of it went.
     */
    private @Nullable ItemStack claim(Raid raid, @Nullable ItemStack stack, Map<UUID, List<ItemRecord>> returns) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        ItemRecord record = plugin.items().toRecord(stack);
        if (record == null || !insuredIn(raid, record)) {
            return stack;
        }
        ItemRecord left = claim(raid, record, returns);
        if (left == null) {
            return null;
        }
        ItemStack rebuilt = plugin.items().fromRecord(left);
        return rebuilt == null ? stack : rebuilt;
    }

    /** Record form of {@link #claim(Raid, ItemStack, Map)}: packed contents first, then the record itself. */
    private @Nullable ItemRecord claim(Raid raid, ItemRecord record, Map<UUID, List<ItemRecord>> returns) {
        if (record.contents != null) {
            record.contents.removeIf(child -> claim(raid, child, returns) == null);
        }
        UUID owner = owner(raid, record.insured);
        if (owner == null) {
            return record;
        }
        record.uninsure(); // what is left inside is not insured for this raid: no mark goes to the mailbox
        returns.computeIfAbsent(owner, k -> new ArrayList<>()).add(record);
        return null;
    }

    private static boolean insuredIn(Raid raid, ItemRecord record) {
        if (owner(raid, record.insured) != null) {
            return true;
        }
        if (record.contents != null) {
            for (ItemRecord child : record.contents) {
                if (insuredIn(raid, child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The owner an insurance mark names for this raid, or null (no mark, another raid, garbage). */
    private static @Nullable UUID owner(Raid raid, @Nullable String mark) {
        if (mark == null || !mark.endsWith(":" + raid.key) || mark.indexOf(':') <= 0) {
            return null;
        }
        try {
            return UUID.fromString(mark.substring(0, mark.indexOf(':')));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
