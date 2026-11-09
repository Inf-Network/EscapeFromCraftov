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
 * </ul>
 */
public final class Nms {

    private static final String HANDLER = "craftov_capture";

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
