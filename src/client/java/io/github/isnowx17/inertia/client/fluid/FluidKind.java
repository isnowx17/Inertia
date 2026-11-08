package io.github.isnowx17.inertia.client.fluid;

/**
 * The liquids Inertia simulates. Each has its own simulation (its own PhysX particle system and
 * material, so they do not mix), its own items, look, sounds and save file.
 */
public enum FluidKind {
	/** Water: thin, quick, see-through, with foam and spray. */
	WATER("water"),
	/** Lava: thick and slow, glowing, opaque; burns what gets into it. */
	LAVA("lava");

	/** Name in save files and logs. */
	public final String id;

	FluidKind(String id) {
		this.id = id;
	}
}
