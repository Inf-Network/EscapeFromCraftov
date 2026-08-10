package com.birditch.raidcore.api.profile;

import com.birditch.raidcore.common.check.Preconditions;

/// Deterministic cumulative XP curve used by persistence, HUD and commands.
public final class ProgressionCurve {

    public static final int MAX_LEVEL = 100;

    private ProgressionCurve() {}

    /// Total XP required to reach `level`: 0, 100, 400, 900, ...
    public static long experienceForLevel(int level) {
        Preconditions.checkArgument(level >= 1 && level <= MAX_LEVEL,
                "level must be 1.." + MAX_LEVEL);
        long step = level - 1L;
        return Math.multiplyExact(100L, Math.multiplyExact(step, step));
    }

    public static int levelForExperience(long experience) {
        Preconditions.checkArgument(experience >= 0, "experience must not be negative");
        for (int level = MAX_LEVEL; level >= 1; level--) {
            if (experience >= experienceForLevel(level)) return level;
        }
        return 1;
    }

    public static long experienceToNextLevel(long experience) {
        int level = levelForExperience(experience);
        return level == MAX_LEVEL ? 0 : experienceForLevel(level + 1) - experience;
    }
}
