package net.infnetwork.snowball.craftov.raid;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
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
import net.infnetwork.snowball.craftov.profile.ProfileService;
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
import org.bukkit.entity.ItemDisplay;
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
import org.bukkit.event.player.PlayerTeleportEvent;
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
import java.util.function.Supplier;

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

    /**
     * True while a dead player watches a squadmate. They have already left the raid (raidOf is
     * null), but they are still out of the lobby and their loadout is the one death left them.
     */
    public boolean isSpectating(Player player) {
        return spectators.containsKey(player.getUniqueId());
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

    /**
     * {@code attacker} hit {@code victim} (or had the shot blocked: {@code hit} is null then). A
     * raid player on either side is in combat from now on; the victim also remembers the hit, so
     * leaving the game right after credits whoever made it. Hits between squadmates that do no
     * damage (a grenade near a squadmate without friendly fire) count for nothing.
     */
    public void onHostileHit(LivingEntity victim, LivingEntity attacker, DamageService.@Nullable LastHit hit) {
        if (!canDamage(attacker, victim)) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        RaidPlayer rv = victim instanceof Player v ? session(v) : null;
        if (rv != null && rv.active()) {
            rv.lastCombatTick = now;
            if (hit != null) {
                rv.lastHostile = hit;
            }
        }
        RaidPlayer ra = attacker instanceof Player a ? session(a) : null;
        if (ra != null && ra.active()) {
            ra.lastCombatTick = now;
        }
    }

    /** Whether leaving the game now counts as death: within raid.combat-logout-seconds of a fight (0: never). */
    private boolean inCombat(RaidPlayer rp, long now) {
        long window = plugin.getConfig().getLong("raid.combat-logout-seconds", 15) * 20L;
        return window > 0 && rp.lastCombatTick >= 0 && now - rp.lastCombatTick < window;
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

    /**
     * Starts a raid for the given squads. Players must currently be in the lobby. The roster is final:
     * nobody joins a raid after it starts. {@code ranked} holds the players who win or lose rank points.
     */
    public void start(MapDef map, Tier tier, List<List<Player>> squads, java.util.Set<UUID> ranked) {
        World world = plugin.worlds().createRaidWorld(map);
        if (world == null) {
            for (List<Player> squad : squads) {
                squad.forEach(p -> Text.send(p, "<#FF8A80>创建行动地图失败，请联系管理员。"));
            }
            return;
        }
        String id = "R" + (++counter);
        Raid raid = new Raid(id, map, tier, world);
        raids.put(id, raid);
        byWorld.put(world, raid);
        int squadNo = 0;
        for (List<Player> squad : squads) {
            squadNo++;
            List<UUID> members = new ArrayList<>();
            for (Player player : squad) {
                RaidPlayer rp = new RaidPlayer(player.getUniqueId(), player.getName(), squadNo, ranked.contains(player.getUniqueId()));
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
        if (raid.players.isEmpty()) {
            // Everyone dropped out while the map loaded (see onQuit): nothing to deploy. tickSecond
            // closes it in a moment; unloading the world right away cuts off chunk generation the
            // preload set off around the map, and CraftEngine then logs a stack trace per chunk.
            raid.state = Raid.State.ENDING;
            raid.endTick = Bukkit.getCurrentTick() + 100L;
            return;
        }
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
        plugin.events().begin(raid);
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
            if (rp == null || rp.state != RaidPlayer.State.DEPLOYING) {
                // Already settled (gone missing, left): never charge and deploy anyone twice.
                continue;
            }
            if (player == null) {
                rp.state = RaidPlayer.State.LEFT;
                byPlayer.remove(uuid, raid);
                continue;
            }
            // The insertion point is fixed before the camera flies: whoever drops out during the
            // flight reconnects there, or leaves their body there when the reconnect window ends.
            rp.lastLocation = points.isEmpty() ? world.getSpawnLocation() : points.get(members.size() % points.size()).pos().at(world);
            rp.inFlight = true;
            members.add(player);
            prepare(raid, player, rp);
        }
        Location spawn = points.isEmpty() ? world.getSpawnLocation() : points.get(0).pos().at(world);
        Cinematic.play(plugin, raid, squadNo, members, spawn, uuid -> {
            RaidPlayer rp = raid.player(uuid);
            if (rp != null) {
                rp.inFlight = false;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || rp != null && rp.state != RaidPlayer.State.DEPLOYING) {
                return; // offline (a later reconnect lands at once), or settled while the camera flew
            }
            // Everyone still on the camera lands now, including whoever reconnected during the flight.
            insert(raid, player, rp == null || rp.lastLocation == null ? spawn : rp.lastLocation);
        });
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
        long entryFee = Math.max(0, raid.tier.entryFee());
        profile.money -= entryFee;
        rp.fees = entryFee + plugin.insurance().onDeploy(player, raid);
        plugin.tutorial().done(player, net.infnetwork.snowball.craftov.lobby.TutorialService.Step.DEPLOY);
        plugin.profiles().saveNow(player.getUniqueId());
        rp.secure = new ArrayList<>();
        for (ItemRecord record : profile.secure) {
            rp.secure.add(record.copy());
        }
        rp.gearUids = tagGear(player, rp.secure);
        List<ItemRecord> takenIn = new ArrayList<>(plugin.carry().snapshot(player).values());
        takenIn.addAll(rp.secure);
        rp.broughtIn = stackCounts(takenIn);
        rp.gearValue = plugin.carry().carriedValue(player);
        rp.startValue = rp.gearValue + recordsValue(rp.secure);
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
                // A backpack taken off onto the cursor with its contents is put back on when the
                // extraction settles the cursor, so holding one counts as wearing it.
                ItemStack held = player.getItemOnCursor();
                ItemDef heldDef = plugin.items().defOf(held);
                if (pack != null && !pack.isEmpty()
                        || heldDef != null && heldDef.category() == Category.BACKPACK && ItemFactory.hasContents(held)) {
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
            if (remaining > 0) {
                landOverdue(raid, now);
            }
            if (remaining <= 0) {
                for (RaidPlayer rp : new ArrayList<>(raid.players.values())) {
                    if (rp.state == RaidPlayer.State.DEPLOYING || rp.state == RaidPlayer.State.DISCONNECTED && rp.inFlight) {
                        callOff(raid, rp);
                    } else if (rp.active() || rp.state == RaidPlayer.State.DISCONNECTED) {
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
                raid.endTick = Math.max(raid.endTick, now + 20L * 8); // a kill replay may need longer
            }
        }
    }

    /**
     * The entry camera lands its squad after about 7 seconds. Whoever is still flying in long after
     * that (the camera broke down, or a player was moved off it) would hang in the air for the whole
     * raid, and keep it open: they are put down on their insertion point now.
     */
    private void landOverdue(Raid raid, long now) {
        long timeout = Math.max(10, plugin.getConfig().getInt("raid.deploy-timeout-seconds", 20)) * 20L;
        for (RaidPlayer rp : new ArrayList<>(raid.players.values())) {
            Player player = rp.state == RaidPlayer.State.DEPLOYING ? Bukkit.getPlayer(rp.uuid) : null;
            if (player == null || now - rp.startTick <= timeout) {
                continue;
            }
            plugin.getLogger().warning(rp.name + " was still flying into raid " + raid.id + " "
                    + (now - rp.startTick) / 20 + " s after deployment; landing them now");
            rp.inFlight = false;
            insert(raid, player, rp.lastLocation != null && rp.lastLocation.getWorld() == raid.world
                    ? rp.lastLocation : raid.world.getSpawnLocation());
        }
    }

    /**
     * The raid ran out before this player landed (in practice an admin's /cv end during the entry
     * camera): home with everything they brought, and the entry fee and insurance premium back, as
     * when the server stops during the flight. That goes for whoever dropped out of the camera too
     * (DISCONNECTED, still inFlight): they never landed either, and find it all back when they
     * come back.
     */
    private void callOff(Raid raid, RaidPlayer rp) {
        rp.inFlight = false;
        Player player = rp.state == RaidPlayer.State.DISCONNECTED ? null : Bukkit.getPlayer(rp.uuid);
        if (player == null) {
            if (rp.state == RaidPlayer.State.DISCONNECTED && rp.disconnectedInventory != null) {
                // As far as other players' profit goes, they went home with all of it (see held).
                rp.carriedOut = stackCounts(homeOffline(raid, rp, rp.fees, "raid " + raid.id + " ended during the entry camera"));
                rp.disconnectedInventory = null;
            }
            // Otherwise (not expected: leaving turns DEPLOYING into DISCONNECTED in onQuit) the next
            // join finds the raid gone and keeps the lobby loadout (LobbyService.onJoin).
            rp.state = RaidPlayer.State.LEFT;
            byPlayer.remove(rp.uuid, raid);
            plugin.escorts().voided(rp);
            return;
        }
        plugin.profiles().get(player).money += rp.fees;
        evacuate(raid, player, rp, rp.fees > 0
                ? "行动在你落地前结束，入场费和保险费 ◈" + Text.money(rp.fees) + " 已退还，携带的物资已保留。"
                : "行动在你落地前结束，携带的物资已保留。");
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
        // Something held on the cursor is carried out too: back into the inventory before the value
        // is counted, and what does not fit goes to the mailbox but is counted with the rest.
        List<ItemRecord> mailed = plugin.carry().reclaimCursorHome(player);
        plugin.insurance().strip(player);
        plugin.tutorial().done(player, net.infnetwork.snowball.craftov.lobby.TutorialService.Step.EXTRACT);
        plugin.downed().clear(player);
        rp.state = RaidPlayer.State.EXTRACTED;
        rp.extracting = null;
        long carried = plugin.carry().carriedValue(player) + recordsValue(mailed);
        long secure = recordsValue(rp.secure);
        long total = carried + secure;
        List<ItemRecord> out = new ArrayList<>(plugin.carry().snapshot(player).values());
        out.addAll(mailed);
        out.addAll(rp.secure);
        long handed = othersGear(raid, rp, out);
        rp.carriedOut = stackCounts(out);
        long profit = total - handed - rp.startValue;
        profile.secure = new ArrayList<>(rp.secure);
        profile.addStat("extracts", 1);
        profile.addStat("extracted_value", Math.max(0, profit));
        profile.addStat("kills", rp.kills);
        profile.addStat("ai_kills", rp.aiKills);
        profile.activeRaid = null;
        List<String> newCollectibles = plugin.collection().registerExtraction(player, profile, raid, mailed);
        int rankDelta = rp.ranked ? plugin.ranks().onExtract(profile, rp, profit, raid) : 0;
        long xp = Math.round((extractXp(rp) + Math.max(0, profit) / 800.0 + rp.kills * 60 + rp.aiKills * 20) * raid.tier.xpMultiplier());
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
        if (handed > 0) {
            Text.send(player, "<#9AA0A6>其他玩家带进本局的装备 <#F2C94C>◈" + Text.money(handed)
                    + " <#9AA0A6>归你所有，但不计入净收益（排位分、经验和任务）。");
        }
        // Inventory stays as the new lobby loadout.
        plugin.lobby().enterLobbyKeepInventory(player);
        plugin.lobby().captureLoadout(player);
        plugin.profiles().saveNow(player.getUniqueId());
        sendReport(player, report, newCollectibles);
    }

    /**
     * The flat experience for getting out: in full for players who brought in gear worth at least
     * rank.extract-xp-min-gear, in proportion below that. Walking out naked earned 150 a run with
     * nothing at stake; profit and kills still earn their own experience.
     */
    private double extractXp(RaidPlayer rp) {
        double base = plugin.getConfig().getDouble("rank.extract-xp", 150);
        long min = plugin.getConfig().getLong("rank.extract-xp-min-gear", 15000);
        return min <= 0 ? base : base * Math.min(1.0, rp.gearValue / (double) min);
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
        // Whatever was held on the cursor (a picked-up backpack with everything in it, say) is carried too.
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.isEmpty() && !ItemFactory.isUi(cursor) && !plugin.carry().isMelee(cursor)) {
            drop.add(cursor.clone());
            lost += plugin.items().value(cursor);
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
        int rankDelta = rp.ranked ? plugin.ranks().onDeath(profile, rp, lost, raid) : 0;
        long xp = Math.round((40 + rp.kills * 60 + rp.aiKills * 20) * raid.tier.xpMultiplier());
        plugin.ranks().addXp(player, profile, xp);
        Profile.RaidReport report = report(raid, rp, reason, 0, lost, rankDelta, xp);
        profile.reports.add(0, report);
        trimReports(profile);
        plugin.profiles().saveNow(player.getUniqueId());
        plugin.escorts().settle(raid, rp, false);
        // Your own grenade is no kill: it would count for the daily contract and pay the top player
        // his own house bounty.
        if (hit != null && hit.attacker() != null && !hit.attacker().equals(player.getUniqueId())) {
            Player killer = Bukkit.getPlayer(hit.attacker());
            RaidPlayer kr = killer == null ? null : raid.player(killer.getUniqueId());
            if (kr != null) {
                kr.kills++;
                plugin.tasks().onPlayerKill(killer);
                plugin.bounties().onKill(killer, rp, raid);
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
        // The kill replay (if there is a killer to follow) plays first, then the report and spectating.
        plugin.replays().afterDeath(player, raid, hit, () -> {
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
        });
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

    /**
     * The spectator menu (the number keys or the mouse wheel in spectator mode) teleports to any
     * player on the server, in any world: to the lobby off the entry camera, or next to an enemy
     * after death. Nobody in a raid, dead in one or watching a squadmate may use it. The client
     * still opens the menu (it cannot be kept from doing so), but picking someone does nothing.
     * Paper gives the same cause to the server's own camera moves (setSpectatorTarget snaps the
     * player onto the entity they now watch); those land on that entity and are let through.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) {
            return;
        }
        Player player = event.getPlayer();
        if (!byPlayer.containsKey(player.getUniqueId()) && !spectators.containsKey(player.getUniqueId())) {
            return;
        }
        Entity target = player.getSpectatorTarget();
        Location to = event.getTo();
        if (target != null && target.getWorld() == to.getWorld() && target.getLocation().distanceSquared(to) < 1.0E-6) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * Left-clicking an entity in spectator mode starts watching it. That would let the dead follow
     * an enemy around, or slip off the frozen death view: click the squadmate standing in front (or
     * the body at their feet), then Shift off that camera instead of the held one, into a free
     * camera. So in a raid every camera is only for whoever the plugin put on it: the entry camera
     * (an item display) while flying in, the death view and the kill replay's camera after death
     * (ReplayService knows which), and squadmates once the dead are watching them. Clicking anything
     * else does nothing. The plugin's own camera moves pass through here too and are let through.
     */
    @EventHandler(ignoreCancelled = true)
    public void onStartSpectating(PlayerStartSpectatingEntityEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Map.Entry<Raid, RaidPlayer> watching = spectators.get(id);
        Raid raid = watching != null ? watching.getKey() : byPlayer.get(id);
        RaidPlayer own = watching != null ? watching.getValue() : raid == null ? null : raid.player(id);
        if (raid == null || own == null) {
            return;
        }
        Entity target = event.getNewSpectatorTarget();
        if (plugin.replays().isCamera(player, target)) {
            return;
        }
        boolean allowed;
        if (watching != null) {
            // spectateMate files the dead under spectators before it moves the camera.
            RaidPlayer mate = target instanceof Player p ? raid.player(p.getUniqueId()) : null;
            allowed = mate != null && mate.squad == own.squad;
        } else {
            // Dead but not yet watching a mate: nothing but ReplayService's cameras, above.
            allowed = own.state == RaidPlayer.State.DEPLOYING && target instanceof ItemDisplay;
        }
        if (!allowed) {
            event.setCancelled(true);
        }
    }

    /**
     * Keeps players on the camera the plugin gave them. Flying in, nothing takes them off the entry
     * camera (Shift, the spectator menu, a teleport by command: Cinematic brings back whoever is
     * moved to another world). Watching a squadmate, Shift leaves; a target that died, extracted
     * or left hands over to the next mate; anything else (the spectator menu) stays on the mate.
     * Paper sends this event too when the plugin moves the camera or changes the game mode, so a
     * camera is only held on to while the player is still a spectator in the camera's world.
     */
    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        Player player = event.getPlayer();
        Entity from = event.getSpectatorTarget();
        boolean held = player.getGameMode() == GameMode.SPECTATOR && from.isValid() && from.getWorld() == player.getWorld();
        RaidPlayer flying = session(player);
        if (held && from instanceof ItemDisplay && flying != null && flying.state == RaidPlayer.State.DEPLOYING && flying.inFlight) {
            event.setCancelled(true);
            return;
        }
        Map.Entry<Raid, RaidPlayer> entry = spectators.get(player.getUniqueId());
        if (entry == null) {
            return;
        }
        Raid raid = entry.getKey();
        RaidPlayer watched = from instanceof Player t ? raid.player(t.getUniqueId()) : null;
        boolean targetGone = watched == null || !watched.active() || from.getWorld() != raid.world;
        if (held && !targetGone && !player.getCurrentInput().isSneak()) {
            event.setCancelled(true);
            return;
        }
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

    /**
     * Spectators drift if their target extracts or dies between events, or if a command moves them
     * to another world while their camera stays behind: re-check once a second.
     */
    private void tickSpectators() {
        for (UUID uuid : new ArrayList<>(spectators.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            Map.Entry<Raid, RaidPlayer> entry = spectators.get(uuid);
            if (player == null) {
                spectators.remove(uuid);
                continue;
            }
            Entity target = player.getSpectatorTarget();
            RaidPlayer watched = target instanceof Player t ? entry.getKey().player(t.getUniqueId()) : null;
            if (watched == null || !watched.active() || target.getWorld() != player.getWorld()) {
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
        // Items whose id items.yml no longer has cannot lie in the body: they go to the mailbox.
        List<ItemRecord> unknown = new ArrayList<>();
        ItemStack kept = null;
        if (rp.disconnectedInventory != null) {
            for (Map.Entry<Integer, ItemStack> entry : rp.disconnectedInventory.entrySet()) {
                ItemStack stack = entry.getValue();
                // As in die(): the melee weapon stays out of the body and goes back into the loadout.
                if (entry.getKey() == CarryService.MELEE_SLOT && plugin.carry().isMelee(stack)) {
                    kept = stack;
                } else if (plugin.items().defOf(stack) == null) {
                    // An armory item has no record and stays behind with the raid.
                    ItemRecord record = plugin.items().toRecord(stack);
                    if (record != null) {
                        record.uninsure();
                        unknown.add(record);
                    }
                } else {
                    drop.add(stack);
                }
            }
        }
        ItemStack melee = kept;
        if (rp.lastLocation != null && !drop.isEmpty()) {
            spawnCorpse(raid, rp.lastLocation, rp.name, drop, null);
        }
        long lost = 0;
        for (ItemStack stack : drop) {
            lost += plugin.items().value(stack);
        }
        long lostValue = lost;
        // For the journal, should the profile be unreadable: the gear is in the raid now, so the
        // loadout must be emptied (but for the melee weapon) and the raid cleared, or a repaired
        // profile brings it back.
        ItemRecord meleeRecord = plugin.carry().isMelee(melee) ? plugin.items().toRecord(melee) : null;
        Map<Integer, ItemRecord> left = meleeRecord == null ? Map.of() : Map.of(CarryService.MELEE_SLOT, meleeRecord);
        Supplier<String> change = () -> "missing in raid " + raid.id + ": loadout = " + ProfileService.json(left)
                + " (the rest of that gear stayed in the raid),"
                + (unknown.isEmpty() ? "" : " add to mailbox " + ProfileService.json(unknown) + ",")
                + " secure = " + ProfileService.json(rp.secure) + ", activeRaid = null, stats.deaths +1"
                + (rp.ranked ? ", rankPoints " + plugin.ranks().deathDelta(1100, rp, lostValue) + " ("
                + plugin.ranks().deathDelta(0, rp, lostValue) + " below 1100, never below 0)" : "");
        plugin.profiles().edit(rp.uuid, change, profile -> {
            profile.loadout.clear();
            keepMelee(profile, melee);
            profile.mailbox.addAll(unknown);
            profile.secure = new ArrayList<>(rp.secure);
            profile.activeRaid = null;
            profile.addStat("deaths", 1);
            int delta = rp.ranked ? plugin.ranks().onDeath(profile, rp, lostValue, raid) : 0;
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
        // A fully loaded player carries up to 38 stacks (7 pockets, 9 rig cells, 18 backpack cells
        // and 4 worn pieces), and the cursor can add one more: more than the 4-row bag holds, and worn
        // armour comes last in slot order. The body grows to fit, up to a double chest.
        int rows = Math.min(6, Math.max(type.rows(), (items.size() + 8) / 9));
        LootContainer container = new LootContainer(LootContainer.newId(), new ContainerType(type.id(), type.name(), "none",
                1f, 1.4, 0.6, rows, 0, 0, type.searchSpeed(), type.categories(), type.rarities(), List.of()),
                ground, 0, title);
        List<ItemStack> left = container.fill(items, false);
        if (!left.isEmpty()) {
            plugin.getLogger().warning(title + " holds " + items.size() + " items, " + left.size()
                    + " more than fit in a body; they are dropped beside it");
            for (ItemStack stack : left) {
                raid.world.dropItemNaturally(ground, stack);
            }
        }
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
        report.tier = raid.tier.name() + (rp.ranked ? " · 排位" : "");
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

    /** What the records are worth together (the secure container, items mailed on the way out). */
    public long recordsValue(List<ItemRecord> records) {
        long total = 0;
        for (ItemRecord record : records) {
            total += plugin.items().value(record);
        }
        return total;
    }

    /**
     * Gives every single (non-stacking) item the player takes into the raid an instance id, if it
     * has none yet, and returns the ids of everything they took in. Guns always have one; armour,
     * rigs, backpacks, med kits and the like get theirs here, and keep it.
     */
    private Set<String> tagGear(Player player, List<ItemRecord> secure) {
        Set<String> uids = new java.util.HashSet<>();
        for (int slot = 0; slot <= CarryService.HEAD; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            ItemDef def = plugin.items().defOf(stack);
            if (def == null || ItemFactory.isUi(stack) || ItemFactory.isRange(stack)) {
                continue;
            }
            String uid = ItemFactory.uid(stack);
            if (uid == null && def.maxStack() == 1) {
                String fresh = UUID.randomUUID().toString();
                stack.editPersistentDataContainer(pdc -> pdc.set(Keys.UID, PersistentDataType.STRING, fresh));
                uid = fresh;
            }
            if (uid != null) {
                uids.add(uid);
            }
        }
        for (ItemRecord record : secure) {
            ItemDef def = plugin.registry().get(record.id);
            if (record.uid == null && def != null && def.maxStack() == 1) {
                record.uid = UUID.randomUUID().toString();
            }
            if (record.uid != null) {
                uids.add(record.uid);
            }
        }
        return uids;
    }

    /**
     * Value of what {@code player} carries out (secure container included) that another player
     * of this raid brought in. It is theirs to keep, but it is not their raid's profit: two players
     * could otherwise hand a ◈250,000 loadout to each other in turn and score rank points,
     * experience and the daily "net profit" contract off it every raid.
     *
     * <p>Single items are recognised by their instance id. Stacking items (ammo, meds, valuables)
     * have none, so they go by count: of each item the player carries out more of than they took in,
     * up to what the others took in and no longer hold counts as theirs. A crate find of the same
     * item can be caught by this when someone else fired, threw or used theirs; it costs little,
     * whereas leaving stacks out let a pile of .338 rounds or gold bars change hands every raid.
     */
    private long othersGear(Raid raid, RaidPlayer rp, List<ItemRecord> out) {
        Set<String> theirs = new java.util.HashSet<>();
        for (RaidPlayer other : raid.players.values()) {
            if (other != rp) {
                theirs.addAll(other.gearUids);
            }
        }
        long total = 0;
        Map<String, long[]> stacks = new HashMap<>();
        for (ItemRecord record : out) {
            total += foreignValue(record, theirs);
            countStacks(record, stacks, theirs);
        }
        Map<UUID, Map<String, Long>> held = new HashMap<>();
        for (Map.Entry<String, long[]> entry : stacks.entrySet()) {
            String id = entry.getKey();
            long count = entry.getValue()[0];
            long excess = count - rp.broughtIn.getOrDefault(id, 0L);
            if (excess <= 0) {
                continue;
            }
            long given = 0;
            for (RaidPlayer other : raid.players.values()) {
                long in = other == rp ? 0 : other.broughtIn.getOrDefault(id, 0L);
                if (in > 0) {
                    given += Math.max(0, in - held.computeIfAbsent(other.uuid, k -> held(raid, other)).getOrDefault(id, 0L));
                }
            }
            long foreign = Math.min(excess, given);
            if (foreign > 0) {
                total += entry.getValue()[1] * foreign / count;
            }
        }
        return total;
    }

    /**
     * Value of the items in {@code record} with one of {@code theirs} instance ids. Whatever is packed
     * in someone else's rig or backpack is judged on its own, like everything else.
     */
    private long foreignValue(ItemRecord record, Set<String> theirs) {
        long total = 0;
        long inside = 0;
        if (record.contents != null) {
            for (ItemRecord child : record.contents) {
                inside += plugin.items().value(child);
                total += foreignValue(child, theirs);
            }
        }
        if (record.uid != null && theirs.contains(record.uid)) {
            total += plugin.items().value(record) - inside;
        }
        return total;
    }

    /**
     * Adds the stacking items in {@code record}, and in anything packed inside it, to {@code into}
     * as item id -> {count, value}. Rounds loaded in a gun count as that ammo, so loading someone
     * else's rounds into your own gun does not hide them; the magazine of a gun with one of
     * {@code skip} ids is left out, because foreignValue already counts that gun whole.
     */
    private void countStacks(ItemRecord record, Map<String, long[]> into, Set<String> skip) {
        ItemDef def = plugin.registry().get(record.id);
        if (def == null) {
            return;
        }
        if (def.maxStack() > 1) {
            long[] entry = into.computeIfAbsent(record.id, k -> new long[2]);
            entry[0] += Math.max(1, record.amount);
            entry[1] += plugin.items().value(record);
        }
        boolean skipped = record.uid != null && skip.contains(record.uid);
        ItemDef ammo = record.ammoType == null ? null : plugin.registry().get(record.ammoType);
        if (!skipped && ammo != null && record.ammo != null && record.ammo > 0) {
            long[] entry = into.computeIfAbsent(ammo.id(), k -> new long[2]);
            entry[0] += record.ammo;
            entry[1] += ammo.value() * record.ammo;
        }
        if (record.contents != null) {
            for (ItemRecord child : record.contents) {
                countStacks(child, into, skip);
            }
        }
    }

    /** Stacking items in {@code records} by item id (counts only, every magazine included). */
    private Map<String, Long> stackCounts(Collection<ItemRecord> records) {
        Map<String, long[]> stacks = new HashMap<>();
        for (ItemRecord record : records) {
            countStacks(record, stacks, Set.of());
        }
        Map<String, Long> counts = new HashMap<>();
        stacks.forEach((id, entry) -> counts.put(id, entry[0]));
        return counts;
    }

    /**
     * Stacking items {@code other} still holds: what they carried out if they are out, their
     * inventory if they are still in the raid (or its snapshot while disconnected), and always
     * their secure container. The dead hold only the secure container; their body is up for grabs.
     */
    private Map<String, Long> held(Raid raid, RaidPlayer other) {
        if (other.carriedOut != null) {
            return other.carriedOut;
        }
        List<ItemRecord> records = new ArrayList<>(other.secure);
        if (other.state == RaidPlayer.State.DISCONNECTED && other.disconnectedInventory != null) {
            // Read like snapshot(): armory items count for nothing.
            for (ItemStack stack : other.disconnectedInventory.values()) {
                ItemRecord record = plugin.items().toRecord(stack);
                if (record != null) {
                    records.add(record);
                }
            }
        } else if (other.state != RaidPlayer.State.DEAD) {
            Player p = Bukkit.getPlayer(other.uuid);
            if (p != null && raidOf(p) == raid) {
                records.addAll(plugin.carry().snapshot(p).values());
            }
        }
        return stackCounts(records);
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
        plugin.insurance().settle(raid);
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
        // Gone home with everything, like an extraction, as far as other players' profit goes: the
        // cursor item too, whether it goes back into the inventory or to the mailbox.
        List<ItemRecord> out = new ArrayList<>(plugin.carry().reclaimCursorHome(player));
        out.addAll(plugin.carry().snapshot(player).values());
        out.addAll(rp.secure);
        rp.carriedOut = stackCounts(out);
        Profile profile = plugin.profiles().get(player);
        profile.secure = new ArrayList<>(rp.secure);
        profile.activeRaid = null;
        plugin.escorts().voided(rp);
        byPlayer.remove(player.getUniqueId());
        plugin.match().leaveTeam(player);
        plugin.lobby().enterLobbyKeepInventory(player);
        plugin.lobby().captureLoadout(player);
        Text.send(player, "<#FFC23D>" + message);
    }

    /**
     * Plugin shutdown: the raid is voided and everybody in it goes home with what they carry (no
     * losses, no rewards). Whoever was still flying in never set foot in it and also gets the entry
     * fee and insurance premium back. Each player and each raid is handled on its own, so one
     * failure cannot leave the others in a raid that no longer exists.
     */
    public void shutdown() {
        for (Raid raid : new ArrayList<>(raids.values())) {
            // A loading raid has not taken anything from its squads yet: they are still in the lobby.
            boolean deployed = raid.state != Raid.State.LOADING;
            for (RaidPlayer rp : raid.players.values()) {
                try {
                    sendHome(raid, rp, deployed);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not send " + rp.name + " home from raid " + raid.id, ex);
                }
            }
            raid.state = Raid.State.CLOSED;
            try {
                // As when a raid closes: insured gear still on bodies, in containers or on the ground
                // goes back to its owners. The world is deleted below and nothing would be left to
                // settle after the restart. On its own, so a failing settlement cannot keep the world
                // and a failing teardown cannot cost the payouts.
                plugin.insurance().settle(raid);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not settle insurance for raid " + raid.id, ex);
            }
            try {
                // The dead watching a squadmate are sent home too.
                spectators.values().removeIf(entry -> entry.getKey() == raid);
                for (Player player : raid.world.getPlayers()) {
                    plugin.lobby().enterLobby(player);
                }
                plugin.ai().clear(raid);
                for (LootContainer container : raid.containers.values()) {
                    container.remove();
                }
                plugin.match().disposeTeams(raid);
                plugin.worlds().disposeRaidWorld(raid.world);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not close raid " + raid.id, ex);
            }
        }
        raids.clear();
        byPlayer.clear();
        byWorld.clear();
    }

    private void sendHome(Raid raid, RaidPlayer rp, boolean deployed) {
        Player player = Bukkit.getPlayer(rp.uuid);
        boolean flyingIn = deployed && rp.state == RaidPlayer.State.DEPLOYING;
        if ((rp.active() || flyingIn) && player != null) {
            plugin.weapons().reset(player);
            plugin.downed().clear(player);
            Profile profile = plugin.profiles().get(player);
            profile.secure = new ArrayList<>(rp.secure);
            profile.activeRaid = null;
            if (flyingIn) {
                profile.money += rp.fees;
            }
            plugin.lobby().enterLobbyKeepInventory(player);
            plugin.lobby().captureLoadout(player);
            Text.send(player, flyingIn && rp.fees > 0
                    ? "<#FFC23D>服务器维护：行动在入场前中止，入场费和保险费 ◈" + Text.money(rp.fees) + " 已退还。"
                    : "<#FFC23D>服务器维护：本次行动已中止，你携带的物资已保留。");
        } else if (rp.state == RaidPlayer.State.DISCONNECTED && rp.disconnectedInventory != null) {
            // Dropped out during the entry camera: like those still watching it, they never
            // landed, so the fees come back too.
            homeOffline(raid, rp, rp.inFlight ? rp.fees : 0, "server stopped during raid " + raid.id);
        }
    }

    /**
     * Sends a disconnected player home from a raid that is void for them: what they carried when
     * they dropped out becomes their loadout, the secure container goes back as it was, and
     * {@code refund} is paid back. Armory items are left out; own items from an armory rig or
     * backpack that find no free pocket go to the mailbox. Returns everything that went home.
     */
    private List<ItemRecord> homeOffline(Raid raid, RaidPlayer rp, long refund, String why) {
        List<ItemRecord> homeless = new ArrayList<>();
        Map<Integer, ItemRecord> inv = rp.disconnectedInventory == null ? new HashMap<>()
                : plugin.carry().persistent(rp.disconnectedInventory, homeless);
        plugin.profiles().edit(rp.uuid, () -> why + ": loadout = "
                + ProfileService.json(inv) + (homeless.isEmpty() ? "" : ", add to mailbox " + ProfileService.json(homeless))
                + ", secure = " + ProfileService.json(rp.secure) + ", activeRaid = null"
                + (refund > 0 ? ", money +" + refund : ""), profile -> {
            profile.loadout = inv;
            profile.mailbox.addAll(homeless);
            profile.secure = new ArrayList<>(rp.secure);
            profile.activeRaid = null;
            profile.money += refund;
        });
        List<ItemRecord> home = new ArrayList<>(inv.values());
        home.addAll(homeless);
        home.addAll(rp.secure);
        return home;
    }

    /** Rejoin after a disconnect. Returns true if the player was put back into their raid. */
    public boolean tryRejoin(Player player) {
        Raid raid = raidOf(player);
        RaidPlayer rp = raid == null ? null : raid.player(player.getUniqueId());
        if (raid == null || rp == null || rp.state != RaidPlayer.State.DISCONNECTED || raid.state != Raid.State.RUNNING
                || rp.disconnectedInventory == null || rp.lastLocation == null) {
            return false;
        }
        plugin.carry().restore(player, rp.disconnectedInventory, UiItems.raidTerminal());
        rp.disconnectedInventory = null;
        plugin.health().setupPlayer(player, false);
        Location back = rp.lastLocation.getWorld() == raid.world ? rp.lastLocation : raid.map.spawns().isEmpty()
                ? raid.world.getSpawnLocation() : raid.map.spawns().get(0).pos().at(raid.world);
        player.teleport(back);
        if (rp.inFlight) {
            // Back before the squad's entry camera ended: the camera picks the player up again
            // (Cinematic looks players up by UUID) and they land with the squad through insert(),
            // with the same title and spawn protection, instead of ahead of everyone else.
            rp.state = RaidPlayer.State.DEPLOYING;
            player.setGameMode(GameMode.SPECTATOR);
            plugin.match().teamFor(raid, rp, player);
            Text.send(player, "<#5BD16B>已重新连接到行动，正在随小队前往渗透点。");
            return true;
        }
        rp.state = RaidPlayer.State.ALIVE;
        rp.protectedUntil = Bukkit.getCurrentTick() + 40;
        player.setGameMode(GameMode.ADVENTURE);
        player.setFallDistance(0);
        plugin.match().teamFor(raid, rp, player);
        // LobbyService.onJoin skips the lobby HUD for a rejoin, so the raid HUD is shown here.
        plugin.hud().onRaidStart(player);
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
            long dark = (raid.blackoutUntil - Bukkit.getCurrentTick()) / 20;
            if (dark > 0) {
                player.sendActionBar(Text.mm("<#FFC23D>⚡ 电网故障，电闸暂时无法使用 <#9AA0A6>(" + dark + "s)"));
                return;
            }
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
        // A free proving-ground armory copy of the key wears out before the player's own one does.
        ItemStack key = null;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            ItemDef def = plugin.items().defOf(stack);
            if (def != null && def.category() == Category.KEY && lock.equals(def.lockId())
                    && (key == null || ItemFactory.isTest(stack) && !ItemFactory.isTest(key))) {
                key = stack;
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
        if (raid.state == Raid.State.LOADING) {
            // Still in the lobby and nothing charged or taken yet (prepare runs at deployment):
            // the player just drops out of this raid. LobbyService.onQuit then saves the real
            // inventory as the loadout; the secure container is never touched.
            withdraw(raid, rp);
            return;
        }
        plugin.weapons().reset(player);
        if (rp.state == RaidPlayer.State.DOWNED) {
            die(raid, player, rp, "倒地后断线", plugin.damage().lastHit(player.getUniqueId()));
            byPlayer.remove(player.getUniqueId());
            player.getInventory().clear();
            return;
        }
        long now = Bukkit.getCurrentTick();
        if (rp.state == RaidPlayer.State.ALIVE && inCombat(rp, now)) {
            // Closing the game mid-fight used to take the player out of the fight with all their
            // gear, to come back two minutes later where they stood. Now it is death, as in other
            // extraction shooters (a real connection drop mid-fight too), and the kill goes to
            // whoever hit them last, if that was during this fight.
            long window = plugin.getConfig().getLong("raid.combat-logout-seconds", 15) * 20L;
            DamageService.LastHit hit = rp.lastHostile != null && now - rp.lastHostile.tick() < window ? rp.lastHostile : null;
            die(raid, player, rp, "交战中断线", hit);
            byPlayer.remove(player.getUniqueId());
            player.getInventory().clear();
            return;
        }
        if (rp.state == RaidPlayer.State.ALIVE || rp.state == RaidPlayer.State.DEPLOYING) {
            // The cursor item is already back in the inventory (or on the ground here): CarryListener
            // settles it first, at the lowest priority.
            // During the entry flight (DEPLOYING) lastLocation already holds the insertion point:
            // the usual reconnect window applies and a timeout leaves the body there. The copies keep
            // insurance marks and armory items for a rejoin; armory items never reach a saved loadout.
            rp.disconnectedInventory = plugin.carry().copyCarried(player);
            if (rp.state == RaidPlayer.State.ALIVE) {
                rp.lastLocation = player.getLocation();
            }
            rp.state = RaidPlayer.State.DISCONNECTED;
            rp.disconnectedAt = Bukkit.getCurrentTick();
            player.getInventory().clear();
        } else if (rp.state == RaidPlayer.State.DEAD || rp.state == RaidPlayer.State.EXTRACTED) {
            byPlayer.remove(player.getUniqueId());
        }
        plugin.match().leaveTeam(player);
    }

    /** Takes a player out of a raid that has not deployed yet and tells their squad. */
    private void withdraw(Raid raid, RaidPlayer rp) {
        raid.players.remove(rp.uuid);
        // Squad lists keep their place even when emptied: their index is the squad number.
        for (List<UUID> squad : raid.squads) {
            squad.remove(rp.uuid);
        }
        byPlayer.remove(rp.uuid, raid);
        for (RaidPlayer mate : raid.players.values()) {
            Player p = mate.squad == rp.squad ? Bukkit.getPlayer(mate.uuid) : null;
            if (p != null) {
                Text.send(p, "<#9AA0A6>队友 <white>" + Text.escape(rp.name) + "</white> 在部署前断开连接，已退出本局。");
            }
        }
        plugin.escorts().onWithdraw(rp.uuid, rp.name);
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
