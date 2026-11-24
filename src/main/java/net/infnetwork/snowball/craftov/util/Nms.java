package net.infnetwork.snowball.craftov.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The two places Craftov reaches past the Bukkit API, by reflection on the Mojang-mapped Paper
 * runtime. Both fail soft: if a name ever changes, the feature degrades instead of breaking.
 *
 * <ul>
 *   <li>Trigger capture cells: vanilla answers every right-click on a block with real block
 *   updates, which would erase the client-only capture cells in front of a gun. A small outbound
 *   handler rewrites those updates back to the capture block, and sends the cells again behind a
 *   chunk that holds them. The same handler reports, in the order they arrive, the client's dig
 *   starts and arm swings (see {@link DigInputs}).</li>
 *   <li>Own view: the client's FOV multiplier is {@code (moveSpeed / abilities.walkingSpeed + 1) / 2},
 *   so the walking speed in the player's own abilities packets sets the field of view without
 *   touching how fast they move: the movement speed itself at the hip, so nothing that slows them
 *   changes the view, and a (negative) value that zooms while aiming (see {@link SelfView}). The
 *   same handler keeps a screen overlay's client-only helmet on their head, and keeps health lost
 *   to bleeding from their client, which would take it for a hit (see {@link #quietHealthLoss}).</li>
 *   <li>Stances: what a player is told about others' hands, their main-hand item and whether they
 *   are using it (the living-entity flags), is rewritten so their client draws the pose a stance
 *   calls for, a gun carried low or shouldered, say (see {@code StanceService}). The same handler
 *   keeps sounds the rewriter refuses (other players' vanilla footsteps) from the listener.</li>
 * </ul>
 */
public final class Nms {

    private static final String HANDLER = "craftov_capture";
    /**
     * The client-only block the trigger keeps in front of a gun (see {@code TriggerService}): a sculk
     * vein on all six faces, which has no collision, does nothing to a body inside it and has an
     * outline shell a crosshair always meets. The resource pack makes it invisible and silent.
     */
    public static final String CAPTURE_BLOCK =
            "minecraft:sculk_vein[down=true,east=true,north=true,south=true,up=true,west=true,waterlogged=false]";
    private static final String VIEW_HANDLER = "craftov_view";

    private static boolean ready;
    private static @Nullable Method getHandle;
    private static @Nullable Field playerConnection;
    private static @Nullable Field networkManager;
    private static @Nullable Field channelField;
    private static @Nullable Method sendPacket;
    private static @Nullable Class<?> blockUpdateClass;
    private static @Nullable Method blockUpdatePos;
    private static @Nullable Constructor<?> blockUpdateCtor;
    private static @Nullable Method asLong;
    private static @Nullable Object captureState;
    private static @Nullable Class<?> actionPacketClass;
    private static @Nullable Method actionPacketAction;
    private static @Nullable Method actionPacketPos;
    private static @Nullable Class<?> swingPacketClass;
    private static boolean inputsReady;
    private static @Nullable Class<?> chunkPacketClass;
    private static @Nullable Method chunkPacketX;
    private static @Nullable Method chunkPacketZ;
    private static @Nullable Method blockPosOf;
    private static volatile boolean chunksReady;
    /** For a warning should re-sending cells behind chunks fail once running ({@link CaptureHandler}). */
    private static @Nullable Logger chunkLogger;
    private static @Nullable Method getAbilities;
    private static @Nullable Class<?> abilitiesClass;
    private static @Nullable Constructor<?> abilitiesPacketCtor;
    private static @Nullable Method onUpdateAbilities;
    private static boolean viewReady;
    private static @Nullable Class<?> equipmentClass;
    private static @Nullable Method equipmentEntity;
    private static @Nullable Method equipmentSlots;
    private static @Nullable Constructor<?> equipmentCtor;
    private static @Nullable Class<?> bundleClass;
    private static @Nullable Method bundleSubPackets;
    private static @Nullable Constructor<?> bundleCtor;
    private static @Nullable Class<?> animateClass;
    private static @Nullable Method animateId;
    private static @Nullable Method animateAction;
    private static @Nullable Method pairOf;
    private static @Nullable Method pairFirst;
    private static @Nullable Method pairSecond;
    private static @Nullable Method asBukkitCopy;
    private static @Nullable Method asNmsCopy;
    private static @Nullable Class<?> pickBlockClass;
    private static @Nullable Class<?> pickEntityClass;
    private static @Nullable Class<?> entityDataClass;
    private static @Nullable Method entityDataId;
    private static @Nullable Method entityDataValues;
    private static @Nullable Constructor<?> entityDataCtor;
    private static @Nullable Method dataValueId;
    private static @Nullable Method dataValueValue;
    private static @Nullable Method dataValueCreate;
    private static @Nullable Object livingFlags;
    private static int livingFlagsId = -1;
    private static boolean dataReady;
    private static @Nullable Class<?> soundClass;
    private static @Nullable Method soundHolder;
    private static @Nullable Method soundSource;
    private static @Nullable Method holderValue;
    private static @Nullable Method soundLocation;
    /** Set on the main thread, read on Netty threads. */
    private static volatile org.bukkit.plugin.@Nullable Plugin pickPlugin;
    private static volatile java.util.function.@Nullable Consumer<Player> pickHandler;

    /**
     * The client's dig starts and arm swings, reported on the network thread in the order they
     * arrived, before the server handles them. In adventure mode the server turns every arm swing
     * into a left-click on whatever block its own ray finds, which looks exactly like a dig start;
     * this tells the two apart.
     */
    public interface DigInputs {
        /** A START_DESTROY_BLOCK packet for this block (a {@code BlockPos.asLong} key). */
        void started(long pos);

        /** An arm swing packet (either hand). */
        void swung();
    }

    /**
     * Decides what other players see of an entity's hands, and which of the sounds about it they hear.
     * Called on Netty threads. Nothing a player is sent about themselves goes through it.
     */
    public interface ViewRewriter {
        /** True while the entity's arm-swing animations are not sent to others (a gun in its hands). */
        boolean raised(int entityId);

        /** The main-hand item to show instead (an empty stack for empty hands), or null to show the real one. */
        org.bukkit.inventory.@Nullable ItemStack mainHand(int entityId, org.bukkit.inventory.ItemStack real);

        /** True if this player's middle click (pick block/entity) should be taken over: they hold a gun. */
        default boolean takesPick(int entityId) {
            return false;
        }

        /**
         * The living-entity flags to show others for this entity (bit 1: using the item in hand, bit 2:
         * the one in the off hand), given the real ones. Only asked about entities the rewriter poses.
         */
        default byte livingFlags(int entityId, byte real) {
            return real;
        }

        /** True to keep a sound from this listener: {@code key} is its event, {@code source} its category. */
        default boolean dropsSound(String key, String source) {
            return false;
        }
    }

    /**
     * The entity data packets {@link #sendLivingFlags} builds carry their values in one of these, so
     * the view handler, which rewrites every other living-flags update, lets them through as they are.
     */
    private static final class OwnValues extends java.util.ArrayList<Object> {
        OwnValues(int size) {
            super(size);
        }
    }

    /**
     * Middle click with a gun goes to {@code handler} on the main thread instead of the server's pick
     * logic (which would otherwise ignore the client-only capture cell or swap the held item away).
     */
    public static void onPick(org.bukkit.plugin.Plugin plugin, java.util.function.Consumer<Player> handler) {
        pickPlugin = plugin;
        pickHandler = handler;
    }

    private Nms() {
    }

    public static void init(Logger logger) {
        chunkLogger = logger;
        try {
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            getHandle = craftPlayer.getMethod("getHandle");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            playerConnection = serverPlayer.getField("connection");
            Class<?> common = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl");
            networkManager = common.getField("connection");
            channelField = Class.forName("net.minecraft.network.Connection").getField("channel");
            Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
            sendPacket = common.getMethod("send", packet);
            blockUpdateClass = Class.forName("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
            blockUpdatePos = blockUpdateClass.getMethod("getPos");
            Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
            Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState");
            blockUpdateCtor = blockUpdateClass.getConstructor(blockPos, blockState);
            asLong = blockPos.getMethod("asLong");
            Object craftData = org.bukkit.Bukkit.createBlockData(CAPTURE_BLOCK);
            captureState = craftData.getClass().getMethod("getState").invoke(craftData);
            Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player");
            getAbilities = playerClass.getMethod("getAbilities");
            abilitiesClass = Class.forName("net.minecraft.world.entity.player.Abilities");
            abilitiesPacketCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket")
                    .getConstructor(abilitiesClass);
            onUpdateAbilities = serverPlayer.getMethod("onUpdateAbilities");
            ready = true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.warning("Low-level hooks unavailable (" + ex + "); trigger capture falls back to resending cells, "
                    + "and aiming does not zoom.");
        }
        try {
            actionPacketClass = Class.forName("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket");
            actionPacketAction = actionPacketClass.getMethod("getAction");
            actionPacketPos = actionPacketClass.getMethod("getPos");
            swingPacketClass = Class.forName("net.minecraft.network.protocol.game.ServerboundSwingPacket");
            inputsReady = ready;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.warning("Dig packets unreadable (" + ex + "); stray arm swings are only filtered for a moment after Q, "
                    + "doors and menus.");
        }
        try {
            chunkPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket");
            chunkPacketX = chunkPacketClass.getMethod("getX");
            chunkPacketZ = chunkPacketClass.getMethod("getZ");
            blockPosOf = Class.forName("net.minecraft.core.BlockPos").getMethod("of", long.class);
            chunksReady = ready;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.warning("Chunk packets unreadable (" + ex + "); right after a world change the trigger may not fire "
                    + "until the crosshair moves.");
        }
        try {
            equipmentClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket");
            equipmentEntity = equipmentClass.getMethod("getEntity");
            equipmentSlots = equipmentClass.getMethod("getSlots");
            equipmentCtor = equipmentClass.getConstructor(int.class, java.util.List.class);
            bundleClass = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket");
            bundleSubPackets = bundleClass.getMethod("subPackets");
            bundleCtor = bundleClass.getConstructor(Iterable.class);
            animateClass = Class.forName("net.minecraft.network.protocol.game.ClientboundAnimatePacket");
            animateId = animateClass.getMethod("getId");
            animateAction = animateClass.getMethod("getAction");
            pickBlockClass = Class.forName("net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket");
            pickEntityClass = Class.forName("net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket");
            Class<?> pair = Class.forName("com.mojang.datafixers.util.Pair");
            pairOf = pair.getMethod("of", Object.class, Object.class);
            pairFirst = pair.getMethod("getFirst");
            pairSecond = pair.getMethod("getSecond");
            Class<?> craftStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            Class<?> nmsStack = Class.forName("net.minecraft.world.item.ItemStack");
            asBukkitCopy = craftStack.getMethod("asBukkitCopy", nmsStack);
            asNmsCopy = craftStack.getMethod("asNMSCopy", org.bukkit.inventory.ItemStack.class);
            viewReady = ready;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.warning("Held-gun stance unavailable (" + ex + "); others see guns held like items.");
        }
        try {
            entityDataClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
            entityDataId = entityDataClass.getMethod("id");
            entityDataValues = entityDataClass.getMethod("packedItems");
            entityDataCtor = entityDataClass.getConstructor(int.class, java.util.List.class);
            Class<?> dataValue = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
            dataValueId = dataValue.getMethod("id");
            dataValueValue = dataValue.getMethod("value");
            Class<?> accessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");
            dataValueCreate = dataValue.getMethod("create", accessor, Object.class);
            Field flags = Class.forName("net.minecraft.world.entity.LivingEntity").getDeclaredField("DATA_LIVING_ENTITY_FLAGS");
            flags.setAccessible(true);
            livingFlags = flags.get(null);
            livingFlagsId = (int) accessor.getMethod("id").invoke(livingFlags);
            dataReady = viewReady;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.warning("Entity data hooks unavailable (" + ex + "); others see a gun held low as a one-handed carry.");
        }
        try {
            soundClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSoundPacket");
            soundHolder = soundClass.getMethod("getSound");
            soundSource = soundClass.getMethod("getSource");
            holderValue = Class.forName("net.minecraft.core.Holder").getMethod("value");
            soundLocation = Class.forName("net.minecraft.sounds.SoundEvent").getMethod("location");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            soundClass = null;
            logger.warning("Sound packets unreadable (" + ex + "); other players' vanilla footsteps are heard as well.");
        }
        initSelf(logger);
    }

    public static boolean ready() {
        return ready;
    }

    private static @Nullable Method asVanilla;
    private static @Nullable Field containerMenu;
    private static @Nullable Field containerId;
    private static @Nullable Method menuType;
    private static @Nullable Method sendAllData;
    private static @Nullable Constructor<?> openScreenCtor;
    private static boolean retitleTried;

    /**
     * Gives the open container a new title without closing it: the open-screen packet again with the
     * same container id, then the contents (which a new screen starts without). Bukkit's own
     * InventoryView#setTitle only takes legacy text, which cannot carry the craftov:ui font that draws
     * the designed backgrounds. Returns false when the hooks are unavailable (the caller reopens).
     */
    public static boolean retitle(Player player, net.kyori.adventure.text.Component title) {
        if (!ready) {
            return false;
        }
        try {
            if (!retitleTried) {
                retitleTried = true;
                asVanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure")
                        .getMethod("asVanilla", net.kyori.adventure.text.Component.class);
                containerMenu = Class.forName("net.minecraft.world.entity.player.Player").getField("containerMenu");
                Class<?> menu = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu");
                containerId = menu.getField("containerId");
                menuType = menu.getMethod("getType");
                sendAllData = menu.getMethod("sendAllDataToRemote");
                openScreenCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundOpenScreenPacket").getConstructor(
                        int.class, Class.forName("net.minecraft.world.inventory.MenuType"),
                        Class.forName("net.minecraft.network.chat.Component"));
            }
            if (openScreenCtor == null) {
                return false;
            }
            Object handle = getHandle.invoke(player);
            Object menu = containerMenu.get(handle);
            Object packet = openScreenCtor.newInstance(containerId.getInt(menu), menuType.invoke(menu), asVanilla.invoke(null, title));
            sendPacket.invoke(playerConnection.get(handle), packet);
            sendAllData.invoke(menu);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            openScreenCtor = null;
            return false;
        }
    }

    private static @Nullable Object connection(Player player) throws ReflectiveOperationException {
        Object handle = getHandle.invoke(player);
        return playerConnection.get(handle);
    }

    // ------------------------------------------------------------------ capture cells

    private static @Nullable Channel channel(Player player) throws ReflectiveOperationException {
        Object listener = connection(player);
        return listener == null ? null : (Channel) channelField.get(networkManager.get(listener));
    }

    /**
     * Adds {@code handler} under {@code name} unless one of ours is already there. A handler of that
     * name that is not of {@code type} was left by an earlier load of the plugin (a /reload whose
     * shutdown could not remove it): it still feeds the old plugin's state, so it is replaced.
     */
    private static void install(Channel channel, String name, Class<? extends ChannelHandler> type, ChannelHandler handler) {
        ChannelPipeline pipeline = channel.pipeline();
        ChannelHandler existing = pipeline.get(name);
        if (type.isInstance(existing) || pipeline.get("packet_handler") == null) {
            return;
        }
        if (existing != null) {
            pipeline.replace(existing, name, handler);
        } else {
            pipeline.addBefore("packet_handler", name, handler);
        }
    }

    /**
     * Plugin shutdown: takes the handlers out of every player's connection and forgets the
     * middle-click handler. Left in place they would keep running the disabled plugin's code
     * after a /reload (firing would stop working until a reconnect, and a middle click would
     * schedule a task for a disabled plugin on a Netty thread).
     */
    public static void shutdown(Collection<? extends Player> players) {
        pickHandler = null;
        pickPlugin = null;
        if (!ready) {
            return;
        }
        for (Player player : players) {
            try {
                Channel channel = channel(player);
                if (channel == null) {
                    continue;
                }
                // Netty pipelines may be changed from any thread; removing the exact instance
                // never touches a handler a newer load of the plugin has put in its place.
                ChannelPipeline pipeline = channel.pipeline();
                ChannelHandler capture = pipeline.get(HANDLER);
                if (capture instanceof CaptureHandler) {
                    pipeline.remove(capture);
                }
                ChannelHandler view = pipeline.get(VIEW_HANDLER);
                if (view instanceof ViewHandler) {
                    pipeline.remove(view);
                }
                ChannelHandler self = pipeline.get(SELF_HANDLER);
                if (self instanceof SelfHandler) {
                    pipeline.remove(self);
                    // Back to the real walking speed: a zoom left behind would outlast the plugin.
                    onUpdateAbilities.invoke(getHandle.invoke(player));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // the connection is closing anyway
            }
        }
    }

    /**
     * Keeps the given cells (BlockPos.asLong keys) showing the capture block for this player, and
     * reports the player's dig starts and arm swings to {@code inputs}. The set is read on the network
     * thread: a cell must be in it before its capture block is sent, out of it before its real block
     * is, and never taken out and put back while it stays (see {@link CaptureHandler}).
     */
    public static void protectCells(Player player, Set<Long> cells, DigInputs inputs) {
        if (!ready) {
            return;
        }
        try {
            Channel channel = channel(player);
            if (channel != null) {
                install(channel, HANDLER, CaptureHandler.class, new CaptureHandler(cells, inputs));
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Without the filter TriggerService resends cells after right-clicks instead.
        }
    }

    /**
     * Rewrites real block updates at the capture cells back to the capture block, and follows every
     * chunk packet holding a capture cell with that cell's capture block.
     *
     * <p>The chunk is the one that matters right after a world change, a join or a long teleport: the
     * capture blocks go out the next tick, while the chunks they are in reach the client a few hundred
     * milliseconds later. The client drops a block update for a chunk it does not have, and the chunk
     * itself holds the real air, so until the crosshair moved to other cells the trigger was dead
     * (#141). Putting the cells back right behind the chunk, in the same outbound stream, lands them
     * after it whatever sent the chunk and however long it took. Since the set is read here at the
     * moment the chunk goes out, a cell the server is about to remove may still be put back; its real
     * block goes out after the chunk too (it was removed from the set before it was sent), so the client
     * ends up with the real block either way.
     */
    private static final class CaptureHandler extends ChannelDuplexHandler {
        private final Set<Long> cells;
        private final DigInputs inputs;

        CaptureHandler(Set<Long> cells, DigInputs inputs) {
            this.cells = cells;
            this.inputs = inputs;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (inputsReady) {
                try {
                    if (swingPacketClass.isInstance(msg)) {
                        inputs.swung();
                    } else if (actionPacketClass.isInstance(msg)
                            && "START_DESTROY_BLOCK".equals(((Enum<?>) actionPacketAction.invoke(msg)).name())) {
                        inputs.started((Long) asLong.invoke(actionPacketPos.invoke(msg)));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // the trigger falls back to its mute window for this packet
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!cells.isEmpty() && blockUpdateClass.isInstance(msg)) {
                Object pos = blockUpdatePos.invoke(msg);
                if (cells.contains((Long) asLong.invoke(pos))) {
                    msg = blockUpdateCtor.newInstance(pos, captureState);
                }
            }
            super.write(ctx, msg, promise);
            if (chunksReady && !cells.isEmpty() && chunkPacketClass.isInstance(msg)) {
                restoreCells(ctx, msg);
            }
        }

        /** The capture cells in the chunk just written, sent again behind it (the chunk holds air there). */
        private void restoreCells(ChannelHandlerContext ctx, Object chunk) {
            try {
                int x = (int) chunkPacketX.invoke(chunk);
                int z = (int) chunkPacketZ.invoke(chunk);
                for (long cell : cells) {
                    // BlockPos.asLong: x in the top 26 bits, z in the next 26, y in the low 12.
                    if ((int) (cell >> 38) >> 4 == x && (int) (cell << 26 >> 38) >> 4 == z) {
                        ctx.write(blockUpdateCtor.newInstance(blockPosOf.invoke(null, cell), captureState));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // The packet classes do not change while running, so one failure means every chunk
                // would throw: say so once and stop, which brings back the cells only when the
                // crosshair next moves to other ones (the behaviour before this was added).
                if (chunksReady) {
                    chunksReady = false;
                    Logger logger = chunkLogger;
                    if (logger != null) {
                        logger.warning("Re-sending capture cells behind chunks failed (" + ex
                                + "); right after a world change the trigger may not fire until the crosshair moves.");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ held-gun stance

    /** Routes everything this player is sent about other entities' hands through the rewriter. */
    public static void installView(Player player, ViewRewriter rewriter) {
        if (!viewReady) {
            return;
        }
        try {
            Channel channel = channel(player);
            if (channel != null) {
                install(channel, VIEW_HANDLER, ViewHandler.class, new ViewHandler(player, rewriter));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the stance is cosmetic
        }
    }

    /** Takes over middle clicks with a gun and rewrites what this player sees of others' hands. */
    private static final class ViewHandler extends ChannelDuplexHandler {
        private final Player player;
        private final int self;
        private final ViewRewriter rewriter;

        ViewHandler(Player player, ViewRewriter rewriter) {
            this.player = player;
            this.self = player.getEntityId();
            this.rewriter = rewriter;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            java.util.function.Consumer<Player> handler = pickHandler;
            org.bukkit.plugin.Plugin owner = pickPlugin;
            if (handler != null && owner != null && (pickBlockClass.isInstance(msg) || pickEntityClass.isInstance(msg))
                    && rewriter.takesPick(self)) {
                if (owner.isEnabled()) {
                    org.bukkit.Bukkit.getScheduler().runTask(owner, () -> handler.accept(player));
                }
                return;
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Object out;
            try {
                out = rewrite(msg, self, rewriter);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                out = msg;
            }
            if (out == null) {
                promise.setSuccess();
                return;
            }
            super.write(ctx, out, promise);
        }
    }

    /** The packet to send instead of msg (msg itself if unchanged), or null to drop it. */
    private static @Nullable Object rewrite(Object msg, int self, ViewRewriter rewriter) throws ReflectiveOperationException {
        if (equipmentClass.isInstance(msg)) {
            return rewriteEquipment(msg, self, rewriter);
        }
        if (dataReady && entityDataClass.isInstance(msg)) {
            return rewriteData(msg, self, rewriter);
        }
        if (animateClass.isInstance(msg)) {
            int id = (int) animateId.invoke(msg);
            // Action 0 is a main-hand swing; while it plays the client drops the crossbow-hold pose.
            return id != self && (int) animateAction.invoke(msg) == 0 && rewriter.raised(id) ? null : msg;
        }
        if (soundClass != null && soundClass.isInstance(msg)) {
            return dropped(msg, rewriter) ? null : msg;
        }
        if (bundleClass.isInstance(msg)) {
            java.util.List<Object> parts = new java.util.ArrayList<>();
            boolean changed = false;
            for (Object part : (Iterable<?>) bundleSubPackets.invoke(msg)) {
                Object replaced = equipmentClass.isInstance(part) ? rewriteEquipment(part, self, rewriter)
                        : dataReady && entityDataClass.isInstance(part) ? rewriteData(part, self, rewriter) : part;
                changed |= replaced != part;
                parts.add(replaced);
            }
            return changed ? bundleCtor.newInstance(parts) : msg;
        }
        return msg;
    }

    /** True if the rewriter keeps this sound from the listener (another player's vanilla footstep, say). */
    private static boolean dropped(Object packet, ViewRewriter rewriter) throws ReflectiveOperationException {
        Object event = holderValue.invoke(soundHolder.invoke(packet));
        return rewriter.dropsSound(String.valueOf(soundLocation.invoke(event)), ((Enum<?>) soundSource.invoke(packet)).name());
    }

    /**
     * Another entity's data with the living flags as the rewriter shows them. The packets
     * {@link #sendLivingFlags} builds itself pass as they are, and so does everything about the
     * player's own entity: their own client must never think they are using their gun, or it would
     * stop sending the trigger's dig packets.
     */
    private static Object rewriteData(Object packet, int self, ViewRewriter rewriter) throws ReflectiveOperationException {
        int id = (int) entityDataId.invoke(packet);
        java.util.List<?> values = (java.util.List<?>) entityDataValues.invoke(packet);
        if (id == self || values instanceof OwnValues) {
            return packet;
        }
        java.util.List<Object> out = null;
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if ((int) dataValueId.invoke(value) != livingFlagsId || !(dataValueValue.invoke(value) instanceof Byte real)) {
                continue;
            }
            byte shown = rewriter.livingFlags(id, real);
            if (shown == real) {
                continue;
            }
            if (out == null) {
                out = new java.util.ArrayList<>(values);
            }
            out.set(i, dataValueCreate.invoke(null, livingFlags, shown));
        }
        return out == null ? packet : entityDataCtor.newInstance(id, out);
    }

    /** True when the living flags of other entities can be rewritten and sent (see {@link #sendLivingFlags}). */
    public static boolean dataReady() {
        return dataReady;
    }

    /**
     * Sends the viewer the entity's living flags, each value in turn in one bundle (the client takes
     * them in order within a tick), as they are: the view handler does not rewrite these. Going from
     * not using to using an item restarts the client's count of how long it has been used, which is
     * what holds a crossbow-charge pose at its start.
     */
    public static void sendLivingFlags(Player viewer, int entityId, byte... sequence) {
        if (!dataReady || sequence.length == 0) {
            return;
        }
        try {
            java.util.List<Object> parts = new java.util.ArrayList<>(sequence.length);
            for (byte flags : sequence) {
                OwnValues values = new OwnValues(1);
                values.add(dataValueCreate.invoke(null, livingFlags, flags));
                parts.add(entityDataCtor.newInstance(entityId, values));
            }
            Object packet = parts.size() == 1 ? parts.get(0) : bundleCtor.newInstance(parts);
            sendPacket.invoke(connection(viewer), packet);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the stance is cosmetic
        }
    }

    private static Object rewriteEquipment(Object packet, int self, ViewRewriter rewriter) throws ReflectiveOperationException {
        int id = (int) equipmentEntity.invoke(packet);
        if (id == self) {
            return packet;
        }
        java.util.List<?> slots = (java.util.List<?>) equipmentSlots.invoke(packet);
        java.util.List<Object> out = null;
        for (int i = 0; i < slots.size(); i++) {
            Object pair = slots.get(i);
            if (!"MAINHAND".equals(((Enum<?>) pairFirst.invoke(pair)).name())) {
                continue;
            }
            org.bukkit.inventory.ItemStack real = (org.bukkit.inventory.ItemStack) asBukkitCopy.invoke(null, pairSecond.invoke(pair));
            // Empty hands are asked about too: a stance can put something in them (a reviver's bandage).
            org.bukkit.inventory.ItemStack shown = rewriter.mainHand(id, real);
            if (shown == null || shown.isEmpty() && real.isEmpty()) {
                continue;
            }
            if (out == null) {
                out = new java.util.ArrayList<>(slots);
            }
            out.set(i, pairOf.invoke(null, pairFirst.invoke(pair), asNmsCopy.invoke(null, shown)));
        }
        return out == null ? packet : equipmentCtor.newInstance(id, out);
    }

    // ------------------------------------------------------------------ own view: field of view and overlay helmet

    /**
     * What a player's own client is made to show, read on the network thread: the field-of-view
     * multiplier their walking speed asks for, and how the head slot carries a screen overlay.
     */
    public static final class SelfView {
        /** Field-of-view multiplier to show (1 = the hip view, below 1 zoomed in), as the client will see it. */
        public volatile double fov = 1.0;
        /**
         * The client's FOV Effects slider (0..1) the zoom is divided by: the one the player declared
         * where the server makes up for it, else 1 (see FovService).
         */
        public volatile double effects = 1.0;
        /** Movement speed without sprinting in the last attribute update this player was sent. */
        volatile double move = 0.1;
        /**
         * Turns the real head item (empty for none) into the client-only one carrying a screen overlay,
         * applied to every update of the player's own head slot; null while no overlay is up. Runs on
         * the network thread, on a copy.
         */
        volatile java.util.function.@Nullable UnaryOperator<org.bukkit.inventory.ItemStack> helmet;
        /**
         * What the player's own crafting grid shows (raw slots 0..4 of container 0, as NMS stacks), or
         * null for what is really there (always nothing). Client-only: the inventory screen's info cells.
         */
        volatile Object @Nullable [] crafting;
        /**
         * When the server last took health off this player without a hit (see {@link #quietHealthLoss}),
         * in {@link System#nanoTime}, or {@link #NO_MARK}. Set on the connection's event loop, so
         * it falls between the packets sent before the loss and those sent after, and taken by the
         * first health update after it, which is the one that carries the loss. One exception: with
         * Paper's anti-xray on, a chunk it has not finished holds every later packet in Paper's own
         * queue, so an update from before the loss can come after the mark and take it (the bleed
         * then tilts the view once). Anti-xray is off on Craftov's servers and by default.
         */
        final java.util.concurrent.atomic.AtomicLong quietLoss = new java.util.concurrent.atomic.AtomicLong(NO_MARK);
        /**
         * When the server last took health off this player without a hit, for a reason their client
         * is to show all the same (see {@link #loudHealthLoss}), or {@link #NO_MARK}: set and taken as
         * {@link #quietLoss} is.
         */
        final java.util.concurrent.atomic.AtomicLong loudLoss = new java.util.concurrent.atomic.AtomicLong(NO_MARK);
        /** The event loop of the connection {@link SelfHandler} rewrites, once it is installed. */
        volatile java.util.concurrent.@Nullable Executor loop;

        /** Whether a loss marked quiet is waiting for this health update; takes the mark either way. */
        boolean takeQuietLoss() {
            return take(quietLoss);
        }

        /** Whether a loss marked loud is waiting for this health update; takes the mark either way. */
        boolean takeLoudLoss() {
            return take(loudLoss);
        }

        private static boolean take(java.util.concurrent.atomic.AtomicLong mark) {
            long at = mark.getAndSet(NO_MARK);
            return at != NO_MARK && System.nanoTime() - at < MARK_NANOS;
        }

        /**
         * The walking speed to send: the client's multiplier is lerp(slider, 1, (move / walk + 1) / 2),
         * so walk = move / (2 m' - 1) with m' = 1 - (1 - fov) / slider shows exactly {@link #fov}. At
         * the hip (fov 1) walk is the movement speed itself, so armour, weight or aiming that slow the
         * player never change the view; sprinting still widens it as in vanilla, since the sprint
         * modifier is left out of {@link #move}.
         */
        float walkingSpeed() {
            double slider = effects;
            double target = slider >= 0.05 ? 1 - (1 - fov) / slider : fov;
            double denominator = 2 * target - 1;
            if (Math.abs(denominator) < 1e-3) {
                denominator = denominator < 0 ? -1e-3 : 1e-3;
            }
            return (float) (move / denominator);
        }
    }

    private static final String SELF_HANDLER = "craftov_self";
    private static boolean selfReady;
    private static @Nullable Class<?> attributesPacketClass;
    private static @Nullable Method attributesEntity;
    private static @Nullable Method attributesValues;
    private static @Nullable Constructor<?> attributesCtor;
    private static @Nullable Method snapshotAttribute;
    private static @Nullable Method snapshotBase;
    private static @Nullable Method snapshotModifiers;
    private static @Nullable Method modifierId;
    private static @Nullable Method modifierAmount;
    private static @Nullable Method modifierOperation;
    private static @Nullable Object movementSpeed;
    private static @Nullable Method getAttribute;
    private static @Nullable Class<?> abilitiesPacketClass;
    /** Abilities' no-argument constructor, its fields in packet order, and the packet's getters in the same order. */
    private static @Nullable Constructor<?> abilitiesNew;
    private static final String[] ABILITY_FIELDS = {"invulnerable", "flying", "mayfly", "instabuild", "flyingSpeed"};
    private static final String[] ABILITY_GETTERS = {"isInvulnerable", "isFlying", "canFly", "canInstabuild", "getFlyingSpeed"};
    private static final Field[] abilityFields = new Field[ABILITY_FIELDS.length];
    private static final Method[] abilityGetters = new Method[ABILITY_GETTERS.length];
    private static @Nullable Field walkingSpeedField;
    private static @Nullable Class<?> setSlotClass;
    private static @Nullable Method setSlotContainer;
    private static @Nullable Method setSlotState;
    private static @Nullable Method setSlotSlot;
    private static @Nullable Method setSlotItem;
    private static @Nullable Constructor<?> setSlotCtor;
    private static @Nullable Class<?> setContentClass;
    private static @Nullable Method setContentContainer;
    private static @Nullable Method setContentState;
    private static @Nullable Method setContentItems;
    private static @Nullable Method setContentCarried;
    private static @Nullable Constructor<?> setContentCtor;
    /** The helmet slot of the player's own inventory menu (container 0). */
    private static final int HEAD_SLOT = 5;
    private static final long NO_MARK = Long.MIN_VALUE;
    /**
     * How long a quiet or loud loss waits for the health update that carries it. That update goes out
     * within the tick and takes the mark whatever it shows; the bound is for a loss no update carries
     * (a heal in the same tick that makes up for it exactly), whose mark must not decide about a
     * later, unrelated loss: hide a drop that is not bleeding, or show one that is.
     */
    private static final long MARK_NANOS = 1_000_000_000L;
    private static boolean healthReady;
    private static @Nullable Class<?> healthPacketClass;
    private static @Nullable Method healthValue;
    private static @Nullable Method healthFood;
    private static @Nullable Method healthSaturation;
    private static @Nullable Constructor<?> healthCtor;
    private static @Nullable Class<?> damageEventClass;
    private static @Nullable Method damageEventEntity;
    private static @Nullable Class<?> hurtAnimationClass;
    private static @Nullable Method hurtAnimationEntity;

    private static void initSelf(Logger logger) {
        try {
            attributesPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket");
            attributesEntity = attributesPacketClass.getMethod("getEntityId");
            attributesValues = attributesPacketClass.getMethod("getValues");
            attributesCtor = attributesPacketClass.getConstructor(int.class, Collection.class);
            Class<?> snapshot = Class.forName("net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket$AttributeSnapshot");
            snapshotAttribute = snapshot.getMethod("attribute");
            snapshotBase = snapshot.getMethod("base");
            snapshotModifiers = snapshot.getMethod("modifiers");
            Class<?> modifier = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier");
            modifierId = modifier.getMethod("id");
            modifierAmount = modifier.getMethod("amount");
            modifierOperation = modifier.getMethod("operation");
            movementSpeed = Class.forName("net.minecraft.world.entity.ai.attributes.Attributes").getField("MOVEMENT_SPEED").get(null);
            getAttribute = Class.forName("net.minecraft.world.entity.LivingEntity")
                    .getMethod("getAttribute", Class.forName("net.minecraft.core.Holder"));
            abilitiesPacketClass = abilitiesPacketCtor.getDeclaringClass();
            abilitiesNew = abilitiesClass.getConstructor();
            for (int i = 0; i < ABILITY_FIELDS.length; i++) {
                abilityFields[i] = abilitiesClass.getField(ABILITY_FIELDS[i]);
                abilityGetters[i] = abilitiesPacketClass.getMethod(ABILITY_GETTERS[i]);
            }
            walkingSpeedField = abilitiesClass.getField("walkingSpeed");
            Class<?> stack = Class.forName("net.minecraft.world.item.ItemStack");
            setSlotClass = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket");
            setSlotContainer = setSlotClass.getMethod("getContainerId");
            setSlotState = setSlotClass.getMethod("getStateId");
            setSlotSlot = setSlotClass.getMethod("getSlot");
            setSlotItem = setSlotClass.getMethod("getItem");
            setSlotCtor = setSlotClass.getConstructor(int.class, int.class, int.class, stack);
            setContentClass = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket");
            setContentContainer = setContentClass.getMethod("containerId");
            setContentState = setContentClass.getMethod("stateId");
            setContentItems = setContentClass.getMethod("items");
            setContentCarried = setContentClass.getMethod("carriedItem");
            setContentCtor = setContentClass.getConstructor(int.class, int.class, java.util.List.class, stack);
            selfReady = ready && viewReady;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.warning("Own-view hooks unavailable (" + ex + "); aiming does not zoom and scopes show no picture.");
        }
        try {
            String game = "net.minecraft.network.protocol.game.";
            healthPacketClass = Class.forName(game + "ClientboundSetHealthPacket");
            healthValue = healthPacketClass.getMethod("getHealth");
            healthFood = healthPacketClass.getMethod("getFood");
            healthSaturation = healthPacketClass.getMethod("getSaturation");
            healthCtor = healthPacketClass.getConstructor(float.class, int.class, float.class);
            damageEventClass = Class.forName(game + "ClientboundDamageEventPacket");
            damageEventEntity = damageEventClass.getMethod("entityId");
            hurtAnimationClass = Class.forName(game + "ClientboundHurtAnimationPacket");
            hurtAnimationEntity = hurtAnimationClass.getMethod("id");
            healthReady = true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            logger.warning("Quiet health loss unavailable (" + ex + "); bleeding tilts the bleeder's own view as a hit does.");
        }
    }

    /**
     * The health this player is about to lose comes without a hit (a wound bleeding), so their own
     * client is not to show it: the vanilla client takes any drop of its health as being hurt, and
     * tilts the camera (and, in third person, flashes the player's own model red) just as a hit does.
     * Call it right before taking the health off. The client is then left believing the health it
     * had: nothing shows the client's own health (the resource pack hides vanilla's hearts; the HUD
     * reads the server's), and the next hit, loud loss ({@link #loudHealthLoss}) or death sets it right
     * again (see {@link SelfHandler}).
     */
    public static void quietHealthLoss(SelfView view) {
        mark(view, view.quietLoss);
    }

    /**
     * The health this player is about to lose comes without a hit, but their own client is to show
     * it as one (the view tilts): knocked down, a tripwire. Call it right before setting the health.
     * The health update that carries it shows the true health even when that is below the one the
     * client was left with, so a loss the server does not see (knocked down to 1 from a health a
     * bleed already took below it) still shows. Needed in a tick that may also bleed, too: the bleed
     * and this loss then reach the client in one health update, which a bleed's quiet mark alone
     * would keep from it.
     */
    public static void loudHealthLoss(SelfView view) {
        mark(view, view.loudLoss);
    }

    private static void mark(SelfView view, java.util.concurrent.atomic.AtomicLong mark) {
        java.util.concurrent.Executor loop = view.loop;
        if (!selfReady || !healthReady || loop == null) {
            return;
        }
        long at = System.nanoTime();
        try {
            // Marked in line with the packets: an update sent before the loss (a hit's, the tick
            // before) can still be on its way, and must not take the mark the loss's own update needs.
            loop.execute(() -> mark.set(at));
        } catch (RuntimeException ignored) {
            // the connection is closing: there is no client left to show the loss or keep it from
        }
    }

    /** True when each player's own view can be rewritten (zoom and the scope helmet, see {@link SelfView}). */
    public static boolean selfReady() {
        return selfReady;
    }

    /** Routes everything this player is sent about their own speed, abilities and helmet through {@code view}. */
    public static void installSelf(Player player, SelfView view) {
        if (!selfReady) {
            return;
        }
        try {
            Channel channel = channel(player);
            if (channel != null) {
                install(channel, SELF_HANDLER, SelfHandler.class, new SelfHandler(player, view));
                view.loop = channel.eventLoop();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // no zoom for this player
        }
    }

    /**
     * Sends the player their movement speed and abilities in one bundle, so the client applies both
     * in the same tick: on its way out the walking speed becomes the one {@code view} asks for (see
     * {@link SelfHandler}). False if it could not be sent.
     */
    public static boolean sendView(Player player) {
        if (!selfReady) {
            return false;
        }
        try {
            Object handle = getHandle.invoke(player);
            Object instance = getAttribute.invoke(handle, movementSpeed);
            java.util.List<Object> parts = new java.util.ArrayList<>(2);
            if (instance != null) {
                parts.add(attributesCtor.newInstance(player.getEntityId(), java.util.List.of(instance)));
            }
            parts.add(abilitiesPacketCtor.newInstance(getAbilities.invoke(handle)));
            sendPacket.invoke(playerConnection.get(handle), bundleCtor.newInstance(parts));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Dresses every update of the player's own head slot with {@code helmet} (the real item in, the
     * one to show out) until cleared with null.
     */
    public static void selfHelmet(SelfView view, java.util.function.@Nullable UnaryOperator<org.bukkit.inventory.ItemStack> helmet) {
        view.helmet = selfReady ? helmet : null;
    }

    /** The crafting grid's raw slots 0..4 in the player's own inventory menu. */
    public static final int CRAFTING_SLOTS = 5;

    /**
     * Shows {@code shown} (raw slots 0..4: result, then the 2x2 grid) in the player's own crafting
     * grid from the next update on, or the real, empty grid again for null. The stacks exist only in
     * packets: the server's grid stays empty, so nothing shown there can ever be taken out or be
     * dropped back into the inventory when the screen closes. Returns false when the own view cannot
     * be rewritten on this server.
     */
    public static boolean selfCrafting(SelfView view, org.bukkit.inventory.@Nullable ItemStack @Nullable [] shown) {
        if (!selfReady) {
            view.crafting = null;
            return false;
        }
        if (shown == null) {
            view.crafting = null;
            return true;
        }
        try {
            Object[] nms = new Object[CRAFTING_SLOTS];
            for (int i = 0; i < CRAFTING_SLOTS; i++) {
                org.bukkit.inventory.ItemStack stack = i < shown.length ? shown[i] : null;
                nms[i] = asNmsCopy.invoke(null, stack == null ? org.bukkit.inventory.ItemStack.empty() : stack);
            }
            view.crafting = nms;
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            view.crafting = null;
            return false;
        }
    }

    /**
     * Sends the player their own inventory menu's {@code slots} as the server has them, so the own-view
     * rewriting dresses them again (the crafting grid's info cells after a click the client already
     * acted out, or after the screen closed). Uses the menu's current state id, so no resync follows.
     */
    public static void resendOwnSlots(Player player, int... slots) {
        if (!selfReady) {
            return;
        }
        try {
            Object handle = getHandle.invoke(player);
            if (inventoryMenu == null) {
                inventoryMenu = Class.forName("net.minecraft.world.entity.player.Player").getField("inventoryMenu");
                menuStateId = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu").getMethod("getStateId");
                menuSlot = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu").getMethod("getSlot", int.class);
                slotItem = Class.forName("net.minecraft.world.inventory.Slot").getMethod("getItem");
                itemCopy = Class.forName("net.minecraft.world.item.ItemStack").getMethod("copy");
            }
            Object menu = inventoryMenu.get(handle);
            int state = (int) menuStateId.invoke(menu);
            Object connection = playerConnection.get(handle);
            for (int slot : slots) {
                Object item = itemCopy.invoke(slotItem.invoke(menuSlot.invoke(menu, slot)));
                sendPacket.invoke(connection, setSlotCtor.newInstance(0, state, slot, item));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the cells come back with the next inventory update
        }
    }

    private static @Nullable Field inventoryMenu;
    private static @Nullable Method menuStateId;
    private static @Nullable Method menuSlot;
    private static @Nullable Method slotItem;
    private static @Nullable Method itemCopy;

    /**
     * Rewrites what the player is told about themselves: every abilities packet carries the walking
     * speed {@link SelfView} asks for, every update of their movement speed is followed in the same
     * bundle by such an abilities packet (the server sends one whenever armour, weight or aiming
     * change the speed, and a gamemode change or respawn resends abilities), and while a screen
     * overlay is up every update of their head slot carries it (a headshot that damages the helmet, a
     * helmet swap or an inventory resync would otherwise take a scope picture away until the next aim).
     * A loss of health marked quiet ({@link #quietHealthLoss}) is kept from their health updates,
     * unless one marked loud ({@link #loudHealthLoss}) came with it.
     */
    private static final class SelfHandler extends ChannelDuplexHandler {
        private final Player player;
        private final int self;
        private final SelfView view;
        // Only touched on the channel's event loop, as every write is.
        /** The health in the last health update the server sent, and the one the client was given (NaN: none yet). */
        private float sentHealth = Float.NaN;
        private float shownHealth = Float.NaN;
        /** The client was sent a damage event or hurt animation of the player since the last health update. */
        private boolean hurt;

        SelfHandler(Player player, SelfView view) {
            this.player = player;
            this.self = player.getEntityId();
            this.view = view;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Object out = msg;
            try {
                if (bundleClass.isInstance(msg)) {
                    java.util.List<Object> parts = new java.util.ArrayList<>();
                    boolean changed = rewriteAll((Iterable<?>) bundleSubPackets.invoke(msg), parts);
                    if (changed) {
                        out = bundleCtor.newInstance(parts);
                    }
                } else if (concerns(msg)) {
                    java.util.List<Object> parts = new java.util.ArrayList<>(2);
                    if (rewriteAll(java.util.List.of(msg), parts)) {
                        out = parts.size() == 1 ? parts.get(0) : bundleCtor.newInstance(parts);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                out = msg;
            }
            super.write(ctx, out, promise);
        }

        /** Whether a packet on its own may need rewriting: most do not, and pass without any allocation. */
        private boolean concerns(Object msg) {
            return attributesPacketClass.isInstance(msg) || abilitiesPacketClass.isInstance(msg)
                    || view.helmet != null && (setSlotClass.isInstance(msg) || setContentClass.isInstance(msg) || equipmentClass.isInstance(msg))
                    || view.crafting != null && (setSlotClass.isInstance(msg) || setContentClass.isInstance(msg))
                    || healthReady && (healthPacketClass.isInstance(msg) || damageEventClass.isInstance(msg) || hurtAnimationClass.isInstance(msg));
        }

        /** Notes a damage event or hurt animation of the player themselves: a hit, which shows its loss. */
        private void noteHurt(Object part) throws ReflectiveOperationException {
            if (!healthReady) {
                return;
            }
            if (damageEventClass.isInstance(part) && (int) damageEventEntity.invoke(part) == self
                    || hurtAnimationClass.isInstance(part) && (int) hurtAnimationEntity.invoke(part) == self) {
                hurt = true;
            }
        }

        /**
         * The health update as the player's own client is to get it. A loss marked quiet is left out,
         * unless a hit reached the client since the last update (the hit tilts the view anyway, and the
         * client then learns the true health) or a loss marked loud came in the same update (being
         * knocked down in the tick the player bleeds: the drop is to show, and a hit's damage event
         * does not say so, since the lethal hit was cancelled). The client keeps the health it
         * believes until the server's is higher again, or a loss that is not quiet, a hit or death
         * comes: so gains never show it a drop either, even when a heal leaves the health below the
         * one it was left with. A loss marked loud is the exception: its update shows the true health
         * whatever the client believes, so a knock-down that the server sees as no loss at all (a
         * bleed had already taken the health below the 1 it sets, or onto it) is a drop from the
         * higher health the client was left with before the bleed. Food and saturation always pass
         * as they are: they decide whether the client lets the player sprint.
         * <p>
         * The first update after a quiet or loud loss carries it, whatever else came in the same tick
         * (a hit, a heal, death), so that update takes the mark even when it changes nothing. A quiet
         * mark left for a later update would hide the next drop that is not quiet, being knocked down,
         * say; a loud one would show the next bleed.
         */
        private Object withHealth(Object packet) throws ReflectiveOperationException {
            float health = (float) healthValue.invoke(packet);
            float sent = sentHealth;
            float shown = shownHealth;
            boolean hit = hurt;
            boolean quiet = view.takeQuietLoss();
            boolean loud = view.takeLoudLoss();
            sentHealth = health;
            hurt = false;
            if (Float.isNaN(sent) || health <= 0) {
                shownHealth = health;
                return packet;
            }
            float show;
            if (loud) {
                show = health;
            } else if (health < sent) {
                show = quiet && !hit ? shown : health;
            } else {
                show = Math.max(health, shown);
            }
            shownHealth = show;
            return show == health ? packet
                    : healthCtor.newInstance(show, (int) healthFood.invoke(packet), (float) healthSaturation.invoke(packet));
        }

        /** Copies {@code in} to {@code out} rewritten; true if anything changed. */
        private boolean rewriteAll(Iterable<?> in, java.util.List<Object> out) throws ReflectiveOperationException {
            boolean changed = false;
            boolean speedSent = false;
            boolean abilitiesSent = false;
            for (Object part : in) {
                Object replaced = part;
                noteHurt(part);
                if (healthReady && healthPacketClass.isInstance(part)) {
                    replaced = withHealth(part);
                } else if (attributesPacketClass.isInstance(part) && (int) attributesEntity.invoke(part) == self) {
                    Double move = walkSpeed(part);
                    if (move != null) {
                        view.move = move;
                        speedSent = true;
                    }
                } else if (abilitiesPacketClass.isInstance(part)) {
                    replaced = withWalkingSpeed(part);
                    abilitiesSent = true;
                } else {
                    java.util.function.UnaryOperator<org.bukkit.inventory.ItemStack> dress = view.helmet;
                    if (dress != null) {
                        replaced = withHelmet(part, dress);
                    }
                    Object[] crafting = view.crafting;
                    if (crafting != null) {
                        replaced = withCrafting(replaced, crafting);
                    }
                }
                changed |= replaced != part;
                out.add(replaced);
            }
            if (speedSent && !abilitiesSent) {
                out.add(abilitiesPacketCtor.newInstance(getAbilities.invoke(getHandle.invoke(player))));
                out.set(out.size() - 1, withWalkingSpeed(out.get(out.size() - 1)));
                changed = true;
            }
            return changed;
        }

        /** The same abilities packet with the walking speed {@link SelfView} asks for. */
        private Object withWalkingSpeed(Object packet) throws ReflectiveOperationException {
            Object abilities = abilitiesNew.newInstance();
            for (int i = 0; i < abilityFields.length; i++) {
                abilityFields[i].set(abilities, abilityGetters[i].invoke(packet));
            }
            walkingSpeedField.setFloat(abilities, view.walkingSpeed());
            return abilitiesPacketCtor.newInstance(abilities);
        }

        /** The NMS stack {@code real} as {@code dress} shows it. */
        private static Object dressed(Object real, java.util.function.UnaryOperator<org.bukkit.inventory.ItemStack> dress)
                throws ReflectiveOperationException {
            return asNmsCopy.invoke(null, dress.apply((org.bukkit.inventory.ItemStack) asBukkitCopy.invoke(null, real)));
        }

        /** The same slot or content update of container 0 with the crafting grid showing {@code shown}. */
        private static Object withCrafting(Object part, Object[] shown) throws ReflectiveOperationException {
            if (setSlotClass.isInstance(part)) {
                int slot = (int) setSlotSlot.invoke(part);
                if ((int) setSlotContainer.invoke(part) == 0 && slot >= 0 && slot < shown.length) {
                    return setSlotCtor.newInstance(0, setSlotState.invoke(part), slot, shown[slot]);
                }
                return part;
            }
            if (setContentClass.isInstance(part)) {
                java.util.List<?> items = (java.util.List<?>) setContentItems.invoke(part);
                if ((int) setContentContainer.invoke(part) != 0 || items.size() < shown.length) {
                    return part;
                }
                java.util.List<Object> copy = new java.util.ArrayList<>(items);
                for (int i = 0; i < shown.length; i++) {
                    copy.set(i, shown[i]);
                }
                return setContentCtor.newInstance(0, setContentState.invoke(part), copy, setContentCarried.invoke(part));
            }
            return part;
        }

        private Object withHelmet(Object part, java.util.function.UnaryOperator<org.bukkit.inventory.ItemStack> dress)
                throws ReflectiveOperationException {
            if (setSlotClass.isInstance(part)) {
                if ((int) setSlotContainer.invoke(part) == 0 && (int) setSlotSlot.invoke(part) == HEAD_SLOT) {
                    return setSlotCtor.newInstance(0, setSlotState.invoke(part), HEAD_SLOT, dressed(setSlotItem.invoke(part), dress));
                }
                return part;
            }
            if (setContentClass.isInstance(part)) {
                java.util.List<?> items = (java.util.List<?>) setContentItems.invoke(part);
                if ((int) setContentContainer.invoke(part) != 0 || items.size() <= HEAD_SLOT) {
                    return part;
                }
                java.util.List<Object> copy = new java.util.ArrayList<>(items);
                copy.set(HEAD_SLOT, dressed(items.get(HEAD_SLOT), dress));
                return setContentCtor.newInstance(0, setContentState.invoke(part), copy, setContentCarried.invoke(part));
            }
            if (equipmentClass.isInstance(part) && (int) equipmentEntity.invoke(part) == self) {
                java.util.List<?> slots = (java.util.List<?>) equipmentSlots.invoke(part);
                java.util.List<Object> copy = null;
                for (int i = 0; i < slots.size(); i++) {
                    Object pair = slots.get(i);
                    if ("HEAD".equals(((Enum<?>) pairFirst.invoke(pair)).name())) {
                        if (copy == null) {
                            copy = new java.util.ArrayList<>(slots);
                        }
                        copy.set(i, pairOf.invoke(null, pairFirst.invoke(pair), dressed(pairSecond.invoke(pair), dress)));
                    }
                }
                return copy == null ? part : equipmentCtor.newInstance(self, copy);
            }
            return part;
        }
    }

    /**
     * The movement speed in an attribute update, sprinting left out, or null if the update is not
     * about the movement speed: the base plus every modifier, in vanilla's order (additions, then
     * multiples of the base, then multiples of the total), clamped to the attribute's 0..1024.
     */
    private static @Nullable Double walkSpeed(Object packet) throws ReflectiveOperationException {
        for (Object snapshot : (java.util.List<?>) attributesValues.invoke(packet)) {
            if (snapshotAttribute.invoke(snapshot) != movementSpeed) {
                continue;
            }
            double base = (double) snapshotBase.invoke(snapshot);
            double scaled = 0;
            double total = 1;
            for (Object modifier : (Collection<?>) snapshotModifiers.invoke(snapshot)) {
                if ("minecraft:sprinting".equals(String.valueOf(modifierId.invoke(modifier)))) {
                    continue;
                }
                double amount = (double) modifierAmount.invoke(modifier);
                switch (((Enum<?>) modifierOperation.invoke(modifier)).name()) {
                    case "ADD_VALUE" -> base += amount;
                    case "ADD_MULTIPLIED_BASE" -> scaled += amount;
                    default -> total *= 1 + amount;
                }
            }
            return Math.max(0, Math.min(1024, (base + base * scaled) * total));
        }
        return null;
    }

    // Shares containerMenu with retitle above, which may have looked it up first.
    private static @Nullable Method broadcastChanges;
    private static boolean syncUnavailable;

    /**
     * Sends the changes to the player's open inventory now rather than at the end of their tick, so a
     * packet sent right after (a cooldown, say) reaches the client after them. Does nothing if the
     * server's internals cannot be reached; the changes then go out at the end of the tick as usual.
     */
    public static void syncInventory(Player player) {
        if (!ready || syncUnavailable) {
            return;
        }
        try {
            Object handle = getHandle.invoke(player);
            if (broadcastChanges == null) {
                containerMenu = Class.forName("net.minecraft.world.entity.player.Player").getField("containerMenu");
                broadcastChanges = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu").getMethod("broadcastChanges");
            }
            broadcastChanges.invoke(containerMenu.get(handle));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            syncUnavailable = true;
        }
    }
}
