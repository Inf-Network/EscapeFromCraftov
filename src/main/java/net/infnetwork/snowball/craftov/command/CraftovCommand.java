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

    private static final List<String> PLAYER_SUBS = List.of("menu", "deploy", "stash", "mail", "trader", "market", "secure", "party",
            "leave", "help", "mod");
    private static final List<String> ADMIN_SUBS = List.of("give", "money", "raids", "end", "reload", "lobby", "tp", "editor", "injure", "event", "feed",
            "marketaudit", "ai", "lagcomp", "luck");

    private final CraftovPlugin plugin;

    public CraftovCommand(CraftovPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // The label is whatever was typed (/squad, /craftov:party); the command's own name is not.
        if (command.getName().equalsIgnoreCase("party")) {
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
            case "mail" -> {
                if (player != null) plugin.menus().openMail(player);
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
                // /cv queue <map> <tier> [SOLO|TRIO] [ranked] [fill]: the deploy menu's queue, for test clients.
                if (player == null) {
                    return true;
                }
                RaidMode mode = args.length >= 4 ? mode(args[3]) : RaidMode.SOLO;
                if (args.length < 3 || mode == null) {
                    Text.send(player, "<#9AA0A6>用法：/cv queue <地图ID> <难度> [SOLO|TRIO] [排位 true|false] [补位 true|false]");
                    return true;
                }
                plugin.match().enqueue(player, args[1], args[2].toUpperCase(Locale.ROOT), mode, args.length >= 5 && Boolean.parseBoolean(args[4]),
                        args.length < 6 || Boolean.parseBoolean(args[5]));
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
        sender.sendMessage(Text.mm("<#9AA0A6>/cv stash | mail | trader | market | secure"));
        sender.sendMessage(Text.mm("<#9AA0A6>/cv party invite <玩家> | accept | leave | kick <玩家>"));
        sender.sendMessage(Text.mm("<#9AA0A6>/cv leave <#8A8F98>取消匹配"));
        if (sender instanceof Player p && plugin.canUseCatalog(p)) {
            sender.sendMessage(Text.mm("<#FF9F43>/cv items <#8A8F98>测试军械库（免费领取任意物品）"));
        }
        if (sender.hasPermission("craftov.admin")) {
            sender.sendMessage(Text.mm("<#FFC23D>管理：/cv give <玩家> <物品> [数量] · money <玩家> <数量> · raids · end <id> · tp <id> · lobby · reload"
                    + " · marketaudit [数量] · lagcomp [on|off] · ai [id] · ai spawn <类型> <x> <y> <z> [id] · luck <玩家> · feed <玩家>"));
        }
    }

    private void admin(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "give" -> {
                String usage = "<#9AA0A6>用法：/cv give <玩家> <物品ID> [数量]";
                int amount = args.length > 3 ? positiveInt(args[3]) : 1;
                if (args.length < 3 || amount <= 0) {
                    Text.send(sender, usage);
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                ItemDef def = plugin.registry().get(args[2]);
                if (target == null || def == null) {
                    Text.send(sender, "<#FF8A80>玩家或物品不存在。");
                    return;
                }
                ItemStack stack = def.isGun() ? plugin.items().loadedGun(def, firstAmmo(def)) : plugin.items().create(def, amount);
                plugin.carry().give(target, stack, true);
                // The stack is capped at the item's stack size: say what was handed out.
                Text.send(sender, "<#5BD16B>已给予 " + def.coloredName() + " ×" + stack.getAmount());
            }
            case "injure" -> {
                // /cv injure <player> <head|chest|stomach|left_arm|right_arm|left_leg|right_leg> <light|heavy|fracture> [source]
                // A bleed with a source is that player's wound: it keeps their kill credit going.
                if (args.length < 4) {
                    Text.send(sender, "/cv injure <玩家> <部位> <light|heavy|fracture> [造成伤口的玩家]");
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
                Player source = args.length > 4 ? Bukkit.getPlayerExact(args[4]) : null;
                if (target == null || part == null || injury == null || (args.length > 4 && source == null)) {
                    Text.send(sender, "<#FF8A80>玩家、部位、伤势或造成伤口的玩家无效。");
                    return;
                }
                plugin.health().addInjury(target, part, injury, source == null ? null : source.getUniqueId());
                Text.send(sender, "<#5BD16B>" + target.getName() + " " + part.displayName() + " " + injury.displayName());
            }
            case "feed" -> {
                // /cv feed <player>: the kill feed lines the player's HUD holds, oldest first (tests read it).
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    Text.send(sender, "/cv feed <玩家>");
                    return;
                }
                java.util.List<String> lines = plugin.killFeed().allLines(target);
                if (lines.isEmpty()) {
                    sender.sendMessage(Text.mm("<#9AA0A6>（击杀栏是空的）"));
                }
                for (String line : lines) {
                    sender.sendMessage(Text.mm(line));
                }
                sender.sendMessage(Text.mm("<#9AA0A6>连杀 " + plugin.killFeed().streak(target.getUniqueId())));
            }
            case "money" -> {
                // /cv money <player> <amount>: adds the amount (negative takes money away).
                Long delta = args.length < 3 ? null : parseLong(args[2]);
                if (delta == null) {
                    Text.send(sender, "<#9AA0A6>用法：/cv money <玩家> <数量>（负数为扣除）");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    Text.send(sender, "<#FF8A80>玩家不在线。");
                    return;
                }
                Profile profile = plugin.profiles().get(target);
                long balance;
                try {
                    balance = Math.addExact(profile.money, delta);
                } catch (ArithmeticException ex) {
                    Text.send(sender, "<#FF8A80>数额过大。");
                    return;
                }
                profile.money = balance;
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
            case "ai" -> {
                // /cv ai [raid id]: every soldier of a raid, what it is doing and whom it has noticed.
                // /cv ai spawn <patrol|guard|sniper|shield|operator> <x> <y> <z> [raid id]: one soldier posted there.
                boolean spawn = args.length > 1 && args[1].equalsIgnoreCase("spawn");
                if (spawn && args.length < 6) {
                    Text.send(sender, "<#9AA0A6>/cv ai [对局ID] · /cv ai spawn <patrol|guard|sniper|shield|operator> <x> <y> <z> [对局ID]");
                    return;
                }
                Raid raid = pickRaid(sender, spawn ? (args.length > 6 ? args[6] : null) : (args.length > 1 ? args[1] : null));
                if (raid == null) {
                    return;
                }
                if (spawn) {
                    String kind = args[2].toLowerCase(Locale.ROOT);
                    if (!List.of("patrol", "guard", "sniper", "shield", "operator").contains(kind)) {
                        Text.send(sender, "<#FF8A80>未知敌兵类型：" + Text.escape(args[2]));
                        return;
                    }
                    org.bukkit.Location at;
                    try {
                        at = new org.bukkit.Location(raid.world, Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
                    } catch (NumberFormatException ex) {
                        at = null;
                    }
                    // NaN and Infinity parse too.
                    if (at == null || !Double.isFinite(at.getX() + at.getY() + at.getZ())) {
                        Text.send(sender, "<#FF8A80>坐标无效。");
                        return;
                    }
                    String name = plugin.ai().spawnSoldier(raid, at, kind);
                    Text.send(sender, name == null ? "<#FF8A80>生成失败。" : "<#5BD16B>已在 " + raid.id + " 生成 " + Text.escape(name));
                    return;
                }
                List<String> lines = plugin.ai().describe(raid);
                Text.send(sender, "<#F2C94C>" + raid.id + " · 敌兵 " + lines.size());
                for (String line : lines) {
                    sender.sendMessage(Text.mm("<#9AA0A6>" + Text.escape(line)));
                }
            }
            case "end" -> {
                for (Raid raid : new ArrayList<>(plugin.raids().raids())) {
                    if (args.length < 2 || raid.id.equalsIgnoreCase(args[1])) {
                        if (raid.state != Raid.State.RUNNING) {
                            // Deploying sets startTick, which would undo this.
                            Text.send(sender, "<#FF8A80>" + raid.id + " 还在加载，稍后再试。");
                            continue;
                        }
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
                // Missing arguments fall through to the help lines below.
                if (args.length < 3 && List.of("world", "start", "set").contains(action)) {
                    action = "help";
                }
                switch (action) {
                    case "world" -> plugin.editor().createWorld(p, args[2]);
                    case "start" -> plugin.editor().start(p, args[2]);
                    case "save" -> plugin.editor().save(p);
                    case "publish" -> plugin.editor().publish(p, args.length > 2 ? args[2] : null);
                    case "exit" -> plugin.editor().exit(p);
                    case "discard" -> plugin.editor().discard(p, args.length > 2 && args[2].equalsIgnoreCase("confirm"));
                    case "info" -> plugin.editor().info(p);
                    // "set camera" takes no value: it uses where the editor is looking from.
                    case "set" -> plugin.editor().set(p, args[2].toLowerCase(Locale.ROOT),
                            String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                    default -> {
                        Text.send(p, "<#F2E6C8>地图编辑器");
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor world <世界名> <#8A8F98>创建/前往一个空的建图世界"));
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor start <地图ID> <#8A8F98>新地图在当前世界开始标点；已发布的地图在编辑副本里改"));
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor set name|description|minutes|time|test|camera <值>"));
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor info · save · exit · discard <#8A8F98>保存只写草稿"));
                        p.sendMessage(Text.mm("<#9AA0A6>/cv editor publish [地图ID] <#8A8F98>发布草稿，之后才会出现在「开始行动」里"));
                    }
                }
            }
            case "marketaudit" -> {
                // Accounts with the most market sales: farming the old ratcheting reference price took
                // hundreds of sales an hour, far beyond what selling raid loot needs.
                int limit = 10;
                if (args.length > 1) {
                    try {
                        limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
                    } catch (NumberFormatException ex) {
                        Text.send(sender, "/cv marketaudit [数量]");
                        return;
                    }
                }
                List<net.infnetwork.snowball.craftov.profile.ProfileService.Summary> top = plugin.profiles().leaderboard(
                        (a, b) -> Long.compare(b.marketSales(), a.marketSales()), limit);
                Text.send(sender, "<#F2E6C8>交易行成交最多的账号 <#8A8F98>（收入只统计本版本之后的成交）");
                int rank = 1;
                for (net.infnetwork.snowball.craftov.profile.ProfileService.Summary s : top) {
                    if (s.marketSales() <= 0) {
                        break;
                    }
                    sender.sendMessage(Text.mm("<#9AA0A6>" + rank++ + ". <white>" + Text.escape(s.name()) + " <#8A8F98>Lv." + s.level()
                            + " · 成交 " + s.marketSales() + " 笔 · 收入 ◈" + Text.money(s.marketIncome()) + " · 余额 <#F2C94C>◈"
                            + Text.money(s.money()) + " <#8A8F98>· 带出 ◈" + Text.money(s.extracted())));
                }
                if (rank == 1) {
                    sender.sendMessage(Text.mm("<#9AA0A6>还没有人在交易行卖出过东西。"));
                }
            }
            case "lagcomp" -> {
                // /cv lagcomp [on|off]: hit lag compensation, until the next /cv reload, to compare hits
                // on moving targets with and without it.
                boolean on = args.length < 2 ? !plugin.getConfig().getBoolean("combat.lag-compensation.enabled", true)
                        : args[1].equalsIgnoreCase("on");
                plugin.getConfig().set("combat.lag-compensation.enabled", on);
                Text.send(sender, on
                        ? "<#5BD16B>命中延迟补偿已开启：玩家开枪按 ping + " + plugin.getConfig().getInt("combat.lag-compensation.extra-ms", 200)
                                + "ms 回溯目标位置（最多 " + plugin.getConfig().getInt("combat.lag-compensation.max-ms", 300) + "ms）。"
                        : "<#FF8A80>命中延迟补偿已关闭，<#9AA0A6>/cv reload 后恢复配置文件里的设置。");
            }
            case "luck" -> luck(sender, args);
            case "reload" -> {
                plugin.reloadConfig();
                String itemError = plugin.registry().load(new java.io.File(plugin.getDataFolder(), "items.yml"));
                plugin.loot().load(new java.io.File(plugin.getDataFolder(), "loot.yml"));
                plugin.maps().load();
                plugin.maps().warnCloseSpawns();
                plugin.loadTiers();
                if (itemError == null) {
                    Text.send(sender, "<#5BD16B>已重新加载配置、物品、掉落与地图。");
                } else {
                    String shown = itemError.replaceAll("\\s+", " ");
                    Text.send(sender, "<#FF4D4D>items.yml 有错误，物品表没有更新，仍在使用原来的物品表：<#FF8A80>"
                            + Text.escape(shown.length() > 300 ? shown.substring(0, 300) + "…" : shown));
                    Text.send(sender, "<#9AA0A6>配置、掉落与地图已重新加载，完整错误见服务器日志。");
                }
                for (CraftovPlugin.OutdatedData old : plugin.outdatedData()) {
                    Text.send(sender, "<#FFC23D>" + old.file() + " 比插件自带的旧（revision " + old.local() + "，插件 " + old.bundled()
                            + "）：价格和掉落仍按旧文件。<#9AA0A6>备份后换成插件自带的版本（或把改动合并进去并改成 revision: "
                            + old.bundled() + "），再 /cv reload。");
                }
            }
            default -> help(sender);
        }
    }

    /**
     * /cv luck &lt;player&gt; [quota|pity|boost|reset ...]: shows a player's personal luck (pity counts,
     * quota pool, behaviour multiplier, loss compensation), online or not, or changes it.
     */
    private void luck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Text.send(sender, "/cv luck <玩家> [quota <数量|+数量|-数量> | pity <GOLD|RED|大奖物品> <数量> | boost <局数> | reset]");
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        java.util.UUID uuid = online != null ? online.getUniqueId() : plugin.profiles().findByName(args[1]);
        if (uuid == null) {
            Text.send(sender, "<#FF8A80>没有叫 " + Text.escape(args[1]) + " 的玩家。");
            return;
        }
        if (args.length > 2) {
            String error = plugin.luck().adjust(uuid, args[2].toLowerCase(Locale.ROOT),
                    java.util.Arrays.copyOfRange(args, 3, args.length), sender.getName());
            if (error != null) {
                Text.send(sender, "<#FF8A80>" + Text.escape(error));
                return;
            }
            Text.send(sender, "<#5BD16B>已修改。");
        }
        Profile profile = plugin.profiles().peek(uuid);
        if (profile == null) {
            Text.send(sender, "<#FF8A80>读不出这个玩家的档案。");
            return;
        }
        for (String line : plugin.luck().describe(profile)) {
            sender.sendMessage(Text.mm(line));
        }
    }

    /**
     * The running raid an admin command means: the one named (by id or world name), else the
     * sender's own, else the only one running. Null (with the reason told) if that does not pick
     * exactly one.
     */
    private @org.jspecify.annotations.Nullable Raid pickRaid(CommandSender sender, @org.jspecify.annotations.Nullable String id) {
        List<Raid> running = plugin.raids().raids().stream().filter(r -> r.state == Raid.State.RUNNING).toList();
        if (id != null) {
            for (Raid raid : running) {
                // The world name works too, which is all a test client knows about its raid.
                if (raid.id.equalsIgnoreCase(id) || raid.world.getName().equalsIgnoreCase(id)) {
                    return raid;
                }
            }
            Text.send(sender, "<#FF8A80>没有进行中的对局 " + Text.escape(id));
            return null;
        }
        Raid own = sender instanceof Player p ? plugin.raids().raidOf(p) : null;
        if (own != null && own.state == Raid.State.RUNNING) {
            return own;
        }
        if (running.size() == 1) {
            return running.get(0);
        }
        Text.send(sender, running.isEmpty() ? "<#9AA0A6>没有进行中的行动。" : "<#FF8A80>有多个对局，请指定对局ID。");
        return null;
    }

    /** SOLO or TRIO in any case, null for anything else. */
    private static @org.jspecify.annotations.Nullable RaidMode mode(String arg) {
        try {
            return RaidMode.valueOf(arg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** The number if it is a whole number above 0, else 0. */
    private static int positiveInt(String arg) {
        try {
            return Math.max(0, Integer.parseInt(arg));
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }

    private static @org.jspecify.annotations.Nullable Long parseLong(String arg) {
        try {
            return Long.parseLong(arg);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String firstAmmo(ItemDef gun) {
        List<ItemDef> ammo = plugin.registry().ammoFor(gun.gun().caliber());
        return ammo.isEmpty() ? null : ammo.get(0).id();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("party")) {
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
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("money")
                || args[0].equalsIgnoreCase("luck") && sender.hasPermission("craftov.admin"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("luck") && sender.hasPermission("craftov.admin")) {
            out.addAll(List.of("quota", "pity", "boost", "reset"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("luck") && args[2].equalsIgnoreCase("pity")
                && sender.hasPermission("craftov.admin")) {
            out.addAll(List.of("GOLD", "RED"));
            out.addAll(plugin.loot().jackpots());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.addAll(plugin.registry().ids());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("editor")) {
            out.addAll(List.of("world", "start", "set", "info", "save", "publish", "exit", "discard"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("editor")
                && (args[1].equalsIgnoreCase("start") || args[1].equalsIgnoreCase("publish"))) {
            plugin.maps().all().forEach(m -> out.add(m.id()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("editor") && args[1].equalsIgnoreCase("discard")) {
            out.add("confirm");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("editor") && args[1].equalsIgnoreCase("set")) {
            out.addAll(List.of("name", "description", "minutes", "time", "test", "camera"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("lagcomp")) {
            out.addAll(List.of("on", "off"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("end") || args[0].equalsIgnoreCase("tp"))) {
            plugin.raids().raids().forEach(r -> out.add(r.id));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ai")) {
            out.add("spawn");
            plugin.raids().raids().forEach(r -> out.add(r.id));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("ai") && args[1].equalsIgnoreCase("spawn")) {
            out.addAll(List.of("patrol", "guard", "sniper", "shield", "operator"));
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
