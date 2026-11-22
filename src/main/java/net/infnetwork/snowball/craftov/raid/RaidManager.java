package net.infnetwork.snowball.craftov.raid;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import net.infnetwork.snowball.craftov.CraftovPlugin;
import net.infnetwork.snowball.craftov.util.Notify;
import net.infnetwork.snowball.craftov.gui.SettlementMenu;
import net.infnetwork.snowball.craftov.gui.UiFont;
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
import net.infnetwork.snowball.craftov.profile.MailService;
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

    /** {@code player} used up {@code amount} of item {@code id} in their raid (RaidPlayer.used). */
    public void usedUp(Player player, @Nullable String id, int amount) {
        RaidPlayer rp = id == null ? null : session(player);
        if (rp != null && rp.active()) {
            rp.used.merge(id, (long) amount, Long::sum);
        }
    }

    public boolean isInRaid(Player player) {
        RaidPlayer rp = session(player);
        return rp != null && rp.active() && player.getWorld().getName().startsWith(WorldService.RAID_PREFIX);
    }

    public boolean isRaidWorld(World world) {
        return byWorld.containsKey(world);
    }

    /** Whether the player may handle a weapon at all: aim, reload, and (see {@link #canAttack}) use it. */
    public boolean canFire(Player player) {
        if (plugin.lobby().inRange(player)) {
            return true;
        }
        RaidPlayer rp = session(player);
        return rp != null && rp.state == RaidPlayer.State.ALIVE && player.getGameMode() == GameMode.ADVENTURE
                && player.getWorld().getName().startsWith(WorldService.RAID_PREFIX)
                && !plugin.downed().isReviving(player);
    }

    /**
     * Whether the player may shoot, stab or throw something now: as {@link #canFire}, but not from
     * behind spawn protection. Aiming and reloading are still fine then.
     */
    public boolean canAttack(Player player) {
        return canFire(player) && !isProtected(player);
    }

    /**
     * Spawn protection after landing or rejoining: no damage taken, and no attacks made either, so
     * nobody fights from behind it.
     */
    public boolean isProtected(Player player) {
        RaidPlayer rp = session(player);
        return rp != null && isProtected(rp);
    }

    private static boolean isProtected(RaidPlayer rp) {
        return Bukkit.getCurrentTick() < rp.protectedUntil;
    }

    /** The player fired, threw something or hurt someone: soldiers stop holding back. */
    public void onAttack(Player player) {
        RaidPlayer rp = session(player);
        if (rp != null) {
            rp.aiGraceUntil = 0;
        }
    }

    public boolean canBeShot(Player player) {
        RaidPlayer rp = session(player);
        return rp != null && rp.active() && Bukkit.getCurrentTick() >= rp.protectedUntil;
    }

    /**
     * Whether {@code attacker} may hurt {@code victim}: in a raid or on the range only a combatant can
     * be hurt at all, players only by enemies in their own raid (or squadmates with friendly fire on),
     * and soldiers never hurt each other. Bullets that may not hurt an entity fly through it.
     */
    public boolean canDamage(LivingEntity attacker, LivingEntity victim) {
        // Only where guns fire: the vanilla damage handler asks this too, and in an editor world an
        // admin in creative must still be able to punch away a stray armour stand or mob.
        if (!isCombatant(victim) && isCombatWorld(victim.getWorld())) {
            return false;
        }
        RaidPlayer standsFor = standInOwner(victim);
        if (attacker instanceof Player a && standsFor != null) {
            // A stand-in is its player: squadmates cannot hurt it without friendly fire.
            Raid raid = raidOf(a);
            RaidPlayer ra = raid == null ? null : raid.player(a.getUniqueId());
            return ra != null && raid.player(standsFor.uuid) == standsFor
                    && (ra.squad != standsFor.squad || plugin.getConfig().getBoolean("combat.friendly-fire", false));
        }
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
     * leaving the game right after credits whoever made it. A hit on a stand-in counts for its
     * player, so coming back into the fight and leaving again at once is death like any other
     * leaving mid-fight. Hits between squadmates that do no damage (a grenade near a squadmate
     * without friendly fire) count for nothing.
     */
    public void onHostileHit(LivingEntity victim, LivingEntity attacker, DamageService.@Nullable LastHit hit) {
        if (!canDamage(attacker, victim)) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        RaidPlayer rv = victim instanceof Player v ? session(v) : standInOwner(victim);
        if (rv != null && (rv.active() || rv.state == RaidPlayer.State.DISCONNECTED)) {
            rv.lastCombatTick = now;
            if (hit != null) {
                rv.lastHostile = hit;
            }
        }
        RaidPlayer ra = attacker instanceof Player a ? session(a) : null;
        if (ra != null && ra.active()) {
            ra.lastCombatTick = now;
            ra.aiGraceUntil = 0;
        }
    }

    /** Whether leaving the game now counts as death: within raid.combat-logout-seconds of a fight (0: never). */
    private boolean inCombat(RaidPlayer rp, long now) {
        long window = plugin.getConfig().getLong("raid.combat-logout-seconds", 15) * 20L;
        return window > 0 && rp.lastCombatTick >= 0 && now - rp.lastCombatTick < window;
    }

    /**
     * Players, the stand-ins of disconnected players, soldiers, drones and range dummies: the only
     * living things that take hits in a raid or on the range. Anything else alive there is scenery
     * (corpses, kill-replay stand-ins, whatever decoration comes next), so a shot passes through it
     * instead of stopping in mid-air with a hit marker.
     */
    public boolean isCombatant(LivingEntity entity) {
        return entity instanceof Player || plugin.ai().isAi(entity) || plugin.drones().isDrone(entity)
                || plugin.lobby().isTarget(entity) || standInOwner(entity) != null;
    }

    /** Raid worlds and the private range: the only worlds where anyone can fire a gun. */
    private boolean isCombatWorld(World world) {
        return isRaidWorld(world) || plugin.range().isRangeWorld(world);
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
                squad.forEach(p -> Text.send(p, "<danger>创建行动地图失败，请联系管理员。"));
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
                player.showTitle(Title.title(Text.mm("<accent>匹配成功"), Text.mm("<muted>正在部署 " + Text.escape(map.name()) + " …"),
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
        // The fee was checked at launch, but the squads stay in the lobby while the map loads and
        // can spend money meanwhile: whoever can no longer pay drops out here instead of starting
        // the raid in debt.
        long entryFee = Math.max(0, raid.tier.entryFee());
        for (RaidPlayer rp : new ArrayList<>(raid.players.values())) {
            Player player = Bukkit.getPlayer(rp.uuid);
            if (entryFee > 0 && player != null && plugin.profiles().get(player).money < entryFee) {
                withdraw(raid, rp, "付不起入场费");
                Text.send(player, "<danger>未能出发：块币不足以支付" + raid.tier.name() + "的入场费 ◈" + Text.money(entryFee)
                        + "（匹配成功后花掉了），已退出本局。<muted>装备和安全箱都没有动。");
            }
        }
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
                    raid.tier.shift(def.tier()), type.name());
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
        if (raid.tier.boss()) {
            for (MapDef.BossDef boss : map.bosses()) {
                if (rng.nextDouble() < boss.chance()) {
                    // In place from the start, asleep until its time: it used to appear out of thin air
                    // with its guards in a room someone might be looting.
                    plugin.ai().spawnBoss(raid, boss, true);
                }
            }
        }
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
            if (player == null && rp != null && rp.state == RaidPlayer.State.DISCONNECTED && rp.lastLocation != null
                    && raid.state == Raid.State.RUNNING) {
                // Dropped out during the flight: from now on they stand on their insertion point
                // like anyone else who left the raid, and a reconnect puts them there.
                spawnStandIn(raid, rp, rp.lastLocation, null);
            }
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
        long entryFee = Math.max(0, raid.tier.entryFee());
        profile.money -= entryFee;
        long premium = plugin.insurance().onDeploy(player, raid);
        rp.fees = entryFee + premium;
        plugin.tutorial().done(player, net.infnetwork.snowball.craftov.lobby.TutorialService.Step.DEPLOY);
        rp.secure = new ArrayList<>();
        for (ItemRecord record : profile.secure) {
            rp.secure.add(record.copy());
        }
        rp.gearUids = tagGear(player, rp.secure);
        // Saved together with the fees: whatever happens to the server from here on, the raid can
        // be undone to exactly this (voidRaid), and nothing is charged that would not come back.
        profile.deployment = deployment(raid, player, profile, rp, entryFee, premium);
        rp.deployment = profile.deployment;
        profile.activeRaid = raid.id;
        profile.statInRaid("raids", 1);
        plugin.profiles().saveNow(player.getUniqueId());
        List<ItemRecord> takenIn = new ArrayList<>(plugin.carry().snapshot(player).values());
        takenIn.addAll(rp.secure);
        rp.broughtIn = stackCounts(takenIn);
        rp.gearValue = plugin.carry().gearValue(player);
        // Profit is measured on everything, the knife included, as extraction counts it: a knife
        // brought in cancels out and one found in the raid is profit.
        rp.startValue = plugin.carry().carriedValue(player) + recordsValue(rp.secure);
        rp.startTick = Bukkit.getCurrentTick();
        rp.escortContract = plugin.escorts().contractFor(player.getUniqueId());
        player.getInventory().setItem(CarryService.TERMINAL_SLOT, UiItems.raidTerminal());
        player.closeInventory();
        plugin.health().setupPlayer(player, true);
        plugin.match().teamFor(raid, rp, player);
    }

    /**
     * The record of what the player takes into the raid, read after the gear has its instance ids
     * and insurance marks (the marks are left out: gear that comes home is never insured) and
     * before anything in the raid has touched it.
     */
    private Profile.Deployment deployment(Raid raid, Player player, Profile profile, RaidPlayer rp, long entryFee, long premium) {
        Profile.Deployment d = new Profile.Deployment();
        d.raid = raid.key;
        d.map = raid.map.name();
        d.tier = raid.tier.name();
        d.time = System.currentTimeMillis();
        plugin.carry().snapshot(player).forEach((slot, record) -> {
            ItemRecord kept = ItemFactory.persistent(record);
            if (kept != null) {
                kept = kept.copy();
                kept.uninsure();
                d.loadout.put(slot, kept);
            }
        });
        for (ItemRecord record : rp.secure) {
            ItemRecord kept = record.copy();
            kept.uninsure();
            d.secure.add(kept);
        }
        d.entryFee = entryFee;
        d.premium = premium;
        // Brought up to date first: a contract day or season that turned over before the raid
        // (and is only noticed by the first progress in it) must not count as the raid's doing.
        plugin.tasks().of(profile);
        plugin.season().sync(profile);
        d.taskDay = profile.taskDay;
        for (Profile.TaskState task : profile.tasks) {
            d.tasks.add(task.copy());
        }
        d.level = profile.level;
        d.xp = profile.xp;
        d.seasonId = profile.seasonId;
        d.seasonXp = profile.seasonXp;
        return d;
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
        rp.aiGraceUntil = rp.protectedUntil + plugin.getConfig().getLong("ai.insertion-grace-ticks", 240);
        plugin.carry().refreshLocks(player);
        player.showTitle(Title.title(Text.mm("<accent><b>行动开始"), Text.mm("<muted>搜索物资，活着撤离"),
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
                        Notify.critical(player, "extract", "<danger>已离开撤离区域");
                    }
                    rp.extracting = null;
                    rp.extractTicks = 0;
                    continue;
                }
                String blocked = extractBlocker(raid, inside, player);
                if (blocked != null) {
                    rp.extracting = null;
                    rp.extractTicks = 0;
                    Notify.critical(player, "extract", "<danger>" + blocked);
                    continue;
                }
                if (inside.def.type() == ExtractType.SWITCH) {
                    // Lever extracts leave all at once when the departure countdown ends (see tickSecond).
                    long left = (raid.switchUntil.getOrDefault(inside.def.switchId(), 0L) - now) / 20 + 1;
                    Notify.critical(player, "extract", "<ok>撤离倒计时 <white>" + left + "s <muted>· 留在区域内即可撤离");
                    rp.extracting = inside.def.id();
                    continue;
                }
                if (!inside.def.id().equals(rp.extracting)) {
                    rp.extracting = inside.def.id();
                    rp.extractTicks = 0;
                    player.playSound(player.getLocation(), "craftov:extract.start", SoundCategory.MASTER, 0.9f, 1f);
                    if (inside.def.type() == ExtractType.PAID) {
                        Text.send(player, "<accent>付费撤离：<white>撤离完成时自动从账户扣除 ◈" + Text.money(inside.def.cost())
                                + "</white> <muted>(当前余额 ◈" + Text.money(plugin.profiles().get(player).money) + "，离开区域即取消)");
                    }
                }
                rp.extractTicks += 5;
                int need = inside.def.seconds() * 20;
                if (rp.extractTicks % 20 == 0) {
                    if (need - rp.extractTicks <= 200) {
                        // The last ten seconds of the countdown: a sharper tick.
                        net.infnetwork.snowball.craftov.gui.UiSound.FINAL.play(player, 1f + rp.extractTicks / (float) need * 0.3f);
                    } else {
                        player.playSound(player.getLocation(), "craftov:extract.beep", SoundCategory.MASTER, 0.7f,
                                1f + rp.extractTicks / (float) need * 0.5f);
                    }
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
            if (raid.tier.boss()) {
                for (MapDef.BossDef boss : raid.map.bosses()) {
                    // Asleep at its post since the raid began (deploy); at its own time it is up and about.
                    if (elapsed >= boss.delaySeconds() && raid.bossesWoken.add(boss.id())) {
                        String name = plugin.ai().wakeBoss(raid, boss.id());
                        if (name != null) {
                            announce(raid, "<danger>⚠ 情报：<white>" + Text.escape(name) + "</white> 出现在地图中，携带高价值物资",
                                    net.infnetwork.snowball.craftov.gui.UiSound.WARNING);
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
                    announce(raid, "<ok>撤离点 <white>" + Text.escape(point.def.name()) + "</white> 现已开放"
                            + (point.def.capacity() > 0 ? "，仅限 " + point.def.capacity() + " 人" : ""),
                            net.infnetwork.snowball.craftov.gui.UiSound.EXTRACT_OPEN);
                }
            }
            if (remaining <= 300 && !raid.warned5) {
                raid.warned5 = true;
                announce(raid, "<accent>行动剩余 5 分钟，请尽快撤离", net.infnetwork.snowball.craftov.gui.UiSound.WARNING);
            }
            if (remaining <= 60 && !raid.warned1) {
                raid.warned1 = true;
                announce(raid, "<danger>行动剩余 60 秒 · 超时未撤离将视为失踪", net.infnetwork.snowball.craftov.gui.UiSound.WARNING);
            }
            if (remaining > 0 && remaining <= 10) {
                // The last ten seconds tick for everyone still inside.
                for (Player player : raid.online()) {
                    net.infnetwork.snowball.craftov.gui.UiSound.FINAL.play(player, 0.9f + (10 - remaining) * 0.03f);
                }
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
     * camera): home with everything they brought, and the entry fee and insurance premium back
     * (no compensation letter: that is for a raid the server stops in). That goes for whoever
     * dropped out of the camera too (DISCONNECTED, still inFlight): they never landed either, and
     * find it all back when they come back.
     */
    private void callOff(Raid raid, RaidPlayer rp) {
        rp.inFlight = false;
        Player player = rp.state == RaidPlayer.State.DISCONNECTED ? null : Bukkit.getPlayer(rp.uuid);
        if (player == null) {
            if (rp.state == RaidPlayer.State.DISCONNECTED && rp.disconnectedInventory != null) {
                // As far as other players' profit goes, they went home with all of it (see held).
                List<ItemRecord> home = homeOffline(raid, rp, rp.fees, "raid " + raid.id + " ended during the entry camera");
                rp.carriedOut = stackCounts(home);
                rp.disconnectedInventory = null;
                leftWith(raid, rp, home);
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
        profile.leaveRaid();
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
        player.playSound(player.getLocation(), "craftov:extract.success", SoundCategory.MASTER, 1f, 1f);
        List<ItemStack> outStacks = new ArrayList<>();
        for (ItemRecord record : out) {
            ItemStack stack = plugin.items().fromRecord(record);
            if (stack != null) {
                outStacks.add(stack);
            }
        }
        SettlementMenu.Settlement settlement = new SettlementMenu.Settlement(
                true, report.outcome, raid.map.name(), report.tier, report.seconds, rp.kills, rp.aiKills, xp, rankDelta, rp.startValue,
                total, profit, SettlementMenu.top(plugin, outStacks, 10), newCollectibles, null);
        // Black curtain over the trip home, so the world loading never shows.
        UiFont.blackout(player, 6, 16, 10);
        if (handed > 0) {
            Text.send(player, "<muted>其他玩家带进本局的装备 <accent>◈" + Text.money(handed)
                    + " <muted>归你所有，但不计入净收益（排位分、经验和任务）。");
        }
        // Inventory stays as the new lobby loadout.
        plugin.lobby().enterLobbyKeepInventory(player);
        plugin.lobby().captureLoadout(player);
        plugin.profiles().saveNow(player.getUniqueId());
        leftWith(raid, rp, out);
        settle(player, settlement);
    }

    /** Results waiting for a dead player who is still watching a squadmate. */
    private final Map<UUID, SettlementMenu.Settlement> pendingSettlements = new HashMap<>();

    /**
     * The raid is over for this player, who is back in the lobby: one summary line in chat, and the
     * settlement screen once the curtain has lifted (unless another menu is up by then).
     */
    private void settle(Player player, SettlementMenu.Settlement settlement) {
        player.sendMessage(Text.mm(settlement.summary()));
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || raidOf(p) != null || isSpectating(p)
                    || p.getOpenInventory().getTopInventory().getHolder() instanceof net.infnetwork.snowball.craftov.gui.Menu) {
                return;
            }
            new SettlementMenu(plugin, p, settlement).open();
        }, plugin.getConfig().getLong("raid.settlement-delay-ticks", 30L));
    }

    /**
     * The flat experience for getting out: in full for players who brought in gear worth at least
     * rank.extract-xp-min-gear (gear value, so a knife alone earns none of it), in proportion below
     * that. Walking out naked earned 150 a run with nothing at stake; profit and kills still earn
     * their own experience.
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
        // Body with everything the player carried but the melee weapon, which is kept.
        List<ItemStack> drop = new ArrayList<>();
        ItemStack melee = player.getInventory().getItem(CarryService.MELEE_SLOT);
        for (int slot = 0; slot <= CarryService.HEAD; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (slot != CarryService.MELEE_SLOT && stack != null && !stack.isEmpty() && !ItemFactory.isUi(stack)) {
                drop.add(stack.clone());
            }
        }
        // Whatever was held on the cursor (a picked-up backpack with everything in it, say) is carried too.
        // A knife too: only the one in the melee slot is kept, so one on the cursor is a second knife and
        // goes into the body like a second knife in a pocket. It used to be left out and then deleted by
        // setItemOnCursor(null), while extracting or leaving mails it home. Nothing in normal play puts
        // a knife there (the melee slot cannot be picked up and loot screens never use the cursor).
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.isEmpty() && !ItemFactory.isUi(cursor)) {
            drop.add(cursor.clone());
        }
        // What was lost is what the body holds, as in missing(). The kept knife used to count too,
        // so a karambit made every death cost more rank points and showed in the report's loss.
        long lost = 0;
        for (ItemStack stack : drop) {
            lost += plugin.items().value(stack);
        }
        spawnCorpse(raid, at, drop, player);
        player.getInventory().clear();
        player.setItemOnCursor(null);
        Profile profile = plugin.profiles().get(player);
        profile.loadout.clear();
        keepMelee(profile, melee);
        profile.secure = new ArrayList<>(rp.secure);
        profile.leaveRaid();
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
        leftWith(raid, rp, keptThroughDeath(rp, melee, List.of()));
        plugin.escorts().settle(raid, rp, false);
        creditKill(raid, hit, rp);
        killFeed(raid, hit, player.getName());
        player.setGameMode(GameMode.SPECTATOR);
        player.showTitle(Title.title(Text.mm("<danger><b>行动失败"), Text.mm("<muted>" + Text.escape(reason)),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(600))));
        player.playSound(player.getLocation(), "craftov:ui.death", SoundCategory.MASTER, 1f, 1f);
        UUID uuid = player.getUniqueId();
        List<DamageService.HitRecord> hits = plugin.damage().hitsTaken(uuid);
        SettlementMenu.Settlement settlement = new SettlementMenu.Settlement(
                false, reason, raid.map.name(), report.tier, report.seconds, rp.kills, rp.aiKills, xp, rankDelta, rp.startValue,
                recordsValue(rp.secure), -lost, SettlementMenu.top(plugin, drop, 10), List.of(),
                hit == null || hit.attacker() == null ? null : hit.attackerName());
        // The kill replay (if there is a killer to follow) plays first, then the report and spectating.
        plugin.replays().afterDeath(player, raid, hit, () -> {
            Player p = Bukkit.getPlayer(uuid);
            byPlayer.remove(uuid);
            if (p != null) {
                sendDamageLog(p, hit, hits);
                if (!spectateMate(p, raid, rp)) {
                    plugin.match().leaveTeam(p);
                    UiFont.blackout(p, 6, 16, 10);
                    plugin.lobby().enterLobby(p);
                    settle(p, settlement);
                } else {
                    pendingSettlements.put(uuid, settlement);
                }
            }
        });
    }

    /**
     * Credits a player's death (in the raid or offline) to the player behind {@code hit}, if any.
     * Your own grenade is no kill: it would count for the daily contract and pay the top player his
     * own house bounty. Nor is a squadmate's, with friendly fire on: a squad could otherwise cash in
     * its own member's bounty. Nor does a killer who has already died or got out get one (a wound of
     * theirs bleeding out later, say): their kills were counted and saved when they left, so a late
     * one would pay the bounty and the contract but never reach their stats.
     */
    private void creditKill(Raid raid, DamageService.@Nullable LastHit hit, RaidPlayer victim) {
        if (hit == null || hit.attacker() == null || hit.attacker().equals(victim.uuid)) {
            return;
        }
        Player killer = Bukkit.getPlayer(hit.attacker());
        RaidPlayer kr = killer == null ? null : raid.player(killer.getUniqueId());
        if (kr != null && kr.active() && kr.squad != victim.squad) {
            kr.kills++;
            plugin.tasks().onPlayerKill(killer);
            plugin.bounties().onKill(killer, victim, raid);
            Notify.info(killer, "<danger>✖ 击杀 <white>" + Text.escape(victim.name));
        }
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
                Notify.hint(player, "正在观战队友 <white>" + Text.escape(target.getName()) + " <muted>· 按 <white>Shift</white> 返回大厅");
                return true;
            }
        }
        spectators.remove(player.getUniqueId());
        return false;
    }

    private void stopSpectating(Player player) {
        spectators.remove(player.getUniqueId());
        plugin.match().leaveTeam(player);
        UiFont.blackout(player, 6, 16, 10);
        plugin.lobby().enterLobby(player);
        SettlementMenu.Settlement settlement = pendingSettlements.remove(player.getUniqueId());
        if (settlement != null) {
            settle(player, settlement);
        }
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
                pendingSettlements.remove(uuid);
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

    /** What a death leaves the player: the secure container, the melee weapon and {@code mailed}. */
    private List<ItemRecord> keptThroughDeath(RaidPlayer rp, @Nullable ItemStack melee, List<ItemRecord> mailed) {
        List<ItemRecord> kept = new ArrayList<>(rp.secure);
        ItemRecord record = plugin.carry().isMelee(melee) ? plugin.items().toRecord(melee) : null;
        if (record != null) {
            kept.add(record);
        }
        kept.addAll(mailed);
        return kept;
    }

    /** The reconnect window or the raid ran out on a disconnected player: missing in action. */
    private void missing(Raid raid, RaidPlayer rp, String reason) {
        if (rp.state != RaidPlayer.State.DISCONNECTED && !rp.active()) {
            return;
        }
        lostOffline(raid, rp, "失踪（" + reason + "）", "missing in raid " + raid.id, false);
        squadFeed(raid, rp.squad, "<muted>队友 <white>" + Text.escape(rp.name) + " <muted>失去联络");
    }

    /**
     * A disconnected player died where they stood: their stand-in was killed, or they bled out.
     * Settled like going missing, but it is a death in the raid like any other: the kill
     * feed shows it, the kill counts for whoever made it, and the player's own kills and
     * experience from the raid count as they would on any death.
     */
    private void diedOffline(Raid raid, RaidPlayer rp, DamageService.@Nullable LastHit hit) {
        if (rp.state != RaidPlayer.State.DISCONNECTED) {
            return;
        }
        lostOffline(raid, rp, "断线后" + deathReason(hit), "killed while offline in raid " + raid.id, true);
        creditKill(raid, hit, rp);
        killFeed(raid, hit, rp.name);
    }

    /**
     * Settles a player who is gone from the raid for good while offline. Their body, in their skin
     * and with everything they carried but the melee weapon, lies where their stand-in stood (or
     * where they left, without one); the melee weapon goes back into the loadout and the secure
     * container home, as on any death. A player who {@code died} (rather than went missing) also
     * gets their raid kills into their stats and the experience die() grants.
     */
    private void lostOffline(Raid raid, RaidPlayer rp, String outcome, String why, boolean died) {
        Location standing = removeStandIn(rp);
        Location at = standing != null ? standing : rp.lastLocation;
        rp.state = RaidPlayer.State.DEAD;
        List<ItemStack> drop = new ArrayList<>();
        // Items whose id items.yml no longer has cannot lie in the body: they go to the mailbox.
        List<ItemRecord> unknown = new ArrayList<>();
        ItemStack kept = null;
        ItemStack helmet = null;
        ItemStack vest = null;
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
                    if (entry.getKey() == CarryService.HEAD) {
                        helmet = stack;
                    } else if (entry.getKey() == CarryService.CHEST) {
                        vest = stack;
                    }
                }
            }
        }
        ItemStack melee = kept;
        if (at != null && !drop.isEmpty()) {
            // Dressed as they left, like the body of anyone who dies in the raid.
            spawnBody(raid, at, rp.name + " 的遗体", drop, rp.look == null ? Mannequin.defaultProfile() : rp.look,
                    helmet, vest);
        }
        long lost = 0;
        for (ItemStack stack : drop) {
            lost += plugin.items().value(stack);
        }
        long lostValue = lost;
        long xp = died ? Math.round((40 + rp.kills * 60 + rp.aiKills * 20) * raid.tier.xpMultiplier()) : 0;
        // For the journal, should the profile be unreadable: the gear is in the raid now, so the
        // loadout must be emptied (but for the melee weapon) and the raid cleared, or a repaired
        // profile brings it back.
        ItemRecord meleeRecord = plugin.carry().isMelee(melee) ? plugin.items().toRecord(melee) : null;
        Map<Integer, ItemRecord> left = meleeRecord == null ? Map.of() : Map.of(CarryService.MELEE_SLOT, meleeRecord);
        Supplier<String> change = () -> why + ": loadout = " + ProfileService.json(left)
                + " (the rest of that gear stayed in the raid),"
                + (unknown.isEmpty() ? "" : " add to mailbox " + ProfileService.json(unknown) + ",")
                + " secure = " + ProfileService.json(rp.secure) + ", activeRaid = null, deployment = null, stats.deaths +1"
                + (died ? ", stats.kills +" + rp.kills + ", stats.ai_kills +" + rp.aiKills + ", xp +" + xp
                + " and seasonXp +" + xp + " (each level reached pays 5000 x the level)" : "")
                + (rp.ranked ? ", rankPoints " + plugin.ranks().deathDelta(1100, rp, lostValue) + " ("
                + plugin.ranks().deathDelta(0, rp, lostValue) + " below 1100, never below 0)" : "");
        plugin.profiles().edit(rp.uuid, change, profile -> {
            profile.loadout.clear();
            keepMelee(profile, melee);
            profile.mailbox.addAll(unknown);
            profile.secure = new ArrayList<>(rp.secure);
            profile.leaveRaid();
            profile.addStat("deaths", 1);
            if (died) {
                profile.addStat("kills", rp.kills);
                profile.addStat("ai_kills", rp.aiKills);
                plugin.ranks().addXp(null, profile, xp);
            }
            int delta = rp.ranked ? plugin.ranks().onDeath(profile, rp, lostValue, raid) : 0;
            profile.reports.add(0, report(raid, rp, outcome, 0, lostValue, delta, xp));
            trimReports(profile);
        });
        leftWith(raid, rp, keptThroughDeath(rp, melee, unknown));
        plugin.escorts().settle(raid, rp, false);
        byPlayer.remove(rp.uuid);
    }

    // ------------------------------------------------------------------ stand-ins

    /**
     * A player who leaves the raid outside a fight stays in it as a stand-in: a mannequin in their
     * skin, helmet, vest, rig and backpack, in the pose they left in, with their health. It is shot,
     * stabbed and blown up like the player, armour included, so leaving the game is no way to get
     * out of trouble. Killing it kills them: their gear goes into a body there. A rejoin puts the
     * player in its place, with its health and whatever is left of the armour. Soldiers do not go
     * looking for it.
     */
    private void spawnStandIn(Raid raid, RaidPlayer rp, Location at, @Nullable Pose pose) {
        Map<Integer, ItemStack> carried = rp.disconnectedInventory == null ? Map.of() : rp.disconnectedInventory;
        try {
            Mannequin standIn = raid.world.spawn(at, Mannequin.class, m -> {
                m.setProfile(rp.look == null ? Mannequin.defaultProfile() : rp.look);
                m.setDescription(null); // no "NPC" line under the name
                m.customName(net.kyori.adventure.text.Component.text(rp.name));
                m.setImmovable(true);
                m.setPersistent(false);
                // Facing the way the player faced, body too: hit zones read the body's yaw, and a
                // body left 50 degrees off its head would take chest shots in an arm.
                m.setBodyYaw(at.getYaw());
                org.bukkit.attribute.AttributeInstance max = m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (max != null) {
                    max.setBaseValue(net.infnetwork.snowball.craftov.combat.HealthService.MAX_HP);
                }
                m.setHealth(Math.max(1, Math.min(net.infnetwork.snowball.craftov.combat.HealthService.MAX_HP, rp.offlineHealth)));
                m.getEquipment().setHelmet(wornCopy(carried, CarryService.HEAD));
                m.getEquipment().setChestplate(wornCopy(carried, CarryService.CHEST));
                m.getEquipment().setLeggings(wornCopy(carried, CarryService.LEGS));
                m.getEquipment().setBoots(wornCopy(carried, CarryService.FEET));
                if (pose != null && pose != Pose.STANDING && Mannequin.validPoses().contains(pose)) {
                    m.setPose(pose, true);
                }
                m.getPersistentDataContainer().set(Keys.STAND_IN, PersistentDataType.STRING, rp.uuid.toString());
            });
            // Kept loaded (the map's chunks are pinned, but only up to 40x40): an unloaded chunk
            // would drop it, being non-persistent. raid close lets go of every ticket.
            standIn.getChunk().addPluginChunkTicket(plugin);
            plugin.match().teamFor(raid, rp, standIn);
            rp.standIn = standIn;
        } catch (RuntimeException ex) {
            // Without one the player still comes back where they left, as before.
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not leave a stand-in for " + rp.name + " in raid " + raid.id, ex);
        }
    }

    private static @Nullable ItemStack wornCopy(Map<Integer, ItemStack> carried, int slot) {
        ItemStack stack = carried.get(slot);
        return stack == null || stack.isEmpty() ? null : stack.clone();
    }

    /** The disconnected player this entity stands in for, or null if it is no stand-in. */
    public @Nullable RaidPlayer standInOwner(@Nullable Entity entity) {
        if (!(entity instanceof Mannequin)) {
            return null;
        }
        String owner = entity.getPersistentDataContainer().get(Keys.STAND_IN, PersistentDataType.STRING);
        Raid raid = owner == null ? null : raidIn(entity.getWorld());
        if (raid == null) {
            return null;
        }
        RaidPlayer rp;
        try {
            rp = raid.player(UUID.fromString(owner));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return rp != null && rp.state == RaidPlayer.State.DISCONNECTED && rp.standIn != null
                && rp.standIn.getUniqueId().equals(entity.getUniqueId()) ? rp : null;
    }

    /**
     * Takes the player's stand-in out of the world, if they have one. What its helmet and vest lost
     * to bullets is written back to what the player carries, and its health becomes theirs.
     * Returns where it stood, or null without one.
     */
    private @Nullable Location removeStandIn(RaidPlayer rp) {
        Mannequin standIn = rp.standIn;
        rp.standIn = null;
        if (standIn == null) {
            return null;
        }
        plugin.match().leaveTeam(standIn);
        plugin.damage().forget(standIn.getUniqueId());
        if (!standIn.isValid() && !standIn.isDead()) {
            return null; // gone some other way: the player comes back where they left
        }
        if (rp.disconnectedInventory != null) {
            wornBack(rp.disconnectedInventory, CarryService.HEAD, standIn.getEquipment().getHelmet());
            wornBack(rp.disconnectedInventory, CarryService.CHEST, standIn.getEquipment().getChestplate());
        }
        rp.offlineHealth = standIn.getHealth();
        Location at = standIn.getLocation();
        standIn.remove();
        return at;
    }

    private static void wornBack(Map<Integer, ItemStack> carried, int slot, @Nullable ItemStack worn) {
        if (carried.containsKey(slot) && worn != null && !worn.isEmpty()) {
            carried.put(slot, worn.clone());
        }
    }

    /**
     * Damage to a stand-in. What would hurt the player in a fight gets through (bullets, knives,
     * blows, grenades and other explosions) and so does the void; it does not drown, burn or choke
     * in a wall. A lethal hit is the player's death.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStandInDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Mannequin standIn)) {
            return;
        }
        RaidPlayer rp = standInOwner(standIn);
        Raid raid = rp == null ? null : raidIn(standIn.getWorld());
        if (rp == null || raid == null) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean counts = event instanceof EntityDamageByEntityEvent || plugin.damage().isApplying()
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.VOID;
        if (!counts || raid.state != Raid.State.RUNNING) {
            event.setCancelled(true);
            return;
        }
        if (event.getFinalDamage() < standIn.getHealth()) {
            return;
        }
        event.setCancelled(true);
        DamageService.LastHit hit = plugin.damage().lastHit(standIn.getUniqueId());
        if (hit == null && event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof LivingEntity living) {
            plugin.damage().recordHit(standIn, living, "近战", net.infnetwork.snowball.craftov.combat.HitZone.TORSO);
            hit = plugin.damage().lastHit(standIn.getUniqueId());
        }
        diedOffline(raid, rp, hit);
    }

    /**
     * A disconnected player's wounds bleed on (HealthService): out of their stand-in, which dies of
     * it like the player would, or out of the health kept for them while they have none. As for a
     * player, the bleeding of a wound the stand-in's last attacker made ({@code sources}) keeps
     * their kill credit going.
     */
    public void bleedOffline(Raid raid, RaidPlayer rp, double amount, net.infnetwork.snowball.craftov.combat.BodyPart worst,
                             java.util.Set<UUID> sources) {
        if (rp.state != RaidPlayer.State.DISCONNECTED) {
            return;
        }
        Mannequin standIn = rp.standIn != null && rp.standIn.isValid() ? rp.standIn : null;
        if (standIn != null) {
            plugin.damage().recordBleed(standIn, "失血", worst, sources);
            plugin.damage().deal(standIn, null, amount); // a lethal amount ends in onStandInDamage
            standIn.getWorld().spawnParticle(Particle.BLOCK, standIn.getLocation().add(0, 0.8, 0), 6, 0.2, 0.3, 0.2,
                    Material.REDSTONE_BLOCK.createBlockData());
            return;
        }
        rp.offlineHealth -= amount;
        if (rp.offlineHealth <= 0) {
            long now = Bukkit.getCurrentTick();
            diedOffline(raid, rp, new DamageService.LastHit(null, "未知", "失血", worst.zone(), worst, now, false, now));
        }
    }

    /** Should a stand-in die all the same (a /kill), it drops nothing: its gear is the player's. */
    @EventHandler
    public void onStandInDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        RaidPlayer rp = standInOwner(event.getEntity());
        if (rp == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        Raid raid = raidIn(event.getEntity().getWorld());
        if (raid != null) {
            diedOffline(raid, rp, plugin.damage().lastHit(event.getEntity().getUniqueId()));
        }
    }

    public void spawnCorpse(Raid raid, Location at, List<ItemStack> items, Player owner) {
        spawnBody(raid, at, owner.getName() + " 的遗体", items,
                io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(owner.getPlayerProfile()),
                owner.getInventory().getHelmet(), owner.getInventory().getChestplate());
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
            m.setDescription(Text.mm("<muted>☠ " + Text.escape(title)));
            m.customName(Text.mm("<muted>☠ " + Text.escape(title)));
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

    /**
     * The kill feed (HUD top right, chat without BetterHud) goes only to the squads of the killer and
     * the victim: nobody else learns who died where, as in an extraction shooter.
     */
    private void killFeed(Raid raid, DamageService.@Nullable LastHit hit, String victim) {
        String line = hit == null || hit.attacker() == null
                ? "<muted>☠ <white>" + Text.escape(victim)
                : "<danger>" + Text.escape(hit.attackerName()) + " <muted>[" + Text.escape(hit.weapon()) + "]"
                + (hit.headshot() ? " <danger>⊕" : "") + " <muted>→ <white>" + Text.escape(victim);
        java.util.Set<Integer> squads = new java.util.HashSet<>();
        for (RaidPlayer rp : raid.players.values()) {
            if (rp.name.equals(victim) || hit != null && hit.attacker() != null && rp.uuid.equals(hit.attacker())) {
                squads.add(rp.squad);
            }
        }
        for (int squad : squads) {
            squadFeed(raid, squad, line);
        }
    }

    /** A kill-feed line for one squad (dead squadmates watching included). */
    private void squadFeed(Raid raid, int squad, String line) {
        for (RaidPlayer rp : raid.players.values()) {
            Player player = rp.squad == squad ? Bukkit.getPlayer(rp.uuid) : null;
            if (player != null && player.getWorld() == raid.world) {
                plugin.hud().feed(player, line);
            }
        }
    }

    /** A raid-wide notice on the alert banner under the compass (chat without BetterHud). */
    public void announce(Raid raid, String message) {
        announce(raid, message, null);
    }

    public void announce(Raid raid, String message, net.infnetwork.snowball.craftov.gui.@Nullable UiSound sound) {
        for (Player player : raid.online()) {
            plugin.hud().alert(player, message, 120, sound);
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

    /** Who hurt the player, where and how badly: one line per attacker and weapon. */
    private void sendDamageLog(Player player, DamageService.@Nullable LastHit killing, List<DamageService.HitRecord> hits) {
        if (hits.isEmpty()) {
            return;
        }
        if (killing != null && killing.attacker() != null) {
            DamageService.HitRecord last = hits.get(hits.size() - 1);
            player.sendMessage(Text.mm("<danger>致命一击 <white>" + Text.escape(killing.attackerName()) + " <muted>· "
                    + Text.escape(killing.weapon()) + " · " + killing.part().displayName() + " · " + last.distance() + "m"));
        }
        java.util.Map<String, java.util.Map<net.infnetwork.snowball.craftov.combat.BodyPart, double[]>> byAttacker = new java.util.LinkedHashMap<>();
        for (DamageService.HitRecord h : hits) {
            double[] acc = byAttacker.computeIfAbsent(h.attacker() + " · " + h.weapon(), k -> new java.util.EnumMap<>(net.infnetwork.snowball.craftov.combat.BodyPart.class))
                    .computeIfAbsent(h.part(), k -> new double[2]);
            acc[0] += 1;
            acc[1] += h.damage();
        }
        player.sendMessage(Text.mm("<muted>受到的伤害"));
        byAttacker.forEach((who, parts) -> {
            StringBuilder sb = new StringBuilder("<white>" + Text.escape(who) + " <muted>→ ");
            parts.forEach((part, acc) -> sb.append("<text>").append(part.displayName()).append(" ×").append((int) acc[0])
                    .append(" <danger>").append(Math.round(acc[1])).append("  "));
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

    /**
     * Gives up the raid (counts as death). Whoever hurt the player in the last 30 seconds, or
     * knocked them down, still gets the kill: giving up from the terminal while crawling or about to
     * die would otherwise deny them it and the bounty.
     */
    public void surrender(Player player) {
        Raid raid = raidOf(player);
        RaidPlayer rp = session(player);
        if (raid != null && rp != null && rp.active()) {
            die(raid, player, rp, "放弃行动", plugin.downed().killingHit(player));
        }
    }

    private void close(Raid raid) {
        raid.state = Raid.State.CLOSED;
        raid.players.values().forEach(this::removeStandIn);
        plugin.insurance().settle(raid);
        spectators.values().removeIf(entry -> entry.getKey() == raid);
        for (Player player : raid.world.getPlayers()) {
            byPlayer.remove(player.getUniqueId());
            plugin.lobby().enterLobby(player);
            // The dead who were still watching a squadmate get their results now.
            SettlementMenu.Settlement settlement = pendingSettlements.remove(player.getUniqueId());
            if (settlement != null) {
                settle(player, settlement);
            }
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
        profile.leaveRaid();
        plugin.escorts().voided(rp);
        byPlayer.remove(player.getUniqueId());
        plugin.match().leaveTeam(player);
        plugin.lobby().enterLobbyKeepInventory(player);
        plugin.lobby().captureLoadout(player);
        plugin.profiles().saveNow(player.getUniqueId());
        leftWith(raid, rp, out);
        Text.send(player, "<accent>" + message);
    }

    /**
     * Plugin shutdown. Whoever is still in a raid (alive, downed, flying in, or disconnected inside
     * the reconnect window) has that raid voided ({@link #voidRaid}): back to the gear and secure
     * container they went in with, the fees refunded and a letter with the compensation, and an
     * escort contract they head is called off. Whoever already died or got out is settled as it
     * was. Each player and each raid is handled on its own, so one failure cannot leave the others
     * in a raid that no longer exists; a player whose void fails is voided on their next login
     * instead, the same way (their profile still holds the deployment record).
     */
    public void shutdown() {
        for (Raid raid : new ArrayList<>(raids.values())) {
            // A loading raid has not taken anything from its squads yet: they are still in the lobby.
            List<RaidPlayer> voided = new ArrayList<>();
            if (raid.state != Raid.State.LOADING) {
                for (RaidPlayer rp : raid.players.values()) {
                    if (stillIn(rp)) {
                        voided.add(rp);
                    }
                }
            }
            // Decided before anything is undone, so their gear can never also be paid out by the
            // insurance below, whatever fails in between.
            Set<UUID> owners = new java.util.HashSet<>();
            voided.forEach(rp -> owners.add(rp.uuid));
            List<ItemStack> carried = new ArrayList<>();
            for (RaidPlayer rp : voided) {
                try {
                    carried.addAll(leftBehind(rp));
                    voidOnStop(raid, rp);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not void raid " + raid.id + " for " + rp.name
                            + "; it is voided when they next log in", ex);
                }
            }
            raid.state = Raid.State.CLOSED;
            try {
                // As when a raid closes: the insured gear of those who died, still on bodies, in
                // containers or on the ground, goes back to its owners. The world is deleted below
                // and nothing would be left to settle after the restart. On its own, so a failing
                // settlement cannot keep the world and a failing teardown cannot cost the payouts.
                plugin.insurance().settle(raid, owners, carried);
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

    /**
     * Copies of what a player whose raid is being voided has on them: it stays in the raid, and
     * someone else's insured gear among it is paid out to its owner. A stand-in's armour counts
     * as worn as it is.
     */
    private List<ItemStack> leftBehind(RaidPlayer rp) {
        List<ItemStack> out = new ArrayList<>();
        if (rp.state == RaidPlayer.State.DISCONNECTED) {
            removeStandIn(rp);
            if (rp.disconnectedInventory != null) {
                rp.disconnectedInventory.values().forEach(stack -> out.add(stack.clone()));
            }
            return out;
        }
        Player player = Bukkit.getPlayer(rp.uuid);
        if (player != null) {
            for (int slot = 0; slot <= CarryService.HEAD; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack != null && !stack.isEmpty() && !ItemFactory.isUi(stack)) {
                    out.add(stack.clone());
                }
            }
            if (!player.getItemOnCursor().isEmpty()) {
                out.add(player.getItemOnCursor().clone());
            }
        }
        return out;
    }

    /**
     * Join: the profile still names a raid, yet none is running for the player. The server went
     * down without stopping cleanly (a crash, a kill, a power cut), or a stop could not void it:
     * it is voided now, exactly as a stop would have ({@link #voidRaid}). The raid's world and
     * everything in it are gone, so there is nothing to keep out of an insurance payout either.
     */
    public void voidLost(Player player) {
        if (raidOf(player) != null) {
            return;
        }
        Voided voided = voidRaid(player.getUniqueId(), player.getName(), null, true);
        if (voided != null) {
            Text.send(player, "<accent>上一次行动因服务器中断已作废。" + voided.summary());
        }
    }

    /** Shutdown: voids the raid for one player still in it and, if they are online, sends them home. */
    private void voidOnStop(Raid raid, RaidPlayer rp) {
        Player player = rp.state == RaidPlayer.State.DISCONNECTED ? null : Bukkit.getPlayer(rp.uuid);
        removeStandIn(rp);
        rp.state = RaidPlayer.State.LEFT;
        rp.inFlight = false;
        rp.disconnectedInventory = null;
        byPlayer.remove(rp.uuid, raid);
        plugin.escorts().callOff(rp);
        Voided voided = voidRaid(rp.uuid, rp.name, rp.deployment, false);
        if (player == null) {
            return;
        }
        plugin.weapons().reset(player);
        plugin.downed().clear(player);
        plugin.match().leaveTeam(player);
        // What they carried stays in the raid: the lobby puts the loadout they went in with on them.
        plugin.lobby().enterLobby(player);
        Text.send(player, "<accent>服务器维护：本次行动已作废。" + (voided == null ? "" : voided.summary()));
    }

    /**
     * What a void did to one player, for the chat line that goes with its letter: {@code refund}
     * the entry fee and premium handed back, {@code earned} what the raid had paid out (less what it
     * cost on the way) and was taken back, {@code withheld} whether anything was not handed back
     * because others had taken it out, {@code record} false for a profile without a deployment
     * record (left by an older build), whose loadout simply stays and which gets no refund.
     */
    private record Voided(long refund, long earned, boolean withheld, boolean record) {
        String summary() {
            if (!record) {
                return "<muted>配装和安全箱仍是出发前的样子，局内捡到的物品不计，详见仓库邮箱里的邮件。";
            }
            StringBuilder sb = new StringBuilder("<muted>出发前的装备和安全箱已退回");
            if (withheld) {
                sb.append("（已被其他干员带出本局的不再退回）");
            }
            if (refund > 0) {
                sb.append("，入场费和保险费 ◈").append(Text.money(refund)).append(" 已退还");
            }
            if (earned > 0) {
                sb.append("，局内获得的任务奖励、升级奖励和悬赏共 ◈").append(Text.money(earned)).append(" 已扣回");
            } else if (earned < 0) {
                sb.append("，局内更换任务花的 ◈").append(Text.money(-earned)).append(" 已退还");
            }
            return sb.append("，详见仓库邮箱里的邮件。").toString();
        }
    }

    /**
     * Voids a raid for a player who was still in it when the server stopped ({@link #shutdown}) or
     * went down ({@link #voidLost}, on their next login): the one way a raid is voided, so a stop
     * and a crash come out the same. The loadout and secure container go back to how they went in;
     * the entry fee and insurance premium are refunded; what the raid paid out or counted is taken
     * back (contract rewards and progress, the experience and level rewards they brought, the raid
     * in the stats, items it mailed) and bounties collected go back on the heads they came off; and
     * a letter says so, with raid.void-compensation.money attached. Kills in it never reach the
     * stats, since only a settled raid adds them. The profile's own record is what is undone, in
     * one change that also clears it, so a raid is voided once only ({@code known}, the copy kept
     * with the raid, only describes the change should the profile be unreadable). A profile that
     * still names a raid but has no record (left by an older build) gets the letter and keeps its
     * loadout, which a raid never changes. Gear that went in with them but has since left the raid
     * with someone else (Profile.Deployment.gone and goneStacks, see {@link #leftWith}) does not
     * come back to them as well. Returns what it did, or null when there was nothing to void (or it
     * went to the journal of an unreadable profile).
     */
    private @Nullable Voided voidRaid(UUID uuid, String name, Profile.@Nullable Deployment known, boolean crash) {
        long compensation = Math.max(0, plugin.getConfig().getLong("raid.void-compensation.money", 5000));
        Voided[] sent = new Voided[1];
        List<Profile.BountyClaim> claims = new ArrayList<>();
        String cause = (crash ? "raid lost when the server went down" : "server stopped during the raid") + ", voided for " + name;
        plugin.profiles().edit(uuid, () -> {
            int[] lost = new int[1];
            Map<Integer, ItemRecord> loadout = known == null ? Map.of() : without(known.loadout, known.gone, lost);
            List<ItemRecord> secure = known == null ? List.of() : without(known.secure, known.gone, lost);
            Map<String, Long> lostStacks = known == null ? Map.of() : deduct(loadout, secure, known.goneStacks);
            return describeVoid(cause, known, loadout, secure, voidLetter(known, lost[0], lostStacks, compensation, crash));
        }, p -> {
            Profile.Deployment d = p.deployment;
            if (p.activeRaid == null && d == null || known != null && d != null && !d.raid.equals(known.raid)) {
                return; // settled already (or, never expected, another raid's record)
            }
            // Everything is worked out before the profile is touched, so it changes all at once.
            int[] lost = new int[1];
            Map<Integer, ItemRecord> loadout = d == null ? p.loadout : without(d.loadout, d.gone, lost);
            List<ItemRecord> secure = d == null ? p.secure : without(d.secure, d.gone, lost);
            Map<String, Long> lostStacks = d == null ? Map.of() : deduct(loadout, secure, d.goneStacks);
            Profile.Mail letter = voidLetter(d, lost[0], lostStacks, compensation, crash);
            List<Profile.TaskState> tasks = new ArrayList<>();
            if (d != null) {
                d.tasks.forEach(task -> tasks.add(task.copy()));
            }
            p.loadout = loadout;
            p.secure = secure;
            if (d != null) {
                p.money += d.entryFee + d.premium - d.earned;
                d.stats.forEach((key, delta) -> {
                    long now = p.stat(key) - delta;
                    if (now == 0) {
                        p.stats.remove(key); // as if the raid had never been counted
                    } else {
                        p.stats.put(key, now);
                    }
                });
                p.taskDay = d.taskDay;
                p.tasks = tasks;
                p.level = d.level;
                p.xp = d.xp;
                if (java.util.Objects.equals(p.seasonId, d.seasonId)) {
                    p.seasonXp = d.seasonXp;
                }
                for (ItemRecord mailed : d.mailed) {
                    String json = ProfileService.json(mailed);
                    for (int i = 0; i < p.mailbox.size(); i++) {
                        if (ProfileService.json(p.mailbox.get(i)).equals(json)) {
                            p.mailbox.remove(i);
                            break;
                        }
                    }
                }
                claims.addAll(d.bounties);
            }
            p.leaveRaid();
            MailService.deliver(p, letter);
            sent[0] = new Voided(d == null ? 0 : d.entryFee + d.premium, d == null ? 0 : d.earned,
                    lost[0] > 0 || !lostStacks.isEmpty(), d != null);
        });
        if (sent[0] == null) {
            return null;
        }
        plugin.profiles().saveNow(uuid);
        for (Profile.BountyClaim claim : claims) {
            // The kill does not count: what it collected goes back on the victim's head.
            plugin.profiles().edit(claim.victim, () -> "bounty collected by " + name + " in a raid that was voided goes back: bounty +"
                    + claim.placed + (claim.house ? ", houseBountyDay = " + claim.dayBefore + " if it is " + claim.day : ""), p -> {
                p.bounty += claim.placed;
                if (claim.house && p.houseBountyDay == claim.day) {
                    p.houseBountyDay = claim.dayBefore;
                }
            });
        }
        return sent[0];
    }

    /** The voided raid's change to the profile in the journal's terms, for a profile that cannot be read. */
    private static String describeVoid(String cause, Profile.@Nullable Deployment d, Map<Integer, ItemRecord> loadout,
                                       List<ItemRecord> secure, Profile.Mail letter) {
        StringBuilder sb = new StringBuilder(cause).append(": ");
        if (d != null) {
            long money = d.entryFee + d.premium - d.earned;
            sb.append("loadout = ").append(ProfileService.json(loadout))
                    .append(", secure = ").append(ProfileService.json(secure))
                    .append(", money ").append(money >= 0 ? "+" : "").append(money);
            d.stats.forEach((key, delta) -> sb.append(", stats.").append(key).append(' ').append(-delta >= 0 ? "+" : "").append(-delta));
            sb.append(", taskDay = ").append(d.taskDay).append(", tasks = ").append(ProfileService.json(d.tasks))
                    .append(", level = ").append(d.level).append(", xp = ").append(d.xp)
                    .append(", seasonXp = ").append(d.seasonXp).append(" (if seasonId is still \"").append(d.seasonId).append("\")");
            if (!d.mailed.isEmpty()) {
                sb.append(", remove from mailbox ").append(ProfileService.json(d.mailed));
            }
            sb.append(", ");
        }
        return sb.append("activeRaid = null, deployment = null, add to mail ").append(ProfileService.json(letter)).toString();
    }

    /**
     * The letter that tells a player their raid was voided, with the compensation attached.
     * {@code lost} single items and {@code lostStacks} (item id -> count) had left the raid with
     * others and were not handed back.
     */
    private Profile.Mail voidLetter(Profile.@Nullable Deployment d, int lost, Map<String, Long> lostStacks, long compensation,
                                    boolean crash) {
        StringBuilder body = new StringBuilder(crash ? "服务器意外中断时" : "服务器维护时");
        body.append(d == null ? "你还在行动中" : "你还在「" + d.map + "」（" + d.tier + "）的行动中").append("，这局行动已作废。\n")
                .append(d == null ? "你的配装和安全箱仍是出发前的样子，局内捡到的物品不计。"
                        : "出发前的装备和安全箱已原样退回；局内获得的物品、经验、任务进度和战绩都不计。");
        List<String> gone = new ArrayList<>();
        if (lost > 0) {
            gone.add(lost + " 件装备");
        }
        lostStacks.forEach((id, n) -> {
            ItemDef def = plugin.registry().get(id);
            gone.add((def == null ? id : def.name()) + " ×" + n);
        });
        if (!gone.isEmpty()) {
            body.append("\n已被其他干员带出本局的不再退回：").append(String.join("、", gone)).append("。");
        }
        if (d != null && d.entryFee + d.premium > 0) {
            body.append("\n已退还").append(d.entryFee > 0 ? "入场费 ◈" + Text.money(d.entryFee) : "")
                    .append(d.entryFee > 0 && d.premium > 0 ? "、" : "").append(d.premium > 0 ? "保险费 ◈" + Text.money(d.premium) : "")
                    .append("，已直接到账。");
        }
        if (d != null && d.earned > 0) {
            body.append("\n局内获得的任务奖励、升级奖励和悬赏共 ◈").append(Text.money(d.earned)).append(" 已从余额扣回")
                    .append(d.bounties.isEmpty() ? "。" : "，领取的悬赏退回到被击杀者头上。");
        } else if (d != null && d.earned < 0) {
            body.append("\n局内更换任务花的 ◈").append(Text.money(-d.earned)).append(" 已退还到余额。");
        }
        if (compensation > 0) {
            body.append("\n附件是 ◈").append(Text.money(compensation)).append(" 补偿，领取后到账。");
        }
        return MailService.letter("服务器维护补偿", body.toString(), compensation, List.of());
    }

    /**
     * Copies of the records, leaving out (packed contents included) everything whose instance id
     * is in {@code gone}; {@code lost[0]} counts what was left out.
     */
    private static Map<Integer, ItemRecord> without(Map<Integer, ItemRecord> records, Set<String> gone, int[] lost) {
        Map<Integer, ItemRecord> out = new HashMap<>();
        records.forEach((slot, record) -> {
            ItemRecord kept = without(record, gone, lost);
            if (kept != null) {
                out.put(slot, kept);
            }
        });
        return out;
    }

    private static List<ItemRecord> without(List<ItemRecord> records, Set<String> gone, int[] lost) {
        List<ItemRecord> out = new ArrayList<>();
        for (ItemRecord record : records) {
            ItemRecord kept = without(record, gone, lost);
            if (kept != null) {
                out.add(kept);
            }
        }
        return out;
    }

    private static @Nullable ItemRecord without(ItemRecord record, Set<String> gone, int[] lost) {
        if (record.uid != null && gone.contains(record.uid)) {
            lost[0]++;
            return null;
        }
        ItemRecord copy = record.copy();
        if (copy.contents != null) {
            copy.contents = without(record.contents, gone, lost);
        }
        return copy;
    }

    /** Still in the raid: a stop would void it for them ({@link #shutdown}). */
    private static boolean stillIn(RaidPlayer rp) {
        return rp.state == RaidPlayer.State.DEPLOYING || rp.active() || rp.state == RaidPlayer.State.DISCONNECTED;
    }

    /**
     * {@code leaver} is out of the raid for good and keeps {@code kept}: what they got out with, or
     * what a death leaves them (the secure container, the melee weapon). What among it someone still
     * in the raid took in goes on that player's deployment record (Profile.Deployment.gone and
     * goneStacks) and is saved at once, so a void raid, whether a stop or a crash voids it, does not
     * hand it back to them as well. Called once the leaver's own save is queued (saves land in
     * order): should the server die in between, it exists twice rather than not at all.
     *
     * <p>Single items go by instance id. Stacking items (ammo, meds, valuables) have none, so they
     * go by count, as in {@link #othersGear}: of each item the leaver keeps more of than they took
     * in, as much as a void would still hand someone back but they neither hold nor have used up
     * counts as theirs, up to that excess. Rounds in someone's gun are left out of the count, as the
     * gun goes by its id, rounds and all. A find of the same item can be caught by this when the
     * owner lost theirs another way (dropped it, left it in a crate); it costs them only what a void
     * would have handed back, whereas leaving stacks out let gold bars handed to a squadmate who got
     * out come back with every server stop.
     */
    private void leftWith(Raid raid, RaidPlayer leaver, Collection<ItemRecord> kept) {
        List<RaidPlayer> owners = new ArrayList<>();
        Set<String> skip = new java.util.HashSet<>();
        for (RaidPlayer owner : raid.players.values()) {
            if (owner != leaver && owner.deployment != null && stillIn(owner)) {
                owners.add(owner);
                skip.addAll(owner.gearUids);
            }
        }
        if (owners.isEmpty()) {
            return;
        }
        Set<String> ids = new java.util.HashSet<>();
        instanceIds(kept, ids);
        Map<String, long[]> stacks = new HashMap<>();
        for (ItemRecord record : kept) {
            countStacks(record, stacks, skip);
        }
        Map<String, Long> excess = new HashMap<>();
        stacks.forEach((id, entry) -> {
            long more = entry[0] - leaver.broughtIn.getOrDefault(id, 0L);
            if (more > 0) {
                excess.put(id, more);
            }
        });
        for (RaidPlayer owner : owners) {
            Set<String> theirs = new java.util.LinkedHashSet<>(ids);
            theirs.retainAll(owner.gearUids);
            Map<String, Long> counted = new java.util.LinkedHashMap<>();
            if (!excess.isEmpty()) {
                Set<String> gone = new java.util.HashSet<>(owner.deployment.gone);
                gone.addAll(theirs);
                Map<String, Long> back = refundable(owner.deployment, gone);
                Map<String, Long> held = null;
                for (Map.Entry<String, Long> entry : excess.entrySet()) {
                    String id = entry.getKey();
                    long would = back.getOrDefault(id, 0L);
                    if (entry.getValue() <= 0 || would <= 0) {
                        continue;
                    }
                    if (held == null) {
                        held = held(raid, owner);
                    }
                    long take = Math.min(entry.getValue(), would - held.getOrDefault(id, 0L) - owner.used.getOrDefault(id, 0L));
                    if (take > 0) {
                        counted.put(id, take);
                        entry.setValue(entry.getValue() - take);
                    }
                }
            }
            if (!theirs.isEmpty() || !counted.isEmpty()) {
                noteGone(raid, owner, theirs, counted, leaver.name);
            }
        }
    }

    /**
     * Stacking items a void would hand back from {@code d} (item id -> count): its loadout and
     * secure container without the gear by instance id in {@code gone}, less its goneStacks.
     */
    private Map<String, Long> refundable(Profile.Deployment d, Set<String> gone) {
        int[] ignored = new int[1];
        List<ItemRecord> records = new ArrayList<>(without(d.loadout, gone, ignored).values());
        records.addAll(without(d.secure, gone, ignored));
        Map<String, Long> counts = stackCounts(records);
        d.goneStacks.forEach((id, n) -> counts.merge(id, -n, Long::sum));
        return counts;
    }

    /** Adds what of {@code owner}'s {@code by} has taken out of the raid to their deployment record. */
    private void noteGone(Raid raid, RaidPlayer owner, Set<String> uids, Map<String, Long> stacks, String by) {
        Profile.Deployment known = owner.deployment;
        boolean[] same = new boolean[1];
        plugin.profiles().edit(owner.uuid, () -> "gear " + owner.name + " took into raid " + raid.id + " left it with " + by
                + ": deployment.gone add " + ProfileService.json(uids) + ", deployment.goneStacks add " + ProfileService.json(stacks)
                + " (each count added to the one there; only while deployment.raid is \"" + raid.key + "\")", p -> {
            Profile.Deployment d = p.deployment;
            if (d != null && d.raid.equals(raid.key)) {
                addGone(d, uids, stacks);
                same[0] = d == known;
            }
        });
        if (known != null && !same[0]) {
            // The copy kept with the raid, when it is not the profile's own (they are offline): a
            // stop voids from the profile, and this only describes the void should that be unreadable.
            addGone(known, uids, stacks);
        }
        if (plugin.profiles().cached(owner.uuid) != null) {
            plugin.profiles().saveNow(owner.uuid); // an offline profile was written by the edit
        }
    }

    private static void addGone(Profile.Deployment d, Set<String> uids, Map<String, Long> stacks) {
        d.gone.addAll(uids);
        stacks.forEach((id, n) -> d.goneStacks.merge(id, n, Long::sum));
    }

    /**
     * Takes {@code counts} (item id -> how many) out of the stacking items in these records, which
     * it changes: loose stacks before rounds loaded in guns, the loadout before the secure
     * container. Returns what it took (never more than there is).
     */
    private Map<String, Long> deduct(Map<Integer, ItemRecord> loadout, List<ItemRecord> secure, Map<String, Long> counts) {
        Map<String, Long> left = new HashMap<>(counts);
        Map<String, Long> taken = new java.util.LinkedHashMap<>();
        if (left.values().stream().noneMatch(n -> n > 0)) {
            return taken;
        }
        List<Integer> slots = new ArrayList<>(loadout.keySet());
        Collections.sort(slots);
        for (Integer slot : slots) {
            if (takeLoose(loadout.get(slot), left, taken)) {
                loadout.remove(slot);
            }
        }
        secure.removeIf(record -> takeLoose(record, left, taken));
        for (Integer slot : slots) {
            ItemRecord record = loadout.get(slot);
            if (record != null) {
                takeRounds(record, left, taken);
            }
        }
        secure.forEach(record -> takeRounds(record, left, taken));
        return taken;
    }

    /** {@link #deduct} on one record and what is packed in it; true when the record itself is used up. */
    private boolean takeLoose(ItemRecord record, Map<String, Long> left, Map<String, Long> taken) {
        if (record.contents != null) {
            record.contents.removeIf(child -> takeLoose(child, left, taken));
        }
        ItemDef def = plugin.registry().get(record.id);
        long want = left.getOrDefault(record.id, 0L);
        if (def == null || def.maxStack() <= 1 || want <= 0) {
            return false;
        }
        long have = Math.max(1, record.amount);
        long take = Math.min(want, have);
        left.put(record.id, want - take);
        taken.merge(record.id, take, Long::sum);
        record.amount = (int) (have - take);
        return record.amount <= 0;
    }

    /** {@link #deduct} on the rounds loaded in a gun (and in guns packed in it). */
    private static void takeRounds(ItemRecord record, Map<String, Long> left, Map<String, Long> taken) {
        if (record.contents != null) {
            record.contents.forEach(child -> takeRounds(child, left, taken));
        }
        long want = record.ammoType == null ? 0 : left.getOrDefault(record.ammoType, 0L);
        if (want <= 0 || record.ammo == null || record.ammo <= 0) {
            return;
        }
        long take = Math.min(want, record.ammo);
        left.put(record.ammoType, want - take);
        taken.merge(record.ammoType, take, Long::sum);
        record.ammo = (int) (record.ammo - take);
    }

    /** Adds the instance ids of these records (packed contents too) to {@code into}. */
    private static void instanceIds(Collection<ItemRecord> records, Set<String> into) {
        for (ItemRecord record : records) {
            if (record.uid != null) {
                into.add(record.uid);
            }
            if (record.contents != null) {
                instanceIds(record.contents, into);
            }
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
                + ", secure = " + ProfileService.json(rp.secure) + ", activeRaid = null, deployment = null"
                + (refund > 0 ? ", money +" + refund : ""), profile -> {
            profile.loadout = inv;
            profile.mailbox.addAll(homeless);
            profile.secure = new ArrayList<>(rp.secure);
            profile.leaveRaid();
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
        // Back in the stand-in's place, with its health and what is left of its armour.
        Location standing = removeStandIn(rp);
        plugin.carry().restore(player, rp.disconnectedInventory, UiItems.raidTerminal());
        rp.disconnectedInventory = null;
        plugin.health().setupPlayer(player, false);
        if (rp.offlineHealth > 0) {
            player.setHealth(Math.min(net.infnetwork.snowball.craftov.combat.HealthService.MAX_HP, rp.offlineHealth));
        }
        Location back = standing != null ? standing : rp.lastLocation.getWorld() == raid.world ? rp.lastLocation
                : raid.map.spawns().isEmpty() ? raid.world.getSpawnLocation() : raid.map.spawns().get(0).pos().at(raid.world);
        player.teleport(back);
        if (rp.inFlight) {
            // Back before the squad's entry camera ended: the camera picks the player up again
            // (Cinematic looks players up by UUID) and they land with the squad through insert(),
            // with the same title and spawn protection, instead of ahead of everyone else.
            rp.state = RaidPlayer.State.DEPLOYING;
            player.setGameMode(GameMode.SPECTATOR);
            plugin.match().teamFor(raid, rp, player);
            Text.send(player, "<ok>已重新连接到行动，正在随小队前往渗透点。");
            return true;
        }
        rp.state = RaidPlayer.State.ALIVE;
        // Two seconds to load in, and no more: no attacks either, and soldiers react as usual (the
        // insertion grace is not handed out again, or rejoining would shake off a fight with them).
        rp.protectedUntil = Bukkit.getCurrentTick() + 40;
        player.setGameMode(GameMode.ADVENTURE);
        player.setFallDistance(0);
        plugin.match().teamFor(raid, rp, player);
        // LobbyService.onJoin skips the lobby HUD for a rejoin, so the raid HUD is shown here.
        plugin.hud().onRaidStart(player);
        Text.send(player, "<ok>已重新连接到行动。");
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
            if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                // Cancelled, so HealthService.onFallen never sees it: the legs still break, and the
                // fall is named as the cause.
                plugin.health().fallen(player, event.getDamage());
            }
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
                Notify.info(player, "<accent>⚡ 电网故障，电闸暂时无法使用 <muted>(" + dark + "s)");
                return;
            }
            if (raid.switchActive(sw.id())) {
                Notify.hint(player, "<muted>" + Text.escape(sw.name()) + " 已经开启");
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
            announce(raid, "<accent>" + Text.escape(sw.name()) + " 已被拉下！相关撤离点将在 " + countdown + " 秒后撤离，留在区域内的人将一同离开。");
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
            Notify.error(player, "门被锁住了，需要 <white>" + Text.escape(needed));
            player.playSound(player.getLocation(), "minecraft:block.iron_door.close", 0.6f, 1.6f);
            return;
        }
        ItemDef def = plugin.items().defOf(key);
        double uses = ItemFactory.dura(key, def.durability()) - 1;
        if (uses <= 0) {
            key.setAmount(key.getAmount() - 1);
            Notify.info(player, "<accent>已解锁 · " + Text.escape(def.name()) + " 已用尽");
        } else {
            plugin.items().setDura(key, uses);
            Notify.ok(player, "已解锁 · " + Text.escape(def.name()) + " <muted>剩余 " + (int) Math.round(uses) + " 次");
        }
        raid.lockedDoors.remove(doorKey);
        raid.unlockedDoors.add(doorKey);
        setDoor(door, true);
        door.getWorld().playSound(door.getLocation(), "minecraft:block.iron_door.open", 1f, 1f);
    }

    /** Opens or closes both halves of a door without block updates (soldiers use it too). */
    public static void setDoor(Block lower, boolean open) {
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
                    Notify.hint(player, "潜行拾取可直接替换当前的" + def.category().displayName());
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
            withdraw(raid, rp, "在部署前断开连接");
            return;
        }
        plugin.weapons().reset(player);
        if (rp.state == RaidPlayer.State.DOWNED) {
            // As if bleeding out: the knocker keeps the kill and the bounty past the 30 seconds.
            die(raid, player, rp, "倒地后断线", plugin.downed().killingHit(player));
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
            rp.look = io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile(player.getPlayerProfile());
            rp.offlineHealth = player.getHealth();
            rp.armorWear = plugin.operators().armorWearMultiplier(player); // read while still in the raid
            boolean landed = rp.state == RaidPlayer.State.ALIVE;
            if (landed) {
                rp.lastLocation = player.getLocation();
            }
            rp.state = RaidPlayer.State.DISCONNECTED;
            rp.disconnectedAt = Bukkit.getCurrentTick();
            if (landed) {
                // Someone flying in is nowhere in the world yet: they get theirs when the camera lands.
                spawnStandIn(raid, rp, rp.lastLocation, player.getPose());
            }
            player.getInventory().clear();
        } else if (rp.state == RaidPlayer.State.DEAD || rp.state == RaidPlayer.State.EXTRACTED) {
            byPlayer.remove(player.getUniqueId());
        }
        plugin.match().leaveTeam(player);
    }

    /** Takes a player out of a raid that has not deployed yet and tells their squad why ({@code reason}). */
    private void withdraw(Raid raid, RaidPlayer rp, String reason) {
        raid.players.remove(rp.uuid);
        // Squad lists keep their place even when emptied: their index is the squad number.
        for (List<UUID> squad : raid.squads) {
            squad.remove(rp.uuid);
        }
        byPlayer.remove(rp.uuid, raid);
        for (RaidPlayer mate : raid.players.values()) {
            Player p = mate.squad == rp.squad ? Bukkit.getPlayer(mate.uuid) : null;
            if (p != null) {
                // Before deployment the squad is still in the lobby: a chat line, not the raid feed.
                Text.send(p, "<muted>队友 <white>" + Text.escape(rp.name) + "</white> " + reason + "，已退出本局。");
            }
        }
        plugin.escorts().onWithdraw(rp.uuid, rp.name, reason);
    }

    /** Called by loot menus so systems (escort, stats) can react to looting. */
    public void onLooted(Player player, ItemStack stack) {
        RaidPlayer rp = session(player);
        if (rp == null) {
            return;
        }
        ItemDef def = plugin.items().defOf(stack);
        // Gold and red finds are worth telling the squad about; ammo is not, even gold rounds, which
        // come in several stacks and would send a line for each one taken.
        if (def != null && def.rarity().tier() >= 5 && def.category() != Category.AMMO) {
            Raid raid = raidOf(player);
            if (raid != null) {
                for (RaidPlayer mate : raid.squadOf(rp)) {
                    Player p = Bukkit.getPlayer(mate.uuid);
                    if (p != null && p != player) {
                        plugin.hud().feed(p, "<muted>队友 <white>" + Text.escape(player.getName()) + " <muted>找到了 " + def.coloredName());
                    }
                }
            }
        }
    }
}
