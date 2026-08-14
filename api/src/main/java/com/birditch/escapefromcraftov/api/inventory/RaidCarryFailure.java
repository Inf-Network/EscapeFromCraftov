package com.birditch.escapefromcraftov.api.inventory;

/** Stable rejection reason for an authoritative cargo mutation. */
public enum RaidCarryFailure {
  SLOT_CAPACITY,
  WEIGHT_CAPACITY,
  DUPLICATE_INSTANCE,
  UNKNOWN_ITEM,
  INVALID_PHYSICAL_STACK
}
