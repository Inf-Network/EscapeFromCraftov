package net.infnetwork.snowball.craftov.raid;

import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.gui.UiItems;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.ItemDef;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRecord;
import net.infnetwork.snowball.craftov.item.Keys;
import net.infnetwork.snowball.craftov.loot.ContainerType;
import net.infnetwork.snowball.craftov.map.ExtractType;
import net.infnetwork.snowball.craftov.map.MapDef;
import net.infnetwork.snowball.craftov.map.WorldService;
import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/** Owns every running raid: deployment, extraction, death, disconnects and settlement. */
public final class RaidManager implements Listener {

    private static final Set<Material> BLOCKED_INTERACT = EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.CRAFTING_TABLE, Material.ANVIL,
            Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL, Material.ENCHANTING_TABLE, Material.BREWING_STAND,
            Material.LOOM, Material.CARTOGRAPHY_TABLE, Material.SMITHING_TABLE, Material.STONECUTTER, Material.GRINDSTONE,
            Material.LECTERN, Material.HOPPER, Material.DROPPER, Material.DISPENSER, Material.ENDER_CHEST, Material.BEACON,
            Material.CRAFTER, Material.DECORATED_POT, Material.JUKEBOX, Material.NOTE_BLOCK, Material.CAULDRON,
            Material.FLOWER_POT, Material.CHISELED_BOOKSHELF, Material.RESPAWN_ANCHOR, Material.BELL);

    private final CraftovPlugin plugin;
    private final Map<String, Raid> raids = new HashMap<>();
    private final Map<UUID, Raid> byPlayer = new HashMap<>();
    private final Map<World, Raid> byWorld = new HashMap<>();
    private int counter;

    public RaidManager(CraftovPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickFast, 10L, 5L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickSpectators, 30L, 20L);
    }

    // ------------------------------------------------------------------ queries

    public Collection<Raid> raids() {
        return Collections.unmodifiableCollection(raids.values());
    }

    public @Nullable Raid raidOf(Player player) {
        return byPlayer.get(player.getUniqueId());
    }

    public @Nullable Raid raidOf(UUID uuid) {
        return byPlayer.get(uuid);
    }

    public @Nullable Raid raidIn(World world) {
        return byWorld.get(world);
    }

    public @Nullable RaidPlayer session(Player player) {
        Raid raid = raidOf(player);
        return raid == null ? null : raid.player(player.getUniqueId());
    }

    public boolean isInRaid(Player player) {
        RaidPlayer rp = session(player);
        return rp != null && rp.active() && player.getWorld().getName().startsWith(WorldService.RAID_PREFIX);
    }

    public boolean isRaidWorld(World world) {
        return byWorld.containsKey(world);
    }

    public boolean canFire(Player player) {
        if (plugin.lobby().inRange(player)) {
            return true;
        }
        RaidPlayer rp = session(player);
        return rp != null && rp.state == RaidPlayer.State.ALIVE && player.getGameMode() == GameMode.ADVENTURE
                && player.getWorld().getName().startsWith(WorldService.RAID_PREFIX);
    }

    public boolean canBeShot(Player player) {
        RaidPlayer rp = session(player);
        return rp != null && rp.active() && Bukkit.getCurrentTick() >= rp.protectedUntil;
    }

    public boolean canDamage(LivingEntity attacker, LivingEntity victim) {
        if (attacker instanceof Player a && victim instanceof Player v) {
            Raid raid = raidOf(a);
            if (raid == null || raid != raidOf(v)) {
                return false;
            }
            RaidPlayer ra = raid.player(a.getUniqueId());
            RaidPlayer rv = raid.player(v.getUniqueId());
            return ra != null && rv != null && (ra.squad != rv.squad || plugin.getConfig().getBoolean("combat.friendly-fire", false));
        }
        if (!(attacker instanceof Player) && !(victim instanceof Player)) {
            return plugin.ai().isAi(victim) != plugin.ai().isAi(attacker) || plugin.lobby().isTarget(victim);
        }
        return true;
    }

    public boolean sameSquad(Player a, Player b) {
        Raid raid = raidOf(a);
        if (raid == null || raid != raidOf(b)) {
            return false;
        }
        RaidPlayer ra = raid.player(a.getUniqueId());
        RaidPlayer rb = raid.player(b.getUniqueId());
        return ra != null && rb != null && ra.squad == rb.squad;
    }

    // ------------------------------------------------------------------ start

    /** Starts a raid for the given squads. Players must currently be in the lobby. */
    public void start(MapDef map, Tier tier, RaidMode mode, boolean ranked, List<List<Player>> squads) {
        World world = plugin.worlds().createRaidWorld(map);
        if (world == null) {
            for (List<Player> squad : squads) {
                squad.forEach(p -> Text.send(p, "<#FF8A80>创建行动地图失败，请联系管理员。"));
            }
            return;
        }
        String id = "R" + (++counter);
        Raid raid = new Raid(id, map, tier, mode, ranked, world);
        raids.put(id, raid);
        byWorld.put(world, raid);
        int squadNo = 0;
        for (List<Player> squad : squads) {
            squadNo++;
            List<UUID> members = new ArrayList<>();
            for (Player player : squad) {
                RaidPlayer rp = new RaidPlayer(player.getUniqueId(), player.getName(), squadNo);
                raid.players.put(player.getUniqueId(), rp);
                byPlayer.put(player.getUniqueId(), raid);
                members.add(player.getUniqueId());
                player.showTitle(Title.title(Text.mm("<#F2C94C>匹配成功"), Text.mm("<#9AA0A6>正在部署 " + Text.escape(map.name()) + " …"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(300))));
                player.playSound(player.getLocation(), "craftov:ui.match_found", SoundCategory.MASTER, 1f, 1f);
            }
            raid.squads.add(members);
        }
        // Preload and pin the whole map so containers and AI never unload mid-raid.
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        int minCX = (int) Math.floor(map.minX() - 16) >> 4;
        int maxCX = (int) Math.floor(map.maxX() + 16) >> 4;
        int minCZ = (int) Math.floor(map.minZ() - 16) >> 4;
        int maxCZ = (int) Math.floor(map.maxZ() + 16) >> 4;
        // Huge or unbounded maps: pin at most 40x40 chunks around the map centre.
        if (maxCX - minCX > 40) {
            int mid = (minCX + maxCX) / 2;
            minCX = mid - 20;
            maxCX = mid + 20;
        }
        if (maxCZ - minCZ > 40) {
            int mid = (minCZ + maxCZ) / 2;
            minCZ = mid - 20;
            maxCZ = mid + 20;
        }
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                futures.add(world.getChunkAtAsync(cx, cz));
            }
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((v, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (CompletableFuture<Chunk> f : futures) {
                        Chunk chunk = f.getNow(null);
                        if (chunk != null) {
                            chunk.addPluginChunkTicket(plugin);
                        }
                    }
                    deploy(raid);
                }));
    }

    private void deploy(Raid raid) {
        World world = raid.world;
        MapDef map = raid.map;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (MapDef.ContainerDef def : map.containers()) {
            ContainerType type = plugin.loot().type(def.type());
            if (type == null) {
                continue;
            }
            LootContainer container = new LootContainer(LootContainer.newId(), type, def.pos().at(world),
                    raid.tier.lootShift() + def.tier(), type.name());
            container.spawn(def.pos().yaw());
            raid.containers.put(container.id, container);
        }
        for (MapDef.ExtractDef def : map.extracts()) {
            raid.extracts.add(new ExtractPoint(def, def.pos().at(world), rng.nextDouble() < def.chance()));
        }
        for (MapDef.DoorDef door : map.doors()) {
            raid.lockedDoors.put(Raid.blockKey(door.pos().at(world)), door.lock());
        }
        for (MapDef.SwitchDef sw : map.switches()) {
            raid.switchBlocks.put(Raid.blockKey(sw.pos().at(world)), sw);
        }
        plugin.ai().populate(raid);
        // Assign spawn groups.
        List<Integer> groups = new ArrayList<>();
        for (MapDef.SpawnDef spawn : map.spawns()) {
            if (!groups.contains(spawn.group())) {
                groups.add(spawn.group());
            }
        }
        Collections.shuffle(groups);
        raid.state = Raid.State.RUNNING;
        raid.startTick = Bukkit.getCurrentTick();
        for (int i = 0; i < raid.squads.size(); i++) {
            deploySquad(raid, i + 1, groups.isEmpty() ? 1 : groups.get(i % groups.size()));
        }
        announce(raid, "<#9AA0A6>行动开始。<white>" + raid.players.size() + "</white> 名干员已部署，限时 "
                + raid.map.raidSeconds() / 60 + " 分钟。");
    }

    private void deploySquad(Raid raid, int squadNo, int group) {
        World world = raid.world;
        raid.squadSpawnGroup.put(squadNo, group);
        List<MapDef.SpawnDef> points = raid.map.spawns().stream().filter(s -> s.group() == group).toList();
        List<Player> members = new ArrayList<>();
        for (UUID uuid : raid.squads.get(squadNo - 1)) {
            Player player = Bukkit.getPlayer(uuid);
            RaidPlayer rp = raid.player(uuid);
            if (player == null || rp == null) {
                if (rp != null) {
                    rp.state = RaidPlayer.State.LEFT;
                }
                byPlayer.remove(uuid);
                continue;
            }
            members.add(player);
            prepare(raid, player, rp);
        }
        Location spawn = points.isEmpty() ? world.getSpawnLocation() : points.get(0).pos().at(world);
        Cinematic.play(plugin, raid, squadNo, members, spawn, player -> {
            int index = members.indexOf(player);
            Location target = points.isEmpty() ? spawn : points.get(Math.max(0, index) % points.size()).pos().at(world);
            insert(raid, player, target);
        });
    }

    /**
     * A running raid of this map, difficulty and ranking that is still in its join window and has
     * room for {@code size} more players, or null. Late squads backfill it instead of opening a new raid.
     */
    public @Nullable Raid joinable(String mapId, String tierId, boolean ranked, int size) {
        int window = plugin.getConfig().getInt("match.join-window-seconds", 120);
        int max = plugin.getConfig().getInt("match.max-players", 12);
        Raid best = null;
        for (Raid raid : raids.values()) {
            if (raid.state != Raid.State.RUNNING || raid.ranked != ranked || !raid.map.id().equals(mapId)
                    || !raid.tier.id().equals(tierId) || raid.elapsedSeconds() > window) {
                continue;
            }
            int active = 0;
            for (RaidPlayer rp : raid.players.values()) {
                if (rp.state == RaidPlayer.State.ALIVE || rp.state == RaidPlayer.State.DOWNED) {
                    active++;
                }
            }
            if (active + size <= max && (best == null || raid.startTick > best.startTick)) {
                best = raid;
            }
        }
        return best;
    }

    /** Adds a squad to a raid that is already running, at the spawn group fewest squads use. */
    public void joinRunning(Raid raid, List<Player> squad) {
        List<UUID> members = new ArrayList<>();
        int squadNo = raid.squads.size() + 1;
        for (Player player : squad) {
            RaidPlayer rp = new RaidPlayer(player.getUniqueId(), player.getName(), squadNo);
            raid.players.put(player.getUniqueId(), rp);
            byPlayer.put(player.getUniqueId(), raid);
            members.add(player.getUniqueId());
            player.showTitle(Title.title(Text.mm("<#F2C94C>匹配成功"), Text.mm("<#9AA0A6>加入进行中的行动 " + Text.escape(raid.map.name()) + " …"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(300))));
            player.playSound(player.getLocation(), "craftov:ui.match_found", SoundCategory.MASTER, 1f, 1f);
        }
        raid.squads.add(members);
        Map<Integer, Integer> use = new java.util.HashMap<>();
        for (MapDef.SpawnDef spawn : raid.map.spawns()) {
            use.putIfAbsent(spawn.group(), 0);
        }
        for (int group : raid.squadSpawnGroup.values()) {
            use.merge(group, 1, Integer::sum);
        }
        List<Integer> groups = new ArrayList<>(use.keySet());
        Collections.shuffle(groups);
        int group = groups.stream().min(java.util.Comparator.comparingInt(use::get)).orElse(1);
        deploySquad(raid, squadNo, group);
    }

    /** Takes the lobby loadout into the raid. */
    private void prepare(Raid raid, Player player, RaidPlayer rp) {
        plugin.weapons().reset(player);
        plugin.damage().forget(player.getUniqueId());
        plugin.stamina().refill(player);
        plugin.lobby().stripRangeItems(player);
        plugin.lobby().captureLoadout(player);
        Profile profile = plugin.profiles().get(player);
        profile.activeRaid = raid.id;
        profile.addStat("raids", 1);
        if (raid.tier.entryFee() > 0) {
            profile.money -= raid.tier.entryFee();
        }
        plugin.profiles().saveNow(player.getUniqueId());
        rp.secure = new ArrayList<>();
        for (ItemRecord record : profile.secure) {
            rp.secure.add(record.copy());
        }
        rp.startValue = plugin.carry().carriedValue(player) + secureValue(rp.secure);
        rp.startTick = Bukkit.getCurrentTick();
        rp.escortContract = plugin.escorts().contractFor(player.getUniqueId());
        player.getInventory().setItem(CarryService.TERMINAL_SLOT, UiItems.raidTerminal());
        player.closeInventory();
        plugin.health().setupPlayer(player, true);
        plugin.match().teamFor(raid, rp, player);
    }

    private void insert(Raid raid, Player player, Location at) {
        RaidPlayer rp = raid.player(player.getUniqueId());
        if (rp == null || raid.state != Raid.State.RUNNING) {
            plugin.lobby().enterLobby(player);
            return;
        }
        player.teleport(at);
        player.setGameMode(GameMode.ADVENTURE);
        player.setFallDistance(0);
        rp.state = RaidPlayer.State.ALIVE;
        rp.protectedUntil = Bukkit.getCurrentTick() + 60L;
        plugin.carry().refreshLocks(player);
        player.showTitle(Title.title(Text.mm("<#F2C94C><b>行动开始"), Text.mm("<#9AA0A6>搜索物资，活着撤离"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1600), Duration.ofMillis(500))));
        player.playSound(player.getLocation(), "craftov:ui.deploy", SoundCategory.MASTER, 1f, 1f);
        plugin.hud().onRaidStart(player);
    }

    // ------------------------------------------------------------------ ticking

    private void tickFast() {
        long now = Bukkit.getCurrentTick();
        for (Raid raid : new ArrayList<>(raids.values())) {
            if (raid.state != Raid.State.RUNNING) {
                continue;
            }
            for (ExtractPoint point : raid.extracts) {
                drawExtract(raid, point, now);
            }
            for (RaidPlayer rp : raid.players.values()) {
                if (rp.state != RaidPlayer.State.ALIVE) {
                    rp.extracting = null;
                    rp.extractTicks = 0;
                    continue;
                }
                Player player = Bukkit.getPlayer(rp.uuid);
                if (player == null) {
                    continue;
                }
                if (player.getWorld() != raid.world) {
                    evacuate(raid, player, rp, "你已离开行动区域，本次行动作废，携带物资已保留。");
                    continue;
                }
                rp.lastLocation = player.getLocation();
                ExtractPoint inside = null;
                for (ExtractPoint point : raid.extracts) {
                    if (point.available && point.center.getWorld() == player.getWorld()
                            && horizontalDistanceSq(point.center, player.getLocation()) <= point.def.radius() * point.def.radius()
                            && Math.abs(point.center.getY() - player.getY()) < 4) {
                        inside = point;
                        break;
                    }
                }
                if (inside == null) {
                    if (rp.extracting != null) {
                        player.sendActionBar(Text.mm("<#FF8A80>已离开撤离区域"));
                    }
                    rp.extracting = null;
                    rp.extractTicks = 0;
                    continue;
                }
                String blocked = extractBlocker(raid, inside, player);
                if (blocked != null) {
                    rp.extracting = null;
                    rp.extractTicks = 0;
                    player.sendActionBar(Text.mm("<#FF8A80>" + blocked));
                    continue;
                }
                if (inside.def.type() == ExtractType.SWITCH) {
                    // Lever extracts leave all at once when the departure countdown ends (see tickSecond).
                    long left = (raid.switchUntil.getOrDefault(inside.def.switchId(), 0L) - now) / 20 + 1;
                    player.sendActionBar(Text.mm("<#5BD16B>撤离倒计时 <white>" + left + "s <#9AA0A6>· 留在区域内即可撤离"));
                    rp.extracting = inside.def.id();
                    continue;
                }
                if (!inside.def.id().equals(rp.extracting)) {
                    rp.extracting = inside.def.id();
                    rp.extractTicks = 0;
                    player.playSound(player.getLocation(), "craftov:extract.start", SoundCategory.MASTER, 0.9f, 1f);
                    if (inside.def.type() == ExtractType.PAID) {
                        Text.send(player, "<#FFC23D>付费撤离：<white>撤离完成时自动从账户扣除 ◈" + Text.money(inside.def.cost())
                                + "</white> <#9AA0A6>(当前余额 ◈" + Text.money(plugin.profiles().get(player).money) + "，离开区域即取消)");
                    }
                }
                rp.extractTicks += 5;
                int need = inside.def.seconds() * 20;
                if (rp.extractTicks % 20 == 0) {
                    player.playSound(player.getLocation(), "craftov:extract.beep", SoundCategory.MASTER, 0.7f,
                            1f + rp.extractTicks / (float) need * 0.5f);
                }
                if (rp.extractTicks >= need) {
                    extract(raid, player, rp, inside);
                }
            }
        }
    }

    private static double horizontalDistanceSq(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Reason a player cannot use an extract right now, or null if they can. */
    public @Nullable String extractBlocker(Raid raid, ExtractPoint point, Player player) {
        MapDef.ExtractDef def = point.def;
        int elapsed = raid.elapsedSeconds();
        if (def.opensAt() > 0 && elapsed < def.opensAt()) {
            return def.name() + " 将在 " + Text.duration(def.opensAt() - elapsed) + " 后开放";
        }
        if (def.closesAt() > 0 && elapsed >= def.closesAt()) {
            return def.name() + " 已关闭";
        }
        if (def.capacity() > 0 && point.used >= def.capacity()) {
            return def.name() + " 名额已满";
        }
        switch (def.type()) {
            case SWITCH -> {
                if (!raid.switchActive(def.switchId()) && !raid.departing.contains(def.switchId())
                        && !departingNow.contains(def.switchId())) {
                    MapDef.SwitchDef sw = raid.map.switches().stream().filter(s -> s.id().equals(def.switchId())).findFirst().orElse(null);
                    return "需要先开启" + (sw == null ? "电闸" : sw.name());
                }
            }
            case PAID -> {
                if (plugin.profiles().get(player).money < def.cost()) {
                    return "撤离需要支付 ◈" + Text.money(def.cost());
                }
            }
            case NO_BACKPACK -> {
                ItemStack pack = player.getInventory().getItem(CarryService.FEET);
                if (pack != null && !pack.isEmpty()) {
                    return "此撤离点无法携带背包通过";
                }
            }
            default -> {
            }
        }
        return null;
    }

    private void drawExtract(Raid raid, ExtractPoint point, long now) {
        if (now % 10 != 0) {
            return;
        }
        Location c = point.center;
        boolean open = point.available && extractOpenByTime(raid, point);
        Color color = !point.available ? Color.fromRGB(90, 90, 90) : open ? Color.fromRGB(80, 230, 120) : Color.fromRGB(255, 170, 60);
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.3f);
        double r = point.def.radius();
        for (int i = 0; i < 24; i++) {
            double a = i / 24.0 * Math.PI * 2 + now * 0.02;
            raid.world.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * r, c.getY() + 0.15, c.getZ() + Math.sin(a) * r,
                    1, 0, 0, 0, 0, dust, true);
        }
        if (point.available) {
            for (int y = 0; y < 8; y++) {
                raid.world.spawnParticle(Particle.DUST, c.getX(), c.getY() + y * 1.5, c.getZ(), 1, 0.05, 0.2, 0.05, 0,
                        new Particle.DustOptions(color, 2.0f), true);
            }
        }
    }

    public boolean extractOpenByTime(Raid raid, ExtractPoint point) {
        int elapsed = raid.elapsedSeconds();
        MapDef.ExtractDef def = point.def;
        if (def.opensAt() > 0 && elapsed < def.opensAt()) {
            return false;
        }
        if (def.closesAt() > 0 && elapsed >= def.closesAt()) {
            return false;
        }
        return def.type() != ExtractType.SWITCH || raid.switchActive(def.switchId());
    }

    private void tickSecond() {
        long now = Bukkit.getCurrentTick();
        for (Raid raid : new ArrayList<>(raids.values())) {
            if (raid.state == Raid.State.ENDING && now >= raid.endTick) {
                close(raid);
                continue;
            }
            if (raid.state != Raid.State.RUNNING) {
                continue;
            }
            int remaining = raid.remainingSeconds();
            int elapsed = raid.elapsedSeconds();
            if (!raid.bossSpawned && raid.tier.boss()) {
                for (MapDef.BossDef boss : raid.map.bosses()) {
                    if (elapsed >= boss.delaySeconds()) {
                        raid.bossSpawned = true;
                        if (ThreadLocalRandom.current().nextDouble() < boss.chance()) {
                            plugin.ai().spawnBoss(raid, boss);
                            announce(raid, "<#FF4D4D>⚠ 情报：<white>" + Text.escape(boss.name()) + "</white> 出现在地图中，携带高价值物资。");
                        }
                    }
                }
            }
            for (String id : new ArrayList<>(raid.departing)) {
                if (!raid.switchActive(id)) {
                    raid.departing.remove(id);
                    departingNow.add(id);
                    depart(raid, id);
                    departingNow.remove(id);
                }
            }
            for (ExtractPoint point : raid.extracts) {
                if (point.available && !point.announcedOpen && point.def.opensAt() > 0 && elapsed >= point.def.opensAt()) {
                    point.announcedOpen = true;
                    announce(raid, "<#5BD16B>撤离点 <white>" + Text.escape(point.def.name()) + "</white> 现已开放"
                            + (point.def.capacity() > 0 ? "，仅限 " + point.def.capacity() + " 人" : "") + "。");
                }
            }
            if (remaining <= 300 && !raid.warned5) {
                raid.warned5 = true;
                announce(raid, "<#FFC23D>行动剩余 5 分钟，请尽快撤离！");
            }
            if (remaining <= 60 && !raid.warned1) {
                raid.warned1 = true;
                announce(raid, "<#FF4D4D>行动剩余 60 秒！超时未撤离将视为失踪。");
            }
            for (RaidPlayer rp : new ArrayList<>(raid.players.values())) {
                if (rp.state == RaidPlayer.State.DISCONNECTED
                        && now - rp.disconnectedAt > plugin.getConfig().getInt("raid.reconnect-seconds", 120) * 20L) {
                    missing(raid, rp, "断线超时");
                }
            }
            if (remaining <= 0) {
                for (RaidPlayer rp : new ArrayList<>(raid.players.values())) {
                    if (rp.active() || rp.state == RaidPlayer.State.DISCONNECTED) {
                        Player player = Bukkit.getPlayer(rp.uuid);
                        if (player != null && rp.active()) {
                            die(raid, player, rp, "行动超时，失踪", null);
                        } else {
                            missing(raid, rp, "行动超时");
                        }
                    }
                }
            }
            if (raid.activeCount() == 0) {
                raid.state = Raid.State.ENDING;
                raid.endTick = now + 20L * 8;
            }
        }
    }

    // ------------------------------------------------------------------ extraction

    /** Switch ids whose departure is being processed right now (the switch itself has just expired). */
    private final java.util.Set<String> departingNow = new java.util.HashSet<>();

    /** A lever countdown ran out: everyone standing in an extract tied to that lever leaves now. */
    private void depart(Raid raid, String switchId) {
        for (ExtractPoint point : raid.extracts) {
            if (!point.available || point.def.type() != ExtractType.SWITCH || !point.def.switchId().equals(switchId)) {
                continue;
            }
            for (RaidPlayer rp : new ArrayList<>(raid.players.values())) {
                Player player = Bukkit.getPlayer(rp.uuid);
                if (player == null || rp.state != RaidPlayer.State.ALIVE || player.getWorld() != raid.world
                        || horizontalDistanceSq(point.center, player.getLocation()) > point.def.radius() * point.def.radius()
                        || Math.abs(point.center.getY() - player.getY()) >= 4 || extractBlocker(raid, point, player) != null) {
                    continue;
                }
                extract(raid, player, rp, point);
            }
            raid.world.playSound(point.center, "craftov:extract.start", SoundCategory.MASTER, 4f, 0.8f);
        }
    }

    private void extract(Raid raid, Player player, RaidPlayer rp, ExtractPoint point) {
        Profile profile = plugin.profiles().get(player);
        if (point.def.type() == ExtractType.PAID) {
            if (profile.money < point.def.cost()) {
                return;
            }
            profile.money -= point.def.cost();
        }
        point.used++;
        plugin.weapons().reset(player);
        plugin.downed().clear(player);
        rp.state = RaidPlayer.State.EXTRACTED;
        rp.extracting = null;
        long carried = plugin.carry().carriedValue(player);
        long secure = secureValue(rp.secure);
        long total = carried + secure;
        long profit = total - rp.startValue;
        profile.secure = new ArrayList<>(rp.secure);
        profile.addStat("extracts", 1);
        profile.addStat("extracted_value", Math.max(0, profit));
        profile.addStat("kills", rp.kills);
        profile.addStat("ai_kills", rp.aiKills);
        profile.activeRaid = null;
        List<String> newCollectibles = plugin.collection().registerExtraction(player, profile, raid);
        int rankDelta = raid.ranked ? plugin.ranks().onExtract(profile, rp, profit, raid) : 0;
        long xp = Math.round((150 + Math.max(0, profit) / 800.0 + rp.kills * 60 + rp.aiKills * 20) * raid.tier.xpMultiplier());
        plugin.ranks().addXp(player, profile, xp);
        plugin.tasks().onExtract(player, raid, profit);
        Profile.RaidReport report = report(raid, rp, "撤离成功 · " + point.def.name(), Math.max(0, profit), 0, rankDelta, xp);
        profile.reports.add(0, report);
        trimReports(profile);
        plugin.escorts().settle(raid, rp, true);
        byPlayer.remove(player.getUniqueId());
        plugin.match().leaveTeam(player);
        announce(raid, "<#5BD16B>" + Text.escape(player.getName()) + " 已从 " + Text.escape(point.def.name()) + " 撤离。");
        player.showTitle(Title.title(Text.mm("<#5BD16B><b>撤离成功"), Text.mm("<#9AA0A6>带出价值 <#F2C94C>◈" + Text.money(total)
                + " <#8A8F98>· 净收益 " + (profit >= 0 ? "<#5BD16B>+" : "<#FF8A80>") + Text.money(profit)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
        player.playSound(player.getLocation(), "craftov:extract.success", SoundCategory.MASTER, 1f, 1f);
        // Inventory stays as the new lobby loadout.
        plugin.lobby().enterLobbyKeepInventory(player);
        plugin.lobby().captureLoadout(player);
        plugin.profiles().saveNow(player.getUniqueId());
        sendReport(player, report, newCollectibles);
    }

    // ------------------------------------------------------------------ death

    /** Lethal damage to a raid player: downed if possible, otherwise death. */
    public void lethal(Player player, DamageService.@Nullable LastHit hit) {
        Raid raid = raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        if (raid == null || rp == null || !rp.active()) {
            return;
        }
        if (rp.state == RaidPlayer.State.ALIVE && plugin.downed().canBeDowned(raid, rp)) {
            plugin.downed().down(raid, rp, player, hit);
            return;
        }
        die(raid, player, rp, deathReason(hit), hit);
    }

    public static String deathReason(DamageService.@Nullable LastHit hit) {
        if (hit == null) {
            return "阵亡";
        }
        if (hit.attacker() == null) {
            return "阵亡（" + hit.weapon() + "）";
        }
        return "被 " + hit.attackerName() + " 击杀（" + hit.weapon() + "）";
    }

    public void die(Raid raid, Player player, RaidPlayer rp, String reason, DamageService.@Nullable LastHit hit) {
        if (!rp.active()) {
            return;
        }
        plugin.weapons().reset(player);
        plugin.downed().clear(player);
        rp.state = RaidPlayer.State.DEAD;
        Location at = player.getLocation();
        long lost = plugin.carry().carriedValue(player);
        // Body with everything the player carried.
        List<ItemStack> drop = new ArrayList<>();
        ItemStack melee = player.getInventory().getItem(CarryService.MELEE_SLOT);
        for (int slot = 0; slot <= CarryService.HEAD; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (slot != CarryService.MELEE_SLOT && stack != null && !stack.isEmpty() && !ItemFactory.isUi(stack)) {
                drop.add(stack.clone());
            }
        }
        spawnCorpse(raid, at, player.getName(), drop, player);
        player.getInventory().clear();
        player.setItemOnCursor(null);
        Profile profile = plugin.profiles().get(player);
        profile.loadout.clear();
        keepMelee(profile, melee);
        profile.secure = new ArrayList<>(rp.secure);
        profile.activeRaid = null;
        profile.addStat("deaths", 1);
        profile.addStat("kills", rp.kills);
        profile.addStat("ai_kills", rp.aiKills);
        int rankDelta = raid.ranked ? plugin.ranks().onDeath(profile, rp, lost, raid) : 0;
        long xp = Math.round((40 + rp.kills * 60 + rp.aiKills * 20) * raid.tier.xpMultiplier());
        plugin.ranks().addXp(player, profile, xp);
        Profile.RaidReport report = report(raid, rp, reason, 0, lost, rankDelta, xp);
        profile.reports.add(0, report);
        trimReports(profile);
        plugin.profiles().saveNow(player.getUniqueId());
        plugin.escorts().settle(raid, rp, false);
        if (hit != null && hit.attacker() != null) {
            Player killer = Bukkit.getPlayer(hit.attacker());
            RaidPlayer kr = killer == null ? null : raid.player(killer.getUniqueId());
            if (kr != null) {
                kr.kills++;
                plugin.tasks().onPlayerKill(killer);
                killer.sendActionBar(Text.mm("<#FF4D4D>✖ 击杀 <white>" + Text.escape(player.getName())));
            }
        }
        killFeed(raid, hit, player.getName());
        player.setGameMode(GameMode.SPECTATOR);
        player.showTitle(Title.title(Text.mm("<#FF4D4D><b>行动失败"), Text.mm("<#9AA0A6>" + Text.escape(reason)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(600))));
        player.playSound(player.getLocation(), "craftov:ui.death", SoundCategory.MASTER, 1f, 1f);
        UUID uuid = player.getUniqueId();
        List<DamageService.HitRecord> hits = plugin.damage().hitsTaken(uuid);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            byPlayer.remove(uuid);
            if (p != null) {
                sendReport(p, report, List.of());
                sendDamageLog(p, hit, hits);
                if (!spectateMate(p, raid, rp)) {
                    plugin.match().leaveTeam(p);
                    plugin.lobby().enterLobby(p);
                }
            }
        }, 20L * 5);
    }

    /** Dead players watching a squadmate: spectator -> (raid, own raid record). */
    private final Map<UUID, Map.Entry<Raid, RaidPlayer>> spectators = new HashMap<>();

    /** After death the player follows a squadmate who is still in the raid. Returns false if none is left. */
    private boolean spectateMate(Player player, Raid raid, RaidPlayer rp) {
        if (raid.state != Raid.State.RUNNING || player.getWorld() != raid.world) {
            spectators.remove(player.getUniqueId());
            return false;
        }
        for (RaidPlayer mate : raid.squadOf(rp)) {
            Player target = mate == rp ? null : Bukkit.getPlayer(mate.uuid);
            if (target != null && mate.active() && target.getWorld() == raid.world) {
                spectators.put(player.getUniqueId(), Map.entry(raid, rp));
                player.setGameMode(GameMode.SPECTATOR);
                player.setSpectatorTarget(target);
                player.sendActionBar(Text.mm("<#9AA0A6>正在观战队友 <white>" + Text.escape(target.getName()) + " <#9AA0A6>· 按 <white>Shift</white> 返回大厅"));
                return true;
            }
        }
        spectators.remove(player.getUniqueId());
        return false;
    }

    private void stopSpectating(Player player) {
        spectators.remove(player.getUniqueId());
        plugin.match().leaveTeam(player);
        plugin.lobby().enterLobby(player);
    }

    /** Shift leaves the camera; a target that died, extracted or left hands over to the next mate. */
    @EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent event) {
        Player player = event.getPlayer();
        Map.Entry<Raid, RaidPlayer> entry = spectators.get(player.getUniqueId());
        if (entry == null) {
            return;
        }
        Raid raid = entry.getKey();
        RaidPlayer watched = event.getSpectatorTarget() instanceof Player t ? raid.player(t.getUniqueId()) : null;
        boolean targetGone = watched == null || !watched.active() || event.getSpectatorTarget().getWorld() != raid.world;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !spectators.containsKey(player.getUniqueId())) {
                return;
            }
            if (targetGone && spectateMate(player, raid, entry.getValue())) {
                return;
            }
            stopSpectating(player);
        });
    }

    /** Spectators drift if their target extracts or dies between events: re-check once a second. */
    private void tickSpectators() {
        for (UUID uuid : new ArrayList<>(spectators.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            Map.Entry<Raid, RaidPlayer> entry = spectators.get(uuid);
            if (player == null) {
                spectators.remove(uuid);
                continue;
            }
            org.bukkit.entity.Entity target = player.getSpectatorTarget();
            RaidPlayer watched = target instanceof Player t ? entry.getKey().player(t.getUniqueId()) : null;
            if (watched == null || !watched.active()) {
                if (!spectateMate(player, entry.getKey(), entry.getValue())) {
                    stopSpectating(player);
                }
            }
        }
    }

    /** The melee weapon is never lost: it goes back into the next loadout. */
    private void keepMelee(Profile profile, @Nullable ItemStack melee) {
        if (plugin.carry().isMelee(melee)) {
            ItemRecord record = plugin.items().toRecord(melee);
            if (record != null) {
                profile.loadout.put(CarryService.MELEE_SLOT, record);
            }
        }
    }

    private void missing(Raid raid, RaidPlayer rp, String reason) {
        if (rp.state != RaidPlayer.State.DISCONNECTED && !rp.active()) {
            return;
        }
        rp.state = RaidPlayer.State.DEAD;
        List<ItemStack> drop = new ArrayList<>();
        if (rp.disconnectedInventory != null) {
            for (ItemRecord record : rp.disconnectedInventory.values()) {
                ItemStack stack = plugin.items().fromRecord(record);
                if (stack != null) {
                    drop.add(stack);
                }
            }
        }
        if (rp.lastLocation != null && !drop.isEmpty()) {
            spawnCorpse(raid, rp.lastLocation, rp.name, drop, null);
        }
        long lost = 0;
        for (ItemStack stack : drop) {
            lost += plugin.items().value(stack);
        }
        long lostValue = lost;
        plugin.profiles().edit(rp.uuid, profile -> {
            profile.loadout.clear();
            profile.secure = new ArrayList<>(rp.secure);
            profile.activeRaid = null;
            profile.addStat("deaths", 1);
            int delta = raid.ranked ? plugin.ranks().onDeath(profile, rp, lostValue, raid) : 0;
            profile.reports.add(0, report(raid, rp, "失踪（" + reason + "）", 0, lostValue, delta, 0));
            trimReports(profile);
        });
        plugin.escorts().settle(raid, rp, false);
        byPlayer.remove(rp.uuid);
        announce(raid, "<#8A8F98>" + Text.escape(rp.name) + " 失去联络。");
    }

    public void spawnCorpse(Raid raid, Location at, String name, List<ItemStack> items, @Nullable Player owner) {
        io.papermc.paper.datacomponent.item.ResolvableProfile look = owner == null ? Mannequin.defaultProfile()
                : io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(owner.getPlayerProfile());
        spawnBody(raid, at, name + " 的遗体", items, look,
                owner == null ? null : owner.getInventory().getHelmet(), owner == null ? null : owner.getInventory().getChestplate());
    }

    /**
     * A body lying where someone fell: a mannequin with their look and the helmet and vest they wore,
     * searched like any container.
     */
    public void spawnBody(Raid raid, Location at, String title, List<ItemStack> items,
                          io.papermc.paper.datacomponent.item.ResolvableProfile look,
                          @Nullable ItemStack helmet, @Nullable ItemStack chest) {
        ContainerType type = plugin.loot().type("body_bag");
        if (type == null) {
            return;
        }
        Location ground = at.clone();
        ground.setPitch(0);
        LootContainer container = new LootContainer(LootContainer.newId(), new ContainerType(type.id(), type.name(), "none",
                1f, 1.4, 0.6, type.rows(), 0, 0, type.searchSpeed(), type.categories(), type.rarities(), List.of()),
                ground, 0, title);
        container.fill(items, false);
        container.spawn(0);
        Mannequin corpse = raid.world.spawn(ground, Mannequin.class, m -> {
            m.setProfile(look);
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setPersistent(false);
            m.setAI(false);
            m.setSilent(true);
            m.setDescription(Text.mm("<#8A8F98>☠ " + Text.escape(title)));
            m.customName(Text.mm("<#8A8F98>☠ " + Text.escape(title)));
            m.setCustomNameVisible(false);
            if (helmet != null && !helmet.isEmpty()) {
                m.getEquipment().setHelmet(helmet.clone());
            }
            if (chest != null && !chest.isEmpty()) {
                m.getEquipment().setChestplate(chest.clone());
            }
            Set<Pose> poses = Mannequin.validPoses();
            if (poses.contains(Pose.SLEEPING)) {
                m.setPose(Pose.SLEEPING, true);
            } else if (poses.contains(Pose.SWIMMING)) {
                m.setPose(Pose.SWIMMING, true);
            }
        });
        container.attachCorpse(corpse);
        raid.containers.put(container.id, container);
    }

    private void killFeed(Raid raid, DamageService.@Nullable LastHit hit, String victim) {
        String line = hit == null || hit.attacker() == null
                ? "<#8A8F98>☠ <white>" + Text.escape(victim)
                : "<#FF8A80>" + Text.escape(hit.attackerName()) + " <#8A8F98>[" + Text.escape(hit.weapon()) + "]"
                + (hit.zone() == net.infnetwork.snowball.craftov.combat.HitZone.HEAD ? " <#FF4D4D>⊕" : "") + " <#8A8F98>→ <white>" + Text.escape(victim);
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm(line));
        }
    }

    public void announce(Raid raid, String message) {
        for (Player player : raid.online()) {
            player.sendMessage(Text.mm(Text.PREFIX + message));
        }
    }

    private Profile.RaidReport report(Raid raid, RaidPlayer rp, String outcome, long extracted, long lost, int rankDelta, long xp) {
        Profile.RaidReport report = new Profile.RaidReport();
        report.time = System.currentTimeMillis();
        report.map = raid.map.name();
        report.tier = raid.tier.name() + (raid.ranked ? " · 排位" : "");
        report.outcome = outcome;
        report.extractedValue = extracted;
        report.lostValue = lost;
        report.kills = rp.kills;
        report.aiKills = rp.aiKills;
        report.seconds = (int) ((Bukkit.getCurrentTick() - rp.startTick) / 20);
        report.rankDelta = rankDelta;
        report.xp = xp;
        return report;
    }

    private static void trimReports(Profile profile) {
        while (profile.reports.size() > 20) {
            profile.reports.remove(profile.reports.size() - 1);
        }
    }

    private void sendReport(Player player, Profile.RaidReport r, List<String> collectibles) {
        player.sendMessage(Text.mm("<#3A3F47>━━━━━━━━━━ <#F2E6C8>行动报告</#F2E6C8> ━━━━━━━━━━"));
        player.sendMessage(Text.mm("<#8A8F98>地图 <white>" + Text.escape(r.map) + " <#8A8F98>· " + Text.escape(r.tier)
                + " <#8A8F98>· 用时 <white>" + Text.duration(r.seconds)));
        player.sendMessage(Text.mm("<#8A8F98>结果 <white>" + Text.escape(r.outcome)));
        if (r.extractedValue > 0) {
            player.sendMessage(Text.mm("<#8A8F98>净收益 <#5BD16B>+◈" + Text.money(r.extractedValue)));
        }
        if (r.lostValue > 0) {
            player.sendMessage(Text.mm("<#8A8F98>损失 <#FF8A80>-◈" + Text.money(r.lostValue)));
        }
        player.sendMessage(Text.mm("<#8A8F98>击败干员 <white>" + r.kills + " <#8A8F98>· 击败敌兵 <white>" + r.aiKills
                + " <#8A8F98>· 经验 <white>+" + r.xp + (r.rankDelta != 0 ? " <#8A8F98>· 排位分 " + (r.rankDelta > 0 ? "<#5BD16B>+" : "<#FF8A80>") + r.rankDelta : "")));
        for (String c : collectibles) {
            player.sendMessage(Text.mm("<#FF4D4D>★ 新收藏品入册：<white>" + Text.escape(c)));
        }
        player.sendMessage(Text.mm("<#3A3F47>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    /** Who hurt the player, where and how badly: one line per attacker and weapon. */
    private void sendDamageLog(Player player, DamageService.@Nullable LastHit killing, List<DamageService.HitRecord> hits) {
        if (hits.isEmpty()) {
            return;
        }
        if (killing != null && killing.attacker() != null) {
            DamageService.HitRecord last = hits.get(hits.size() - 1);
            player.sendMessage(Text.mm("<#FF4D4D>致命一击 <white>" + Text.escape(killing.attackerName()) + " <#8A8F98>· "
                    + Text.escape(killing.weapon()) + " · " + killing.part().displayName() + " · " + last.distance() + "m"));
        }
        java.util.Map<String, java.util.Map<net.infnetwork.snowball.craftov.combat.BodyPart, double[]>> byAttacker = new java.util.LinkedHashMap<>();
        for (DamageService.HitRecord h : hits) {
            double[] acc = byAttacker.computeIfAbsent(h.attacker() + " · " + h.weapon(), k -> new java.util.EnumMap<>(net.infnetwork.snowball.craftov.combat.BodyPart.class))
                    .computeIfAbsent(h.part(), k -> new double[2]);
            acc[0] += 1;
            acc[1] += h.damage();
        }
        player.sendMessage(Text.mm("<#8A8F98>受到的伤害"));
        byAttacker.forEach((who, parts) -> {
            StringBuilder sb = new StringBuilder("<white>" + Text.escape(who) + " <#8A8F98>→ ");
            parts.forEach((part, acc) -> sb.append("<#C8CCD2>").append(part.displayName()).append(" ×").append((int) acc[0])
                    .append(" <#FF8A80>").append(Math.round(acc[1])).append("  "));
            player.sendMessage(Text.mm(sb.toString().trim()));
        });
    }

    public long secureValue(List<ItemRecord> records) {
        long total = 0;
        for (ItemRecord record : records) {
            total += plugin.items().value(record);
        }
        return total;
    }

    // ------------------------------------------------------------------ voluntary exit / shutdown

    /** Gives up the raid (counts as death). */
    public void surrender(Player player) {
        Raid raid = raidOf(player);
        RaidPlayer rp = session(player);
        if (raid != null && rp != null && rp.active()) {
            die(raid, player, rp, "放弃行动", null);
        }
    }

    private void close(Raid raid) {
        raid.state = Raid.State.CLOSED;
        spectators.values().removeIf(entry -> entry.getKey() == raid);
        for (Player player : raid.world.getPlayers()) {
            byPlayer.remove(player.getUniqueId());
            plugin.lobby().enterLobby(player);
        }
        plugin.ai().clear(raid);
        for (LootContainer container : raid.containers.values()) {
            container.remove();
        }
        plugin.match().disposeTeams(raid);
        raids.remove(raid.id);
        byWorld.remove(raid.world);
        raid.players.keySet().forEach(u -> {
            if (byPlayer.get(u) == raid) {
                byPlayer.remove(u);
            }
        });
        for (Chunk chunk : raid.world.getLoadedChunks()) {
            chunk.removePluginChunkTicket(plugin);
        }
        plugin.worlds().disposeRaidWorld(raid.world);
    }

    /** Voids the raid for one player who ended up outside the raid world (admin teleport, glitch). */
    private void evacuate(Raid raid, Player player, RaidPlayer rp, String message) {
        plugin.weapons().reset(player);
        plugin.downed().clear(player);
        rp.state = RaidPlayer.State.LEFT;
        Profile profile = plugin.profiles().get(player);
        profile.secure = new ArrayList<>(rp.secure);
        profile.activeRaid = null;
        byPlayer.remove(player.getUniqueId());
        plugin.match().leaveTeam(player);
        plugin.lobby().enterLobbyKeepInventory(player);
        plugin.lobby().captureLoadout(player);
        Text.send(player, "<#FFC23D>" + message);
    }

    /** Plugin shutdown: everybody goes home with what they carry (raid voided, no losses). */
    public void shutdown() {
        for (Raid raid : new ArrayList<>(raids.values())) {
            for (RaidPlayer rp : raid.players.values()) {
                Player player = Bukkit.getPlayer(rp.uuid);
                if (rp.active() && player != null) {
                    plugin.weapons().reset(player);
                    plugin.downed().clear(player);
                    Profile profile = plugin.profiles().get(player);
                    profile.secure = new ArrayList<>(rp.secure);
                    profile.activeRaid = null;
                    plugin.lobby().enterLobbyKeepInventory(player);
                    plugin.lobby().captureLoadout(player);
                    Text.send(player, "<#FFC23D>服务器维护：本次行动已中止，你携带的物资已保留。");
                } else if (rp.state == RaidPlayer.State.DISCONNECTED && rp.disconnectedInventory != null) {
                    Map<Integer, ItemRecord> inv = rp.disconnectedInventory;
                    plugin.profiles().edit(rp.uuid, profile -> {
                        profile.loadout = new HashMap<>(inv);
                        profile.secure = new ArrayList<>(rp.secure);
                        profile.activeRaid = null;
                    });
                }
            }
            raid.state = Raid.State.CLOSED;
            plugin.ai().clear(raid);
            for (LootContainer container : raid.containers.values()) {
                container.remove();
            }
            plugin.match().disposeTeams(raid);
            plugin.worlds().disposeRaidWorld(raid.world);
        }
        raids.clear();
        byPlayer.clear();
        byWorld.clear();
    }

    /** Rejoin after a disconnect. Returns true if the player was put back into their raid. */
    public boolean tryRejoin(Player player) {
        Raid raid = raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        if (raid == null || rp == null || rp.state != RaidPlayer.State.DISCONNECTED || raid.state != Raid.State.RUNNING
                || rp.disconnectedInventory == null || rp.lastLocation == null) {
            return false;
        }
        plugin.carry().apply(player, rp.disconnectedInventory, UiItems.raidTerminal());
        rp.disconnectedInventory = null;
        rp.state = RaidPlayer.State.ALIVE;
        rp.protectedUntil = Bukkit.getCurrentTick() + 40;
        plugin.health().setupPlayer(player, false);
        Location back = rp.lastLocation.getWorld() == raid.world ? rp.lastLocation : raid.map.spawns().isEmpty()
                ? raid.world.getSpawnLocation() : raid.map.spawns().get(0).pos().at(raid.world);
        player.teleport(back);
        player.setGameMode(GameMode.ADVENTURE);
        plugin.match().teamFor(raid, rp, player);
        Text.send(player, "<#5BD16B>已重新连接到行动。");
        return true;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Raid raid = raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        if (rp == null || !player.getWorld().equals(raid.world)) {
            return;
        }
        if (!rp.active() || Bukkit.getCurrentTick() < rp.protectedUntil) {
            event.setCancelled(true);
            return;
        }
        if (rp.state == RaidPlayer.State.DOWNED) {
            event.setCancelled(true);
            // Only hostile fire and explosions finish a downed player; falls, walls and the like do not.
            boolean hostile = event instanceof EntityDamageByEntityEvent || plugin.damage().isApplying()
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                    || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
            if (hostile) {
                plugin.downed().damageDowned(raid, rp, player, event.getFinalDamage());
            }
            return;
        }
        if (event.getFinalDamage() >= player.getHealth()) {
            event.setCancelled(true);
            DamageService.LastHit hit = plugin.damage().lastHit(player.getUniqueId());
            if (hit == null && event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof LivingEntity living) {
                plugin.damage().recordHit(player, living, "近战", net.infnetwork.snowball.craftov.combat.HitZone.TORSO);
                hit = plugin.damage().lastHit(player.getUniqueId());
            }
            if (hit == null) {
                plugin.damage().recordHit(player, null, switch (event.getCause()) {
                    case FALL -> "坠落";
                    case DROWNING -> "溺水";
                    case FIRE, FIRE_TICK, LAVA -> "烧伤";
                    case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> "爆炸";
                    default -> "环境伤害";
                }, net.infnetwork.snowball.craftov.combat.HitZone.TORSO);
                hit = plugin.damage().lastHit(player.getUniqueId());
            }
            lethal(player, hit);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter) {
            damager = shooter;
        }
        if (damager instanceof LivingEntity attacker && event.getEntity() instanceof LivingEntity victim
                && !canDamage(attacker, victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onVanillaDeath(PlayerDeathEvent event) {
        // Should not happen (lethal damage is intercepted) but never drop items vanilla-style in raids.
        if (raidOf(event.getEntity()) != null) {
            event.getDrops().clear();
            event.setKeepInventory(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        String role = entity.getPersistentDataContainer().get(Keys.ENTITY_ROLE, PersistentDataType.STRING);
        if (!"container".equals(role)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Raid raid = raidIn(entity.getWorld());
        RaidPlayer rp = session(player);
        if (raid == null || rp == null || rp.state != RaidPlayer.State.ALIVE) {
            return;
        }
        String ref = entity.getPersistentDataContainer().get(Keys.ENTITY_REF, PersistentDataType.STRING);
        LootContainer container = ref == null ? null : raid.containers.get(ref);
        if (container == null) {
            return;
        }
        plugin.weapons().flush(player);
        if (!container.generated()) {
            container.generate(plugin.loot().roll(container.type, container.shift));
            rp.containersSearched++;
            plugin.tasks().onSearch(player);
        }
        player.playSound(player.getLocation(), "craftov:loot.open", SoundCategory.PLAYERS, 0.8f, 1f);
        new LootMenu(plugin, player, container).open();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (BLOCKED_INTERACT.contains(block.getType()) || block.getType().name().endsWith("_BED")
                || block.getType().name().endsWith("SHULKER_BOX")) {
            event.setCancelled(true);
            return;
        }
        Raid raid = raidIn(block.getWorld());
        if (raid == null) {
            return;
        }
        RaidPlayer rp = raid.player(player.getUniqueId());
        if (rp == null || rp.state != RaidPlayer.State.ALIVE) {
            event.setCancelled(true);
            return;
        }
        Block lower = block;
        if (block.getBlockData() instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.TOP) {
            lower = block.getRelative(org.bukkit.block.BlockFace.DOWN);
        }
        String doorKey = Raid.blockKey(lower.getLocation());
        String lock = raid.lockedDoors.get(doorKey);
        if (lock != null) {
            event.setCancelled(true);
            useKey(player, raid, doorKey, lower, lock);
            return;
        }
        if (raid.unlockedDoors.contains(doorKey)) {
            // Iron doors cannot be opened by hand; unlocked ones open and close like any other door.
            event.setCancelled(true);
            boolean open = lower.getBlockData() instanceof Openable o && o.isOpen();
            setDoor(lower, !open);
            lower.getWorld().playSound(lower.getLocation(), open ? "minecraft:block.iron_door.close" : "minecraft:block.iron_door.open", 1f, 1f);
            return;
        }
        MapDef.SwitchDef sw = raid.switchBlocks.get(Raid.blockKey(block.getLocation()));
        if (sw != null) {
            event.setCancelled(true);
            if (raid.switchActive(sw.id())) {
                player.sendActionBar(Text.mm("<#9AA0A6>" + Text.escape(sw.name()) + " 已经开启"));
                return;
            }
            int countdown = plugin.getConfig().getInt("extract.switch-countdown-seconds", 60);
            raid.switchUntil.put(sw.id(), (long) Bukkit.getCurrentTick() + countdown * 20L);
            raid.departing.add(sw.id());
            if (block.getBlockData() instanceof Powerable powerable) {
                powerable.setPowered(true);
                block.setBlockData(powerable, false);
            }
            raid.world.playSound(block.getLocation(), "craftov:world.switch", SoundCategory.BLOCKS, 3f, 1f);
            announce(raid, "<#FFC23D>" + Text.escape(sw.name()) + " 已被拉下！相关撤离点将在 " + countdown + " 秒后撤离，留在区域内的人将一同离开。");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (block.getBlockData() instanceof Powerable powerable) {
                    powerable.setPowered(false);
                    block.setBlockData(powerable, false);
                }
            }, countdown * 20L + 20L);
        }
    }

    private void useKey(Player player, Raid raid, String doorKey, Block door, String lock) {
        ItemStack key = null;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def != null && def.category() == Category.KEY && lock.equals(def.lockId())) {
                key = stack;
                break;
            }
        }
        if (key == null) {
            String needed = plugin.registry().all().stream()
                    .filter(d -> d.category() == Category.KEY && lock.equals(d.lockId()))
                    .map(ItemDef::name).findFirst().orElse("对应的钥匙");
            player.sendActionBar(Text.mm("<#FF8A80>门被锁住了，需要 <white>" + Text.escape(needed)));
            player.playSound(player.getLocation(), "minecraft:block.iron_door.close", 0.6f, 1.6f);
            return;
        }
        ItemDef def = plugin.items().defOf(key);
        double uses = ItemFactory.dura(key, def.durability()) - 1;
        if (uses <= 0) {
            key.setAmount(key.getAmount() - 1);
            player.sendActionBar(Text.mm("<#FFC23D>已解锁 · " + Text.escape(def.name()) + " 已用尽"));
        } else {
            plugin.items().setDura(key, uses);
            player.sendActionBar(Text.mm("<#5BD16B>已解锁 · " + Text.escape(def.name()) + " <#8A8F98>剩余 " + (int) Math.round(uses) + " 次"));
        }
        raid.lockedDoors.remove(doorKey);
        raid.unlockedDoors.add(doorKey);
        setDoor(door, true);
        door.getWorld().playSound(door.getLocation(), "minecraft:block.iron_door.open", 1f, 1f);
    }

    private static void setDoor(Block lower, boolean open) {
        for (Block part : new Block[]{lower, lower.getRelative(org.bukkit.block.BlockFace.UP)}) {
            if (part.getBlockData() instanceof Openable openable) {
                openable.setOpen(open);
                part.setBlockData(openable, false);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        RaidPlayer rp = session(player);
        if (rp == null || rp.state != RaidPlayer.State.ALIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            if (plugin.ai().isAi(event.getEntity())) {
                event.setCancelled(true);
            }
            return;
        }
        RaidPlayer rp = session(player);
        Item item = event.getItem();
        if (rp == null || rp.state != RaidPlayer.State.ALIVE || item.getPersistentDataContainer().has(Keys.ENTITY_ROLE)) {
            event.setCancelled(true);
            return;
        }
        ItemStack stack = item.getItemStack();
        if (ItemFactory.hasContents(stack)) {
            event.setCancelled(true);
            ItemDef def = plugin.items().defOf(stack);
            if (def != null && def.isContainer()) {
                ItemStack equipped = player.getInventory().getItem(CarryService.containerSlot(def.category()));
                if (equipped == null || equipped.isEmpty()) {
                    item.remove();
                    plugin.carry().equip(player, stack);
                } else if (player.isSneaking()) {
                    // Swap: the worn one drops here with its contents, the one on the ground goes on.
                    ItemStack old = plugin.carry().unequip(player, CarryService.containerSlot(def.category()));
                    item.remove();
                    plugin.carry().equip(player, stack);
                    if (old != null) {
                        Item dropped = player.getWorld().dropItem(player.getLocation(), old);
                        dropped.setPickupDelay(40);
                    }
                    player.playSound(player.getLocation(), "minecraft:item.armor.equip_leather", 1f, 1f);
                } else {
                    player.sendActionBar(Text.mm("<#9AA0A6>潜行拾取可直接替换当前的" + def.category().displayName()));
                }
            }
            return;
        }
        if (!plugin.carry().hasRoomFor(player, stack)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Raid raid = raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        if (raid == null || rp == null) {
            return;
        }
        plugin.weapons().reset(player);
        if (rp.state == RaidPlayer.State.DOWNED) {
            die(raid, player, rp, "倒地后断线", plugin.damage().lastHit(player.getUniqueId()));
            byPlayer.remove(player.getUniqueId());
            player.getInventory().clear();
            return;
        }
        if (rp.state == RaidPlayer.State.ALIVE || rp.state == RaidPlayer.State.DEPLOYING) {
            rp.disconnectedInventory = plugin.carry().snapshot(player);
            rp.lastLocation = rp.state == RaidPlayer.State.ALIVE ? player.getLocation() : null;
            rp.state = RaidPlayer.State.DISCONNECTED;
            rp.disconnectedAt = Bukkit.getCurrentTick();
            player.getInventory().clear();
            if (rp.lastLocation == null) {
                missing(raid, rp, "部署时断线");
            }
        } else if (rp.state == RaidPlayer.State.DEAD || rp.state == RaidPlayer.State.EXTRACTED) {
            byPlayer.remove(player.getUniqueId());
        }
        plugin.match().leaveTeam(player);
    }

    /** Called by loot menus so systems (escort, stats) can react to looting. */
    public void onLooted(Player player, ItemStack stack) {
        RaidPlayer rp = session(player);
        if (rp == null) {
            return;
        }
        ItemDef def = plugin.items().defOf(stack);
        if (def != null && (def.rarity().tier() >= 5)) {
            Raid raid = raidOf(player);
            if (raid != null) {
                for (RaidPlayer mate : raid.squadOf(rp)) {
                    Player p = Bukkit.getPlayer(mate.uuid);
                    if (p != null && p != player) {
                        p.sendMessage(Text.mm(Text.PREFIX + "<#9AA0A6>队友 " + Text.escape(player.getName()) + " 找到了 " + def.coloredName()));
                    }
                }
            }
        }
    }
}
