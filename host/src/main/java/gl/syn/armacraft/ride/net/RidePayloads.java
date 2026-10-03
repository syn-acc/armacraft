package gl.syn.armacraft.ride.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ride mode payloads. Registered on both sides from common code; the server only sends them to players whose
 * client registered the channel ({@code ServerPlayNetworking.canSend}), so vanilla clients never see them.
 *
 * <pre>
 * client -> server  armacraft:ride_request  toggle / start / stop (key R, sneak)
 *                   armacraft:ride_state    every tick while riding: pose, speed, rubber, brake, new trail points
 *                   armacraft:ride_crash    rubber ran out against a wall (client-predicted own crash)
 * server -> client  armacraft:ride_start    you ride now: settings (the server's armacraft-ride.json), heading, colour
 *                   armacraft:cycle         /arma cycle [on|off]: switch the client's cycle mode
 *                   armacraft:ride_settings /arma reload, /arma preset: new settings for the running bike; the client
 *                                           also re-reads its own armacraft-ride.json (keys)
 *                   armacraft:whatsnew      /arma whatsnew: print the build stamp and what's-new notes
 *                   armacraft:ride_stop     you stopped (dismount, crash, server reason)
 *                   armacraft:ride_sync     other riders: pose + trail points since the last sync to this viewer
 * </pre>
 */
public final class RidePayloads {

	public static final String NS = "armacraft";
	/** Bounds on decoded collections (abuse guard). */
	public static final int MAX_POINTS = 4096;
	public static final int MAX_RIDERS = 256;

	private RidePayloads() {}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NS, path);
	}

	// ---------------------------------------------------------------- client -> server

	/**
	 * @param action 0 toggle, 1 start, 2 stop; yaw = facing (heading snaps to its nearest cardinal)
	 * @param rgb    preferred rider colour 0xRRGGBB, -1 = let the server pick
	 * @param name   preferred rider name ("" = the player name)
	 */
	public record Request(int action, float yaw, int rgb, String name) implements CustomPacketPayload {
		public static final int TOGGLE = 0;
		public static final int START = 1;
		public static final int STOP = 2;
		public static final Type<Request> TYPE = new Type<>(id("ride_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.action);
				b.writeFloat(p.yaw);
				b.writeInt(p.rgb);
				b.writeUtf(p.name, 32);
			},
			b -> new Request(b.readVarInt(), b.readFloat(), b.readInt(), b.readUtf(32)));

		public Request(int action, float yaw) {
			this(action, yaw, -1, "");
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Rider state, sent every tick while riding.
	 *
	 * @param trailFrom absolute index of the first point in {@code points} (the owner's last point is provisional
	 *                  and resent until it stops changing)
	 * @param points    xyz triples
	 * @param segs      turn counter per point
	 */
	public record State(int heading, float speed, float rubberLeft, float brake, boolean braking, int turns, long trailFrom,
		long trailFirst, double[] points, int[] segs) implements CustomPacketPayload {
		public static final Type<State> TYPE = new Type<>(id("ride_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeByte(p.heading & 3);
				b.writeFloat(p.speed);
				b.writeFloat(p.rubberLeft);
				b.writeFloat(p.brake);
				b.writeBoolean(p.braking);
				b.writeVarInt(p.turns);
				b.writeVarLong(p.trailFrom);
				b.writeVarLong(p.trailFirst);
				writePoints(b, p.points, p.segs);
			},
			b -> {
				int heading = b.readByte() & 3;
				float speed = b.readFloat();
				float rubber = b.readFloat();
				float brake = b.readFloat();
				boolean braking = b.readBoolean();
				int turns = b.readVarInt();
				long from = b.readVarLong();
				long first = b.readVarLong();
				int n = readCount(b, MAX_POINTS);
				double[] pts = new double[n * 3];
				int[] segs = new int[n];
				readPoints(b, n, pts, segs);
				return new State(heading, speed, rubber, brake, braking, turns, from, first, pts, segs);
			});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The client's own crash: rubber ran out against a wall (terrain or trail). */
	public record Crash(double x, double y, double z, String cause) implements CustomPacketPayload {
		public static final Type<Crash> TYPE = new Type<>(id("ride_crash"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Crash> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeDouble(p.x);
				b.writeDouble(p.y);
				b.writeDouble(p.z);
				b.writeUtf(p.cause, 64);
			},
			b -> new Crash(b.readDouble(), b.readDouble(), b.readDouble(), b.readUtf(64)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- server -> client

	/** You ride now. {@code configJson} is the server's armacraft-ride.json. */
	public record Start(String configJson, int heading, int rgb) implements CustomPacketPayload {
		public static final Type<Start> TYPE = new Type<>(id("ride_start"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeUtf(p.configJson, 32767);
				b.writeByte(p.heading & 3);
				b.writeInt(p.rgb);
			},
			b -> new Start(b.readUtf(32767), b.readByte() & 3, b.readInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Cycle mode on the client: {@code /arma cycle [on|off]}. action 0 toggle, 1 on, 2 off. */
	public record Cycle(int action) implements CustomPacketPayload {
		public static final int TOGGLE = 0;
		public static final int ON = 1;
		public static final int OFF = 2;
		public static final Type<Cycle> TYPE = new Type<>(id("cycle"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Cycle> CODEC = CustomPacketPayload.codec(
			(p, b) -> b.writeVarInt(p.action), b -> new Cycle(b.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** New physics settings while riding (/arma preset): the server's resolved armacraft-ride.json. */
	public record Settings(String configJson) implements CustomPacketPayload {
		public static final Type<Settings> TYPE = new Type<>(id("ride_settings"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Settings> CODEC = CustomPacketPayload.codec(
			(p, b) -> b.writeUtf(p.configJson, 32767), b -> new Settings(b.readUtf(32767)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** /arma whatsnew: print the client's build stamp and config/armacraft-whatsnew.txt in chat. No content. */
	public record WhatsNew() implements CustomPacketPayload {
		public static final Type<WhatsNew> TYPE = new Type<>(id("whatsnew"));
		public static final StreamCodec<RegistryFriendlyByteBuf, WhatsNew> CODEC = CustomPacketPayload.codec((p, b) -> {}, b -> new WhatsNew());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** @param reason 0 dismount, 1 crash, 2 stopped by the server */
	public record Stop(int reason, String message) implements CustomPacketPayload {
		public static final int DISMOUNT = 0;
		public static final int CRASH = 1;
		public static final int SERVER = 2;
		public static final Type<Stop> TYPE = new Type<>(id("ride_stop"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Stop> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.reason);
				b.writeUtf(p.message, 256);
			},
			b -> new Stop(b.readVarInt(), b.readUtf(256)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * One other rider as seen by a viewer.
	 *
	 * @param flags      bit 0 riding, bit 1 crashed
	 * @param stoppedFor seconds since the rider stopped (fade), 0 while riding
	 * @param reset      clear the viewer's copy before applying {@code points}
	 * @param trailFirst owner's first held index (viewer drops older points)
	 */
	public record Rider(UUID uuid, int entityId, int rgb, int flags, double x, double y, double z, int heading, float speed,
		float stoppedFor, boolean reset, long trailFirst, long trailFrom, double[] points, int[] segs) {

		public boolean riding() {
			return (flags & 1) != 0;
		}
	}

	public record Sync(List<Rider> riders, List<UUID> gone) implements CustomPacketPayload {
		public static final Type<Sync> TYPE = new Type<>(id("ride_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = CustomPacketPayload.codec(
			(p, b) -> {
				b.writeVarInt(p.riders.size());
				for (Rider r : p.riders) {
					b.writeUUID(r.uuid);
					b.writeVarInt(r.entityId);
					b.writeInt(r.rgb);
					b.writeByte(r.flags);
					b.writeDouble(r.x);
					b.writeDouble(r.y);
					b.writeDouble(r.z);
					b.writeByte(r.heading & 3);
					b.writeFloat(r.speed);
					b.writeFloat(r.stoppedFor);
					b.writeBoolean(r.reset);
					b.writeVarLong(r.trailFirst);
					b.writeVarLong(r.trailFrom);
					writePoints(b, r.points, r.segs);
				}
				b.writeVarInt(p.gone.size());
				for (UUID u : p.gone) {
					b.writeUUID(u);
				}
			},
			b -> {
				int n = readCount(b, MAX_RIDERS);
				List<Rider> riders = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					UUID uuid = b.readUUID();
					int eid = b.readVarInt();
					int rgb = b.readInt();
					int flags = b.readByte();
					double x = b.readDouble();
					double y = b.readDouble();
					double z = b.readDouble();
					int heading = b.readByte() & 3;
					float speed = b.readFloat();
					float stopped = b.readFloat();
					boolean reset = b.readBoolean();
					long first = b.readVarLong();
					long from = b.readVarLong();
					int k = readCount(b, MAX_POINTS);
					double[] pts = new double[k * 3];
					int[] segs = new int[k];
					readPoints(b, k, pts, segs);
					riders.add(new Rider(uuid, eid, rgb, flags, x, y, z, heading, speed, stopped, reset, first, from, pts, segs));
				}
				int g = readCount(b, MAX_RIDERS);
				List<UUID> gone = new ArrayList<>(g);
				for (int i = 0; i < g; i++) {
					gone.add(b.readUUID());
				}
				return new Sync(riders, gone);
			});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- helpers

	static void writePoints(RegistryFriendlyByteBuf b, double[] pts, int[] segs) {
		int n = pts.length / 3;
		b.writeVarInt(n);
		for (int i = 0; i < n; i++) {
			b.writeDouble(pts[3 * i]);
			b.writeDouble(pts[3 * i + 1]);
			b.writeDouble(pts[3 * i + 2]);
			b.writeVarInt(segs != null && i < segs.length ? segs[i] : 0);
		}
	}

	static void readPoints(RegistryFriendlyByteBuf b, int n, double[] pts, int[] segs) {
		for (int i = 0; i < n; i++) {
			pts[3 * i] = b.readDouble();
			pts[3 * i + 1] = b.readDouble();
			pts[3 * i + 2] = b.readDouble();
			segs[i] = b.readVarInt();
		}
	}

	static int readCount(RegistryFriendlyByteBuf b, int max) {
		int n = b.readVarInt();
		if (n < 0 || n > max) {
			throw new IllegalArgumentException("armacraft ride payload: count " + n + " > " + max);
		}
		return n;
	}

	private static boolean registered;

	/** Registers all types once (common init). */
	public static synchronized void register() {
		if (registered) {
			return;
		}
		registered = true;
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(State.TYPE, State.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Crash.TYPE, Crash.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Start.TYPE, Start.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Stop.TYPE, Stop.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Cycle.TYPE, Cycle.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Settings.TYPE, Settings.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WhatsNew.TYPE, WhatsNew.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(Sync.TYPE, Sync.CODEC, 1 << 20);
	}
}
