#!/usr/bin/env python3
"""Compile and run the Java micro-benchmarks in tools/bench against JSONL files of `state` lines.

  python tools/bench/run_java_bench.py <states.jsonl> [more.jsonl ...]

- JDK: $JAVA_HOME, else the newest ~/.jdks/jdk-25*, else `java` on PATH.
- Gson / jspecify: taken from the Gradle cache (~/.gradle/caches/modules-2), the same jars the host builds with.
- The host's pure classes (bridge.Msg, bridge.Protocol, logic.*) are compiled from host/src/main/java into a
  temp dir (read-only use of host/), so HostTickBench times the code as it is in the tree right now.

Input lines come from `tools/bench_guest.py sweep` (samples_<n>ai.jsonl) or `tools/bench_guest.py synth`.
"""

import glob
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
HOST_SRC = os.path.join(ROOT, "host", "src", "main", "java", "gl", "syn", "armacraft")
HOST_FILES = ["bridge/Msg.java", "bridge/Protocol.java", "logic/WallLayout.java", "logic/DyePalette.java",
              "logic/Reconcile.java", "logic/Extrapolate.java", "logic/ArenaMapping.java", "logic/WallKeys.java"]


def find_jdk():
    if os.environ.get("JAVA_HOME"):
        return os.path.join(os.environ["JAVA_HOME"], "bin")
    cands = sorted(glob.glob(os.path.join(os.path.expanduser("~"), ".jdks", "jdk-25*")))
    if cands:
        return os.path.join(cands[-1], "bin")
    return os.path.dirname(shutil.which("java") or "java")


def find_jar(group_path, name):
    pat = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1", group_path, name, "*", "*", f"{name}-*.jar")
    jars = sorted(j for j in glob.glob(pat) if not j.endswith("-sources.jar") and not j.endswith("-javadoc.jar"))
    if not jars:
        sys.exit(f"{name} jar not found in the Gradle cache ({pat}); run a host build once")
    return jars[-1]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    jdk = find_jdk()
    javac = os.path.join(jdk, "javac")
    java = os.path.join(jdk, "java")
    gson = find_jar("com.google.code.gson", "gson")
    jspec = find_jar("org.jspecify", "jspecify")
    out = tempfile.mkdtemp(prefix="armacraft-jbench-")
    cp = os.pathsep.join([gson, jspec, out])
    srcs = [os.path.join(HOST_SRC, f) for f in HOST_FILES] + [os.path.join(HERE, "GsonParseBench.java"), os.path.join(HERE, "HostTickBench.java")]
    subprocess.run([javac, "--release", "25", "-nowarn", "-d", out, "-cp", cp, *srcs], check=True)
    print(f"# jdk {jdk}\n# gson {os.path.basename(gson)}\n", flush=True)
    for f in sys.argv[1:]:
        print(f"## {f}\n### GsonParseBench (Gson tree parse; host Protocol.parse)", flush=True)
        subprocess.run([java, "-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-cp", cp, "GsonParseBench", f], check=True)
        print("### HostTickBench (per-tick server-thread work: Extrapolate + render mirror; current vs memo)", flush=True)
        subprocess.run([java, "-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-cp", cp, "HostTickBench", f], check=True)
    shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    main()
