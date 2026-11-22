package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidPlayer;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.entity.Player;

/** Entry points for every menu. */
public final class MenuService {

    private final CraftovPlugin plugin;

    public MenuService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean lobbyOnly(Player player) {
        if (plugin.raids().raidOf(player) != null) {
            Text.send(player, "<#FF8A80>行动中无法使用该功能。");
            return false;
        }
        if (plugin.raids().isSpectating(player)) {
            Text.send(player, "<#FF8A80>观战中无法使用该功能，按 Shift 返回大厅。");
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
            Text.send(player, "<#FF8A80>靶场内无法使用，请先离开靶场。");
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
            new MainMenu(plugin, player).open();
            plugin.tutorial().done(player, net.infnetwork.snowball.craftov.lobby.TutorialService.Step.TERMINAL);
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
        if (plugin.canUseCatalog(player)) {
            new CatalogMenu(plugin, player).open();
        } else {
            Text.send(player, "<#FF8A80>测试军械库只在测试模式下开放。");
        }
    }

    public void openGunsmith(Player player) {
        new GunsmithMenu(plugin, player).open();
    }

    public void openSecure(Player player) {
        // Not lobbyOnly: the raid terminal opens the secure container mid-raid.
        if (!notInRange(player)) {
            return;
        }
        Raid raid = plugin.raids().raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        // Flying in, or dead (kill replay, then spectating a squadmate, when the player no longer
        // counts as in the raid): the raid has its own copy of the container, or death has just
        // settled it and the loadout, and outside the raid the menu would save the spectator's
        // empty inventory as the loadout.
        if (plugin.raids().isSpectating(player) || rp != null && raid.state != Raid.State.LOADING && !rp.active()) {
            Text.send(player, "<#FF8A80>入场途中或观战时无法打开安全箱。");
            return;
        }
        new SecureMenu(plugin, player).open();
    }
}
