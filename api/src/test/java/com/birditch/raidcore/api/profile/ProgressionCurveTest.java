package com.birditch.raidcore.api.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProgressionCurveTest {

  @Test
  void mapsCumulativeExperienceToAStableLevel() {
    assertEquals(1, ProgressionCurve.levelForExperience(0));
    assertEquals(1, ProgressionCurve.levelForExperience(99));
    assertEquals(2, ProgressionCurve.levelForExperience(100));
    assertEquals(2, ProgressionCurve.levelForExperience(399));
    assertEquals(3, ProgressionCurve.levelForExperience(400));
  }

  @Test
  void reportsExperienceRemainingUntilTheNextLevel() {
    assertEquals(100, ProgressionCurve.experienceToNextLevel(0));
    assertEquals(1, ProgressionCurve.experienceToNextLevel(99));
    assertEquals(300, ProgressionCurve.experienceToNextLevel(100));
    assertEquals(0, ProgressionCurve.experienceToNextLevel(
        ProgressionCurve.experienceForLevel(ProgressionCurve.MAX_LEVEL)));
  }
}
