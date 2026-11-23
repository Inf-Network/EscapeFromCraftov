package net.infnetwork.snowball.craftov.economy;

import net.infnetwork.snowball.craftov.profile.Profile;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The placeholder 点券 balance (#106): {@link Profile#points}, until the server's global currency
 * registers its own {@link PointsBank}. Offline players are read from disk and changed through
 * {@link ProfileService#edit}, like any other offline change.
 */
public final class ProfilePoints implements PointsBank {

    private final ProfileService profiles;

    public ProfilePoints(ProfileService profiles) {
        this.profiles = profiles;
    }

    @Override
    public long balance(UUID player) {
        Profile profile = profiles.peek(player);
        return profile == null ? -1 : profile.points;
    }

    @Override
    public @Nullable String set(UUID player, long amount) {
        if (amount < 0) {
            return "点券不能是负数";
        }
        if (!profiles.canEdit(player)) {
            return "这个玩家的档案现在无法修改";
        }
        profiles.edit(player, () -> "points = " + amount, p -> p.points = amount);
        return null;
    }

    @Override
    public @Nullable String take(UUID player, long amount, String reason) {
        Profile profile = profiles.peek(player);
        if (profile == null || !profiles.canEdit(player)) {
            return "点券余额暂时无法读取";
        }
        if (profile.points < amount) {
            return "点券不足（需要 " + amount + "，余额 " + profile.points + "）";
        }
        profiles.edit(player, () -> "points -" + amount + " (" + reason + ")", p -> p.points -= amount);
        return null;
    }
}
