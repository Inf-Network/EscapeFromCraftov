package net.infnetwork.snowball.craftov.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Rarity;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.util.AtomicFiles;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Player auction house with virtual listings: items leave the stash while listed, sell to other
 * players or to simulated buyers when priced low enough, and return on expiry.
 *
 * <p>The anchor is the item's own value (durability, attachments, loaded ammo and stack size
 * included) times {@code market.reference-multiplier}. It comes from items.yml, so no sale can
 * move it. System buyers keep the market working on a small server, but they are a cheap outlet,
 * not a second trader at full price: they pay at most {@code market.virtual-max-value} (0.7) of
 * the item's value, take nothing above purple, and take at most {@code market.virtual-daily-cap}
 * listings of one item from one seller a day. After fee and tax that is about 0.65 of value,
 * a little more than the trader's 0.55, far below its 1.15 buy price.
 *
 * <p>Every trade queues the market and the profiles it touched for writing straight away, all on
 * the profile writer thread ({@link ProfileService#queueWrite}), so they reach the disk in the
 * order queued, and what a trade takes away is queued before what it hands out: a listing leaves
 * the market before its seller is paid, its buyer gets the item or its seller gets it back, and a
 * listed item leaves the stash before it appears on the market. A crash between two of those
 * writes can cost that one payout or item, but can never sell, return or list anything twice.
 */
public final class MarketService implements Listener {

    public static final class Listing {
        public String id;
        public UUID seller;
        public String sellerName;
        public ItemRecord item;
        public long price;
        public long created;
        public long expires;
    }

    public static final class Data {
        /** Format version; 0 in files written before it existed (Gson keeps the default for a missing field). */
        public int version;
        public List<Listing> listings = new ArrayList<>();
        /** Trade price of one plain unit per item id, moved by player-to-player sales only. */
        public Map<String, Double> reference = new HashMap<>();
        /** Recent sale prices per plain unit, any buyer, newest last. */
        public Map<String, List<Long>> history = new HashMap<>();
        /**
         * Listings system buyers took on {@link #virtualDay}, per "seller uuid:item id"
         * (market.virtual-daily-cap). Saved, so a restart does not hand out a fresh quota.
         */
        public Map<String, Integer> virtualSales = new HashMap<>();
        public long virtualDay = -1;
    }

    /**
     * One item in the market browser.
     *
     * @param anchor    reference price of one plain unit (fixed by items.yml)
     * @param reference trade price of one plain unit from player-to-player sales
     * @param traded    whether players have traded this item at all, i.e. whether reference means anything
     */
    public record Offer(String itemId, long lowest, int count, double anchor, double reference, boolean traded) {
    }

    /** 1: trade prices became player-only and bounded; anything older may hold ratcheted-up prices. */
    private static final int DATA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final CraftovPlugin plugin;
    private final Path file;
    private Data data = new Data();
    private boolean dirty;
    private boolean closed;

    public MarketService(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("data/market.json");
    }

    public void start() {
        AtomicFiles.cleanTemp(file.getParent());
        if (Files.exists(file)) {
            try {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded == null) {
                    throw new IllegalStateException("the file is empty");
                }
                data = loaded;
            } catch (IOException | RuntimeException ex) {
                // Starting over with empty data would hand nobody their listed items back and
                // write the empty data over the file on shutdown: keep the market shut instead.
                closed = true;
                plugin.getLogger().severe("==== Market data " + file.getFileName() + " could not be read (" + ex.getMessage()
                        + "). The market is closed and the file will not be written; repair it and restart the server. ====");
                return;
            }
        }
        if (data.listings == null) data.listings = new ArrayList<>();
        if (data.reference == null) data.reference = new HashMap<>();
        if (data.history == null) data.history = new HashMap<>();
        if (data.virtualSales == null) data.virtualSales = new HashMap<>();
        if (data.version < DATA_VERSION) {
            migrate();
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 10, 20L * 10);
    }

    /**
     * Upgrades market data from before the price fix: trade prices and history may have been
     * ratcheted up by the old feedback loop, so they start over at the reference price, and
     * listings above the new price cap expire now and go back to their sellers' mailboxes on the
     * first tick (the listing fee is not refunded).
     */
    private void migrate() {
        int prices = data.reference.size();
        data.reference.clear();
        data.history.clear();
        int returned = 0;
        long now = System.currentTimeMillis();
        for (Listing listing : data.listings) {
            if (listing.price > maxPrice(listing.item)) {
                listing.expires = now;
                returned++;
            }
        }
        data.version = DATA_VERSION;
        dirty = true;
        if (prices > 0 || returned > 0) {
            plugin.getLogger().warning("Market data from an older version: reset " + prices + " trade prices and returned "
                    + returned + " listings priced above the cap. Accounts that farmed the old prices show up in /cv marketaudit.");
        }
        save();
    }

    /** True when market.json could not be read on start: no trading, and the file is left alone. */
    public boolean closed() {
        return closed;
    }

    /** Tells the player the market is shut and returns true, or returns false when it is open. */
    public boolean refuseIfClosed(Player player) {
        if (closed) {
            Text.send(player, "<#FF8A80>交易行数据读取失败，暂停开放，请联系管理员。");
        }
        return closed;
    }

    public void save() {
        if (!dirty || closed) {
            return;
        }
        dirty = false;
        String json = GSON.toJson(data);
        // The profile writer, so a save lands in order with the profile writes around it.
        plugin.profiles().queueWrite(() -> write(json));
    }

    /**
     * Shutdown, once the profile writer has finished what was queued (CraftovPlugin.onDisable):
     * writes the current state last, so no older copy can land on top of it.
     */
    public void saveSync() {
        if (!closed) {
            write(GSON.toJson(data));
        }
    }

    /**
     * Takes a listing off the market for good and queues the market write at once, before anything
     * is paid out or handed back for it.
     */
    private void takeOff(Listing listing) {
        data.listings.remove(listing);
        dirty = true;
        save();
    }

    /** Queues the market, then the player's profile, after a trade took something off the market. */
    private void commit(UUID player) {
        save();
        plugin.profiles().saveNow(player);
    }

    private void write(String json) {
        try {
            AtomicFiles.write(file, json);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save market data: " + ex.getMessage());
        }
    }

    private double multiplier() {
        return plugin.getConfig().getDouble("market.reference-multiplier", 0.85);
    }

    /** The price system buyers and the pricing screen measure a listing of {@code record} against. */
    public long anchorFor(ItemRecord record) {
        return Math.max(1, Math.round(plugin.items().value(record) * multiplier()));
    }

    public double maxPriceMultiplier() {
        return plugin.getConfig().getDouble("market.max-price-multiplier", 3.0);
    }

    /**
     * Highest price a listing of {@code record} may ask. Without it one colluding sale at any
     * price moved money between accounts (1 round of 9mm for ◈140,000).
     */
    public long maxPrice(ItemRecord record) {
        return Math.max(anchorFor(record), Math.round(plugin.items().value(record) * multiplier() * maxPriceMultiplier()));
    }

    /** Reference price of one plain, full-durability unit of {@code itemId}. */
    public double anchorUnit(String itemId) {
        ItemDef def = plugin.registry().get(itemId);
        return def == null ? 0 : def.value() * multiplier();
    }

    /**
     * Trade price of one plain unit: a moving average of player-to-player sales, shown for
     * information only. Nothing pays out against it, and it stays within a band around the
     * reference price so that even a stored value from an older build cannot run away.
     */
    public double reference(String itemId) {
        double base = anchorUnit(itemId);
        Double ref = data.reference.get(itemId);
        if (ref == null) {
            return base;
        }
        double min = plugin.getConfig().getDouble("market.trade-price-min", 0.6);
        double max = plugin.getConfig().getDouble("market.trade-price-max", 1.5);
        return Math.max(base * min, Math.min(base * max, ref));
    }

    /** Whether players have traded {@code itemId} with each other, so its trade price is more than the default. */
    public boolean traded(String itemId) {
        return data.reference.containsKey(itemId);
    }

    public long referenceFor(ItemRecord record) {
        ItemDef def = plugin.registry().get(record.id);
        if (def == null) {
            return 0;
        }
        double ratio = def.value() <= 0 ? 1 : plugin.items().value(record) / (double) (def.value() * Math.max(1, record.amount));
        return Math.max(1, Math.round(reference(record.id) * ratio * Math.max(1, record.amount)));
    }

    // ------------------------------------------------------------------ system buyers

    private int virtualCap() {
        return plugin.getConfig().getInt("market.virtual-daily-cap", 10);
    }

    /** Share of an item's value system buyers pay at most (market.virtual-max-value). */
    public double virtualMaxValue() {
        return plugin.getConfig().getDouble("market.virtual-max-value", 0.7);
    }

    private Rarity virtualMaxRarity() {
        try {
            return Rarity.parse(plugin.getConfig().getString("market.virtual-max-rarity", "PURPLE"));
        } catch (IllegalArgumentException ex) {
            return Rarity.PURPLE;
        }
    }

    /** "系统买家只收「稀有」及以下品质的物品" in the configured rarity's colour (MiniMessage). */
    public String virtualRarityNote() {
        Rarity max = virtualMaxRarity();
        return "系统买家只收" + max.tag() + "「" + max.displayName() + "」</" + max.hex() + ">及以下品质的物品";
    }

    /**
     * Most a system buyer pays for {@code record} (its value with wear, attachments, loaded rounds
     * and stack size, times market.virtual-max-value), or 0 when they never buy it: anything rarer
     * than market.virtual-max-rarity, so gold and red finds only sell to players or the trader.
     */
    public long virtualPrice(ItemRecord record) {
        ItemDef def = plugin.registry().get(record.id);
        if (def == null || def.rarity().ordinal() > virtualMaxRarity().ordinal()) {
            return 0;
        }
        return Math.round(plugin.items().value(record) * virtualMaxValue());
    }

    /**
     * Chance a system buyer takes a listing of {@code record} at {@code price} in one round (every
     * ten seconds): 0.3 at up to 85% of {@link #virtualPrice}, 0.12 up to 93%, 0.04 up to it, and
     * never above it.
     */
    public double virtualChance(ItemRecord record, long price) {
        long cap = virtualPrice(record);
        if (cap <= 0 || price > cap) {
            return 0;
        }
        double ratio = price / (double) cap;
        return ratio <= 0.85 ? 0.3 : ratio <= 0.93 ? 0.12 : 0.04;
    }

    private static String saleKey(UUID seller, String itemId) {
        return seller + ":" + itemId;
    }

    /** Starts a new quota day (04:00, like the market day) when the stored one is over. */
    private void rollVirtualDay() {
        long day = today();
        if (data.virtualDay != day) {
            data.virtualDay = day;
            data.virtualSales.clear();
            dirty = true;
        }
    }

    /** True when system buyers have taken today's quota of {@code seller}'s {@code itemId} and will wait. */
    public boolean virtualCapped(UUID seller, String itemId) {
        int cap = virtualCap();
        if (cap <= 0) {
            return false;
        }
        rollVirtualDay();
        return data.virtualSales.getOrDefault(saleKey(seller, itemId), 0) >= cap;
    }

    public List<Long> history(String itemId) {
        return data.history.getOrDefault(itemId, List.of());
    }

    public List<Offer> offers(net.infnetwork.snowball.craftov.item.@Nullable Category category) {
        Map<String, Offer> out = new LinkedHashMap<>();
        for (Listing listing : data.listings) {
            ItemDef def = plugin.registry().get(listing.item.id);
            if (def == null || (category != null && def.category() != category)) {
                continue;
            }
            long unit = listing.price / Math.max(1, listing.item.amount);
            Offer old = out.get(def.id());
            if (old == null) {
                out.put(def.id(), new Offer(def.id(), unit, 1, anchorUnit(def.id()), reference(def.id()), traded(def.id())));
            } else {
                out.put(def.id(), new Offer(def.id(), Math.min(old.lowest(), unit), old.count() + 1, old.anchor(), old.reference(),
                        old.traded()));
            }
        }
        List<Offer> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparing(o -> o.itemId()));
        return list;
    }

    public List<Listing> listingsFor(String itemId) {
        List<Listing> out = new ArrayList<>();
        for (Listing listing : data.listings) {
            if (listing.item.id.equals(itemId)) {
                out.add(listing);
            }
        }
        out.sort(Comparator.comparingLong(l -> l.price / Math.max(1, l.item.amount)));
        return out;
    }

    public List<Listing> mine(UUID seller) {
        List<Listing> out = new ArrayList<>();
        for (Listing listing : data.listings) {
            if (listing.seller.equals(seller)) {
                out.add(listing);
            }
        }
        return out;
    }

    /** Moves a stash record onto the market. */
    public boolean list(Player player, int stashIndex, long price) {
        Profile profile = plugin.profiles().get(player);
        if (refuseIfClosed(player) || stashIndex < 0 || stashIndex >= profile.stash.size()) {
            return false;
        }
        int max = plugin.getConfig().getInt("market.max-listings", 12);
        if (mine(player.getUniqueId()).size() >= max) {
            Text.send(player, "<#FF8A80>最多同时上架 " + max + " 件物品。");
            return false;
        }
        ItemRecord record = profile.stash.get(stashIndex);
        ItemDef def = plugin.registry().get(record.id);
        if (def == null || !def.tradeable()) {
            Text.send(player, "<#FF8A80>该物品无法上架。");
            return false;
        }
        if (record.isKit()) {
            Text.send(player, "<#FF8A80>免费补给的物品不能上架交易行。");
            return false;
        }
        if (record.hasContents()) {
            Text.send(player, "<#FF8A80>请先清空胸挂/背包再上架。");
            return false;
        }
        if (profile.level < minBuyLevel() && virtualPrice(record) <= 0) {
            // Only system buyers take a low-level seller's listings, and they never take this one.
            Text.send(player, "<#FF8A80>账号等级 " + minBuyLevel() + " 前只有系统买家会收购你的商品，而" + virtualRarityNote()
                    + "<#FF8A80>。<#9AA0A6>可以卖给商人，或升到 " + minBuyLevel() + " 级再上架。");
            return false;
        }
        long cap = maxPrice(record);
        if (price > cap) {
            Text.send(player, "<#FF8A80>售价不能超过参考价的 " + Text.decimal(maxPriceMultiplier()) + " 倍（最高 ◈" + Text.money(cap) + "）。");
            return false;
        }
        long deposit = Math.round(price * plugin.getConfig().getDouble("market.listing-fee", 0.02));
        if (profile.money < deposit) {
            Text.send(player, "<#FF8A80>上架手续费不足（◈" + Text.money(deposit) + "）");
            return false;
        }
        profile.money -= deposit;
        profile.stash.remove(stashIndex);
        Listing listing = new Listing();
        listing.id = Long.toHexString(ThreadLocalRandom.current().nextLong());
        listing.seller = player.getUniqueId();
        listing.sellerName = player.getName();
        listing.item = record;
        listing.price = Math.max(1, price);
        listing.created = System.currentTimeMillis();
        listing.expires = listing.created + plugin.getConfig().getLong("market.listing-hours", 48) * 3600_000L;
        data.listings.add(listing);
        dirty = true;
        // The stash first: a crash before the market write costs the listed item, but can never
        // leave it both in the stash and on the market.
        plugin.profiles().saveNow(player.getUniqueId());
        save();
        Text.send(player, "<#5BD16B>已上架 " + def.coloredName() + " ×" + record.amount + " <#8A8F98>售价 ◈" + Text.money(listing.price)
                + " · 手续费 ◈" + Text.money(deposit));
        if (profile.level < minBuyLevel()) {
            long virtual = virtualPrice(record);
            // Allowed (the seller may reach the level while it is listed), but said plainly.
            Text.send(player, "<#FFC23D>账号等级 " + minBuyLevel() + " 前，其他玩家不能购买你的商品，只有系统买家会收购"
                    + (listing.price > virtual ? "，而这个售价高于系统收购价 ◈" + Text.money(virtual) + "：升到 " + minBuyLevel()
                    + " 级前不会卖出，可以下架后重新定价。" : "。"));
        }
        return true;
    }

    public boolean buy(Player player, String listingId) {
        if (refuseIfClosed(player)) {
            return false;
        }
        Listing listing = find(listingId);
        if (listing == null) {
            Text.send(player, "<#FF8A80>该商品已售出或下架。");
            return false;
        }
        if (listing.seller.equals(player.getUniqueId())) {
            Text.send(player, "<#FF8A80>不能购买自己的商品，可在“我的上架”中下架。");
            return false;
        }
        Profile buyer = plugin.profiles().get(player);
        String blocked = tradeBlock(player, buyer, listing);
        if (blocked != null) {
            Text.send(player, "<#FF8A80>" + blocked);
            return false;
        }
        if (buyer.money < listing.price) {
            Text.send(player, "<#FF8A80>块币不足。");
            return false;
        }
        if (buyer.stashUsed() + listing.item.footprint() > buyer.stashCapacity) {
            Text.send(player, "<#FF8A80>仓库空间不足。");
            return false;
        }
        if (!plugin.profiles().canEdit(listing.seller)) {
            Text.send(player, "<#FF8A80>卖家的档案暂时无法读取，这件商品暂停出售。");
            return false;
        }
        addTradeGain(buyer, bargain(listing));
        buyer.money -= listing.price;
        buyer.stash.add(listing.item.copy());
        complete(listing, player.getName(), true);
        commit(player.getUniqueId());
        player.playSound(player.getLocation(), "craftov:ui.buy", 1f, 1f);
        ItemDef def = plugin.registry().get(listing.item.id);
        Text.send(player, "<#5BD16B>购入 " + (def == null ? listing.item.id : def.coloredName()) + " ×" + listing.item.amount
                + " <#8A8F98>（已存入仓库）-◈" + Text.money(listing.price));
        return true;
    }

    public int minBuyLevel() {
        return plugin.getConfig().getInt("market.min-buy-level", 3);
    }

    /** Whether players may buy {@code seller}'s listings (system buyers always may). */
    public boolean sellerCanTrade(UUID seller) {
        return plugin.profiles().level(seller) >= minBuyLevel();
    }

    /**
     * Why {@code buyer} may not buy another player's {@code listing}, or null when they may. These
     * rules only cover sales between players: the plugin cannot tell a second account from a
     * friend in offline mode, so they make funnelling a fresh account's starting money costly.
     * The level applies to both sides: a fresh account could otherwise hand its money over the
     * other way, by listing trader goods at ◈1 for the other account to buy and resell.
     */
    private @Nullable String tradeBlock(Player player, Profile buyer, Listing listing) {
        int level = minBuyLevel();
        if (buyer.level < level) {
            return "购买其他玩家的商品需要账号等级 " + level + "（当前 Lv." + buyer.level + "），商人处的商品不受限制。";
        }
        if (!sellerCanTrade(listing.seller)) {
            return "卖家账号等级不足 " + level + "，这件商品只卖给系统买家。";
        }
        boolean sameIp = plugin.getConfig().getBoolean("market.block-same-ip", false);
        long incomeCap = plugin.getConfig().getLong("market.daily-player-income-cap", 0);
        long bargain = bargain(listing);
        if (incomeCap > 0 && bargain > 0 && incomeToday(buyer) + bargain > incomeCap) {
            return "这件商品比参考价低 ◈" + Text.money(bargain) + "，你今天从其他玩家处的交易行收益已达上限（◈"
                    + Text.money(incomeCap) + "），明天再来吧。";
        }
        if (!sameIp && incomeCap <= 0) {
            return null;
        }
        Profile seller = plugin.profiles().peek(listing.seller);
        if (seller == null) {
            return null;
        }
        if (sameIp && sharesAddress(player, buyer, seller)) {
            return "你和卖家从同一网络地址登录过，不能购买对方的商品。";
        }
        if (incomeCap > 0 && incomeToday(seller) + payout(listing) > incomeCap) {
            return "卖家今天从其他玩家处的交易行收入已达上限（◈" + Text.money(incomeCap) + "），明天再来吧。";
        }
        return null;
    }

    /** How far below the reference price a player buying {@code listing} pays: value handed to the buyer. */
    private long bargain(Listing listing) {
        return Math.max(0, anchorFor(listing.item) - listing.price);
    }

    /** Counts {@code amount} towards the player's market gains from other players today (daily cap). */
    private static void addTradeGain(Profile profile, long amount) {
        if (amount <= 0) {
            return;
        }
        long day = today();
        if (profile.marketDay != day) {
            profile.marketDay = day;
            profile.marketIncome = 0;
        }
        profile.marketIncome += amount;
    }

    /** Market day: rolls over at 04:00 like the daily contracts. */
    private static long today() {
        return LocalDateTime.now().minusHours(4).toLocalDate().toEpochDay();
    }

    private static long incomeToday(Profile profile) {
        return profile.marketDay == today() ? profile.marketIncome : 0;
    }

    private long payout(Listing listing) {
        return listing.price - Math.round(listing.price * plugin.getConfig().getDouble("market.sale-tax", 0.05));
    }

    private static boolean sharesAddress(Player player, Profile buyer, Profile seller) {
        Set<String> mine = new HashSet<>(buyer.ips);
        String now = address(player);
        if (now != null) {
            mine.add(now);
        }
        Player online = Bukkit.getPlayer(seller.uuid);
        String theirs = online == null ? null : address(online);
        return (theirs != null && mine.contains(theirs)) || seller.ips.stream().anyMatch(mine::contains);
    }

    private static @Nullable String address(Player player) {
        InetSocketAddress socket = player.getAddress();
        return socket == null || socket.getAddress() == null ? null : socket.getAddress().getHostAddress();
    }

    /** Remembers the last few login addresses for the same-address check (market.block-same-ip). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String ip = address(player);
        if (ip == null) {
            return;
        }
        Profile profile = plugin.profiles().get(player);
        profile.ips.remove(ip);
        profile.ips.add(ip);
        while (profile.ips.size() > 4) {
            profile.ips.remove(0);
        }
        plugin.profiles().markDirty(player.getUniqueId());
    }

    public boolean cancel(Player player, String listingId) {
        if (refuseIfClosed(player)) {
            return false;
        }
        Listing listing = find(listingId);
        if (listing == null || !listing.seller.equals(player.getUniqueId())) {
            return false;
        }
        takeOff(listing);
        Profile profile = plugin.profiles().get(player);
        profile.mailbox.add(listing.item);
        plugin.profiles().saveNow(player.getUniqueId());
        Text.send(player, "<#9AA0A6>已下架，物品已退回仓库邮箱。");
        return true;
    }

    private @Nullable Listing find(String id) {
        for (Listing listing : data.listings) {
            if (listing.id.equals(id)) {
                return listing;
            }
        }
        return null;
    }

    private void complete(Listing listing, String buyerName, boolean playerSale) {
        takeOff(listing);
        long payout = payout(listing);
        long tax = listing.price - payout;
        // Both callers have checked canEdit, so the seller can be paid now and nothing is journaled.
        plugin.profiles().edit(listing.seller, () -> "market sale of listing " + listing.id + " (" + listing.item.id + " x"
                + listing.item.amount + "): money +" + payout + ", stats.market_sales +1, stats.market_income +" + payout
                + (playerSale && payout > 0 ? ", marketIncome +" + payout + " (only if marketDay is " + today() + ")" : ""), seller -> {
            seller.money += payout;
            seller.addStat("market_sales", 1);
            seller.addStat("market_income", payout);
            if (playerSale) {
                addTradeGain(seller, payout);
            }
        });
        plugin.profiles().saveNow(listing.seller);
        recordSale(listing, playerSale);
        dirty = true;
        Player seller = Bukkit.getPlayer(listing.seller);
        if (seller != null) {
            ItemDef def = plugin.registry().get(listing.item.id);
            Text.send(seller, "<#5BD16B>交易行：你的 " + (def == null ? listing.item.id : def.coloredName()) + " ×" + listing.item.amount
                    + " 已被 " + Text.escape(buyerName) + " 买下，入账 ◈" + Text.money(payout) + " <#8A8F98>(税 ◈" + Text.money(tax) + ")");
            seller.playSound(seller.getLocation(), "craftov:ui.sell", 1f, 1.2f);
        }
    }

    /**
     * Logs a sale in the price history and, for a sale between players, moves the trade price.
     * The price is first brought down to one plain unit by dividing by the record's value, so
     * attachments, loaded ammo, wear and stack size do not leak into the bare item's price. Sales
     * to system buyers, and sales far from the reference price (collusion, dumping), never move it.
     */
    private void recordSale(Listing listing, boolean playerSale) {
        String id = listing.item.id;
        double base = anchorUnit(id);
        double anchor = plugin.items().value(listing.item) * multiplier();
        if (base <= 0 || anchor <= 0) {
            return;
        }
        double ratio = listing.price / anchor;
        double unit = ratio * base;
        List<Long> history = data.history.computeIfAbsent(id, k -> new ArrayList<>());
        history.add(Math.round(unit));
        while (history.size() > 20) {
            history.remove(0);
        }
        if (!playerSale || ratio < plugin.getConfig().getDouble("market.trade-outlier-min", 0.5)
                || ratio > plugin.getConfig().getDouble("market.trade-outlier-max", 2.0)) {
            return;
        }
        data.reference.put(id, reference(id) * 0.85 + unit * 0.15);
    }

    /**
     * Expiry and simulated buyers. A listing whose seller's profile cannot be used right now (an
     * unreadable file waiting for an admin) is left alone, neither returned nor sold, so the item
     * and its price stay in the market until the profile can take them.
     */
    private void tick() {
        long now = System.currentTimeMillis();
        boolean npc = plugin.getConfig().getBoolean("market.virtual-buyers", true);
        long minAge = plugin.getConfig().getLong("market.virtual-min-age-seconds", 45) * 1000L;
        boolean counting = virtualCap() > 0;
        rollVirtualDay();
        for (Listing listing : new ArrayList<>(data.listings)) {
            if (now >= listing.expires) {
                java.util.function.Supplier<String> change = () -> "market listing " + listing.id + " expired: add to mailbox "
                        + ProfileService.json(listing.item);
                if (plugin.profiles().canEdit(listing.seller)) {
                    takeOff(listing); // off the market on disk before the item reaches the mailbox
                    plugin.profiles().edit(listing.seller, change, p -> p.mailbox.add(listing.item));
                    plugin.profiles().saveNow(listing.seller);
                } else if (plugin.profiles().tryEdit(listing.seller, change, p -> p.mailbox.add(listing.item))) {
                    takeOff(listing); // a seller with no account at all: the return is only logged
                }
                continue;
            }
            if (!npc || now - listing.created < minAge || virtualCapped(listing.seller, listing.item.id)) {
                continue;
            }
            // Measured against the item's own value, never the trade-driven reference: otherwise
            // each sale would move the bar for the next one.
            double chance = virtualChance(listing.item, listing.price);
            if (ThreadLocalRandom.current().nextDouble() < chance && plugin.profiles().canEdit(listing.seller)) {
                if (counting) {
                    data.virtualSales.merge(saleKey(listing.seller, listing.item.id), 1, Integer::sum);
                }
                complete(listing, "系统买家", false);
            }
        }
        save();
    }

    public int listingCount() {
        return data.listings.size();
    }
}
