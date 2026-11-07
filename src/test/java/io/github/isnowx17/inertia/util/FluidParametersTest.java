package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FluidParametersTest {
	@Test
	void defaultSpacingGives512ParticlesPerBlock() {
		FluidParameters p = FluidParameters.forSpacing(0.125f);
		assertEquals(8, p.particlesPerAxis());
		assertEquals(512, p.particlesPerBlock());
		assertEquals(1f / 512f, p.particleVolume(), 1e-9);
	}

	@Test
	void offsetsKeepPhysXOrdering() {
		FluidParameters p = FluidParameters.forSpacing(0.1f);
		// PhysX requires rest offsets below contact offsets.
		assertTrue(p.fluidRestOffset() < p.particleContactOffset());
		assertTrue(p.restOffset() < p.contactOffset());
		assertTrue(p.particleMass() > 0f);
	}

	@Test
	void rejectsInvalidSpacing() {
		assertThrows(IllegalArgumentException.class, () -> FluidParameters.forSpacing(0f));
		assertThrows(IllegalArgumentException.class, () -> FluidParameters.forSpacing(-1f));
		assertThrows(IllegalArgumentException.class, () -> FluidParameters.forSpacing(2f));
		assertThrows(IllegalArgumentException.class, () -> FluidParameters.forSpacing(Float.NaN));
	}

	@Test
	void solverLevelsGetCheaperAndKeepAtLeastTwoSubSteps() {
		for (int substeps = 1; substeps <= 8; substeps++) {
			FluidParameters.SolverLevel[] levels = FluidParameters.solverLevels(substeps);
			assertEquals(substeps, levels[0].substeps(), "the first level is the normal setting");
			assertEquals(FluidParameters.POSITION_ITERATIONS, levels[0].iterations());
			assertEquals(1f, levels[0].cost());
			for (int i = 1; i < levels.length; i++) {
				assertTrue(levels[i].substeps() < levels[i - 1].substeps(), "fewer sub-steps");
				assertTrue(levels[i].substeps() >= 2, "sub-steps of 25 ms at most");
				assertTrue(levels[i].cost() < levels[i - 1].cost(), "cheaper");
			}
		}
		assertEquals(2, FluidParameters.solverLevels(4).length);
		assertEquals(3, FluidParameters.solverLevels(4)[1].substeps());
		assertEquals(4, FluidParameters.solverLevels(4)[1].iterations());
		assertEquals(2, FluidParameters.solverLevels(3)[1].substeps());
		assertEquals(6, FluidParameters.solverLevels(3)[1].iterations());
		assertEquals(1, FluidParameters.solverLevels(2).length);
	}
}
