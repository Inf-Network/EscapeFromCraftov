package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.match.Party;
import net.infnetwork.snowball.craftov.match.QueueEntry;
import net.infnetwork.snowball.craftov.raid.RaidMode;
import net.infnetwork.snowball.craftov.raid.Tier;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deploy screen: map, difficulty, solo or squad, ranked, fill and insurance, then queue. Test maps are
 * listed only for players who may enter them, and are never picked for anyone else, not even from the
 * choice remembered from their last deploy. Unpublished editor drafts are not listed at all; a map
 * whose template is being edited in place is listed as under maintenance and cannot be queued for.
 */
final class DeployMenu extends Menu {

    /** Last choice per player (map, tier, mode, ranked), until the server restarts. */
    private static final Map<UUID, String[]> LAST = new HashMap<>();

    private final CraftovPlugin plugin;
    private String map;
    private String tier;
    private RaidMode mode;
    private boolean ranked;
    private boolean fill = true;

    DeployMenu(CraftovPlugin plugin, Player viewer) {
        super(viewer, 5, Text.mm("<#F2E6C8>部署行动"));
        this.plugin = plugin;
        String[] last = LAST.get(viewer.getUniqueId());
        this.map = validMap(last != null ? last[0] : null);
        this.tier = last != null && plugin.tier(last[1]) != null ? last[1] : plugin.tiers().keySet().iterator().next();
        Party party = plugin.match().partyOf(viewer.getUniqueId());
        this.mode = party != null && party.size() > 1 ? RaidMode.TRIO : last != null ? RaidMode.valueOf(last[2]) : RaidMode.TRIO;
        this.ranked = last != null && Boolean.parseBoolean(last[3]);
    }

    /** Maps this player may queue for, in registry order. */
    private List<MapDef> playable() {
        List<MapDef> out = new ArrayList<>();
        for (MapDef def : plugin.maps().all()) {
            if (plugin.canPlay(viewer, def)) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * The wanted map if this player may still queue for it (test access can be lost, a map can be
     * deleted), otherwise the first playable map that is not a test map, then any playable map.
     */
    private String validMap(@Nullable String wanted) {
        List<MapDef> maps = playable();
        for (MapDef def : maps) {
            if (def.id().equals(wanted)) {
                return def.id();
            }
        }
        for (MapDef def : maps) {
            if (!def.test()) {
                return def.id();
            }
        }
        return maps.isEmpty() ? "" : maps.get(0).id();
    }

    @Override
    protected void draw() {
        long value = plugin.carry().gearValue(viewer);
        map = validMap(map);
        int slot = 10;
        for (MapDef def : playable()) {
            boolean selected = def.id().equals(map);
            int waiting = plugin.match().queuedCount(def.id(), tier);
            List<String> mapLore = new ArrayList<>(List.of("<#9AA0A6>" + Text.escape(def.description()),
                    "<#8A8F98>时长 " + def.raidSeconds() / 60 + " 分钟 · 撤离点 " + def.extracts().size() + " · 物资点 " + def.containers().size(),
                    "<#8A8F98>每局最多 " + plugin.match().capacity(def) + " 支小队"
                            + (waiting > 0 ? " · <#F2C94C>当前难度匹配中 " + waiting + " 人" : "")));
            if (def.test()) {
                mapLore.add("<#FFB74D>测试图 · 只对测试模式和管理员开放");
            }
            boolean maintenance = plugin.editor().underMaintenance(def);
            if (maintenance) {
                mapLore.add("<#FF8A80>维护中 · 地图正在编辑，暂时不能匹配");
            }
            mapLore.add(selected ? "<#5BD16B>已选择" : "<#8A8F98>点击选择");
            button(slot++, UiItems.button("craftov:ui/icon_map", (selected ? "<#F2C94C>▶ " : "<white>") + "<b>" + Text.escape(def.name())
                            + (maintenance ? " <#FF8A80>[维护中]" : ""), mapLore),
                    e -> {
                        map = def.id();
                        redraw();
                    });
            if (slot > 16) {
                break;
            }
        }
        int[] tierSlots = {20, 22, 24};
        int i = 0;
        for (Tier t : plugin.tiers().values()) {
            if (i >= tierSlots.length) {
                break;
            }
            boolean selected = t.id().equals(tier);
            boolean ok = value >= t.minValue();
            List<String> lore = new ArrayList<>();
            lore.add("<#8A8F98>战备要求 " + (ok ? "<#5BD16B>" : "<#FF8A80>") + "◈" + Text.money(t.minValue()));
            lore.add("<#8A8F98>物资品质 <white>" + "★".repeat(t.lootShift() + 1) + "<#3A3F47>" + "★".repeat(2 - t.lootShift()));
            lore.add("<#8A8F98>敌兵强度 <white>" + Math.round(t.aiAccuracy() * 100) + "%" + (t.boss() ? " <#FF4D4D>· 首领出没" : ""));
            if (t.entryFee() > 0) {
                lore.add("<#8A8F98>入场费 ◈" + Text.money(t.entryFee()));
            }
            lore.add(selected ? "<#5BD16B>已选择" : "<#8A8F98>点击选择");
            button(tierSlots[i++], UiItems.button("craftov:ui/tier_" + t.id().toLowerCase(), (selected ? "▶ " : "") + t.tag(), lore), e -> {
                tier = t.id();
                redraw();
            });
        }
        button(29, UiItems.button("craftov:ui/icon_solo", (mode == RaidMode.SOLO ? "<#F2C94C>▶ " : "<white>") + "单人行动",
                List.of("<#9AA0A6>独自潜入，致命伤直接阵亡", "<#9AA0A6>带着自救注射器时会倒地，可自己救起")), e -> {
            mode = RaidMode.SOLO;
            redraw();
        });
        button(30, UiItems.button("craftov:ui/icon_party", (mode == RaidMode.TRIO ? "<#F2C94C>▶ " : "<white>") + "三人小队",
                List.of("<#9AA0A6>可倒地并由队友救援", "<#9AA0A6>开启自动补位时，不足 3 人会与其他玩家组队")), e -> {
            mode = RaidMode.TRIO;
            redraw();
        });
        button(32, UiItems.button("craftov:ui/icon_rank", (ranked ? "<#FFC23D>● 排位赛" : "<#8A8F98>○ 排位赛"),
                List.of("<#9AA0A6>撤离按净收益加减排位分（亏损最多扣 20）", "<#9AA0A6>击败干员加分，阵亡扣分", "<#9AA0A6>只影响排位分，不影响匹配",
                        "<#8A8F98>需要 Lv." + plugin.getConfig().getInt("rank.min-level", 3))),
                e -> {
                    ranked = !ranked;
                    redraw();
                });
        button(33, UiItems.button("craftov:ui/icon_fill", (fill ? "<#5BD16B>● 自动补位" : "<#8A8F98>○ 自动补位"),
                List.of("<#9AA0A6>三人模式下与其他玩家组成小队")), e -> {
            fill = !fill;
            redraw();
        });
        var profile = plugin.profiles().get(viewer);
        var insurance = plugin.insurance();
        long fee = insurance.quote(viewer);
        long minutes = insurance.returnMinutes();
        button(34, UiItems.button("craftov:ui/icon_secure", (profile.autoInsure ? "<#5BD16B>● 装备保险" : "<#8A8F98>○ 装备保险"),
                List.of("<#9AA0A6>出发时为枪械、护甲、胸挂和背包投保",
                        "<#9AA0A6>费用为装备价值的 " + percent(insurance.rate()) + "（<#FFC23D>金色<#9AA0A6> "
                                + percent(insurance.rate(net.infnetwork.snowball.craftov.item.Rarity.GOLD)) + "、<#FF4D4D>红色<#9AA0A6> "
                                + percent(insurance.rate(net.infnetwork.snowball.craftov.item.Rarity.RED)) + "），本次约 <#F2C94C>◈" + Text.money(fee),
                        "<#9AA0A6>阵亡后没被别人带出的投保装备，对局结束时",
                        "<#9AA0A6>每件有 " + percent(1 - insurance.lossChance()) + " 的机会找回，"
                                + (minutes > 0 ? minutes + " 分钟后退回仓库邮箱" : "退回仓库邮箱"))), e -> {
            profile.autoInsure = !profile.autoInsure;
            plugin.profiles().markDirty(viewer.getUniqueId());
            redraw();
        });
        Party party = plugin.match().partyOf(viewer.getUniqueId());
        List<String> partyLore = new ArrayList<>();
        if (party == null) {
            partyLore.add("<#9AA0A6>单人");
        } else {
            for (UUID uuid : party.members) {
                Player p = Bukkit.getPlayer(uuid);
                partyLore.add((uuid.equals(party.leader) ? "<#F2C94C>★ " : "<white>· ") + (p == null ? "离线" : Text.escape(p.getName())));
            }
        }
        set(38, UiItems.button("craftov:ui/icon_party", "<white>小队成员", partyLore));
        set(42, UiItems.button("craftov:ui/icon_value", "<white>战备值 <#F2C94C>◈" + Text.money(value),
                List.of("<#9AA0A6>身上装备与物资的总价值", "<#9AA0A6>近战武器不计入（死亡不会丢失）", "<#9AA0A6>在仓库中调整配装后再出发")));
        QueueEntry queued = plugin.match().queued(viewer.getUniqueId());
        if (queued != null) {
            button(40, UiItems.button("craftov:ui/icon_cancel", "<#FF8A80><b>取消匹配", List.of("<#9AA0A6>正在匹配 " + queued.map() + " · " + queued.tier())),
                    e -> {
                        plugin.match().cancelQueue(viewer.getUniqueId(), "");
                        redraw();
                    });
        } else {
            button(40, UiItems.button("craftov:ui/icon_start", "<#5BD16B><b>开始匹配", List.of("<#9AA0A6>出发前请确认配装与弹药")), e -> {
                LAST.put(viewer.getUniqueId(), new String[]{map, tier, mode.name(), Boolean.toString(ranked)});
                if (plugin.match().enqueue(viewer, map, tier, mode, ranked, fill)) {
                    viewer.closeInventory();
                }
            });
        }
    }

    private static String percent(double share) {
        return Math.round(share * 100) + "%";
    }
}
