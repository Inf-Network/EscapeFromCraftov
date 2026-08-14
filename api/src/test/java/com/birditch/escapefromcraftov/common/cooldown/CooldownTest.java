package com.birditch.escapefromcraftov.common.cooldown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CooldownTest {

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    private MutableTimeSource time;
    private Cooldown<String> cooldown;

    @BeforeEach
    void setUp() {
        time = new MutableTimeSource(0L);
        cooldown = new Cooldown<>(TEN_SECONDS, time);
    }

    @Test
    void firstUseSucceedsAndStartsTheCooldown() {
        assertTrue(cooldown.tryUse("player"));
        assertFalse(cooldown.tryUse("player"));
    }

    @Test
    void becomesReadyAgainOnceTheDurationElapsed() {
        assertTrue(cooldown.tryUse("player"));
        time.advance(TEN_SECONDS);
        assertTrue(cooldown.tryUse("player"));
    }

    @Test
    void staysCoolingJustBeforeExpiry() {
        assertTrue(cooldown.tryUse("player"));
        time.advance(TEN_SECONDS.minusMillis(1));
        assertFalse(cooldown.tryUse("player"));
    }

    @Test
    void keysAreIndependent() {
        assertTrue(cooldown.tryUse("alpha"));
        assertTrue(cooldown.tryUse("beta"));
    }

    @Test
    @DisplayName("remaining is ZERO for unknown keys and counts down while cooling")
    void remainingCountsDown() {
        assertEquals(Duration.ZERO, cooldown.remaining("player"));
        cooldown.tryUse("player");
        assertEquals(TEN_SECONDS, cooldown.remaining("player"));
        time.advance(Duration.ofSeconds(4));
        assertEquals(Duration.ofSeconds(6), cooldown.remaining("player"));
        time.advance(Duration.ofSeconds(6));
        assertEquals(Duration.ZERO, cooldown.remaining("player"));
    }

    @Test
    void clearMakesTheKeyImmediatelyReady() {
        cooldown.tryUse("player");
        cooldown.clear("player");
        assertEquals(Duration.ZERO, cooldown.remaining("player"));
        assertTrue(cooldown.tryUse("player"));
    }

    @Test
    void zeroDurationIsAlwaysReady() {
        Cooldown<String> instant = new Cooldown<>(Duration.ZERO, time);
        assertTrue(instant.tryUse("player"));
        assertTrue(instant.tryUse("player"));
    }

    @Test
    void rejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new Cooldown<String>(Duration.ofMillis(-1), time));
    }

    @Test
    void rejectsNullKeys() {
        assertThrows(NullPointerException.class, () -> cooldown.tryUse(null));
        assertThrows(NullPointerException.class, () -> cooldown.remaining(null));
        assertThrows(NullPointerException.class, () -> cooldown.clear(null));
    }
}
