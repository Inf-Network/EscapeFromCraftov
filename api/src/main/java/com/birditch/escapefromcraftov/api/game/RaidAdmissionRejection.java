package com.birditch.escapefromcraftov.api.game;

/** Stable, typed reasons why a player was not admitted to an action-tier raid. */
public enum RaidAdmissionRejection {
  PROFILE_NOT_FOUND,
  UNKNOWN_LOADOUT_ITEM,
  INVALID_LOADOUT_DATA,
  LOADOUT_VALUE_OVERFLOW,
  MINIMUM_LOADOUT_NOT_MET,
  INSUFFICIENT_FUNDS,
  INVALID_ACTION_PROFILE,
  ACTION_PROFILE_MISMATCH,
  ACTIVE_CRAFTOV_EXISTS,
  CRAFTOV_CLOSED,
  ADMISSION_STATE_CONFLICT
}
