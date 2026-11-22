package net.infnetwork.snowball.craftov.economy;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

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
        ATTACHMENT("配件", Category.ATTACHMENT),
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
            boolean match = tab == Tab.CARRY ? def.isContainer()
                    : def.category() == tab.category || (tab == Tab.WEAPON && def.category() == Category.MELEE);
            if (match) {
                out.add(def);
            }
        }
        return out;
    }

    public long buyPrice(ItemDef def) {
        return Math.round(def.value() * plugin.getConfig().getDouble("trader.buy-multiplier", 1.15));
    }

    /** What the trader pays for {@code record}: nothing for free-kit items themselves. */
    public long sellPrice(ItemRecord record) {
        return Math.round(plugin.items().tradeValue(record) * plugin.getConfig().getDouble("trader.sell-multiplier", 0.55));
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
        // Each stack that does not fit on the player takes a stash cell of its own.
        @Nullable ItemStack[] cells = plugin.carry().copyCells(player);
        int needStash = 0;
        for (ItemStack stack : stacks) {
            if (!plugin.carry().simulateGive(cells, stack)) {
                needStash++;
            }
        }
        if (profile.stashUsed() + needStash > profile.stashCapacity) {
            Text.send(player, "<#FF8A80>背包与仓库都放不下了（还差 " + (profile.stashUsed() + needStash - profile.stashCapacity) + " 格仓库空间）。");
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

    public long freeKitMaxAssets() {
        return plugin.getConfig().getLong("free-kit.max-assets", 200_000);
    }

    /**
     * Everything the player owns, at item value: money, stash, mailbox, what they carry, the secure
     * container, their market listings and an escrowed escort reward. The escrow is read from the
     * profile, where EscortService records it with the deduction: that also covers a contract lost
     * in a crash whose reward has not been handed back yet.
     */
    public long assets(Player player) {
        Profile profile = plugin.profiles().get(player);
        long total = profile.money + profile.escortEscrow + plugin.carry().carriedValue(player);
        for (List<ItemRecord> records : List.of(profile.stash, profile.mailbox, profile.secure)) {
            for (ItemRecord record : records) {
                total += plugin.items().value(record);
            }
        }
        for (MarketService.Listing listing : plugin.market().mine(player.getUniqueId())) {
            total += plugin.items().value(listing.item);
        }
        return total;
    }

    /**
     * A basic kit for players who lost everything, so nobody is ever stuck in the lobby. Only what
     * they carry used to count, so anyone could park their gear in the stash and pick up a kit
     * worth ◈30,000 (◈17,000 at the trader) every 20 minutes; everything they own counts now.
     */
    public void claimFreeKit(Player player) {
        Profile profile = plugin.profiles().get(player);
        long value = plugin.carry().carriedValue(player);
        long max = plugin.getConfig().getLong("free-kit.max-value", 15000);
        if (value > max) {
            Text.send(player, "<#FF8A80>你的战备值超过 ◈" + Text.money(max) + "，暂不能领取免费补给。");
            return;
        }
        long assets = assets(player);
        long maxAssets = freeKitMaxAssets();
        if (assets > maxAssets) {
            Text.send(player, "<#FF8A80>免费补给只发给几乎一无所有的干员：你的总资产（块币、仓库、邮箱、携带、安全箱、交易行上架、托管中的护航赏金）约 ◈"
                    + Text.money(assets) + "，超过 ◈" + Text.money(maxAssets) + "。<#9AA0A6>可以从仓库取出装备或到商人处购买。");
            return;
        }
        long last = profile.stat("free_kit_at");
        long cooldown = plugin.getConfig().getInt("free-kit.cooldown-minutes", 20) * 60_000L;
        long wait = last + cooldown - System.currentTimeMillis();
        if (wait > 0) {
            Text.send(player, "<#FF8A80>免费补给冷却中，还需 " + net.infnetwork.snowball.craftov.util.Text.duration(wait / 1000) + "。");
            return;
        }
        List<ItemStack> kit = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("free-kit.items")) {
            String[] parts = raw.split(":");
            ItemDef def = plugin.registry().get(parts[0]);
            if (def == null) {
                continue;
            }
            ItemStack stack = def.isGun() && parts.length > 2 ? plugin.items().loadedGun(def, parts[2])
                    : plugin.items().create(def, parts.length > 1 ? Integer.parseInt(parts[1]) : 1);
            // Marked so the kit cannot be turned straight into money at the trader or the market.
            kit.add(plugin.items().markKit(stack));
        }
        // Dry run of the placement below: whatever the player cannot carry goes to the stash, which
        // must have room for it, or the kit would push the stash over its paid capacity.
        @Nullable ItemStack[] cells = plugin.carry().copyCells(player);
        int toStash = 0;
        for (ItemStack stack : kit) {
            int slot = kitSlot(plugin.items().defOf(stack));
            if (slot >= 0 && (cells[slot] == null || cells[slot].isEmpty())) {
                cells[slot] = stack.clone();
            } else if (!plugin.carry().simulateGive(cells, stack)) {
                toStash++;
            }
        }
        // A stash already over its capacity (older builds let purchases overfill it) only matters
        // when something actually has to go into it.
        int free = profile.stashCapacity - profile.stashUsed();
        if (toStash > 0 && toStash > free) {
            Text.send(player, "<#FF8A80>仓库空间不足：免费补给有 " + toStash + " 件背不下、要存进仓库，但仓库只剩 " + Math.max(0, free)
                    + " 格。<#9AA0A6>清理仓库或腾出背包后再领取。");
            return;
        }
        profile.stats.put("free_kit_at", System.currentTimeMillis());
        for (ItemStack stack : kit) {
            ItemDef def = plugin.items().defOf(stack);
            int slot = kitSlot(def);
            ItemStack worn = slot < 0 ? null : player.getInventory().getItem(slot);
            if (slot >= 0 && (worn == null || worn.isEmpty())) {
                if (def.isContainer()) {
                    plugin.carry().equip(player, stack);
                } else {
                    player.getInventory().setItem(slot, stack);
                }
                continue;
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

    /** Where a free-kit item is put on when that slot is empty: rigs, backpacks, helmets and armour; -1 for the rest. */
    private static int kitSlot(@Nullable ItemDef def) {
        if (def == null) {
            return -1;
        }
        if (def.isContainer()) {
            return CarryService.containerSlot(def.category());
        }
        if (def.isArmor()) {
            return def.category() == Category.HELMET ? CarryService.HEAD : CarryService.CHEST;
        }
        return -1;
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

    /**
     * Share of an armour piece's value charged per full bar of missing durability. A repair raises
     * the item's value by 0.75 of its value per full bar (ItemFactory.value), so below about 0.49
     * (0.75 times what the market's system buyers pay at most after fee and tax, 0.7 x 0.93) repairing
     * worn armour and selling it would make money; tools/econ/repair.py checks every piece in items.yml.
     */
    public double repairRate() {
        return plugin.getConfig().getDouble("trader.repair-rate", 0.75);
    }

    public long repairCost(ItemStack stack) {
        ItemDef def = plugin.items().defOf(stack);
        if (def == null || !def.isArmor()) {
            return 0;
        }
        double dura = net.infnetwork.snowball.craftov.item.ItemFactory.dura(stack, def.durability());
        double missing = def.durability() - dura;
        return Math.round(missing / def.durability() * def.value() * repairRate());
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
