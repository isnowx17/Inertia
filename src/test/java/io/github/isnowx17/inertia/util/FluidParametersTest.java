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
}
