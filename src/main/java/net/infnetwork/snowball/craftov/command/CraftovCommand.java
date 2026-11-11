package net.infnetwork.snowball.craftov.command;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.raid.Raid;
import net.infnetwork.snowball.craftov.raid.RaidMode;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CraftovCommand implements TabExecutor {

    private static final List<String> PLAYER_SUBS = List.of("menu", "deploy", "stash", "trader", "market", "secure", "party",
            "leave", "help", "mod");
    private static final List<String> ADMIN_SUBS = List.of("give", "money", "raids", "end", "reload", "lobby", "tp", "editor", "injure", "event");

    private final CraftovPlugin plugin;

    public CraftovCommand(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("party")) {
            String[] shifted = new String[args.length + 1];
            shifted[0] = "party";
            System.arraycopy(args, 0, shifted, 1, args.length);
            args = shifted;
        }
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        Player player = sender instanceof Player p ? p : null;
        switch (sub) {
            case "menu" -> {
                if (player != null) {
                    if (plugin.raids().raidOf(player) != null) {
                        plugin.menus().openTerminal(player);
                    } else {
                        plugin.menus().openMain(player);
                    }
                }
            }
            case "deploy" -> {
                if (player != null) plugin.menus().openDeploy(player);
            }
            case "stash" -> {
                if (player != null) plugin.menus().openStash(player);
            }
            case "trader" -> {
                if (player != null) plugin.menus().openTrader(player);
            }
            case "market" -> {
                if (player != null) plugin.menus().openMarket(player);
            }
            case "secure" -> {
                if (player != null) plugin.menus().openSecure(player);
            }
            case "leave" -> {
                if (player != null) {
                    if (plugin.match().queued(player.getUniqueId()) != null) {
                        plugin.match().cancelQueue(player.getUniqueId(), "");
                    } else {
                        Text.send(player, "<#9AA0A6>你不在匹配队列中。行动中可通过战术终端放弃行动。");
                    }
                }
            }
            case "party" -> party(player, args);
            case "help" -> help(sender);
            case "items" -> {
                if (player != null) plugin.menus().openCatalog(player);
            }
            case "mod" -> {
                if (player != null) plugin.menus().openGunsmith(player);
            }
            case "queue" -> {
                if (player != null && args.length >= 3) {
                    RaidMode mode = args.length >= 4 ? RaidMode.valueOf(args[3].toUpperCase(Locale.ROOT)) : RaidMode.SOLO;
                    plugin.match().enqueue(player, args[1], args[2].toUpperCase(Locale.ROOT), mode, args.length >= 5 && Boolean.parseBoolean(args[4]), true);
                }
            }
            default -> {
                if (!sender.hasPermission("craftov.admin")) {
                    help(sender);
                    return true;
                }
                admin(sender, sub, args);
            }
        }
        return true;
    }

    private void party(Player player, String[] args) {
        if (player == null) {
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "invite" -> {
                if (args.length < 3) {
                    Text.send(player, "<#9AA0A6>用法：/cv party invite <玩家>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    Text.send(player, "<#FF8A80>玩家不在线。");
                    return;
                }
                plugin.match().invite(player, target);
            }
            case "accept" -> plugin.match().accept(player);
            case "leave" -> plugin.match().leave(player, true);
            case "kick" -> {
                if (args.length >= 3) {
                    plugin.match().kick(player, args[2]);
                }
            }
            default -> {
                var party = plugin.match().partyOf(player.getUniqueId());
                if (party == null) {
                    Text.send(player, "<#9AA0A6>你不在小队中。<white>/cv party invite <玩家></white> 邀请队友。");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (var uuid : party.members) {
                    Player p = Bukkit.getPlayer(uuid);
                    sb.append(uuid.equals(party.leader) ? "★" : "").append(p == null ? "?" : p.getName()).append("  ");
                }
                Text.send(player, "<#9AA0A6>小队 (" + party.size() + "/3)：<white>" + Text.escape(sb.toString().trim()));
            }
        }
    }

    private void help(CommandSender sender) {
        Text.send(sender, "<#F2E6C8>Craftov 指令");
        sender.sendMessage(Text.mm("<#9AA0A6>/cv <#8A8F98>打开战术终端  <#9AA0A6>/cv deploy <#8A8F98>开始行动"));
        sender.sendMessage(Text.mm("<#9AA0A6>/cv stash | trader | market | secure"));
        sender.sendMessage(Text.mm("<#9AA0A6>/cv party invite <玩家> | accept | leave | kick <玩家>"));
        sender.sendMessage(Text.mm("<#9AA0A6>/cv leave <#8A8F98>取消匹配"));
        if (sender instanceof Player p && plugin.canUseCatalog(p)) {
            sender.sendMessage(Text.mm("<#FF9F43>/cv items <#8A8F98>测试军械库（免费领取任意物品）"));
        }
        if (sender.hasPermission("craftov.admin")) {
            sender.sendMessage(Text.mm("<#FFC23D>管理：/cv give <玩家> <物品> [数量] · money <玩家> <数量> · raids · end <id> · tp <id> · lobby · reload"));
        }
    }

    private void admin(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "give" -> {
                if (args.length < 3) {
                    Text.send(sender, "/cv give <玩家> <物品> [数量]");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                ItemDef def = plugin.registry().get(args[2]);
                if (target == null || def == null) {
                    Text.send(sender, "<#FF8A80>玩家或物品不存在。");
                    return;
                }
                int amount = args.length > 3 ? Integer.parseInt(args[3]) : 1;
                ItemStack stack = def.isGun() ? plugin.items().loadedGun(def, firstAmmo(def)) : plugin.items().create(def, amount);
                plugin.carry().give(target, stack, true);
                Text.send(sender, "<#5BD16B>已给予 " + def.coloredName() + " ×" + amount);
            }
            case "injure" -> {
                // /cv injure <player> <head|chest|stomach|left_arm|right_arm|left_leg|right_leg> <light|heavy|fracture>
                if (args.length < 4) {
                    Text.send(sender, "/cv injure <玩家> <部位> <light|heavy|fracture>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                net.infnetwork.snowball.craftov.combat.BodyPart part = java.util.Arrays.stream(net.infnetwork.snowball.craftov.combat.BodyPart.values())
                        .filter(b -> b.key().equalsIgnoreCase(args[2]) || b.name().equalsIgnoreCase(args[2])).findFirst().orElse(null);
                net.infnetwork.snowball.craftov.combat.Injury injury = switch (args[3].toLowerCase(Locale.ROOT)) {
                    case "light" -> net.infnetwork.snowball.craftov.combat.Injury.LIGHT_BLEED;
                    case "heavy" -> net.infnetwork.snowball.craftov.combat.Injury.HEAVY_BLEED;
                    case "fracture" -> net.infnetwork.snowball.craftov.combat.Injury.FRACTURE;
                    default -> null;
                };
                if (target == null || part == null || injury == null) {
                    Text.send(sender, "<#FF8A80>玩家、部位或伤势无效。");
                    return;
                }
                plugin.health().addInjury(target, part, injury);
                Text.send(sender, "<#5BD16B>" + target.getName() + " " + part.displayName() + " " + injury.displayName());
            }
            case "money" -> {
                if (args.length < 3) {
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    return;
                }
                Profile profile = plugin.profiles().get(target);
                profile.money += Long.parseLong(args[2]);
                plugin.profiles().markDirty(target.getUniqueId());
                Text.send(sender, "<#5BD16B>" + target.getName() + " 余额 ◈" + Text.money(profile.money));
            }
            case "raids" -> {
                if (plugin.raids().raids().isEmpty()) {
                    Text.send(sender, "<#9AA0A6>没有进行中的行动。");
                }
                for (Raid raid : plugin.raids().raids()) {
                    Text.send(sender, "<white>" + raid.id + " <#9AA0A6>" + raid.map.name() + " " + raid.tier.name() + " " + raid.state
                            + " · 剩余 " + Text.duration(raid.remainingSeconds()) + " · 玩家 " + raid.activeCount() + " · AI " + plugin.ai().count(raid));
                }
            }
            case "event" -> {
                // /cv event <blackout|barrage|reinforcements|supply_drop|boss_patrol> [raid id]: start a raid event now.
                if (args.length < 2) {
                    Text.send(sender, "<#9AA0A6>/cv event <blackout|barrage|reinforcements|supply_drop|boss_patrol> [对局ID]");
                    return;
                }
                net.infnetwork.snowball.craftov.raid.RaidEvents.Kind kind;
                try {
                    kind = net.infnetwork.snowball.craftov.raid.RaidEvents.Kind.valueOf(args[1].toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    Text.send(sender, "<#FF8A80>未知事件：" + Text.escape(args[1]));
                    return;
                }
                for (Raid raid : new ArrayList<>(plugin.raids().raids())) {
                    if (raid.state == Raid.State.RUNNING && (args.length < 3 || raid.id.equalsIgnoreCase(args[2]))) {
                        boolean fired = plugin.events().fire(raid, kind);
                        Text.send(sender, fired ? "<#FFC23D>已在 " + raid.id + " 触发 " + kind : "<#FF8A80>" + raid.id + " 无法触发 " + kind);
                    }
                }
            }
            case "end" -> {
                for (Raid raid : new ArrayList<>(plugin.raids().raids())) {
                    if (args.length < 2 || raid.id.equalsIgnoreCase(args[1])) {
                        raid.startTick = Bukkit.getCurrentTick() - raid.map.raidSeconds() * 20L;
                        Text.send(sender, "<#FFC23D>已结束 " + raid.id);
                    }
                }
            }
            case "tp" -> {
                if (sender instanceof Player p && args.length >= 2) {
                    for (Raid raid : plugin.raids().raids()) {
                        if (raid.id.equalsIgnoreCase(args[1])) {
                            p.setGameMode(org.bukkit.GameMode.SPECTATOR);
                            p.teleport(raid.map.camera().at(raid.world));
                        }
                    }
                }
            }
            case "lobby" -> {
                if (sender instanceof Player p) {
                    plugin.lobby().enterLobby(p);
                }
            }
            case "editor" -> {
                if (!(sender instanceof Player p)) {
                    return;
                }
                String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
                switch (action) {
                    case "world" -> {
                        if (args.length > 2) plugin.editor().createWorld(p, args[2]);
                    }
                    case "start" -> {
                        if (args.length > 2) plugin.editor().start(p, args[2]);
                    }
                    case "save" -> plugin.editor().save(p);
                    case "exit" -> plugin.editor().exit(p, true);
                    case "discard" -> plugin.editor().exit(p, false);
                    case "info" -> plugin.editor().info(p);
                    case "set" -> {
                        if (args.length > 3) plugin.editor().set(p, args[2].toLowerCase(Locale.ROOT), String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                    }
                    default -> {
                        Text.send(p, "<#F2E6C8>地图编辑器");
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor world <世界名> <#8A8F98>创建/前往一个空的建图世界"));
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor start <地图ID> <#8A8F98>在当前世界开始标点（领取工具）"));
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor set name|description|minutes|time|camera <值>"));
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor info · save · exit · discard"));
                    }
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.registry().load(new java.io.File(plugin.getDataFolder(), "items.yml"));
                plugin.loot().load(new java.io.File(plugin.getDataFolder(), "loot.yml"));
                plugin.maps().load();
                plugin.loadTiers();
                Text.send(sender, "<#5BD16B>已重新加载配置、物品、掉落与地图。");
            }
            default -> help(sender);
        }
    }

    private String firstAmmo(ItemDef gun) {
        List<ItemDef> ammo = plugin.registry().ammoFor(gun.gun().caliber());
        return ammo.isEmpty() ? null : ammo.get(0).id();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (label.equalsIgnoreCase("party")) {
            if (args.length == 1) {
                out.addAll(List.of("invite", "accept", "leave", "kick", "list"));
            } else if (args.length == 2) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            return filter(out, args[args.length - 1]);
        }
        if (args.length == 1) {
            out.addAll(PLAYER_SUBS);
            if (sender instanceof Player p && plugin.canUseCatalog(p)) {
                out.add("items");
            }
            if (sender.hasPermission("craftov.admin")) {
                out.addAll(ADMIN_SUBS);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            out.addAll(List.of("invite", "accept", "leave", "kick", "list"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("party")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("money"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.addAll(plugin.registry().ids());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("editor")) {
            out.addAll(List.of("world", "start", "set", "info", "save", "exit", "discard"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("editor") && args[1].equalsIgnoreCase("start")) {
            plugin.maps().all().forEach(m -> out.add(m.id()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("editor") && args[1].equalsIgnoreCase("set")) {
            out.addAll(List.of("name", "description", "minutes", "time", "camera"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("end") || args[0].equalsIgnoreCase("tp"))) {
            plugin.raids().raids().forEach(r -> out.add(r.id));
        }
        return filter(out, args[args.length - 1]);
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(option);
            }
        }
        return out;
    }
}
