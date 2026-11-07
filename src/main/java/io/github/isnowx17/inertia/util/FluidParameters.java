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
	/** Solver iterations per sub-step at the normal setting (PhysX's default for particle systems). */
	public static final int POSITION_ITERATIONS = 4;
	public static final int VELOCITY_ITERATIONS = 1;
	/** Most of a sub-step's GPU time is the neighbour search: it costs about this many solver iterations. */
	private static final float NEIGHBOUR_SEARCH_COST = 5.4f;

	/**
	 * One way to simulate a game tick.
	 *
	 * @param substeps   sub-steps per tick
	 * @param iterations solver iterations per sub-step
	 * @param cost       expected GPU time relative to the normal setting
	 */
	public record SolverLevel(int substeps, int iterations, float cost) {
	}

	/**
	 * The ways to simulate a game tick, best first: the first is the setting the quality level stands
	 * for, the other (if any) is for when the GPU cannot keep up with it, with fewer sub-steps. It
	 * looks nearly the same; measured at {@code ultra}, three instead of four sub-steps cost a fifth
	 * less and lower the water level of a pool by about 5 %. Fewer still is not an option at
	 * {@code ultra}: with two sub-steps the water visibly churns where a waterfall lands, and with
	 * one still water boils. Levels with three sub-steps go to two with more solver iterations, which
	 * keeps the water level.
	 */
	public static SolverLevel[] solverLevels(int substeps) {
		SolverLevel normal = new SolverLevel(substeps, POSITION_ITERATIONS, 1f);
		if (substeps <= 2) {
			return new SolverLevel[] {normal};
		}
		int fewer = (int) Math.ceil(substeps * 0.75);
		SolverLevel cheaper = fewer < substeps
			? solverLevel(substeps, fewer, POSITION_ITERATIONS)
			: solverLevel(substeps, substeps - 1, POSITION_ITERATIONS + 2);
		return new SolverLevel[] {normal, cheaper};
	}

	private static SolverLevel solverLevel(int base, int substeps, int iterations) {
		// A tenth of the time does not depend on the solver (reading back, collisions).
		float relative = substeps * (NEIGHBOUR_SEARCH_COST + iterations) / (base * (NEIGHBOUR_SEARCH_COST + POSITION_ITERATIONS));
		return new SolverLevel(substeps, iterations, 0.1f + 0.9f * relative);
	}

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
