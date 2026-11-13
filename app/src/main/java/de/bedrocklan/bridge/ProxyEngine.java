package de.bedrocklan.bridge;

import android.content.Context;

import de.bedrocklan.bridge.bedrock.BedrockBridge;
import de.bedrocklan.bridge.nethernet.NetherNetClient;
import de.bedrocklan.bridge.raknet.RakNet;
import de.bedrocklan.bridge.raknet.RakSession;

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
 * Lässt einen entfernten Bedrock-Server im LAN erscheinen:
 * - NetherNet-Modus: Server via WebRTC (/v1/join) verbinden, Konsole via eigenem
 *   RakNet-Server (Port 19132) ankoppeln. MOTD kommt aus dem HTTP-Status.
 * - Fallback: alter RakNet-UDP-Relay, falls der Server kein NetherNet anbietet.
 */
final class ProxyEngine implements RakSession.Host, BedrockBridge.Listener {
    static final int LOCAL_PORT = 19132;

    private static final long OFFLINE_AFTER_MS = 10_000;
    private static final long SESSION_IDLE_MS = 120_000;
    private static final String NOT_REACHABLE =
            "Läuft, aber der Server antwortet nicht. Adresse und Port prüfen.";

    private final Context appContext;
    private final String host;
    private final int port;
    private final long guid = new Random().nextLong();
    private final Map<InetSocketAddress, RakSession> rakSessions = new ConcurrentHashMap<>();
    private final Map<RakSession, BedrockBridge> bridges = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, RelaySession> relays = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile boolean netherNetMode;
    private volatile String netherNetMotd;
    private volatile String remoteInfo; // MOTD des Servers (RakNet-Fallback)
    private volatile long lastPong;
    private volatile String problem = "Starte …";
    private DatagramSocket mainSocket;
    private volatile DatagramSocket pingSocket;

    ProxyEngine(Context appContext, String host, int port) {
        this.appContext = appContext;
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
        spawn(this::statusLoop, "status");
        spawn(this::mainLoop, "main");
        spawn(this::cleanupLoop, "cleanup");
    }

    void stop() {
        running = false;
        close(mainSocket);
        close(pingSocket);
        for (RakSession s : rakSessions.values()) {
            BedrockBridge b = bridges.remove(s);
            if (b != null) b.close("beendet");
            s.close();
        }
        rakSessions.clear();
        for (RelaySession s : relays.values()) s.close();
        relays.clear();
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
                } else if (netherNetMode && relays.isEmpty()) {
                    handleNetherNetPacket(client, buf, len);
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

    private void handleNetherNetPacket(InetSocketAddress client, byte[] buf, int len) {
        if ((buf[0] & 0xe0) == 0x80 || buf[0] == RakNet.FLAG_ACK || buf[0] == RakNet.FLAG_NAK) {
            RakSession s = rakSessions.get(client);
            if (s != null) s.handleDatagram(buf, len);
            return;
        }
        RakSession s = rakSessions.get(client);
        if (s == null) {
            s = new RakSession(this, client);
            rakSessions.put(client, s);
        }
        if (!s.handleOffline(buf, len)) {
            // uninteressantes Offline-Paket (z. B. alter Relay-Rest)
        }
    }

    private void statusLoop() {
        byte[] rb = new byte[2048];
        try (DatagramSocket ps = new DatagramSocket()) {
            ps.setSoTimeout(2000);
            pingSocket = ps;
            while (running) {
                try {
                    // 1. Versuch: NetherNet-Status über den HTTP-Endpunkt des Servers.
                    NetherNetClient.Status status = new NetherNetClient(host, port).fetchStatus(2500);
                    if (status != null) {
                        netherNetMode = true;
                        netherNetMotd = status.rakNetMotd(guid >>> 1);
                        lastPong = System.currentTimeMillis();
                        problem = null;
                    } else if (!netherNetMode || rakSessions.isEmpty() && relays.isEmpty()) {
                        netherNetMode = false;
                        // 2. Fallback: klassischer RakNet-Ping.
                        rakNetPing(ps, rb);
                    }
                } catch (SocketTimeoutException e) {
                    if (rakSessions.isEmpty() && relays.isEmpty()) problem = NOT_REACHABLE;
                } catch (IOException e) {
                    if (!running) return;
                    if (rakSessions.isEmpty() && relays.isEmpty()) problem = "Keine Netzwerkverbindung.";
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

    private void rakNetPing(DatagramSocket ps, byte[] rb) throws IOException {
        InetSocketAddress r = new InetSocketAddress(host, port);
        if (r.isUnresolved()) {
            problem = "Adresse nicht gefunden. Tippfehler oder kein Internet?";
            return;
        }
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

    private void cleanupLoop() {
        while (running) {
            if (!sleep(500)) return;
            for (RakSession s : rakSessions.values()) s.tick();
            long now = System.currentTimeMillis();
            for (RelaySession s : relays.values()) {
                if (now - s.lastSeen > SESSION_IDLE_MS) s.close();
            }
            publishStatus();
        }
    }

    // ---------------------------------------------------------------- Pakete

    private void handlePing(InetSocketAddress client, byte[] buf, int len) throws IOException {
        if (len < 25) return;
        for (int i = 0; i < 16; i++) {
            if (buf[9 + i] != RakNet.MAGIC[i]) return;
        }
        String info;
        if (netherNetMode && netherNetMotd != null) {
            info = netherNetMotd;
            if (System.currentTimeMillis() - lastPong > 60_000) return; // Status veraltet
        } else {
            info = remoteInfo;
            if (info == null || System.currentTimeMillis() - lastPong > OFFLINE_AFTER_MS) return;
            info = rewrite(info);
        }
        byte[] str = info.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(35 + str.length);
        b.put((byte) 0x1c);
        b.put(buf, 1, 8);              // Zeitstempel des Clients zurückgeben
        b.putLong(guid);
        b.put(RakNet.MAGIC);
        b.putShort((short) str.length);
        b.put(str);
        mainSocket.send(new DatagramPacket(b.array(), b.position(), client));
    }

    // ------------------------------------------------------------- RakSession.Host

    @Override
    public void sendUdp(InetSocketAddress to, byte[] data) {
        try {
            mainSocket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException ignored) {
        }
    }

    @Override
    public long serverGuid() {
        return guid;
    }

    @Override
    public void onGameBatch(RakSession session, byte[] batch, int len) {
        BedrockBridge bridge = bridges.get(session);
        if (bridge == null) {
            bridge = new BedrockBridge(appContext, session, host, port, this);
            bridges.put(session, bridge);
            bridge.start();
        }
        bridge.fromConsole(batch);
    }

    @Override
    public void onDisconnect(RakSession session, String reason) {
        rakSessions.remove(session.client());
        BedrockBridge bridge = bridges.remove(session);
        if (bridge != null) bridge.close(reason);
        session.close();
    }

    @Override
    public void onLog(String msg) {
        // Diagnoseausgaben werden aktuell nicht in der Oberfläche angezeigt.
    }

    // ------------------------------------------------------------- BedrockBridge.Listener

    @Override
    public void onBridgeClosed(BedrockBridge bridge, String reason) {
        bridges.remove(bridge.session());
        rakSessions.remove(bridge.session().client());
        bridge.session().close();
    }

    // ---------------------------------------------------------------- Relay (RakNet-Fallback)

    private void forward(InetSocketAddress client, byte[] buf, int len) throws IOException {
        RelaySession s = relays.get(client);
        if (s == null) {
            InetSocketAddress r = new InetSocketAddress(host, port);
            if (r.isUnresolved()) return;
            s = new RelaySession(client, r);
            relays.put(client, s);
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
        b.put(RakNet.MAGIC);
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
        int n = rakSessions.size() + relays.size();
        State.clients = n;
        State.netherNet = netherNetMode;
        String p = problem;
        if (p != null) {
            State.status = p;
        } else if (n > 0) {
            State.status = "Läuft. Konsole ist verbunden.";
        } else if (netherNetMode) {
            State.status = "Läuft. Server via NetherNet erreichbar. Jetzt in Minecraft auf der Konsole die LAN-Spiele öffnen.";
        } else {
            State.status = "Läuft. Server erreichbar (RakNet). Jetzt in Minecraft auf der Konsole die LAN-Spiele öffnen.";
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

    /** Eine Verbindung einer Konsole im RakNet-Fallback: eigener Socket zum echten Server. */
    private final class RelaySession {
        final InetSocketAddress client;
        final InetSocketAddress target;
        final DatagramSocket up;
        volatile long lastSeen = System.currentTimeMillis();

        RelaySession(InetSocketAddress client, InetSocketAddress target) throws IOException {
            this.client = client;
            this.target = target;
            this.up = new DatagramSocket();
        }

        void start() {
            spawn(this::pump, "relay");
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
            relays.remove(client, this);
        }
    }
}
