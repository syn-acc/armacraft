package gl.syn.armacraft.gametest;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Minimal Source RCON client for tests against a local dev server. Reads rcon.port and rcon.password from the
 * server's server.properties, so no secret ever appears on a command line or in a log.
 */
final class Rcon implements AutoCloseable {

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private int nextId = 1;

	static Rcon fromServerProperties(Path props) throws IOException {
		Properties p = new Properties();
		try (InputStream s = Files.newInputStream(props)) {
			p.load(s);
		}
		int port = Integer.parseInt(p.getProperty("rcon.port", "25575"));
		Rcon r = new Rcon(new Socket("127.0.0.1", port));
		if (!r.login(p.getProperty("rcon.password", ""))) {
			r.close();
			throw new IOException("rcon login refused");
		}
		return r;
	}

	private Rcon(Socket socket) throws IOException {
		this.socket = socket;
		this.socket.setSoTimeout(10_000);
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
	}

	private boolean login(String password) throws IOException {
		int id = send(3, password);
		return read()[0] == id;
	}

	String command(String cmd) throws IOException {
		send(2, cmd);
		int[] head = new int[1];
		String body = readBody(head);
		return body;
	}

	private int send(int type, String payload) throws IOException {
		byte[] p = payload.getBytes(StandardCharsets.UTF_8);
		int id = nextId++;
		ByteBuffer b = ByteBuffer.allocate(14 + p.length).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(10 + p.length).putInt(id).putInt(type).put(p).put((byte) 0).put((byte) 0);
		out.write(b.array());
		out.flush();
		return id;
	}

	private int[] read() throws IOException {
		int[] head = new int[1];
		readBody(head);
		return head;
	}

	private String readBody(int[] idOut) throws IOException {
		DataInputStream d = new DataInputStream(in);
		byte[] lenBytes = d.readNBytes(4);
		int len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		byte[] rest = d.readNBytes(len);
		ByteBuffer b = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
		idOut[0] = b.getInt();
		b.getInt(); // type
		return new String(rest, 8, Math.max(0, len - 10), StandardCharsets.UTF_8);
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}
}
