package net.infnetwork.snowball.craftov.map.gen;

import net.infnetwork.snowball.craftov.map.MapDef;

/** Builds a built-in map into an empty template world. */
public interface MapGenerator {

    MapDef generate();

    long blocksPlaced();
}
