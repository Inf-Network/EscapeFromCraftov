package net.infnetwork.snowball.craftov.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Party {
    public UUID leader;
    public final List<UUID> members = new ArrayList<>();
    /**
     * Each member's name as they joined. A member who dropped out of a raid stays in the party
     * until their reconnect window ends, offline, and still has to be named in the list, in the
     * reasons matchmaking gives and by /cv party kick.
     */
    private final Map<UUID, String> names = new HashMap<>();

    public Party(UUID leader, String leaderName) {
        this.leader = leader;
        add(leader, leaderName);
    }

    public int size() {
        return members.size();
    }

    public void add(UUID uuid, String name) {
        members.add(uuid);
        names.put(uuid, name);
    }

    public void remove(UUID uuid) {
        members.remove(uuid);
        names.remove(uuid);
    }

    /** The member's name, offline or not. */
    public String name(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            return player.getName();
        }
        String name = names.get(uuid);
        if (name == null) {
            name = Bukkit.getOfflinePlayer(uuid).getName();
        }
        return name == null ? "?" : name;
    }
}
