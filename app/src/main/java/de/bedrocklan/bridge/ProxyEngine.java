package de.bedrocklan.bridge;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lässt einen entfernten Bedrock-Server im LAN erscheinen (Prinzip wie "phantom"):
 * - beantwortet RakNet-Pings (UDP 19132) mit dem MOTD des echten Servers
 * - leitet alle anderen Pakete pro Gerät an den echten Server weiter und zurück
 */
final class ProxyEngine {
    static final int LOCAL_PORT = 19132;

    private static final byte[] MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};
    private static final long OFFLINE_AFTER_MS = 10_000;
    private static final long SESSION_IDLE_MS = 60_000;
    private static final String NOT_REACHABLE =
            "Läuft, aber der Server antwortet nicht. Adresse und Port prüfen.";

    private final String host;
    private final int port;
    private final long guid = new Random().nextLong();
    private final Map<InetSocketAddress, Session> sessions = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile InetSocketAddress remote;
    private volatile String remoteInfo;
    private volatile long lastPong;
    private volatile String problem = "Starte …";
    private DatagramSocket mainSocket;
    private volatile DatagramSocket pingSocket;

    ProxyEngine(String host, int port) {
        this.host = host;
        this.port = port;
    }

    void start() throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new InetSocketAddress(LOCAL_PORT));
        mainSocket = s;
        running = true;
        spawn(this::pingLoop, "pinger");
        spawn(this::mainLoop, "main");
        spawn(this::cleanupLoop, "cleanup");
    }

    void stop() {
        running = false;
        close(mainSocket);
        close(pingSocket);
        for (Session s : sessions.values()) s.close();
        sessions.clear();
        State.clients = 0;
    }

    // ---------------------------------------------------------------- Schleifen

    private void mainLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                mainSocket.receive(p);
                int len = p.getLength();
                if (len < 1) continue;
                InetSocketAddress client = new InetSocketAddress(p.getAddress(), p.getPort());
                int id = buf[0] & 0xff;
                if (id == 0x01 || id == 0x02) {
                    handlePing(client, buf, len);
                } else {
                    forward(client, buf, len);
                }
            } catch (IOException e) {
                if (!running) return;
                sleep(100);
            } catch (RuntimeException e) {
                // ein kaputtes Paket darf die Schleife nie beenden
            }
        }
    }

    private void pingLoop() {
        byte[] rb = new byte[2048];
        try (DatagramSocket ps = new DatagramSocket()) {
            ps.setSoTimeout(2000);
            pingSocket = ps;
            while (running) {
                try {
                    InetSocketAddress r = new InetSocketAddress(host, port);
                    if (r.isUnresolved()) {
                        remoteInfo = null;
                        problem = "Adresse nicht gefunden. Tippfehler oder kein Internet?";
                    } else {
                        remote = r;
                        byte[] ping = buildPing();
                        ps.send(new DatagramPacket(ping, ping.length, r));
                        DatagramPacket rp = new DatagramPacket(rb, rb.length);
                        ps.receive(rp);
                        String info = parsePong(rb, rp.getLength());
                        if (info != null) {
                            remoteInfo = info;
                            lastPong = System.currentTimeMillis();
                            problem = null;
                        } else {
                            problem = NOT_REACHABLE;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    problem = NOT_REACHABLE;
                } catch (IOException e) {
                    if (!running) return;
                    problem = "Keine Netzwerkverbindung.";
                }
                publishStatus();
                if (!sleep(3000)) return;
            }
        } catch (IOException e) {
            if (running) {
                problem = "Netzwerkfehler: " + e.getMessage();
                publishStatus();
            }
        }
    }

    private void cleanupLoop() {
        while (running) {
            if (!sleep(10_000)) return;
            long now = System.currentTimeMillis();
            for (Session s : sessions.values()) {
                if (now - s.lastSeen > SESSION_IDLE_MS) s.close();
            }
            publishStatus();
        }
    }

    // ---------------------------------------------------------------- Pakete

    private void handlePing(InetSocketAddress client, byte[] buf, int len) throws IOException {
        if (len < 25) return;
        for (int i = 0; i < 16; i++) {
            if (buf[9 + i] != MAGIC[i]) return;
        }
        String info = remoteInfo;
        if (info == null || System.currentTimeMillis() - lastPong > OFFLINE_AFTER_MS) return;

        byte[] str = rewrite(info).getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(35 + str.length);
        b.put((byte) 0x1c);
        b.put(buf, 1, 8);              // Zeitstempel des Clients zurückgeben
        b.putLong(guid);
        b.put(MAGIC);
        b.putShort((short) str.length);
        b.put(str);
        mainSocket.send(new DatagramPacket(b.array(), b.position(), client));
    }

    private void forward(InetSocketAddress client, byte[] buf, int len) throws IOException {
        Session s = sessions.get(client);
        if (s == null) {
            InetSocketAddress r = remote;
            if (r == null || r.isUnresolved()) return;
            s = new Session(client, r);
            sessions.put(client, s);
            s.start();
            publishStatus();
        }
        s.lastSeen = System.currentTimeMillis();
        s.toRemote(buf, len);
    }

    /** Eigene Server-ID setzen und Ports auf unseren Port umschreiben (wie phantom). */
    private String rewrite(String info) {
        String[] f = info.split(";", -1);
        if (f.length > 6) f[6] = Long.toString(guid >>> 1);
        if (f.length > 10 && !f[10].isEmpty()) f[10] = Integer.toString(LOCAL_PORT);
        if (f.length > 11 && !f[11].isEmpty()) f[11] = Integer.toString(LOCAL_PORT);
        return String.join(";", f);
    }

    private byte[] buildPing() {
        ByteBuffer b = ByteBuffer.allocate(33);
        b.put((byte) 0x01);
        b.putLong(System.currentTimeMillis());
        b.put(MAGIC);
        b.putLong(guid);
        return b.array();
    }

    private static String parsePong(byte[] b, int len) {
        if (len < 35 || b[0] != 0x1c) return null;
        int sl = ((b[33] & 0xff) << 8) | (b[34] & 0xff);
        if (sl > len - 35) return null;
        return new String(b, 35, sl, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- Status & Hilfen

    private void publishStatus() {
        if (!running) return;
        int n = sessions.size();
        State.clients = n;
        String p = problem;
        if (p != null) {
            State.status = p;
        } else if (n > 0) {
            State.status = "Läuft. Server erreichbar. Konsole ist verbunden.";
        } else {
            State.status = "Läuft. Server erreichbar. Jetzt in Minecraft auf der Konsole die LAN-Spiele öffnen.";
        }
    }

    private static void spawn(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static void close(DatagramSocket s) {
        if (s != null) s.close();
    }

    /** Eine Verbindung einer Konsole: eigener Socket zum echten Server. */
    private final class Session {
        final InetSocketAddress client;
        final InetSocketAddress target;
        final DatagramSocket up;
        volatile long lastSeen = System.currentTimeMillis();

        Session(InetSocketAddress client, InetSocketAddress target) throws IOException {
            this.client = client;
            this.target = target;
            this.up = new DatagramSocket();
        }

        void start() {
            spawn(this::pump, "session");
        }

        void toRemote(byte[] b, int len) {
            try {
                up.send(new DatagramPacket(b, 0, len, target));
            } catch (IOException ignored) {
            }
        }

        private void pump() {
            byte[] b = new byte[4096];
            try {
                while (running) {
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    up.receive(p);
                    lastSeen = System.currentTimeMillis();
                    mainSocket.send(new DatagramPacket(b, 0, p.getLength(), client));
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        void close() {
            up.close();
            sessions.remove(client, this);
        }
    }
}
