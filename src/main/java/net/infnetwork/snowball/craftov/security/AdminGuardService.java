package net.infnetwork.snowball.craftov.security;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps operator accounts to trusted addresses while the server runs in offline mode.
 *
 * <p>With online-mode=false nothing checks who a player is: the UUID comes from the name alone, so
 * anyone who types an operator's name into an offline launcher passes the whitelist as that
 * operator and gets /op, /stop and every /cv admin command. While that is so, a login by an
 * operator is refused unless it comes from {@code security.admin-addresses} (loopback and the
 * private networks by default: the server's own machine and LAN). The guard only knows the ops
 * list; ordinary players' accounts, and craftov.admin handed out by a permissions plugin, are not
 * covered: for those the server needs online-mode=true or a login plugin.
 */
public final class AdminGuardService implements Listener {

    /** Loopback and private networks, IPv4 and IPv6: where the owner sits next to the server. */
    private static final List<String> DEFAULT_ADDRESSES = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
            "192.168.0.0/16", "fc00::/7", "fe80::/10");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final CraftovPlugin plugin;
    /** Operator UUIDs, copied on the main thread: the ops list is not meant to be read from the login thread. */
    private volatile Set<UUID> operators = Set.of();
    /** The address list as last parsed, so a /cv reload that changes it is picked up. */
    private @Nullable List<String> parsedFrom;
    private List<Block> trusted = List.of();

    public AdminGuardService(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        refreshOperators();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOperators, 20L, 20L);
        trusted(); // reports bad entries now rather than at the first login
        if (!offlineMode()) {
            return;
        }
        plugin.getLogger().warning("==================================================================");
        plugin.getLogger().warning("online-mode=false: nothing checks who players are. Anyone who can reach this port can log in"
                + " under any player's name and take over that account, an operator's included.");
        if (active()) {
            plugin.getLogger().warning("Operators may only log in from security.admin-addresses: " + String.join(", ", addresses())
                    + ". Every other account, stash and all, is open to whoever types its name.");
        } else {
            plugin.getLogger().warning("security.admin-guard is off: operators can be impersonated from anywhere too.");
        }
        plugin.getLogger().warning("Before opening the port to the internet, set online-mode=true or install a login plugin.");
        plugin.getLogger().warning("==================================================================");
    }

    private void refreshOperators() {
        Set<UUID> ops = new HashSet<>();
        for (OfflinePlayer op : Bukkit.getOperators()) {
            ops.add(op.getUniqueId());
        }
        operators = Set.copyOf(ops);
    }

    /** Nobody vouches for a player's name: offline mode, and no proxy in front doing the online check. */
    private static boolean offlineMode() {
        return !Bukkit.getOnlineMode() && !Bukkit.getServer().getServerConfig().isProxyOnlineMode();
    }

    /** security.admin-guard: auto (only in offline mode), always, or off. YAML reads a bare off/on as false/true. */
    private boolean active() {
        String mode = String.valueOf(plugin.getConfig().getString("security.admin-guard", "auto")).toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "off", "false" -> false;
            case "always", "on", "true" -> true;
            default -> offlineMode();
        };
    }

    private List<String> addresses() {
        return plugin.getConfig().isList("security.admin-addresses") ? plugin.getConfig().getStringList("security.admin-addresses")
                : DEFAULT_ADDRESSES;
    }

    /** security.admin-addresses, parsed again whenever it changed (a /cv reload). Called from the login thread too. */
    private synchronized List<Block> trusted() {
        List<String> raw = addresses();
        if (!raw.equals(parsedFrom)) {
            List<Block> blocks = new ArrayList<>();
            for (String entry : raw) {
                Block block = Block.parse(entry);
                if (block == null) {
                    plugin.getLogger().warning("security.admin-addresses: \"" + entry + "\" is not an IP address or a block like"
                            + " 192.168.0.0/16; ignored.");
                } else {
                    blocks.add(block);
                }
            }
            trusted = List.copyOf(blocks);
            parsedFrom = List.copyOf(raw);
        }
        return trusted;
    }

    private boolean isTrusted(InetAddress address) {
        return trusted().stream().anyMatch(block -> block.contains(address));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !operators.contains(event.getUniqueId()) || !active()) {
            return;
        }
        InetAddress address = event.getAddress();
        if (isTrusted(address)) {
            return;
        }
        String shown = address.getHostAddress();
        plugin.getLogger().warning("Refused the login of operator " + event.getName() + " (" + event.getUniqueId() + ") from " + shown
                + ": while nobody checks names (online-mode=false), operators may only log in from security.admin-addresses.");
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Text.mm("<#FF4D4D>这个账号是管理员账号，只能从服务器所在的局域网登录。"
                + "\n\n<#9AA0A6>服务器没有开启正版验证，任何人都能用这个名字进服。"
                + "\n<#9AA0A6>如果这就是你，请服主把你的地址 <white>" + Text.escape(shown) + "</white> 加进 Craftov 配置的 security.admin-addresses。"));
    }

    /** An address block: the first {@code prefix} bits of {@code network}. */
    record Block(byte[] network, int prefix) {

        /**
         * "192.168.0.0/16", "fc00::/7", or a single address. Literals only: a host name would be a
         * DNS lookup, and DNS is not something to trust with admin access.
         */
        static @Nullable Block parse(String text) {
            String s = text.trim();
            int slash = s.indexOf('/');
            String host = slash < 0 ? s : s.substring(0, slash);
            if (!IPV4.matcher(host).matches() && !host.contains(":")) {
                return null;
            }
            byte[] bytes;
            try {
                // In brackets a malformed IPv6 literal is an error; bare, it would go to DNS as a name.
                bytes = InetAddress.getByName(host.contains(":") ? "[" + host + "]" : host).getAddress();
            } catch (UnknownHostException | SecurityException ex) {
                return null;
            }
            int prefix = bytes.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(s.substring(slash + 1).trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (prefix < 0 || prefix > bytes.length * 8) {
                    return null;
                }
            }
            return new Block(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] a = address.getAddress();
            if (a.length != network.length) {
                return false;
            }
            int whole = prefix / 8;
            for (int i = 0; i < whole; i++) {
                if (a[i] != network[i]) {
                    return false;
                }
            }
            int bits = prefix % 8;
            if (bits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - bits)) & 0xFF;
            return (a[whole] & mask) == (network[whole] & mask);
        }
    }
}
