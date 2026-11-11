package net.infnetwork.snowball.craftov.economy;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.profile.Profile;
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

    /** Called when a player deploys: insures their gear if they have it switched on and can pay. */
    public void onDeploy(Player player, Raid raid) {
        Profile profile = plugin.profiles().get(player);
        if (!profile.autoInsure) {
            return;
        }
        long fee = quote(player);
        if (fee <= 0) {
            return;
        }
        if (profile.money < fee) {
            Text.send(player, "<#FF8A80>块币不足，本局装备未投保（需要 ◈" + Text.money(fee) + "）");
            return;
        }
        profile.money -= fee;
        String mark = player.getUniqueId() + ":" + raid.id;
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
    }

    /** Extracting ends insurance on everything carried out, whoever it belonged to. */
    public void strip(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.INSURED)) {
                stack.editPersistentDataContainer(pdc -> pdc.remove(Keys.INSURED));
                plugin.items().refreshLore(stack, plugin.items().defOf(stack));
            }
        }
    }

    /** Raid closing: insured items left behind go home. */
    public void settle(Raid raid) {
        Map<UUID, List<ItemRecord>> returns = new HashMap<>();
        for (LootContainer container : raid.containers.values()) {
            for (int slot = 0; slot < container.size(); slot++) {
                ItemStack stack = container.get(slot);
                if (claim(raid, stack, returns)) {
                    container.set(slot, null);
                }
            }
        }
        for (Item item : raid.world.getEntitiesByClass(Item.class)) {
            if (claim(raid, item.getItemStack(), returns)) {
                item.remove();
            }
        }
        returns.forEach((owner, records) -> {
            plugin.profiles().edit(owner, p -> p.mailbox.addAll(records));
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                Text.send(player, "<#5BD16B>保险理赔：<white>" + records.size() + " 件装备 <#9AA0A6>已退回仓库邮箱。");
            }
        });
    }

    private boolean claim(Raid raid, @Nullable ItemStack stack, Map<UUID, List<ItemRecord>> returns) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String mark = stack.getPersistentDataContainer().get(Keys.INSURED, PersistentDataType.STRING);
        if (mark == null || !mark.endsWith(":" + raid.id)) {
            return false;
        }
        UUID owner;
        try {
            owner = UUID.fromString(mark.substring(0, mark.indexOf(':')));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        ItemStack clean = stack.clone();
        clean.editPersistentDataContainer(pdc -> pdc.remove(Keys.INSURED));
        ItemRecord record = plugin.items().toRecord(clean);
        if (record == null) {
            return false;
        }
        returns.computeIfAbsent(owner, k -> new ArrayList<>()).add(record);
        return true;
    }
}
