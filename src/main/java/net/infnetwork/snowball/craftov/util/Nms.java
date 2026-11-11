package net.infnetwork.snowball.craftov.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The two places Craftov reaches past the Bukkit API, by reflection on the Mojang-mapped Paper
 * runtime. Both fail soft: if a name ever changes, the feature degrades instead of breaking.
 *
 * <ul>
 *   <li>Trigger capture cells: vanilla answers every right-click on a block with real block
 *   updates, which would erase the client-only capture cells in front of a gun. A small outbound
 *   handler rewrites those updates back to the capture block.</li>
 *   <li>Scope zoom: the client's FOV multiplier is {@code (moveSpeed / abilities.walkingSpeed + 1) / 2},
 *   so sending a (negative) walking speed in a private abilities packet zooms the view without
 *   touching how fast the player actually moves.</li>
 *   <li>Held-gun stance: other players' equipment packets carry a gun as a charged crossbow so
 *   their client draws the two-handed aiming pose (see {@code StanceService}).</li>
 * </ul>
 */
public final class Nms {

    private static final String HANDLER = "craftov_capture";
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
    private static @Nullable Object cobwebState;
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

    /** Decides what other players see in an entity's main hand. Called on Netty threads. */
    public interface ViewRewriter {
        /** True while the entity holds a gun up: its arm-swing animations are not sent to others. */
        boolean raised(int entityId);

        /** The main-hand item to show instead, or null to show the real one. */
        org.bukkit.inventory.@Nullable ItemStack mainHand(int entityId, org.bukkit.inventory.ItemStack real);
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
            Object craftData = Material.COBWEB.createBlockData();
            cobwebState = craftData.getClass().getMethod("getState").invoke(craftData);
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

    /** Keeps the given cells (BlockPos.asLong keys) showing the capture block for this player. */
    public static void protectCells(Player player, Set<Long> cells) {
        if (!ready) {
            return;
        }
        try {
            Object listener = connection(player);
            if (listener == null) {
                return;
            }
            Channel channel = (Channel) channelField.get(networkManager.get(listener));
            if (channel == null || channel.pipeline().get(HANDLER) != null || channel.pipeline().get("packet_handler") == null) {
                return;
            }
            channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelDuplexHandler() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    if (!cells.isEmpty() && blockUpdateClass.isInstance(msg)) {
                        Object pos = blockUpdatePos.invoke(msg);
                        if (cells.contains((Long) asLong.invoke(pos))) {
                            msg = blockUpdateCtor.newInstance(pos, cobwebState);
                        }
                    }
                    super.write(ctx, msg, promise);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Without the filter TriggerService resends cells after right-clicks instead.
        }
    }

    // ------------------------------------------------------------------ held-gun stance

    /** Routes everything this player is sent about other entities' hands through the rewriter. */
    public static void installView(Player player, ViewRewriter rewriter) {
        if (!viewReady) {
            return;
        }
        int self = player.getEntityId();
        try {
            Object listener = connection(player);
            if (listener == null) {
                return;
            }
            Channel channel = (Channel) channelField.get(networkManager.get(listener));
            if (channel == null || channel.pipeline().get(VIEW_HANDLER) != null || channel.pipeline().get("packet_handler") == null) {
                return;
            }
            channel.pipeline().addBefore("packet_handler", VIEW_HANDLER, new ChannelDuplexHandler() {
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
            });
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the stance is cosmetic
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
