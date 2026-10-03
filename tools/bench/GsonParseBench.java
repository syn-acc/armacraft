import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gson parse cost per bridge `state` snapshot.
 *
 * <p>Standalone: needs only the Gson jar from the Gradle cache. If the host's
 * {@code gl.syn.armacraft.bridge.Protocol} is also on the classpath (tools/bench/run_java_bench.py compiles
 * it from host/src), the real host parser ({@code Protocol.parse}: Gson tree + record extraction) is timed
 * too, via reflection, so this file compiles without it.
 *
 * <p>Usage: {@code java -cp gson.jar:. GsonParseBench states.jsonl [batchMillis]}. Prints one row per state
 * line: bytes, walls, cycles, median microseconds and allocated KB per parse.
 */
public final class GsonParseBench {

	static volatile int sink;

	interface Op {
		int run(String s) throws Exception;
	}

	public static void main(String[] args) throws Exception {
		List<String> lines = new ArrayList<>();
		for (String l : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
			if (l.startsWith("{\"type\":\"state\"")) {
				lines.add(l);
			}
		}
		long batchNanos = (args.length > 1 ? Long.parseLong(args[1]) : 25) * 1_000_000L;
		Method hostParse = null;
		try {
			hostParse = Class.forName("gl.syn.armacraft.bridge.Protocol").getMethod("parse", String.class);
		} catch (ReflectiveOperationException | LinkageError e) {
			System.err.println("host Protocol not on classpath; timing Gson tree parse only");
		}
		final Method hp = hostParse;
		Op tree = s -> {
			JsonElement e = JsonParser.parseString(s);
			return e.getAsJsonObject().getAsJsonArray("walls").size();
		};
		Op host = hp == null ? null : s -> System.identityHashCode(hp.invoke(null, s));

		// JIT warmup over all inputs
		long warmEnd = System.nanoTime() + 3_000_000_000L;
		while (System.nanoTime() < warmEnd) {
			for (String s : lines) {
				sink += tree.run(s);
				if (host != null) {
					sink += host.run(s);
				}
			}
		}

		com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		System.out.println("bytes\twalls\tcycles\tgson_tree_us\tgson_tree_alloc_kb\thost_parse_us\thost_parse_alloc_kb");
		for (String s : lines) {
			JsonElement e = JsonParser.parseString(s);
			int walls = e.getAsJsonObject().getAsJsonArray("walls").size();
			int cycles = e.getAsJsonObject().getAsJsonArray("cycles").size();
			double[] t = time(tree, s, batchNanos, tmx);
			double[] h = host == null ? new double[] {Double.NaN, Double.NaN} : time(host, s, batchNanos, tmx);
			System.out.printf("%d\t%d\t%d\t%.1f\t%.1f\t%.1f\t%.1f%n", s.getBytes(StandardCharsets.UTF_8).length + 1, walls, cycles,
				t[0], t[1], h[0], h[1]);
		}
	}

	/** {median microseconds per op over 7 batches, allocated KB per op} */
	static double[] time(Op op, String s, long batchNanos, com.sun.management.ThreadMXBean tmx) throws Exception {
		// size the batch
		int n = 1;
		while (true) {
			long t0 = System.nanoTime();
			for (int i = 0; i < n; i++) {
				sink += op.run(s);
			}
			if (System.nanoTime() - t0 >= batchNanos || n >= 1 << 20) {
				break;
			}
			n *= 2;
		}
		double[] per = new double[7];
		long tid = Thread.currentThread().getId();
		long a0 = tmx.getThreadAllocatedBytes(tid);
		long ops = 0;
		for (int b = 0; b < per.length; b++) {
			long t0 = System.nanoTime();
			for (int i = 0; i < n; i++) {
				sink += op.run(s);
			}
			per[b] = (System.nanoTime() - t0) / 1e3 / n;
			ops += n;
		}
		long a1 = tmx.getThreadAllocatedBytes(tid);
		Arrays.sort(per);
		return new double[] {per[per.length / 2], (a1 - a0) / 1024.0 / ops};
	}
}
