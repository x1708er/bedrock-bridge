package de.bedrocklan.bridge;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import de.bedrocklan.bridge.discovery.Discovery;
import de.bedrocklan.bridge.nethernet.JoinRelay;
import de.bedrocklan.bridge.nethernet.NetherNetClient;

/**
 * Lässt einen entfernten Bedrock-Server im LAN erscheinen — als reiner
 * NetherNet-Signalisierungs-Relay ohne RakNet:
 * - LAN-Discovery auf UDP 7551 (verschlüsselte Broadcasts nach Spec)
 * - Geräte (Konsole/PC) senden ihren Verbindungswunsch (WebRTC-Offer) über
 *   die Discovery-Nachrichten; die App reicht ihn an POST /v1/join des
 *   Servers weiter und bringt das Answer zurück. Spielverkehr läuft danach
 *   direkt zwischen Gerät und Server, jeder Spieler mit eigenem Konto.
 */
final class ProxyEngine implements JoinRelay.Sender {
    private static final long IDLE_MS = 120_000;

    private final String host;
    private final int port;
    private final long networkId = new Random().nextLong();

    private final Map<Long, JoinRelay> joins = new ConcurrentHashMap<>();      // connId → Relay
    private final Map<Long, Long> joinAt = new ConcurrentHashMap<>();          // connId → Startzeit
    private final Map<Long, InetSocketAddress> peers = new ConcurrentHashMap<>(); // Geräte-ID → Adresse

    private volatile boolean running;
    private volatile String problem = "Starte …";
    private DatagramSocket socket;
    private NetherNetClient upstream;
    private volatile Discovery.ServerData serverData;

    ProxyEngine(String host, int port) {
        this.host = host;
        this.port = port;
    }

    void start() throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new InetSocketAddress(Discovery.PORT));
        socket = s;
        upstream = new NetherNetClient(host, port);
        running = true;
        spawn(this::statusLoop, "status");
        spawn(this::mainLoop, "main");
        spawn(this::cleanupLoop, "cleanup");
    }

    void stop() {
        running = false;
        close(socket);
        joins.clear();
        joinAt.clear();
        peers.clear();
        State.clients = 0;
    }

    // ---------------------------------------------------------------- Schleifen

    private void mainLoop() {
        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                Discovery.Packet pk = Discovery.unmarshal(buf, p.getLength());
                if (pk == null || pk.senderId == networkId) continue;
                InetSocketAddress from = new InetSocketAddress(p.getAddress(), p.getPort());
                if (pk.id == Discovery.ID_REQUEST) {
                    respond(from);
                } else if (pk.id == Discovery.ID_MESSAGE && pk.recipientId == networkId) {
                    peers.put(pk.senderId, from);
                    handleSignal(pk.senderId, pk.messageData);
                }
            } catch (IOException e) {
                if (!running) return;
                sleep(100);
            } catch (Throwable e) {
                // ein kaputtes Paket darf die Schleife nie beenden
                Guard.logError("Discovery-Paket", e);
            }
        }
    }

    private void respond(InetSocketAddress to) {
        Discovery.ServerData data = serverData;
        if (data == null) return;
        byte[] out = Discovery.marshalResponse(networkId, data.encode());
        send(to, out);
    }

    private void handleSignal(long senderId, String data) {
        String[] parts = data.split(" ", 3);
        if (parts.length != 3) return; // inklusive Ping/Keepalive des Geräts
        long connId;
        try {
            connId = Long.parseUnsignedLong(parts[1].trim());
        } catch (NumberFormatException e) {
            return;
        }
        switch (parts[0]) {
            case "CONNECTREQUEST": {
                if (parts[2].trim().isEmpty()) return;
                if (joins.containsKey(connId)) return; // Duplikat ignorieren
                Guard.log("Verbindungswunsch von Gerät " + Long.toUnsignedString(senderId)
                        + " (Verbindung " + Long.toUnsignedString(connId) + "), Offer "
                        + parts[2].length() + " Bytes");
                JoinRelay relay = new JoinRelay(upstream, this, connId, senderId, parts[2]);
                joins.put(connId, relay);
                joinAt.put(connId, System.currentTimeMillis());
                Thread t = new Thread(() -> {
                    relay.run();
                    joins.remove(connId);
                    joinAt.remove(connId);
                }, "join-relay");
                t.setDaemon(true);
                t.start();
                break;
            }
            case "CANDIDATEADD": {
                JoinRelay relay = joins.get(connId);
                if (relay != null) relay.addCandidate(parts[2]);
                break;
            }
            case "CONNECTERROR": {
                joins.remove(connId);
                joinAt.remove(connId);
                break;
            }
            default:
                break;
        }
    }

    private void statusLoop() {
        while (running) {
            try {
                NetherNetClient.Status status = upstream.fetchStatus(2500);
                if (status != null) {
                    serverData = buildServerData(status);
                    problem = null;
                } else {
                    problem = "Server bietet kein NetherNet an (HTTP-Endpunkt fehlt).";
                }
            } catch (IOException e) {
                if (!running) return;
                problem = "Server antwortet nicht. Adresse und Port prüfen.";
            } catch (Throwable e) {
                if (!running) return;
                Guard.logError("Status-Abruf", e);
                problem = "Serverstatus-Fehler.";
            }
            publishStatus();
            if (!sleep(5000)) return;
        }
    }

    private static Discovery.ServerData buildServerData(NetherNetClient.Status s) {
        Discovery.ServerData d = new Discovery.ServerData();
        d.serverName = NetherNetClient.Status.plain(s.name);
        d.protocol = s.protocol;
        d.version = s.version;
        d.levelName = NetherNetClient.Status.plain(s.level);
        d.playerCount = s.players;
        d.maxPlayerCount = s.maxPlayers;
        d.gameType = s.gameType;
        d.editorWorld = s.editor;
        d.hardcore = s.hardcore;
        d.onlineAuth = s.onlineAuth;
        d.selfSignedAuth = s.selfSignedAuth;
        d.nonce = s.nonce != null && !s.nonce.isEmpty() ? s.nonce
                : Discovery.hexEncode(randomBytes(8));
        d.connectionType = 4;
        return d;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random().nextBytes(b);
        return b;
    }

    private void cleanupLoop() {
        while (running) {
            if (!sleep(5000)) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<Long, Long> e : joinAt.entrySet()) {
                if (now - e.getValue() > 30_000) {
                    joins.remove(e.getKey());
                    joinAt.remove(e.getKey());
                }
            }
            publishStatus();
        }
    }

    // ------------------------------------------------------------- JoinRelay.Sender

    @Override
    public void sendSignal(long peerNetId, String type, long connId, String data) {
        InetSocketAddress to = peers.get(peerNetId);
        if (to == null) return;
        String signal = type + " " + Long.toUnsignedString(connId) + " " + data;
        byte[] out = Discovery.marshalMessage(networkId, peerNetId, signal);
        send(to, out);
    }

    // ---------------------------------------------------------------- Status & Hilfen

    private void publishStatus() {
        if (!running) return;
        int n = joins.size();
        State.clients = n;
        String p = problem;
        if (p != null) {
            State.status = p;
        } else if (n > 0) {
            State.status = "Läuft. Verbinde " + n + " Gerät" + (n == 1 ? "" : "e") + " mit dem Server …";
        } else {
            State.status = "Läuft. Server via NetherNet erreichbar. Jetzt Minecraft öffnen und in den LAN-Spielen wählen.";
        }
    }

    private void send(InetSocketAddress to, byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException ignored) {
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
}
