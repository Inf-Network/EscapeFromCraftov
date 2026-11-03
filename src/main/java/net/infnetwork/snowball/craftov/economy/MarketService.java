package net.infnetwork.snowball.craftov.economy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Player auction house with virtual listings: items leave the stash while listed, sell to other
 * players or to simulated buyers when priced near the reference price, and return on expiry.
 */
public final class MarketService {

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
        public List<Listing> listings = new ArrayList<>();
        public Map<String, Double> reference = new HashMap<>();
        public Map<String, List<Long>> history = new HashMap<>();
    }

    public record Offer(String itemId, long lowest, int count, double reference) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final CraftovPlugin plugin;
    private final Path file;
    private Data data = new Data();
    private boolean dirty;

    public MarketService(CraftovPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("data/market.json");
    }

    public void start() {
        try {
            if (Files.exists(file)) {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) {
                    data = loaded;
                }
            }
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().severe("Could not read market data: " + ex.getMessage());
        }
        if (data.listings == null) data.listings = new ArrayList<>();
        if (data.reference == null) data.reference = new HashMap<>();
        if (data.history == null) data.history = new HashMap<>();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 10, 20L * 10);
    }

    public void save() {
        if (!dirty) {
            return;
        }
        dirty = false;
        String json = GSON.toJson(data);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(json));
    }

    public void saveSync() {
        write(GSON.toJson(data));
    }

    private void write(String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("market.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save market data: " + ex.getMessage());
        }
    }

    public double reference(String itemId) {
        Double ref = data.reference.get(itemId);
        if (ref != null) {
            return ref;
        }
        ItemDef def = plugin.registry().get(itemId);
        return def == null ? 0 : def.value() * plugin.getConfig().getDouble("market.reference-multiplier", 0.85);
    }

    public long referenceFor(ItemRecord record) {
        ItemDef def = plugin.registry().get(record.id);
        if (def == null) {
            return 0;
        }
        double ratio = def.value() <= 0 ? 1 : plugin.items().value(record) / (double) (def.value() * Math.max(1, record.amount));
        return Math.max(1, Math.round(reference(record.id) * ratio * Math.max(1, record.amount)));
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
                out.put(def.id(), new Offer(def.id(), unit, 1, reference(def.id())));
            } else {
                out.put(def.id(), new Offer(def.id(), Math.min(old.lowest(), unit), old.count() + 1, old.reference()));
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
        if (stashIndex < 0 || stashIndex >= profile.stash.size()) {
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
        if (record.hasContents()) {
            Text.send(player, "<#FF8A80>请先清空胸挂/背包再上架。");
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
        plugin.profiles().markDirty(player.getUniqueId());
        Text.send(player, "<#5BD16B>已上架 " + def.coloredName() + " ×" + record.amount + " <#8A8F98>售价 ◈" + Text.money(listing.price)
                + " · 手续费 ◈" + Text.money(deposit));
        return true;
    }

    public boolean buy(Player player, String listingId) {
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
        if (buyer.money < listing.price) {
            Text.send(player, "<#FF8A80>块币不足。");
            return false;
        }
        if (buyer.stashUsed() + listing.item.footprint() > buyer.stashCapacity) {
            Text.send(player, "<#FF8A80>仓库空间不足。");
            return false;
        }
        buyer.money -= listing.price;
        buyer.stash.add(listing.item.copy());
        plugin.profiles().markDirty(player.getUniqueId());
        complete(listing, player.getName());
        player.playSound(player.getLocation(), "craftov:ui.buy", 1f, 1f);
        ItemDef def = plugin.registry().get(listing.item.id);
        Text.send(player, "<#5BD16B>购入 " + (def == null ? listing.item.id : def.coloredName()) + " ×" + listing.item.amount
                + " <#8A8F98>（已存入仓库）-◈" + Text.money(listing.price));
        return true;
    }

    public boolean cancel(Player player, String listingId) {
        Listing listing = find(listingId);
        if (listing == null || !listing.seller.equals(player.getUniqueId())) {
            return false;
        }
        data.listings.remove(listing);
        dirty = true;
        Profile profile = plugin.profiles().get(player);
        profile.mailbox.add(listing.item);
        plugin.profiles().markDirty(player.getUniqueId());
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

    private void complete(Listing listing, String buyerName) {
        data.listings.remove(listing);
        long tax = Math.round(listing.price * plugin.getConfig().getDouble("market.sale-tax", 0.05));
        long payout = listing.price - tax;
        plugin.profiles().edit(listing.seller, seller -> {
            seller.money += payout;
            seller.addStat("market_sales", 1);
        });
        double unit = listing.price / (double) Math.max(1, listing.item.amount);
        double ref = reference(listing.item.id);
        data.reference.put(listing.item.id, ref * 0.85 + unit * 0.15);
        List<Long> history = data.history.computeIfAbsent(listing.item.id, k -> new ArrayList<>());
        history.add(Math.round(unit));
        while (history.size() > 20) {
            history.remove(0);
        }
        dirty = true;
        Player seller = Bukkit.getPlayer(listing.seller);
        if (seller != null) {
            ItemDef def = plugin.registry().get(listing.item.id);
            Text.send(seller, "<#5BD16B>交易行：你的 " + (def == null ? listing.item.id : def.coloredName()) + " ×" + listing.item.amount
                    + " 已被 " + Text.escape(buyerName) + " 买下，入账 ◈" + Text.money(payout) + " <#8A8F98>(税 ◈" + Text.money(tax) + ")");
            seller.playSound(seller.getLocation(), "craftov:ui.sell", 1f, 1.2f);
        }
    }

    /** Expiry and simulated buyers. */
    private void tick() {
        long now = System.currentTimeMillis();
        boolean npc = plugin.getConfig().getBoolean("market.virtual-buyers", true);
        long minAge = plugin.getConfig().getLong("market.virtual-min-age-seconds", 45) * 1000L;
        for (Listing listing : new ArrayList<>(data.listings)) {
            if (now >= listing.expires) {
                data.listings.remove(listing);
                plugin.profiles().edit(listing.seller, p -> p.mailbox.add(listing.item));
                dirty = true;
                continue;
            }
            if (!npc || now - listing.created < minAge) {
                continue;
            }
            double ref = referenceFor(listing.item);
            double ratio = ref <= 0 ? 9 : listing.price / ref;
            double chance = ratio <= 0.9 ? 0.45 : ratio <= 1.0 ? 0.22 : ratio <= 1.1 ? 0.09 : ratio <= 1.25 ? 0.025 : 0;
            if (ThreadLocalRandom.current().nextDouble() < chance) {
                complete(listing, "系统买家");
            }
        }
        save();
    }

    public int listingCount() {
        return data.listings.size();
    }
}
