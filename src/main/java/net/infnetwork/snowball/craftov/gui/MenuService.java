package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** Entry points for every menu. */
public final class MenuService {

    private static final String IN_RANGE = "靶场内无法使用，请先离开靶场。";
    private final CraftovPlugin plugin;

    public MenuService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean lobbyOnly(Player player) {
        if (plugin.raids().raidOf(player) != null) {
            Notify.error(player, "行动中无法使用该功能。");
            return false;
        }
        if (plugin.raids().isSpectating(player)) {
            Notify.error(player, "观战中无法使用该功能，按 <key:key.sneak> 返回大厅。");
            return false;
        }
        return notInRange(player);
    }

    /**
     * The range holds the real loadout aside and hands out loaners until the player leaves, so no
     * menu that moves items between the inventory and the profile (stash, trader, market, secure
     * container, presets, free kit) may open there.
     */
    private boolean notInRange(Player player) {
        if (plugin.range().isIn(player)) {
            Notify.error(player, IN_RANGE);
            return false;
        }
        return true;
    }

    public void openMain(Player player) {
        if (plugin.range().isIn(player)) {
            // On the range the terminal is the weapon rack, which also leads to the gunsmith.
            new RangeMenu(plugin, player).open();
            return;
        }
        if (lobbyOnly(player)) {
            // First, so the menu's guide checklist and the shine on the next step's button count it.
            plugin.tutorial().done(player, net.infnetwork.snowball.craftov.lobby.TutorialService.Step.TERMINAL);
            new MainMenu(plugin, player).open();
        }
    }

    public void openTerminal(Player player) {
        new TerminalMenu(plugin, player).open();
    }

    public void openStation(Player player, String station) {
        // The rack is the one station inside the range, so it is handled before lobbyOnly.
        if ("range_rack".equals(station)) {
            if (plugin.range().isIn(player)) {
                new RangeMenu(plugin, player).open();
            } else if (lobbyOnly(player)) {
                plugin.range().enter(player);
            }
            return;
        }
        if (!lobbyOnly(player)) {
            return;
        }
        switch (station) {
            case "deploy" -> openDeploy(player);
            case "stash" -> openStash(player);
            case "trader" -> openTrader(player);
            case "market" -> openMarket(player);
            case "operator" -> new OperatorMenu(plugin, player).open();
            case "escort" -> new EscortMenu(plugin, player).open();
            case "collection" -> new CollectionMenu(plugin, player).open();
            case "rank", "rank_board" -> new RankMenu(plugin, player).open();
            case "tasks" -> new TaskMenu(plugin, player, false).open();
            case "hideout" -> new HideoutMenu(plugin, player).open();
            default -> openMain(player);
        }
    }

    public void openDeploy(Player player) {
        if (lobbyOnly(player)) {
            new DeployMenu(plugin, player).open();
        }
    }

    public void openStash(Player player) {
        if (lobbyOnly(player)) {
            new StashMenu(plugin, player).open();
        }
    }

    /** The stash mailbox: system letters and the item mailbox. */
    public void openMail(Player player) {
        if (lobbyOnly(player)) {
            new MailMenu(plugin, player).open();
        }
    }

    public void openTrader(Player player) {
        if (lobbyOnly(player)) {
            new TraderMenu(plugin, player).open();
        }
    }

    public void openMarket(Player player) {
        if (lobbyOnly(player) && !plugin.market().refuseIfClosed(player)) {
            new MarketMenu(plugin, player).open();
        }
    }

    public void openCatalog(Player player) {
        if (!plugin.canUseCatalog(player)) {
            Notify.error(player, "测试军械库只在测试模式下开放。");
            return;
        }
        // It hands out self-revive injectors and has a full-heal button: a downed player who could
        // open it would stand up without anyone's help (the terminal and /cv items both lead here).
        if (plugin.downed().isDowned(player)) {
            refuse(player, "倒地时无法使用测试军械库");
            return;
        }
        new CatalogMenu(plugin, player).open();
    }

    public void openGunsmith(Player player) {
        if (plugin.downed().isDowned(player)) {
            refuse(player, "倒地时无法改枪");
            return;
        }
        new GunsmithMenu(plugin, player).open();
    }

    public void openSecure(Player player) {
        String refused = secureRefusal(player);
        if (refused != null) {
            refuse(player, refused);
            return;
        }
        new SecureMenu(plugin, player).open();
    }

    /**
     * Why the player cannot use the secure container now, or null if they can. Asked when it opens
     * and again for every item moved in or out, since the menu can be open when this changes. Not
     * lobbyOnly: the raid terminal opens the secure container mid-raid.
     */
    @Nullable String secureRefusal(Player player) {
        if (plugin.range().isIn(player)) {
            return IN_RANGE;
        }
        Raid raid = plugin.raids().raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        // Flying in, or dead (kill replay, then spectating a squadmate, when the player no longer
        // counts as in the raid): the raid has its own copy of the container, or death has just
        // settled it and the loadout, and outside the raid the menu would save the spectator's
        // empty inventory as the loadout.
        if (plugin.raids().isSpectating(player) || rp != null && raid.state != Raid.State.LOADING && !rp.active()) {
            return "入场途中或观战时无法打开安全箱。";
        }
        // Downed still counts as active, but a crawling player who could reach the container would
        // put the loot their killer is about to take out of reach (or pull an injector out of it).
        if (rp != null && rp.state == RaidPlayer.State.DOWNED || plugin.downed().isDowned(player)) {
            return "倒地时无法使用安全箱";
        }
        return null;
    }

    /**
     * Says why something did not open. A downed player's action bar is held by the bleed-out bar,
     * which would swallow the reason, so the reason goes on that bar as well.
     */
    void refuse(Player player, String reason) {
        Notify.error(player, reason);
        if (plugin.downed().isDowned(player)) {
            plugin.downed().refused(player, reason);
        }
    }
}
