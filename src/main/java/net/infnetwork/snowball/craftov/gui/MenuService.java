package net.infnetwork.snowball.craftov.gui;

import net.infnetwork.snowball.craftov.CraftovPlugin;
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
        return true;
    }

    public void openMain(Player player) {
        if (lobbyOnly(player)) {
            new MainMenu(plugin, player).open();
        }
    }

    public void openTerminal(Player player) {
        new TerminalMenu(plugin, player).open();
    }

    public void openStation(Player player, String station) {
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
            case "range_rack" -> {
                if (plugin.range().isIn(player)) {
                    new RangeMenu(plugin, player).open();
                } else {
                    plugin.range().enter(player);
                }
            }
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

    public void openTrader(Player player) {
        if (lobbyOnly(player)) {
            new TraderMenu(plugin, player).open();
        }
    }

    public void openMarket(Player player) {
        if (lobbyOnly(player)) {
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
        new SecureMenu(plugin, player).open();
    }
}
