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
 *   handler rewrites those updates back to the capture block. The same handler reports, in the
 *   order they arrive, the client's dig starts and arm swings (see {@link DigInputs}).</li>
 *   <li>Scope zoom: the client's FOV multiplier is {@code (moveSpeed / abilities.walkingSpeed + 1) / 2},
 *   so sending a (negative) walking speed in a private abilities packet zooms the view without
 *   touching how fast the player actually moves.</li>
 *   <li>Held-gun stance: other players' equipment packets carry a gun as a charged crossbow so
 *   their client draws the two-handed aiming pose (see {@code StanceService}).</li>
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

    /** Decides what other players see in an entity's main hand. Called on Netty threads. */
    public interface ViewRewriter {
        /** True while the entity holds a gun up: its arm-swing animations are not sent to others. */
        boolean raised(int entityId);

        /** The main-hand item to show instead, or null to show the real one. */
        org.bukkit.inventory.@Nullable ItemStack mainHand(int entityId, org.bukkit.inventory.ItemStack real);

        /** True if this player's middle click (pick block/entity) should be taken over: they hold a gun. */
        default boolean takesPick(int entityId) {
            return false;
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
                    + "scopes use movement-based zoom.");
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
    }

    public static boolean ready() {
        return ready;
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
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // the connection is closing anyway
            }
        }
    }

    /** True if {@link #protectCells} also reports dig starts and swings to its {@link DigInputs}. */
    public static boolean readsInputs() {
        return inputsReady;
    }

    /**
     * Keeps the given cells (BlockPos.asLong keys) showing the capture block for this player, and
     * reports the player's dig starts and arm swings to {@code inputs}.
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

    /** Rewrites real block updates at the capture cells back to the capture block. */
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
        if (animateClass.isInstance(msg)) {
            int id = (int) animateId.invoke(msg);
            // Action 0 is a main-hand swing; while it plays the client drops the crossbow-hold pose.
            return id != self && (int) animateAction.invoke(msg) == 0 && rewriter.raised(id) ? null : msg;
        }
        if (bundleClass.isInstance(msg)) {
            java.util.List<Object> parts = new java.util.ArrayList<>();
            boolean changed = false;
            for (Object part : (Iterable<?>) bundleSubPackets.invoke(msg)) {
                Object replaced = equipmentClass.isInstance(part) ? rewriteEquipment(part, self, rewriter) : part;
                changed |= replaced != part;
                parts.add(replaced);
            }
            return changed ? bundleCtor.newInstance(parts) : msg;
        }
        return msg;
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
            org.bukkit.inventory.ItemStack shown = real.isEmpty() ? null : rewriter.mainHand(id, real);
            if (shown == null) {
                continue;
            }
            if (out == null) {
                out = new java.util.ArrayList<>(slots);
            }
            out.set(i, pairOf.invoke(null, pairFirst.invoke(pair), asNmsCopy.invoke(null, shown)));
        }
        return out == null ? packet : equipmentCtor.newInstance(id, out);
    }

    // ------------------------------------------------------------------ scope zoom

    /**
     * Shows the player a field of view of {@code fovMultiplier} times normal (client FOV effects
     * must be enabled), given their current movement-speed attribute value.
     */
    public static boolean zoom(Player player, double fovMultiplier, double moveSpeed) {
        if (!ready) {
            return false;
        }
        try {
            Object handle = getHandle.invoke(player);
            Object real = getAbilities.invoke(handle);
            Object copy = abilitiesClass.getConstructor().newInstance();
            for (String name : new String[]{"invulnerable", "flying", "mayfly", "instabuild", "mayBuild", "flyingSpeed"}) {
                Field f = abilitiesClass.getField(name);
                f.set(copy, f.get(real));
            }
            // fov = (move / walk + 1) / 2  =>  walk = move / (2 * fov - 1)
            double denominator = 2 * fovMultiplier - 1;
            if (Math.abs(denominator) < 0.02) {
                denominator = -0.02;
            }
            abilitiesClass.getField("walkingSpeed").setFloat(copy, (float) (moveSpeed / denominator));
            sendPacket.invoke(playerConnection.get(handle), abilitiesPacketCtor.newInstance(copy));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** Sends the player's real abilities again, ending any zoom. */
    public static void unzoom(Player player) {
        if (!ready) {
            return;
        }
        try {
            onUpdateAbilities.invoke(getHandle.invoke(player));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // nothing to undo
        }
    }
}
