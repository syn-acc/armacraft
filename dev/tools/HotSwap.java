// armacraft dev hotswap: redefine changed mod classes inside a running server JVM.
//
// Two transports:
//   pid:<pid>:<agent.jar>  (default in dev/host.ps1) dynamic attach + java.lang.instrument agent
//                          (HotSwapAgent below). Nothing is listening in the server, and startup is
//                          not slowed by a JDWP agent.
//   jdwp:<port>            JDI over a JDWP socket on 127.0.0.1 (server started with host.ps1 -Debug),
//                          for when a debugger is attached anyway.
//
//   java -cp <dir> HotSwap swap <target> <classesDir> <manifest> [--restart-prefix P]... [--allow-new]
//       diff <classesDir> against <manifest> (what the JVM has loaded), redefine changed loaded
//       classes, then rewrite <manifest>.
//   java -cp <dir> HotSwap snapshot <classesDir> <manifest>
//
// Exit codes: 0 swapped (or nothing to do); 3 the JVM refused (a structural change without JBR's
// -XX:+AllowEnhancedClassRedefinition); 4 restart required (mixin, or a new / not-yet-loaded class
// while the mod is loaded from a jar); 2 cannot attach.
//
// Limits (host.ps1 falls back to a restart for these): mixins are merged into Minecraft classes at
// load time; static initialisers, registered callbacks and commands do not re-run; a stock JDK only
// allows method-body changes.
//
// The agent jar holds only HotSwapAgent.class (host.ps1 builds it with Agent-Class/Can-Redefine-Classes).
//
// Play-client push (dev/play.ps1 push), for a running game client:
//
//   java -cp <dir> HotSwap push <pid> <agent.jar> --set <classesDir>|<resourcesDir>|<anchorClass> ...
//        [--mixin-prefix P]... [--live-res PREFIX]... [--announce] [--dry-run]
//
//   Each --set is one source set of the new build (main, client). The agent first reports where the JVM loaded
//   <anchorClass> from; that directory (the game's mod dir) is what the new build is diffed against, so the
//   baseline is always what the game really loads, with no manifest to go stale.
//
//   The push is all-or-nothing and can never leave a class to be lazy-loaded at the wrong version:
//     1. refuse (exit 4, nothing touched) on a removed/renamed class, a changed mixin, or a changed resource
//        other than the --live-res ones (files the mod re-reads at run time);
//     2. inside the JVM: every changed class that is not loaded yet is loaded now, from the old files;
//     3. brand-new classes are written to the mod dir (old code cannot reference them);
//     4. one redefineClasses call swaps all changed classes at a safepoint; if the JVM refuses (a structural
//        change on a stock JDK), the new files are deleted again and exit is 3;
//     5. only then the changed .class files and live resources are replaced on disk (temp file + atomic move).
//        They matter for the next start only: step 2 left nothing to load lazily.
//   Exit codes: 0 pushed (or nothing to do), 2 cannot attach, 3 JVM refused, 4 restart needed, 5 agent error.

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public class HotSwap {

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equals("snapshot")) {
            Map<String, String> m = scan(Path.of(args[1]));
            writeManifest(Path.of(args[2]), m);
            System.out.println("[hotswap] snapshot of " + m.size() + " classes");
            return;
        }
        if (args.length >= 4 && args[0].equals("push")) {
            System.exit(Push.run(args));
        }
        if (args.length < 4 || !args[0].equals("swap")) {
            System.err.println("usage: HotSwap snapshot <classesDir> <manifest> | swap <pid:PID:agent.jar|jdwp:PORT> <classesDir> <manifest> [--restart-prefix P]... [--allow-new]");
            System.exit(2);
        }
        String target = args[1];
        Path dir = Path.of(args[2]);
        Path manifest = Path.of(args[3]);
        List<String> restartPrefixes = new ArrayList<>();
        boolean allowNew = false;
        for (int i = 4; i < args.length; i++) {
            if (args[i].equals("--restart-prefix") && i + 1 < args.length) {
                restartPrefixes.add(args[++i]);
            } else if (args[i].equals("--allow-new")) {
                allowNew = true;
            }
        }

        Map<String, String> before = readManifest(manifest);
        Map<String, String> now = scan(dir);
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (var e : now.entrySet()) {
            String old = before.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String k : before.keySet()) {
            if (!now.containsKey(k)) {
                removed.add(k);
            }
        }
        if (changed.isEmpty() && added.isEmpty() && removed.isEmpty()) {
            System.out.println("[hotswap] no class changes");
            return;
        }
        System.out.println("[hotswap] changed " + changed.size() + ", added " + added.size() + ", removed " + removed.size());

        for (String c : changed) {
            for (String p : restartPrefixes) {
                if (c.startsWith(p)) {
                    System.out.println("[hotswap] RESTART: " + c + " is a mixin (applied at class load)");
                    System.exit(4);
                }
            }
        }
        if (!added.isEmpty() && !allowNew) {
            System.out.println("[hotswap] RESTART: new classes " + added + " (the server loads the mod from a jar)");
            System.exit(4);
        }
        if (!removed.isEmpty()) {
            System.out.println("[hotswap] note: removed " + removed + " (stay loaded until restart)");
        }

        long t0 = System.nanoTime();
        Result r = target.startsWith("jdwp:")
            ? viaJdwp(Integer.parseInt(target.substring(5)), dir, changed)
            : viaAgent(target, dir, changed);
        if (r.status.equals("ATTACH")) {
            System.out.println("[hotswap] cannot attach: " + r.detail);
            System.exit(2);
        }
        if (!r.notLoaded.isEmpty() && !allowNew) {
            System.out.println("[hotswap] RESTART: changed but not yet loaded " + r.notLoaded + " (the stale jar copy would load)");
            System.exit(4);
        }
        if (r.status.equals("REFUSED")) {
            System.out.println("[hotswap] REFUSED by the JVM: " + r.detail
                + " (a stock JDK only redefines method bodies; JBR with -XX:+AllowEnhancedClassRedefinition also takes fields/methods/hierarchy)");
            System.exit(3);
        }
        System.out.printf("[hotswap] redefined %d class(es) in %.0f ms via %s%s%s%n",
            r.redefined, (System.nanoTime() - t0) / 1e6, target.startsWith("jdwp:") ? "jdwp" : "attach agent",
            r.notLoaded.isEmpty() ? "" : ", " + r.notLoaded.size() + " not loaded yet (load fresh from disk)",
            added.isEmpty() ? "" : ", " + added.size() + " new (load from disk)");
        writeManifest(manifest, now);
    }

    record Result(String status, int redefined, List<String> notLoaded, String detail) {}

    // ---------------------------------------------------------------- transport: attach agent

    static Result viaAgent(String target, Path dir, List<String> changed) throws Exception {
        // pid:<pid>:<agent jar>  (the jar path may contain ':' on Windows, so split at most twice)
        String[] p = target.split(":", 3);
        if (p.length != 3 || !p[0].equals("pid")) {
            throw new IllegalArgumentException("target must be pid:<pid>:<agent.jar> or jdwp:<port>");
        }
        Path resultFile = Files.createTempFile("armacraft-hotswap", ".txt");
        Path listFile = Files.createTempFile("armacraft-hotswap", ".lst");
        try {
            Files.write(listFile, changed, StandardCharsets.UTF_8);
            com.sun.tools.attach.VirtualMachine vm;
            try {
                vm = com.sun.tools.attach.VirtualMachine.attach(p[1]);
            } catch (Exception e) {
                return new Result("ATTACH", 0, List.of(), e.toString());
            }
            try {
                vm.loadAgent(Path.of(p[2]).toAbsolutePath().toString(),
                    resultFile.toAbsolutePath() + "|" + dir.toAbsolutePath() + "|" + listFile.toAbsolutePath());
            } catch (Exception e) {
                // agentmain reports through the result file; anything else is an attach-level failure
                if (Files.size(resultFile) == 0) {
                    return new Result("ATTACH", 0, List.of(), e.toString());
                }
            } finally {
                vm.detach();
            }
            List<String> lines = Files.readAllLines(resultFile, StandardCharsets.UTF_8);
            String status = lines.isEmpty() ? "ATTACH" : lines.get(0);
            int n = lines.size() > 1 ? Integer.parseInt(lines.get(1)) : 0;
            List<String> notLoaded = lines.size() > 2 && !lines.get(2).isBlank() ? List.of(lines.get(2).split(",")) : List.of();
            String detail = lines.size() > 3 ? lines.get(3) : "";
            return new Result(status, n, notLoaded, detail);
        } finally {
            Files.deleteIfExists(resultFile);
            Files.deleteIfExists(listFile);
        }
    }

    // ---------------------------------------------------------------- transport: JDWP / JDI

    static Result viaJdwp(int port, Path dir, List<String> changed) throws Exception {
        AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst().orElseThrow();
        Map<String, Connector.Argument> a = socket.defaultArguments();
        a.get("hostname").setValue("127.0.0.1");
        a.get("port").setValue(String.valueOf(port));
        if (a.containsKey("timeout")) {
            a.get("timeout").setValue("5000");
        }
        com.sun.jdi.VirtualMachine vm;
        try {
            vm = socket.attach(a);
        } catch (Exception e) {
            return new Result("ATTACH", 0, List.of(), e.toString());
        }
        try {
            Map<ReferenceType, byte[]> defs = new LinkedHashMap<>();
            List<String> notLoaded = new ArrayList<>();
            for (String c : changed) {
                List<ReferenceType> types = vm.classesByName(c);
                if (types.isEmpty()) {
                    notLoaded.add(c);
                    continue;
                }
                byte[] bytes = Files.readAllBytes(dir.resolve(c.replace('.', '/') + ".class"));
                for (ReferenceType t : types) {
                    defs.put(t, bytes);
                }
            }
            if (defs.isEmpty()) {
                return new Result("OK", 0, notLoaded, "");
            }
            try {
                vm.redefineClasses(defs);
            } catch (UnsupportedOperationException | LinkageError e) {
                return new Result("REFUSED", 0, notLoaded, e.toString());
            }
            return new Result("OK", defs.size(), notLoaded, "");
        } finally {
            vm.dispose();
        }
    }

    // ---------------------------------------------------------------- manifest

    static Map<String, String> scan(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                String name = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                out.put(name, sha256(Files.readAllBytes(p)));
            }
        }
        return out;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> readManifest(Path f) throws IOException {
        Map<String, String> m = new TreeMap<>();
        if (Files.isRegularFile(f)) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    m.put(line.substring(0, sp), line.substring(sp + 1).trim());
                }
            }
        }
        return m;
    }

    static void writeManifest(Path f, Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append(' ').append(v).append('\n'));
        Files.createDirectories(f.toAbsolutePath().getParent());
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
    }
}

/**
 * Runs inside the server JVM (loaded by VirtualMachine.loadAgent). Args: "resultFile|classesDir|listFile".
 * Writes: status (OK/REFUSED), redefined count, comma-separated not-loaded names, detail.
 * Uses only java.base + java.instrument so it never drags tool classes into the server.
 */
class HotSwapAgent {
    static final String PROTO = "2";

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        if (args.startsWith("probe|")) {
            probe(args.split("\\|", 3), inst);
            return;
        }
        if (args.startsWith("swap|")) {
            String[] b = args.split("\\|", 3);
            Path result = Path.of(b[1]);
            try {
                swap(result, Path.of(b[2]), inst);
            } catch (Throwable t) {
                Files.writeString(result, "proto " + PROTO + "\nstatus ERROR\ndetail " + String.valueOf(t).replace('\n', ' ') + "\n", StandardCharsets.UTF_8);
            }
            return;
        }
        String[] a = args.split("\\|", 3);
        Path result = Path.of(a[0]);
        Path dir = Path.of(a[1]);
        Set<String> want = new HashSet<>(Files.readAllLines(Path.of(a[2]), StandardCharsets.UTF_8));
        want.removeIf(String::isBlank);
        Map<String, List<Class<?>>> loaded = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (want.contains(c.getName())) {
                loaded.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c);
            }
        }
        List<ClassDefinition> defs = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();
        for (String n : want) {
            List<Class<?>> cs = loaded.get(n);
            if (cs == null) {
                notLoaded.add(n);
                continue;
            }
            byte[] bytes = Files.readAllBytes(dir.resolve(n.replace('.', '/') + ".class"));
            for (Class<?> c : cs) {
                defs.add(new ClassDefinition(c, bytes));
            }
        }
        String status = "OK";
        String detail = "";
        try {
            if (!defs.isEmpty()) {
                inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
            }
        } catch (Throwable t) {
            status = "REFUSED";
            detail = t.toString().replace('\n', ' ');
        }
        Files.writeString(result, status + "\n" + (status.equals("OK") ? defs.size() : 0) + "\n"
            + String.join(",", notLoaded) + "\n" + detail + "\n", StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- play-client push (see the file header)

    /** probe|resultFile|anchor1,anchor2: where each anchor class was loaded from, and what this JVM can redefine. */
    private static void probe(String[] a, Instrumentation inst) throws Exception {
        Set<String> anchors = new HashSet<>(List.of(a[2].split(",")));
        StringBuilder sb = new StringBuilder("proto " + PROTO + "\n");
        Map<String, String> roots = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (anchors.contains(c.getName()) && !roots.containsKey(c.getName())) {
                String where = "?";
                try {
                    java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
                    if (cs != null && cs.getLocation() != null) {
                        where = Path.of(cs.getLocation().toURI()).toAbsolutePath().toString();
                    }
                } catch (Exception e) {
                    where = "?";
                }
                roots.put(c.getName(), where);
            }
        }
        for (String n : anchors) {
            sb.append("root ").append(n).append(' ').append(roots.getOrDefault(n, "-")).append('\n');
        }
        boolean enhanced = false;
        try {
            for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                enhanced |= arg.equals("-XX:+AllowEnhancedClassRedefinition");
            }
        } catch (Throwable t) {
            // no java.management: report false
        }
        sb.append("enhanced ").append(enhanced).append('\n');
        sb.append("vm ").append(System.getProperty("java.vm.vendor")).append(' ').append(System.getProperty("java.vm.version")).append('\n');
        Files.writeString(Path.of(a[1]), sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * swap|resultFile|planFile. Plan lines (tab separated):
     * L anchorClass | C name src dst (changed) | A name src dst (added) | R src dst (live resource) | N announce text
     */
    private static void swap(Path result, Path plan, Instrumentation inst) throws Exception {
        List<String[]> changed = new ArrayList<>();
        List<String[]> added = new ArrayList<>();
        List<String[]> res = new ArrayList<>();
        String anchor = null;
        String announce = null;
        for (String line : Files.readAllLines(plan, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            switch (f[0]) {
                case "L" -> anchor = f[1];
                case "C" -> changed.add(f);
                case "A" -> added.add(f);
                case "R" -> res.add(f);
                case "N" -> announce = f.length > 1 ? f[1] : "";
                default -> { }
            }
        }
        Set<String> want = new HashSet<>();
        for (String[] c : changed) {
            want.add(c[1]);
        }
        ClassLoader loader = null;
        Map<String, List<Class<?>>> loaded = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (want.contains(c.getName())) {
                loaded.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c);
            }
            if (loader == null && c.getName().equals(anchor)) {
                loader = c.getClassLoader();
            }
        }
        if (loader == null) {
            write(result, "RESTART", 0, 0, "anchor class " + anchor + " is not loaded in this JVM");
            return;
        }
        // 2. nothing changed may stay unloaded: load it now, from the old files (no initialisation)
        int preloaded = 0;
        for (String[] c : changed) {
            if (!loaded.containsKey(c[1])) {
                try {
                    Class<?> k = Class.forName(c[1], false, loader);
                    loaded.computeIfAbsent(c[1], x -> new ArrayList<>()).add(k);
                    preloaded++;
                } catch (Throwable t) {
                    write(result, "RESTART", 0, 0, "cannot load " + c[1] + " in the running game before swapping it: " + String.valueOf(t).replace('\n', ' '));
                    return;
                }
            }
        }
        List<ClassDefinition> defs = new ArrayList<>();
        Map<String[], byte[]> bytes = new java.util.LinkedHashMap<>();
        for (String[] c : changed) {
            byte[] b = Files.readAllBytes(Path.of(c[2]));
            bytes.put(c, b);
            for (Class<?> k : loaded.get(c[1])) {
                defs.add(new ClassDefinition(k, b));
            }
        }
        // 3. new classes first: nothing running refers to them yet
        List<Path> created = new ArrayList<>();
        try {
            for (String[] c : added) {
                Path dst = Path.of(c[3]);
                if (!Files.exists(dst)) {
                    created.add(dst);
                }
                put(Files.readAllBytes(Path.of(c[2])), dst);
            }
            // 4. all changed classes in one atomic redefinition
            if (!defs.isEmpty()) {
                inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
            }
        } catch (Throwable t) {
            for (Path p : created) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // reported below through the detail
                }
            }
            boolean refused = t instanceof UnsupportedOperationException || t instanceof LinkageError;
            write(result, refused ? "REFUSED" : "ERROR", 0, preloaded, String.valueOf(t).replace('\n', ' '));
            return;
        }
        // 5. disk follows memory (for the next start; every changed class is loaded already)
        StringBuilder late = new StringBuilder();
        for (Map.Entry<String[], byte[]> e : bytes.entrySet()) {
            try {
                put(e.getValue(), Path.of(e.getKey()[3]));
            } catch (IOException x) {
                late.append(e.getKey()[1]).append(' ');
            }
        }
        for (String[] r : res) {
            try {
                put(Files.readAllBytes(Path.of(r[1])), Path.of(r[2]));
            } catch (IOException x) {
                late.append(r[2]).append(' ');
            }
        }
        if (announce != null) {
            try {
                Class.forName("gl.syn.armacraft.client.ride.BuildStamp", true, loader).getMethod("onHotPush", String.class).invoke(null, announce);
            } catch (Throwable t) {
                // a build without BuildStamp, or a server: no chat line
            }
        }
        write(result, late.isEmpty() ? "OK" : "DISK", defs.size(), preloaded, late.isEmpty() ? "" : "live in memory, but could not write: " + late);
    }

    private static void put(byte[] b, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        Path tmp = dst.resolveSibling(dst.getFileName() + ".push-tmp");
        Files.write(tmp, b);
        try {
            Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static void write(Path result, String status, int redefined, int preloaded, String detail) throws IOException {
        Files.writeString(result, "proto " + PROTO + "\nstatus " + status + "\nredefined " + redefined + "\npreloaded " + preloaded
            + "\ndetail " + detail + "\n", StandardCharsets.UTF_8);
    }
}

/** dev/play.ps1 push: diff a new build against what a running game loads, then swap it in through the agent. */
class Push {

    record SourceSet(Path classes, Path resources, String anchor) {}

    static int run(String[] args) throws Exception {
        String pid = args[1];
        Path agent = Path.of(args[2]).toAbsolutePath();
        List<SourceSet> sets = new ArrayList<>();
        List<String> mixinPrefixes = new ArrayList<>();
        List<String> liveRes = new ArrayList<>();
        String announce = null;
        boolean dry = false;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--set" -> {
                    String[] f = args[++i].split("\\|", 3);
                    sets.add(new SourceSet(Path.of(f[0]).toAbsolutePath(), Path.of(f[1]).toAbsolutePath(), f[2]));
                }
                case "--mixin-prefix" -> mixinPrefixes.add(args[++i]);
                case "--live-res" -> liveRes.add(args[++i]);
                case "--announce" -> announce = "";
                case "--dry-run" -> dry = true;
                default -> {
                    System.err.println("[push] unknown argument " + args[i]);
                    return 5;
                }
            }
        }
        if (sets.isEmpty()) {
            System.err.println("[push] no --set given");
            return 5;
        }

        com.sun.tools.attach.VirtualMachine vm;
        try {
            vm = com.sun.tools.attach.VirtualMachine.attach(pid);
        } catch (Exception e) {
            System.out.println("[push] cannot attach to pid " + pid + ": " + e + " (the game needs -XX:+EnableDynamicAgentLoading)");
            return 2;
        }
        try {
            // ---- where does the game load the mod from?
            List<String> anchors = new ArrayList<>();
            for (SourceSet s : sets) {
                anchors.add(s.anchor);
            }
            Map<String, String> probe = call(vm, agent, "probe", String.join(",", anchors));
            if (probe == null || !HotSwapAgent.PROTO.equals(probe.get("proto"))) {
                System.out.println("[push] RESTART NEEDED: this game holds an older hot-push agent (or none answered). Nothing changed.");
                return 4;
            }
            boolean enhanced = "true".equals(probe.get("enhanced"));
            // new build, and the game's current files, as name -> path
            Map<String, Path> newClasses = new TreeMap<>();
            Map<String, Path> newRes = new TreeMap<>();
            Map<String, Path> curClasses = new TreeMap<>();
            Map<String, Path> curRes = new TreeMap<>();
            Map<String, Path> classDst = new HashMap<>();
            Map<String, Path> resDst = new HashMap<>();
            Set<Path> seenRoots = new HashSet<>();
            for (SourceSet s : sets) {
                String where = probe.get("root " + s.anchor);
                if (where == null || where.equals("-") || where.equals("?")) {
                    System.out.println("[push] RESTART NEEDED: " + s.anchor + " is not loaded from a directory in pid " + pid
                        + " (not an armacraft play client, or the mod runs from a jar). Nothing changed.");
                    return 4;
                }
                Path root = Path.of(where);
                if (!Files.isDirectory(root)) {
                    System.out.println("[push] RESTART NEEDED: the game loads " + s.anchor + " from " + root + ", which is not a directory. Nothing changed.");
                    return 4;
                }
                // resources: next to the classes (a merged mod dir), or gradle's build/resources/<set>
                Path resRoot = root;
                if (!Files.exists(root.resolve("fabric.mod.json")) && root.getNameCount() >= 3
                    && root.getParent().getFileName().toString().equals("java") && root.getParent().getParent().getFileName().toString().equals("classes")) {
                    resRoot = root.getParent().getParent().getParent().resolve("resources").resolve(root.getFileName());
                }
                for (var e : tree(s.classes, true).entrySet()) {
                    newClasses.put(e.getKey(), e.getValue());
                    classDst.put(e.getKey(), root.resolve(e.getKey()));
                }
                for (var e : tree(s.resources, false).entrySet()) {
                    newRes.put(e.getKey(), e.getValue());
                    resDst.put(e.getKey(), resRoot.resolve(e.getKey()));
                }
                if (seenRoots.add(root)) {
                    curClasses.putAll(tree(root, true));
                }
                if (seenRoots.add(resRoot) || resRoot.equals(root)) {
                    for (var e : tree(resRoot, false).entrySet()) {
                        if (!e.getKey().endsWith(".push-tmp")) {
                            curRes.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }

            List<String> changed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            diff(newClasses, curClasses, changed, added, removed);
            List<String> resChanged = new ArrayList<>();
            List<String> resAdded = new ArrayList<>();
            List<String> resRemoved = new ArrayList<>();
            diff(newRes, curRes, resChanged, resAdded, resRemoved);

            // ---- anything that cannot be swapped safely: refuse before touching the game
            List<String> why = new ArrayList<>();
            if (!removed.isEmpty()) {
                why.add("removed or renamed class" + (removed.size() == 1 ? " " : "es ") + names(removed));
            }
            List<String> mixins = new ArrayList<>();
            for (String c : changed) {
                if (isMixin(c, mixinPrefixes)) {
                    mixins.add(c);
                }
            }
            for (String c : added) {
                if (isMixin(c, mixinPrefixes)) {
                    mixins.add(c);
                }
            }
            if (!mixins.isEmpty()) {
                why.add("mixin change " + names(mixins) + " (mixins are merged into Minecraft classes when those load)");
            }
            List<String> liveChanged = new ArrayList<>();
            List<String> hardRes = new ArrayList<>();
            for (List<String> l : List.of(resChanged, resAdded)) {
                for (String r : l) {
                    (isLive(r, liveRes) ? liveChanged : hardRes).add(r);
                }
            }
            for (String r : resRemoved) {
                if (!isLive(r, liveRes)) {
                    hardRes.add(r);
                }
            }
            if (!hardRes.isEmpty()) {
                why.add("resource change " + names(hardRes) + " (read once at startup)");
            }
            System.out.println("[push] classes: " + changed.size() + " changed, " + added.size() + " new, " + removed.size() + " removed; resources: "
                + liveChanged.size() + " live, " + hardRes.size() + " startup-only; game JVM: " + probe.get("vm")
                + (enhanced ? " (enhanced redefinition)" : " (method bodies only)"));
            if (!why.isEmpty()) {
                System.out.println("[push] RESTART NEEDED: " + String.join("; ", why) + ". Nothing changed in the game or on disk.");
                return 4;
            }
            if (changed.isEmpty() && added.isEmpty() && liveChanged.isEmpty()) {
                System.out.println("[push] the game already runs this build");
                return 0;
            }
            if (dry) {
                System.out.println("[push] dry run, would swap: " + names(changed) + (added.isEmpty() ? "" : "; new: " + names(added)));
                return 0;
            }

            // ---- swap
            List<String> plan = new ArrayList<>();
            plan.add("L\t" + sets.get(0).anchor);
            for (String c : changed) {
                plan.add("C\t" + c.substring(0, c.length() - 6).replace('/', '.') + "\t" + newClasses.get(c) + "\t" + classDst.get(c));
            }
            for (String c : added) {
                plan.add("A\t" + c.substring(0, c.length() - 6).replace('/', '.') + "\t" + newClasses.get(c) + "\t" + classDst.get(c));
            }
            for (String r : liveChanged) {
                plan.add("R\t" + newRes.get(r) + "\t" + resDst.get(r));
            }
            if (announce != null) {
                String what = changed.size() + added.size() == 0 ? "resources" : (changed.size() + added.size()) + " class" + (changed.size() + added.size() == 1 ? "" : "es");
                plan.add("N\t" + (announce.isEmpty() ? what : announce + ", " + what));
            }
            Path planFile = Files.createTempFile("armacraft-push", ".plan");
            Map<String, String> r;
            long t0 = System.nanoTime();
            try {
                Files.write(planFile, plan, StandardCharsets.UTF_8);
                r = call(vm, agent, "swap", planFile.toAbsolutePath().toString());
            } finally {
                Files.deleteIfExists(planFile);
            }
            String status = r == null ? "ERROR" : r.getOrDefault("status", "ERROR");
            String detail = r == null ? "the agent wrote no result" : r.getOrDefault("detail", "");
            switch (status) {
                case "OK", "DISK" -> {
                    System.out.printf("[push] LIVE: %s class(es) redefined (%s loaded first), %d new, %d resource(s), in %.0f ms%n",
                        r.get("redefined"), r.get("preloaded"), added.size(), liveChanged.size(), (System.nanoTime() - t0) / 1e6);
                    if (status.equals("DISK")) {
                        System.out.println("[push] WARNING: " + detail + " (the running game is consistent; the next push retries)");
                    }
                    return 0;
                }
                case "REFUSED" -> {
                    System.out.println("[push] RESTART NEEDED: the JVM refused the change (" + detail + "). "
                        + (enhanced ? "Even enhanced redefinition cannot apply it." : "A stock JDK only swaps method bodies; a game started on JBR with -XX:+AllowEnhancedClassRedefinition also takes new fields and methods.")
                        + " Nothing changed in the game or on disk.");
                    return 3;
                }
                case "RESTART" -> {
                    System.out.println("[push] RESTART NEEDED: " + detail + ". Nothing changed in the game or on disk.");
                    return 4;
                }
                default -> {
                    System.out.println("[push] agent error: " + detail);
                    return 5;
                }
            }
        } finally {
            vm.detach();
        }
    }

    /** Runs the agent in one mode; returns its result file as key -> rest of line (keys "root X" keep their argument). */
    static Map<String, String> call(com.sun.tools.attach.VirtualMachine vm, Path agent, String mode, String arg) throws Exception {
        Path result = Files.createTempFile("armacraft-push", ".txt");
        try {
            try {
                vm.loadAgent(agent.toString(), mode + "|" + result.toAbsolutePath() + "|" + arg);
            } catch (Exception e) {
                if (Files.size(result) == 0) {
                    System.out.println("[push] agent call failed: " + e);
                    return null;
                }
            }
            Map<String, String> out = new HashMap<>();
            for (String line : Files.readAllLines(result, StandardCharsets.UTF_8)) {
                String[] f = line.split(" ", line.startsWith("root ") ? 3 : 2);
                if (line.startsWith("root ") && f.length == 3) {
                    out.put("root " + f[1], f[2]);
                } else if (f.length == 2) {
                    out.put(f[0], f[1]);
                } else if (f.length == 1 && !f[0].isEmpty()) {
                    out.put(f[0], "");
                }
            }
            return out.isEmpty() ? null : out;
        } finally {
            Files.deleteIfExists(result);
        }
    }

    /** Relative path (forward slashes) -> file; classes only, or everything but classes. */
    static Map<String, Path> tree(Path dir, boolean classes) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                if (p.toString().endsWith(".class") == classes) {
                    out.put(dir.relativize(p).toString().replace('\\', '/'), p);
                }
            }
        }
        return out;
    }

    static void diff(Map<String, Path> now, Map<String, Path> cur, List<String> changed, List<String> added, List<String> removed) throws IOException {
        for (var e : now.entrySet()) {
            Path old = cur.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (Files.size(old) != Files.size(e.getValue()) || Files.mismatch(old, e.getValue()) != -1) {
                changed.add(e.getKey());
            }
        }
        for (String k : cur.keySet()) {
            if (!now.containsKey(k)) {
                removed.add(k);
            }
        }
    }

    static boolean isMixin(String classFile, List<String> prefixes) {
        String name = classFile.replace('/', '.');
        for (String p : prefixes) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    static boolean isLive(String res, List<String> prefixes) {
        for (String p : prefixes) {
            if (res.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    static String names(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String s : l) {
            if (out.size() == 6) {
                out.add("... (" + l.size() + " in all)");
                break;
            }
            out.add(s.endsWith(".class") ? s.substring(0, s.length() - 6).replace('/', '.') : s);
        }
        return String.join(", ", out);
    }
}

