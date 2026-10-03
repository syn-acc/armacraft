package gl.syn.armacraft.client.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModModelTest {

	private static ModModel load(String name) throws IOException {
		try (InputStream in = ModModelTest.class.getResourceAsStream("/assets/armacraft_client/models/arma/" + name)) {
			assertNotNull(in, name);
			return ModModel.parse(in);
		}
	}

	@Test
	void parsesShippedCycleModels() throws IOException {
		ModModel body = load("cycle_body.mod");
		ModModel front = load("cycle_front.mod");
		ModModel rear = load("cycle_rear.mod");
		// vertex numbers are 1-based; index 0 is the unused default vertex
		assertEquals(23, body.vertexCount());
		assertEquals(40, body.faceCount());
		assertEquals(60, front.faceCount());
		assertEquals(80, rear.faceCount());
		double[] b = body.bounds();
		assertEquals(-0.508192, b[0], 1e-6);
		assertEquals(2.04906, b[3], 1e-6);
		assertEquals(0.232375, b[2], 1e-6);
		assertEquals(1.54688, b[5], 1e-6);
	}

	@Test
	void planarTextureMappingMatchesRModel() throws IOException {
		ModModel body = load("cycle_body.mod");
		// u = (x - xmin)/(xmax - xmin), v = (zmax - z)/(zmax - zmin)
		int v = 11; // "v 11 2.04906 0.14148 0.847465" is the frontmost vertex
		assertEquals(1.0, body.tu[v], 1e-6);
		assertEquals((1.54688 - 0.847465) / (1.54688 - 0.232375), body.tv[v], 1e-5);
		for (int i = 1; i < body.vertexCount(); i++) {
			assertTrue(body.tu[i] >= -1e-6 && body.tu[i] <= 1 + 1e-6);
			assertTrue(body.tv[i] >= -1e-6 && body.tv[i] <= 1 + 1e-6);
			double n = Math.sqrt(body.nx[i] * body.nx[i] + body.ny[i] * body.ny[i] + body.nz[i] * body.nz[i]);
			assertEquals(1.0, n, 1e-5, "normal " + i);
		}
	}

	@Test
	void parsesLikeIstreamAndSkipsUnknownCharacters() throws IOException {
		String text = "# comment ignored char by char\nv 1 0 0 0\nv 2 1 0 0\nv\t3\t0 0 1\nf 1 2 3\n";
		ModModel m = ModModel.parse(text);
		assertEquals(4, m.vertexCount());
		assertEquals(1, m.faceCount());
		// (B-A) x (C-A) = (1,0,0) x (0,0,1) = (0,-1,0)
		assertEquals(-1.0, m.ny[1], 1e-9);
		assertEquals(0.0, m.nz[1], 1e-9);
	}

	@Test
	void rejectsFacesWithMissingVertices() {
		assertThrows(IOException.class, () -> ModModel.parse("v 1 0 0 0\nf 1 2 3\n"));
	}
}
