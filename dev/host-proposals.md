# Proposals for `host/` (and one for the guest patch)

None of this is applied. `host/` belongs to the agent that is editing it. Each Java diff below was
prototyped in a scratch copy of `host/` taken on 2026-10-02 at about 00:26, compiled there with
`gradlew classes`, and run on the dev server (`dev/mcserver`) through `dev/host.ps1 -HostDir <copy>`.
Line numbers can drift. The context lines are what matter.

The dev loop in `dev/` works without any of these. They remove the remaining restarts.

| # | what | why | measured |
|---|------|-----|----------|
| 1 | `/arma reload` | apply `config/armacraft.json` edits without restarting MC | a restart is 18-28 s end to end; `reload` took 0.11 / 0.19 s (scale 1→2→1 applied, verified in `/arma status`) |
| 2 | `mod armacraft <version>` line in `/arma status` | `dev/status.ps1` and deploy checks can read the live mod version over RCON instead of parsing `latest.log` | verified in the scratch copy: `mod armacraft 0.1.0` |
| 3 | bridge reconnect backoff cap 10 s → 2 s | after a guest *container* restart (the `guest/run.ps1` flow), MC idles up to 10 s before retrying | measured lag after the guest was ready: 8.1 s (35 s outage), 2.1 s (22 s outage). The cap bounds it at 2 s. The cap itself was not built or tested |
| 4 | runServer hotswap hooks in `build.gradle` | for anyone who stays on `gradlew runServer`: JBR + dynamic agent, so `dev/host.ps1 hotswap -TargetPid <pid>` works there too | verified in a scratch copy under JBR: 4.0-4.5 s per hotswap, edit live |
| G1 | guest: shorter idle-nap `select` | a just-started guest accepts the bridge after up to 1 s, because the nap loop sleeps in `Select(1.0)` | guest restart → hello 1.3 s → 0.4-0.7 s (edited in the dev volume's tree only, then reverted) |

---

## 1 + 2. `/arma reload` and the version line in `/arma status`

`ArmaController.config` is already non-final and is passed into the renderer and sessions every tick,
so swapping it is safe. `ArmaConfig.load` sanitises and re-saves, like the startup path does.
After applying this, `dev\host.ps1 rcon "arma reload"` applies config edits in place.

```diff
--- a/src/main/java/gl/syn/armacraft/ArmaController.java
+++ b/src/main/java/gl/syn/armacraft/ArmaController.java
@@ -147,6 +147,23 @@
 		dirtyLayout = true;
 	}
 
+	/**
+	 * Re-read config/armacraft.json without a server restart (/arma reload). Layout is rebuilt next
+	 * tick; the bridge reconnects only if host or port changed.
+	 */
+	public String reloadConfig() {
+		ArmaConfig fresh = ArmaConfig.load(configFile);
+		boolean endpoint = !fresh.host.equals(config.host) || fresh.port != config.port;
+		config = fresh;
+		markLayoutDirty();
+		if (endpoint && bridge.isRunning()) {
+			disconnect();
+			connect(fresh.host, fresh.port);
+		}
+		return "[arma] reloaded " + configFile.getFileName()
+			+ (endpoint ? ", reconnecting to " + fresh.host + ":" + fresh.port : "");
+	}
+
 	private void resetArena() {
 		renderer.clear();
 		sessions.clearCams();
@@ -419,6 +436,8 @@
 			.append(String.format("%.1f %.1f %.1f", config.anchor.x, config.anchor.y, config.anchor.z))
 			.append(", scale ").append(config.scale);
 		sb.append("\nlines in/out ").append(bridge.linesIn()).append('/').append(bridge.linesOut());
+		sb.append("\nmod armacraft ").append(net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(Armacraft.MOD_ID)
+			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
 		sb.append("\nplayers: ").append(sessions.all().size());
 		for (SessionManager.Session s : sessions.all()) {
 			sb.append("\n  ").append(s.name).append(' ').append(s.phase);
--- a/src/main/java/gl/syn/armacraft/command/ArmaCommands.java
+++ b/src/main/java/gl/syn/armacraft/command/ArmaCommands.java
@@ -19,7 +19,7 @@
 import net.minecraft.world.phys.Vec3;
 
 /**
- * /arma connect [host] [port] | disconnect | anchor | scale &lt;f&gt; | join | leave | status | cmd &lt;line...&gt;
+ * /arma connect [host] [port] | disconnect | reload | anchor | scale &lt;f&gt; | join | leave | status | cmd &lt;line...&gt;
  *       | debug turn &lt;player&gt; left|right | debug brake &lt;player&gt; on|off
  *
  * <p>connect, disconnect, anchor, scale, cmd and debug need gamemaster permission (op level 2): they move
@@ -52,6 +52,12 @@
 			.then(Commands.literal("join").executes(ArmaCommands::join))
 			.then(Commands.literal("leave").executes(ArmaCommands::leave))
 			.then(Commands.literal("status").executes(ArmaCommands::status))
+			.then(Commands.literal("reload")
+				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
+				.executes(ctx -> {
+					ok(ctx, c().reloadConfig());
+					return 1;
+				}))
 			.then(Commands.literal("debug")
 				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
 				.then(Commands.literal("turn")
```

The files are CRLF in the working tree. If `git apply` complains, use `git apply --ignore-whitespace`, or
paste the hunks by hand.

## 3. Reconnect backoff cap

The client doubles its backoff from 0.5 s up to 10 s while the guest is unreachable. With
`guest/run.ps1` (rebuild → `docker rm -f` → `docker run`) the published port disappears for about 35 s, so
the backoff reaches its 10 s cap, and MC picks the guest back up 0-10 s *after* it is ready. On
localhost a 2 s cap costs nothing. The dev loop (`dev/guest.ps1`) never removes the container, so it
does not hit this.

```diff
--- a/src/main/java/gl/syn/armacraft/bridge/BridgeClient.java
+++ b/src/main/java/gl/syn/armacraft/bridge/BridgeClient.java
@@
 	private static final int CONNECT_TIMEOUT_MS = 3000;
 	private static final long BACKOFF_MIN_MS = 500;
-	private static final long BACKOFF_MAX_MS = 10_000;
+	private static final long BACKOFF_MAX_MS = 2_000;
```

`BridgeClientTest` does not depend on the cap (it waits 700 ms for one failed attempt).

## 4. runServer hotswap (optional, only for the `gradlew runServer` flow)

Loom 1.18.2 has no hotswap support of its own. Its jar contains no `AllowEnhancedClassRedefinition`
handling and no mixin java agent. To make runServer hotswappable with the same tool:

```diff
--- a/build.gradle
+++ b/build.gradle
@@ loom {
 	runs {
+		// dev hotswap (dev/host.ps1 hotswap -Pid <runServer pid>): allow the attach agent; with
+		// -Parmacraft.jbr (gradle started with JAVA_HOME = a JetBrains Runtime) also structural changes.
+		server {
+			vmArg "-XX:+EnableDynamicAgentLoading"
+			if (project.hasProperty("armacraft.jbr")) {
+				vmArg "-XX:+AllowEnhancedClassRedefinition"
+			}
+		}
 		// ./gradlew runGametest
```

`-XX:+AllowEnhancedClassRedefinition` must stay behind the property. A non-JBR JVM refuses to start
with it ("Unrecognized VM option").

## G1. Guest: wake the idle nap for the bridge (patch proposal, not applied)

`gGame.cpp`'s dedicated-server nap loop sleeps in `sn_BasicNetworkSystem.Select( 1.0f )`, which only
watches the game's UDP sockets. A bridge host that connects while the server naps (every fresh start,
since nobody is connected yet) waits up to 1 s for `accept()`. In
`guest/patches/0001-armacraft-bridge-hooks.patch`, add to the gGame.cpp section:

```diff
@@ (gGame.cpp, dedicated nap loop in sg_EnterGameCore / own_game prologue)
             sr_Read_stdin();
             st_DoToDo();
             gGame::NetSyncIdle();
 
-            sn_BasicNetworkSystem.Select( 1.0f );
+            // armacraft: poll the bridge listener often enough that a host connecting to a napping
+            // server gets its hello promptly (Select only watches the game's UDP sockets)
+            sn_BasicNetworkSystem.Select( 0.1f );
```

This is the exact edit that was measured: `src/tron/gGame.cpp` line 1942 at the pinned commit. Idle cost is 10 wakeups/s
while nobody is connected. In the patch file it becomes one more hunk in the gGame.cpp section (the
nap loop is in the dedicated-server prologue before `own_game( nSERVER )`).
