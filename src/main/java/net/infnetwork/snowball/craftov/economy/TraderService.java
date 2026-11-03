package net.infnetwork.snowball.craftov.economy;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** NPC trader: sells standard gear, buys anything at a discount. */
public final class TraderService {

    public enum Tab {
        WEAPON("武器", Category.WEAPON),
        AMMO("弹药", Category.AMMO),
        ARMOR("护甲", Category.ARMOR),
        HELMET("头盔", Category.HELMET),
        CARRY("胸挂/背包", null),
        MEDICAL("医疗", Category.MEDICAL),
        THROWABLE("投掷物", Category.THROWABLE),
        SERVICE("服务", null);

        public final String label;
        public final Category category;

        Tab(String label, Category category) {
            this.label = label;
            this.category = category;
        }
    }

    private final CraftovPlugin plugin;

    public TraderService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public List<ItemDef> catalog(Tab tab) {
        List<ItemDef> out = new ArrayList<>();
        Rarity max = Rarity.valueOf(plugin.getConfig().getString("trader.max-rarity", "PURPLE"));
        for (ItemDef def : plugin.registry().all()) {
            if (!def.tradeable() || def.rarity().ordinal() > max.ordinal()) {
                continue;
            }
            boolean match = tab == Tab.CARRY ? def.isContainer() : def.category() == tab.category;
            if (match) {
                out.add(def);
            }
        }
        return out;
    }

    public long buyPrice(ItemDef def) {
        return Math.round(def.value() * plugin.getConfig().getDouble("trader.buy-multiplier", 1.15));
    }

    public long sellPrice(ItemRecord record) {
        return Math.round(plugin.items().value(record) * plugin.getConfig().getDouble("trader.sell-multiplier", 0.55));
    }

    /** Buys {@code amount}; the purchase goes to the inventory, overflow to the stash. */
    public boolean buy(Player player, ItemDef def, int amount) {
        Profile profile = plugin.profiles().get(player);
        long cost = buyPrice(def) * amount;
        if (profile.money < cost) {
            Text.send(player, "<#FF8A80>块币不足，需要 ◈" + Text.money(cost));
            return false;
        }
        int remaining = amount;
        List<ItemStack> stacks = new ArrayList<>();
        while (remaining > 0) {
            int n = Math.min(def.maxStack(), remaining);
            stacks.add(plugin.items().create(def, n));
            remaining -= n;
        }
        int needStash = 0;
        for (ItemStack stack : stacks) {
            if (!plugin.carry().hasRoomFor(player, stack)) {
                needStash++;
            }
        }
        if (profile.stashUsed() + needStash > profile.stashCapacity) {
            Text.send(player, "<#FF8A80>背包与仓库都放不下了。");
            return false;
        }
        profile.money -= cost;
        boolean toStash = false;
        for (ItemStack stack : stacks) {
            ItemStack left = plugin.carry().give(player, stack, false);
            if (left != null) {
                ItemRecord record = plugin.items().toRecord(left);
                if (record != null) {
                    profile.stash.add(record);
                    toStash = true;
                }
            }
        }
        plugin.profiles().markDirty(player.getUniqueId());
        plugin.lobby().captureLoadout(player);
        player.playSound(player.getLocation(), "craftov:ui.buy", 1f, 1f);
        player.sendActionBar(Text.mm("<#5BD16B>购买 " + def.coloredName() + " ×" + amount + " <#8A8F98>-◈" + Text.money(cost)
                + (toStash ? " <#9AA0A6>(部分存入仓库)" : "")));
        return true;
    }

    public long sell(Player player, ItemRecord record) {
        long price = sellPrice(record);
        Profile profile = plugin.profiles().get(player);
        profile.money += price;
        profile.addStat("sold_value", price);
        plugin.profiles().markDirty(player.getUniqueId());
        player.playSound(player.getLocation(), "craftov:ui.sell", 1f, 1f);
        return price;
    }

    // ------------------------------------------------------------------ free kit

    /** A basic kit for players who lost everything, so nobody is ever stuck in the lobby. */
    public void claimFreeKit(Player player) {
        Profile profile = plugin.profiles().get(player);
        long value = plugin.carry().carriedValue(player);
        long max = plugin.getConfig().getLong("free-kit.max-value", 15000);
        if (value > max) {
            Text.send(player, "<#FF8A80>你的战备值超过 ◈" + Text.money(max) + "，暂不能领取免费补给。");
            return;
        }
        long last = profile.stat("free_kit_at");
        long cooldown = plugin.getConfig().getInt("free-kit.cooldown-minutes", 20) * 60_000L;
        long wait = last + cooldown - System.currentTimeMillis();
        if (wait > 0) {
            Text.send(player, "<#FF8A80>免费补给冷却中，还需 " + net.infnetwork.snowball.craftov.util.Text.duration(wait / 1000) + "。");
            return;
        }
        profile.stats.put("free_kit_at", System.currentTimeMillis());
        for (String raw : plugin.getConfig().getStringList("free-kit.items")) {
            String[] parts = raw.split(":");
            ItemDef def = plugin.registry().get(parts[0]);
            if (def == null) {
                continue;
            }
            ItemStack stack = def.isGun() && parts.length > 2 ? plugin.items().loadedGun(def, parts[2])
                    : plugin.items().create(def, parts.length > 1 ? Integer.parseInt(parts[1]) : 1);
            if (def.isContainer()) {
                ItemStack equipped = player.getInventory().getItem(net.infnetwork.snowball.craftov.inventory.CarryService.containerSlot(def.category()));
                if (equipped == null || equipped.isEmpty()) {
                    plugin.carry().equip(player, stack);
                    continue;
                }
            }
            if (def.isArmor()) {
                int slot = def.category() == Category.HELMET ? net.infnetwork.snowball.craftov.inventory.CarryService.HEAD
                        : net.infnetwork.snowball.craftov.inventory.CarryService.CHEST;
                ItemStack worn = player.getInventory().getItem(slot);
                if (worn == null || worn.isEmpty()) {
                    player.getInventory().setItem(slot, stack);
                    continue;
                }
            }
            ItemStack left = plugin.carry().give(player, stack, false);
            if (left != null) {
                ItemRecord record = plugin.items().toRecord(left);
                if (record != null) {
                    profile.stash.add(record);
                }
            }
        }
        plugin.lobby().captureLoadout(player);
        plugin.profiles().markDirty(player.getUniqueId());
        Text.send(player, "<#5BD16B>已领取免费补给，祝你好运。");
    }

    // ------------------------------------------------------------------ services

    public static final int[] SECURE_SLOTS = {0, 3, 5, 7, 9};
    public static final long[] SECURE_PRICES = {0, 0, 180_000, 650_000, 1_800_000};

    public int secureSlots(Profile profile) {
        return SECURE_SLOTS[Math.max(1, Math.min(4, profile.secureTier))];
    }

    public boolean upgradeSecure(Player player) {
        Profile profile = plugin.profiles().get(player);
        if (profile.secureTier >= 4) {
            Text.send(player, "<#9AA0A6>安全箱已是最高等级。");
            return false;
        }
        long price = SECURE_PRICES[profile.secureTier + 1];
        if (profile.money < price) {
            Text.send(player, "<#FF8A80>块币不足，需要 ◈" + Text.money(price));
            return false;
        }
        profile.money -= price;
        profile.secureTier++;
        plugin.profiles().markDirty(player.getUniqueId());
        Text.send(player, "<#5BD16B>安全箱升级为 " + secureSlots(profile) + " 格！");
        return true;
    }

    public long stashUpgradePrice(Profile profile) {
        return 40_000L + (profile.stashCapacity - 120) * 1_500L;
    }

    public boolean upgradeStash(Player player) {
        Profile profile = plugin.profiles().get(player);
        if (profile.stashCapacity >= 600) {
            Text.send(player, "<#9AA0A6>仓库已扩容至上限。");
            return false;
        }
        long price = stashUpgradePrice(profile);
        if (profile.money < price) {
            Text.send(player, "<#FF8A80>块币不足，需要 ◈" + Text.money(price));
            return false;
        }
        profile.money -= price;
        profile.stashCapacity += 30;
        plugin.profiles().markDirty(player.getUniqueId());
        Text.send(player, "<#5BD16B>仓库扩容至 " + profile.stashCapacity + " 格。");
        return true;
    }

    public long repairCost(ItemStack stack) {
        ItemDef def = plugin.items().defOf(stack);
        if (def == null || !def.isArmor()) {
            return 0;
        }
        double dura = net.infnetwork.snowball.craftov.item.ItemFactory.dura(stack, def.durability());
        double missing = def.durability() - dura;
        return Math.round(missing / def.durability() * def.value() * 0.35);
    }

    /** Repairs every armor piece the player wears or carries. */
    public void repairAll(Player player) {
        Profile profile = plugin.profiles().get(player);
        long total = 0;
        List<ItemStack> targets = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            long cost = stack == null ? 0 : repairCost(stack);
            if (cost > 0) {
                total += cost;
                targets.add(stack);
            }
        }
        if (targets.isEmpty()) {
            Text.send(player, "<#9AA0A6>没有需要维修的护甲。");
            return;
        }
        if (profile.money < total) {
            Text.send(player, "<#FF8A80>维修需要 ◈" + Text.money(total));
            return;
        }
        profile.money -= total;
        for (ItemStack stack : targets) {
            ItemDef def = plugin.items().defOf(stack);
            plugin.items().setDura(stack, def.durability());
        }
        plugin.lobby().captureLoadout(player);
        Text.send(player, "<#5BD16B>已维修 " + targets.size() + " 件护甲，花费 ◈" + Text.money(total));
    }
}
