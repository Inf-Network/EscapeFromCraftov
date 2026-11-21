package net.infnetwork.snowball.craftov.economy;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
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
import java.util.Objects;
import java.util.UUID;

/**
 * Gear insurance. With it switched on, deploying charges a share of the value of your guns, armour,
 * rig and backpack and marks them as insured for that raid. Whoever extracts with a marked item
 * keeps it and the mark is gone (insurance used up, or lost to a looter). Insured items still lying
 * in bodies, containers or on the ground when the raid closes go back to their owner's mailbox,
 * as they were insured: rigs and backpacks come back empty, and guns with only the sight,
 * attachments and rounds they had at deploy. What was fitted or packed in the raid stays there.
 */
public final class InsuranceService {

    private static final Gson GSON = new Gson();

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
                ItemRecord as = plugin.items().toRecord(stack);
                String json = as == null ? null : GSON.toJson(as);
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
        Text.send(player, "<#5BD16B>已为 " + count + " 件装备投保 <#9AA0A6>(◈" + Text.money(fee) + ")。阵亡后若没被别人带出，对局结束时退回邮箱。");
    }

    /** Extracting ends insurance on everything carried out, whoever it belonged to. */
    public void strip(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(Keys.INSURED)) {
                stack.editPersistentDataContainer(pdc -> {
                    pdc.remove(Keys.INSURED);
                    pdc.remove(Keys.INSURED_AS);
                });
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
                Text.send(player, "<#5BD16B>保险理赔：<white>" + records.size() + " 件装备 <#9AA0A6>已退回仓库邮箱"
                        + "<#8A8F98>（按出发时的样子：局内装上的配件、多装的子弹和包里的物品不在理赔范围）");
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
        ItemRecord insured = insuredAs(stack);
        ItemStack clean = stack.clone();
        clean.editPersistentDataContainer(pdc -> {
            pdc.remove(Keys.INSURED);
            pdc.remove(Keys.INSURED_AS);
        });
        ItemRecord record = plugin.items().toRecord(clean);
        if (record == null) {
            return false;
        }
        limitTo(record, insured);
        returns.computeIfAbsent(owner, k -> new ArrayList<>()).add(record);
        return true;
    }

    private static @Nullable ItemRecord insuredAs(ItemStack stack) {
        String json = stack.getPersistentDataContainer().get(Keys.INSURED_AS, PersistentDataType.STRING);
        if (json == null) {
            return null;
        }
        try {
            return GSON.fromJson(json, ItemRecord.class);
        } catch (JsonParseException ex) {
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
