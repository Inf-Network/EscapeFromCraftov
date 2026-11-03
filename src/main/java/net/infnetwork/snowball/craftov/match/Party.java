package net.infnetwork.snowball.craftov.match;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Party {
    public UUID leader;
    public final List<UUID> members = new ArrayList<>();

    public Party(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    public int size() {
        return members.size();
    }
}
