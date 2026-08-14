package com.birditch.escapefromcraftov.game.plugin.medical;

/** Persistent (for the current raid) trauma applied by real combat damage. */
public enum MedicalCondition {
  BLEEDING("流血"),
  FRACTURE("骨折"),
  PAIN("疼痛");

  private final String displayName;

  MedicalCondition(String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
