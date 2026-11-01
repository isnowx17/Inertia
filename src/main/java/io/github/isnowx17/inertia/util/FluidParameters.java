package io.github.isnowx17.inertia.util;

/**
 * Derived particle parameters for a given particle spacing (in blocks; 1 block = 1 metre).
 *
 * <p>The relations follow NVIDIA's PBF reference snippet (SnippetPBF), which is tuned for
 * water-like behaviour.
 */
public record FluidParameters(
	float spacing,
	int particlesPerAxis,
	float restOffset,
	float solidRestOffset,
	float fluidRestOffset,
	float particleContactOffset,
	float contactOffset,
	float particleMass,
	float maxVelocity
) {
	/** Density of water in kg/m^3. */
	public static final float WATER_DENSITY = 1000f;

	public static FluidParameters forSpacing(float spacing) {
		if (!(spacing > 0f) || spacing > 1f) {
			throw new IllegalArgumentException("Particle spacing must be in (0, 1], got " + spacing);
		}

		int perAxis = Math.max(1, Math.round(1f / spacing));
		float restOffset = 0.5f * spacing / 0.6f;
		float solidRestOffset = restOffset;
		float fluidRestOffset = restOffset * 0.6f;
		float particleContactOffset = fluidRestOffset / 0.6f;
		float contactOffset = restOffset + 0.01f;
		float particleMass = WATER_DENSITY * 1.333f * 3.14159f * spacing * spacing * spacing;
		float maxVelocity = solidRestOffset * 100f;

		return new FluidParameters(
			spacing,
			perAxis,
			restOffset,
			solidRestOffset,
			fluidRestOffset,
			particleContactOffset,
			contactOffset,
			particleMass,
			maxVelocity
		);
	}

	/** Number of particles that make up one block of water. */
	public int particlesPerBlock() {
		return particlesPerAxis * particlesPerAxis * particlesPerAxis;
	}

	/** Volume of water (in cubic blocks) one particle represents. */
	public float particleVolume() {
		return 1f / particlesPerBlock();
	}
}
