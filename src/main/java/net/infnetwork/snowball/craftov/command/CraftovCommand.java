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
            "leave", "help", "mod", "guide", "training", "fov", "skins");
    private static final List<String> ADMIN_SUBS = List.of("give", "money", "hud", "close", "raids", "end", "reload", "lobby", "tp", "editor", "injure", "event",
            "feed", "marketaudit", "ai", "lagcomp", "luck", "points", "slide");

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
                        Text.send(player, "<muted>你不在匹配队列中。行动中可通过战术终端放弃行动。");
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
            case "guide" -> {
                // /cv guide: the new-player briefing again; /cv guide start|skip answers it without the
                // window (for bots and players whose client cannot show dialogs).
                if (player == null) {
                    return true;
                }
                String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (action.equals("start") || action.equals("skip")) {
                    plugin.tutorial().mute(player, action.equals("skip"));
                    Text.send(player, action.equals("skip") ? "<muted>已关闭新手指引的目标栏、箭头和提醒（步骤照样记录，六步做完照样发奖励）。"
                            : "<ok>已开启新手指引。");
                    if (action.equals("start")) {
                        plugin.tutorial().show(player, plugin.profiles().get(player));
                    }
                } else {
                    // /cv guide 1|2|3 opens that page (the controls page straight away, say).
                    int page = action.matches("[1-3]") ? Integer.parseInt(action) - 1 : 0;
                    plugin.tutorial().openBriefing(player, page);
                }
            }
            case "training" -> {
                // /cv training: the training raid (the deploy screen offers it while the guide runs).
                if (player != null) {
                    String why = plugin.training().start(player);
                    if (why != null) {
                        Text.send(player, "<danger>" + why);
                    }
                }
            }
            case "fov" -> fov(player, args);
            case "skins" -> {
                if (player != null) plugin.menus().openSkins(player, null);
            }
            case "queue" -> {
                // /cv queue <map> <tier> [SOLO|TRIO] [ranked] [fill]: the deploy menu's queue, for test clients.
                if (player == null) {
                    return true;
                }
                RaidMode mode = args.length >= 4 ? mode(args[3]) : RaidMode.SOLO;
                if (args.length < 3 || mode == null) {
                    Text.send(player, "<muted>用法：/cv queue <地图ID> <难度> [SOLO|TRIO] [排位 true|false] [补位 true|false]");
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
                    Text.send(player, "<muted>用法：/cv party invite <玩家>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    Text.send(player, "<danger>玩家不在线。");
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
                    Text.send(player, "<muted>你不在小队中。<white>/cv party invite <玩家></white> 邀请队友。");
                    return;
                }
                List<String> names = new ArrayList<>();
                for (var uuid : party.members) {
                    names.add((uuid.equals(party.leader) ? "★" : "") + Text.escape(party.name(uuid))
                            + (Bukkit.getPlayer(uuid) == null ? " <muted>(离线)</muted>" : ""));
                }
                Text.send(player, "<muted>小队 (" + party.size() + "/3)：<white>" + String.join("  ", names));
            }
        }
    }

    /**
     * /cv fov [视野 [FOV特效%]] | ok: what the player's client plays at, which the server cannot read
     * (FovService). "ok" confirms FOV Effects is at its default 100% (the tip's button).
     */
    private void fov(Player player, String[] args) {
        if (player == null) {
            return;
        }
        Profile profile = plugin.profiles().get(player);
        if (args.length >= 2 && args[1].equalsIgnoreCase("ok")) {
            String why = plugin.fov().declare(player, profile.fov, 100);
            Text.send(player, why == null ? "<ok>已记录：FOV 特效 100%，视野 " + profile.fov + "。<muted>瞄准镜按真实倍率放大。" : "<danger>" + why);
            plugin.tutorial().done(player, net.infnetwork.snowball.craftov.lobby.TutorialService.Step.FOV);
            return;
        }
        if (args.length < 2) {
            Text.send(player, "<muted>服务器记录的设置：视野 <white>" + profile.fov + "</white>，FOV 特效 <white>"
                    + Math.round(profile.fovEffects * 100) + "%</white>。");
            Text.send(player, plugin.fov().tip());
            return;
        }
        int fov;
        int effects;
        try {
            fov = Integer.parseInt(args[1]);
            effects = args.length >= 3 ? Integer.parseInt(args[2].replace("%", "")) : (int) Math.round(profile.fovEffects * 100);
        } catch (NumberFormatException ex) {
            Text.send(player, "<muted>用法：/cv fov <视野 30-110> [FOV特效 0-100]，例如 <white>/cv fov 70 100</white>");
            return;
        }
        String why = plugin.fov().declare(player, fov, effects);
        if (why != null) {
            Text.send(player, "<danger>" + why);
            return;
        }
        String warning = plugin.fov().effectsWarning(effects / 100.0);
        Text.send(player, "<ok>已记录：视野 " + fov + "，FOV 特效 " + effects + "%。"
                + (warning != null ? " " + warning + "请调回 100%。"
                : "<muted> 瞄准镜按真实倍率放大" + (effects < 100 ? "（已按 FOV 特效补偿）" : "")
                + (fov < net.infnetwork.snowball.craftov.weapon.FovService.MIN_ZOOM_FOV
                ? "，视野低于 " + net.infnetwork.snowball.craftov.weapon.FovService.MIN_ZOOM_FOV + " 时按 "
                + net.infnetwork.snowball.craftov.weapon.FovService.MIN_ZOOM_FOV + " 换算（倍率略低）。" : "。")));
        plugin.tutorial().done(player, net.infnetwork.snowball.craftov.lobby.TutorialService.Step.FOV);
    }

    private void help(CommandSender sender) {
        Text.send(sender, "<text>Craftov 指令");
        sender.sendMessage(Text.mm("<muted>/cv <muted>打开战术终端  <muted>/cv deploy <muted>开始行动"));
        sender.sendMessage(Text.mm("<muted>/cv stash | mail | trader | market | secure | skins"));
        sender.sendMessage(Text.mm("<muted>/cv party invite <玩家> | accept | leave | kick <玩家>"));
        sender.sendMessage(Text.mm("<muted>/cv leave <muted>取消匹配  <muted>/cv guide <muted>新手简报（start | skip 开启或关闭指引）"));
        sender.sendMessage(Text.mm("<muted>/cv training <muted>训练行动（单人，不计战绩，装备原样归还）"));
        sender.sendMessage(Text.mm("<muted>/cv fov <视野> [FOV特效%] <muted>告诉服务器你的视野设置（瞄准镜倍率按它换算）"));
        if (sender instanceof Player p && plugin.canUseCatalog(p)) {
            sender.sendMessage(Text.mm("<accent>/cv items <muted>测试军械库（免费领取任意物品）"));
        }
        if (sender.hasPermission("craftov.admin")) {
            sender.sendMessage(Text.mm("<accent>管理：/cv give <玩家> <物品> [数量] [瞄准镜] · money <玩家> <数量> · hud <玩家> · close <玩家> · raids · end <id> · tp <id> · lobby · reload"
                    + " · marketaudit [数量] · lagcomp [on|off] · ai [id] · ai spawn <类型> <x> <y> <z> [id] · luck <玩家> · feed <玩家>"
                    + " · points <玩家> [数量] · slide <玩家> [stamina <值>]"));
        }
    }

    private void admin(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "give" -> {
                String usage = "<muted>用法：/cv give <玩家> <物品ID> [数量] [瞄准镜]";
                int amount = args.length > 3 ? positiveInt(args[3]) : 1;
                if (args.length < 3 || amount <= 0) {
                    Text.send(sender, usage);
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                ItemDef def = plugin.registry().get(args[2]);
                if (target == null || def == null) {
                    Text.send(sender, "<danger>玩家或物品不存在。");
                    return;
                }
                ItemStack stack = def.isGun() ? plugin.items().loadedGun(def, firstAmmo(def)) : plugin.items().create(def, amount);
                ItemDef optic = args.length > 4 && def.isGun() ? plugin.registry().get(args[4]) : null;
                if (optic != null && optic.optic() != null && def.gun().accepts(optic.optic())) {
                    stack = plugin.items().withOptic(stack, optic.id()); // /cv give <玩家> <枪> 1 <瞄准镜>
                }
                plugin.carry().giveOrWear(target, stack, true, false);
                // The stack is capped at the item's stack size: say what was handed out.
                Text.send(sender, "<ok>已给予 " + def.coloredName() + " ×" + stack.getAmount());
            }
            case "injure" -> {
                // /cv injure <player> <head|chest|stomach|left_arm|right_arm|left_leg|right_leg> <light|heavy|fracture> [source]
                // A bleed with a source is that player's wound: it keeps their kill credit going.
                // /cv injure <player> clear: full health and no wounds, as the test catalog's button, for
                // tests that shoot a player again and again (tools/bot/slide.js).
                if (args.length == 3 && args[2].equalsIgnoreCase("clear")) {
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        Text.send(sender, "<danger>玩家无效。");
                        return;
                    }
                    plugin.health().clear(target);
                    plugin.health().heal(target, net.infnetwork.snowball.craftov.combat.HealthService.MAX_HP);
                    plugin.health().forceSpeedUpdate(target);
                    Text.send(sender, "<ok>" + target.getName() + " 生命回满，伤病已清除");
                    return;
                }
                if (args.length < 4) {
                    Text.send(sender, "/cv injure <玩家> <部位> <light|heavy|fracture> [造成伤口的玩家] · /cv injure <玩家> clear");
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
                    Text.send(sender, "<danger>玩家、部位、伤势或造成伤口的玩家无效。");
                    return;
                }
                plugin.health().addInjury(target, part, injury, source == null ? null : source.getUniqueId());
                Text.send(sender, "<ok>" + target.getName() + " " + part.displayName() + " " + injury.displayName());
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
                    sender.sendMessage(Text.mm("<muted>（击杀栏是空的）"));
                }
                for (String line : lines) {
                    sender.sendMessage(Text.mm(line));
                }
                sender.sendMessage(Text.mm("<muted>连杀 " + plugin.killFeed().streak(target.getUniqueId())));
            }
            case "money" -> {
                // /cv money <player> <amount>: adds the amount (negative takes money away).
                Long delta = args.length < 3 ? null : parseLong(args[2]);
                if (delta == null) {
                    Text.send(sender, "<muted>用法：/cv money <玩家> <数量>（负数为扣除）");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    Text.send(sender, "<danger>玩家不在线。");
                    return;
                }
                Profile profile = plugin.profiles().get(target);
                long balance;
                try {
                    balance = Math.addExact(profile.money, delta);
                } catch (ArithmeticException ex) {
                    Text.send(sender, "<danger>数额过大。");
                    return;
                }
                profile.money = balance;
                plugin.profiles().markDirty(target.getUniqueId());
                Text.send(sender, "<ok>" + target.getName() + " 余额 ◈" + Text.money(profile.money));
            }
            case "close" -> {
                // /cv close <player>: closes whatever screen the player has open (the settlement
                // screen after an extraction, say), for scripts driving a real client.
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    Text.send(sender, "/cv close <玩家>");
                    return;
                }
                target.closeInventory();
                target.closeDialog(); // the first-join briefing, too
            }
            case "hud" -> {
                // /cv hud <player>: what that player's BetterHud shows besides the panels (bots cannot see it).
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    Text.send(sender, "/cv hud <玩家>");
                    return;
                }
                Text.send(sender, Text.escape(plugin.hud().describe(target)));
            }
            case "raids" -> {
                if (plugin.raids().raids().isEmpty()) {
                    Text.send(sender, "<muted>没有进行中的行动。");
                }
                for (Raid raid : plugin.raids().raids()) {
                    Text.send(sender, "<white>" + raid.id + " <muted>" + raid.map.name() + " " + raid.tier.name() + " " + raid.state
                            + " · 剩余 " + Text.duration(raid.remainingSeconds()) + " · 玩家 " + raid.activeCount() + " · AI " + plugin.ai().count(raid));
                }
                // For checking that nothing piles up over a long uptime (issue #45).
                Text.send(sender, "<muted>正在复制地图 " + plugin.worlds().copies() + " 份 · 击中记录 lastHits "
                        + plugin.damage().trackedHits() + " 条 · 在线 " + Bukkit.getOnlinePlayers().size() + " 人");
            }
            case "event" -> {
                // /cv event <blackout|barrage|reinforcements|supply_drop|boss_patrol> [raid id]: start a raid event now.
                if (args.length < 2) {
                    Text.send(sender, "<muted>/cv event <blackout|barrage|reinforcements|supply_drop|boss_patrol> [对局ID]");
                    return;
                }
                net.infnetwork.snowball.craftov.raid.RaidEvents.Kind kind;
                try {
                    kind = net.infnetwork.snowball.craftov.raid.RaidEvents.Kind.valueOf(args[1].toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    Text.send(sender, "<danger>未知事件：" + Text.escape(args[1]));
                    return;
                }
                for (Raid raid : new ArrayList<>(plugin.raids().raids())) {
                    if (raid.state == Raid.State.RUNNING && (args.length < 3 || raid.id.equalsIgnoreCase(args[2]))) {
                        boolean fired = plugin.events().fire(raid, kind);
                        Text.send(sender, fired ? "<accent>已在 " + raid.id + " 触发 " + kind : "<danger>" + raid.id + " 无法触发 " + kind);
                    }
                }
            }
            case "ai" -> {
                // /cv ai [raid id]: every soldier of a raid, what it is doing and whom it has noticed.
                // /cv ai spawn <patrol|guard|sniper|shield|operator> <x> <y> <z> [raid id]: one soldier posted there.
                boolean spawn = args.length > 1 && args[1].equalsIgnoreCase("spawn");
                if (spawn && args.length < 6) {
                    Text.send(sender, "<muted>/cv ai [对局ID] · /cv ai spawn <patrol|guard|sniper|shield|operator> <x> <y> <z> [对局ID]");
                    return;
                }
                Raid raid = pickRaid(sender, spawn ? (args.length > 6 ? args[6] : null) : (args.length > 1 ? args[1] : null));
                if (raid == null) {
                    return;
                }
                if (spawn) {
                    String kind = args[2].toLowerCase(Locale.ROOT);
                    if (!List.of("patrol", "guard", "sniper", "shield", "operator").contains(kind)) {
                        Text.send(sender, "<danger>未知敌兵类型：" + Text.escape(args[2]));
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
                        Text.send(sender, "<danger>坐标无效。");
                        return;
                    }
                    String name = plugin.ai().spawnSoldier(raid, at, kind);
                    Text.send(sender, name == null ? "<danger>生成失败。" : "<ok>已在 " + raid.id + " 生成 " + Text.escape(name));
                    return;
                }
                List<String> lines = plugin.ai().describe(raid);
                Text.send(sender, "<accent>" + raid.id + " · 敌兵 " + lines.size());
                for (String line : lines) {
                    sender.sendMessage(Text.mm("<muted>" + Text.escape(line)));
                }
            }
            case "end" -> {
                for (Raid raid : new ArrayList<>(plugin.raids().raids())) {
                    if (args.length < 2 || raid.id.equalsIgnoreCase(args[1])) {
                        if (raid.state != Raid.State.RUNNING) {
                            // Deploying sets startTick, which would undo this.
                            Text.send(sender, "<danger>" + raid.id + " 还在加载，稍后再试。");
                            continue;
                        }
                        raid.startTick = Bukkit.getCurrentTick() - raid.map.raidSeconds() * 20L;
                        Text.send(sender, "<accent>已结束 " + raid.id);
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
                // From inside a raid it is void for them, before the lobby puts anything on them.
                if (sender instanceof Player p && !plugin.raids().evacuate(p)) {
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
                        Text.send(p, "<text>地图编辑器");
                        p.sendMessage(Text.mm("<muted>/cv editor world <世界名> <muted>创建/前往一个空的建图世界"));
                        p.sendMessage(Text.mm("<muted>/cv editor start <地图ID> <muted>新地图在当前世界开始标点；已发布的地图在编辑副本里改"));
                        p.sendMessage(Text.mm("<muted>/cv editor set name|description|minutes|time|test|camera <值>"));
                        p.sendMessage(Text.mm("<muted>/cv editor info · save · exit · discard <muted>保存只写草稿"));
                        p.sendMessage(Text.mm("<muted>/cv editor publish [地图ID] <muted>发布草稿，之后才会出现在「开始行动」里"));
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
                Text.send(sender, "<text>交易行成交最多的账号 <muted>（收入只统计本版本之后的成交）");
                int rank = 1;
                for (net.infnetwork.snowball.craftov.profile.ProfileService.Summary s : top) {
                    if (s.marketSales() <= 0) {
                        break;
                    }
                    sender.sendMessage(Text.mm("<muted>" + rank++ + ". <white>" + Text.escape(s.name()) + " <muted>Lv." + s.level()
                            + " · 成交 " + s.marketSales() + " 笔 · 收入 ◈" + Text.money(s.marketIncome()) + " · 余额 <accent>◈"
                            + Text.money(s.money()) + " <muted>· 带出 ◈" + Text.money(s.extracted())));
                }
                if (rank == 1) {
                    sender.sendMessage(Text.mm("<muted>还没有人在交易行卖出过东西。"));
                }
            }
            case "lagcomp" -> {
                // /cv lagcomp [on|off]: hit lag compensation, until the next /cv reload, to compare hits
                // on moving targets with and without it.
                boolean on = args.length < 2 ? !plugin.getConfig().getBoolean("combat.lag-compensation.enabled", true)
                        : args[1].equalsIgnoreCase("on");
                plugin.getConfig().set("combat.lag-compensation.enabled", on);
                Text.send(sender, on
                        ? "<ok>命中延迟补偿已开启：玩家开枪按 ping + " + plugin.getConfig().getInt("combat.lag-compensation.extra-ms", 200)
                                + "ms 回溯目标位置（滑铲中的目标 ping + " + plugin.getConfig().getInt("combat.lag-compensation.slide-extra-ms", 150)
                                + "ms，最多 " + plugin.getConfig().getInt("combat.lag-compensation.max-ms", 300) + "ms）。"
                        : "<danger>命中延迟补偿已关闭，<muted>/cv reload 后恢复配置文件里的设置。");
            }
            case "luck" -> luck(sender, args);
            case "points" -> points(sender, args);
            case "slide" -> slide(sender, args);
            case "reload" -> {
                plugin.reloadConfig();
                String itemError = plugin.registry().load(new java.io.File(plugin.getDataFolder(), "items.yml"));
                plugin.loot().load(new java.io.File(plugin.getDataFolder(), "loot.yml"));
                plugin.maps().load();
                plugin.maps().warnCloseSpawns();
                plugin.maps().warnLiveProblems(Math.max(1, plugin.getConfig().getInt("match.max-squads", 4)));
                plugin.loadTiers();
                if (itemError == null) {
                    Text.send(sender, "<ok>已重新加载配置、物品、掉落与地图。");
                } else {
                    String shown = itemError.replaceAll("\\s+", " ");
                    Text.send(sender, "<danger>items.yml 有错误，物品表没有更新，仍在使用原来的物品表：<danger>"
                            + Text.escape(shown.length() > 300 ? shown.substring(0, 300) + "…" : shown));
                    Text.send(sender, "<muted>配置、掉落与地图已重新加载，完整错误见服务器日志。");
                }
                for (CraftovPlugin.OutdatedData old : plugin.outdatedData()) {
                    Text.send(sender, "<accent>" + old.file() + " 比插件自带的旧（revision " + old.local() + "，插件 " + old.bundled()
                            + "）：价格和掉落仍按旧文件。<muted>备份后换成插件自带的版本（或把改动合并进去并改成 revision: "
                            + old.bundled() + "），再 /cv reload。");
                }
            }
            default -> help(sender);
        }
    }

    /**
     * /cv slide &lt;player&gt; [stamina &lt;value&gt;]: a player's stamina, load, pose and hitbox, whether they
     * may slide (cooldown, sprinting since the last slide, why the last double tap did not slide) and
     * how their last slide went (tools/bot/slide.js reads it); with stamina, sets their stamina first.
     */
    private void slide(CommandSender sender, String[] args) {
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null) {
            Text.send(sender, "<muted>用法：/cv slide <玩家> [stamina <值>]");
            return;
        }
        var slides = plugin.slides();
        var stamina = plugin.stamina();
        if (args.length > 3 && args[2].equalsIgnoreCase("stamina")) {
            try {
                stamina.set(target, Double.parseDouble(args[3]));
            } catch (NumberFormatException ex) {
                Text.send(sender, "<danger>体力要写数字（0-100）。");
                return;
            }
        }
        var box = target.getBoundingBox();
        sender.sendMessage(Text.mm(String.format("<muted>%s 体力 stamina=%.2f exhausted=%b 负重 weight=%.1f 滑铲中 sliding=%b 冷却 cooldown=%d 冲刺 sprinted=%d",
                Text.escape(target.getName()), stamina.stamina(target), stamina.exhausted(target), stamina.weight(target),
                slides.isSliding(target), slides.cooldownLeft(target), Math.min(9999, slides.sprintTicks(target)))));
        sender.sendMessage(Text.mm(String.format("<muted>姿势 pose=%s sneaking=%b vehicle=%b y=%.3f box=%.3f..%.3f height=%.2f",
                target.getPose(), target.isSneaking(), target.isInsideVehicle(), target.getY(), box.getMinY(), box.getMaxY(), box.getHeight())));
        String refused = slides.refused(target);
        sender.sendMessage(Text.mm("<muted>上次没滑成 refused=" + (refused == null ? "-" : Text.escape(refused))));
        var last = slides.last(target);
        if (last == null) {
            sender.sendMessage(Text.mm("<muted>还没滑铲过 last=none"));
            return;
        }
        sender.sendMessage(Text.mm(String.format("<muted>上次滑铲 last reason=%s ticks=%d distance=%.3f spent=%.3f speed=%.3f ago=%d"
                        + " from=%.3f,%.3f,%.3f to=%.3f,%.3f,%.3f minY=%.3f..%.3f maxY=%.3f..%.3f",
                last.reason(), last.ticks(), last.distance(), last.spent(), last.startSpeed(), Bukkit.getCurrentTick() - last.endTick(),
                last.startX(), last.startY(), last.startZ(), last.endX(), last.endY(), last.endZ(),
                last.minYLow(), last.minYHigh(), last.maxYLow(), last.maxYHigh())));
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
            Text.send(sender, "<danger>没有叫 " + Text.escape(args[1]) + " 的玩家。");
            return;
        }
        if (args.length > 2) {
            String error = plugin.luck().adjust(uuid, args[2].toLowerCase(Locale.ROOT),
                    java.util.Arrays.copyOfRange(args, 3, args.length), sender.getName());
            if (error != null) {
                Text.send(sender, "<danger>" + Text.escape(error));
                return;
            }
            Text.send(sender, "<ok>已修改。");
        }
        Profile profile = plugin.profiles().peek(uuid);
        if (profile == null) {
            Text.send(sender, "<danger>读不出这个玩家的档案。");
            return;
        }
        for (String line : plugin.luck().describe(profile)) {
            sender.sendMessage(Text.mm(line));
        }
    }

    /**
     * /cv points &lt;player&gt; [amount]: shows a player's 点券 (online or not), or sets it. Goes
     * through the PointsBank like everything else, so once the server's global currency is plugged
     * in this reads and sets that balance instead of the placeholder.
     */
    private void points(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Text.send(sender, "<muted>用法：/cv points <玩家> [数量]（不写数量时查看）");
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        java.util.UUID uuid = online != null ? online.getUniqueId() : plugin.profiles().findByName(args[1]);
        if (uuid == null) {
            Text.send(sender, "<danger>没有叫 " + Text.escape(args[1]) + " 的玩家。");
            return;
        }
        String name = online != null ? online.getName() : args[1];
        var bank = plugin.points();
        if (args.length > 2) {
            Long amount = parseLong(args[2]);
            if (amount == null || amount < 0) {
                Text.send(sender, "<danger>数量要是不小于 0 的整数。");
                return;
            }
            String error = bank.set(uuid, amount);
            if (error != null) {
                Text.send(sender, "<danger>" + Text.escape(error));
                return;
            }
            plugin.getLogger().info(sender.getName() + " set the points of " + name + " (" + uuid + ") to " + amount);
        }
        long balance = bank.balance(uuid);
        Text.send(sender, balance < 0 ? "<danger>读不出 " + Text.escape(name) + " 的点券余额。"
                : "<text>" + Text.escape(name) + " <muted>点券 <info>" + Text.money(balance));
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
            Text.send(sender, "<danger>没有进行中的对局 " + Text.escape(id));
            return null;
        }
        Raid own = sender instanceof Player p ? plugin.raids().raidOf(p) : null;
        if (own != null && own.state == Raid.State.RUNNING) {
            return own;
        }
        if (running.size() == 1) {
            return running.get(0);
        }
        Text.send(sender, running.isEmpty() ? "<muted>没有进行中的行动。" : "<danger>有多个对局，请指定对局ID。");
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
        } else if (args.length == 2 && args[0].equalsIgnoreCase("fov")) {
            out.addAll(List.of("ok", "70", "90"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("fov")) {
            out.addAll(List.of("100", "50"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
            out.addAll(List.of("invite", "accept", "leave", "kick", "list"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("party")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("money")
                || (args[0].equalsIgnoreCase("luck") || args[0].equalsIgnoreCase("points") || args[0].equalsIgnoreCase("slide"))
                && sender.hasPermission("craftov.admin"))) {
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
