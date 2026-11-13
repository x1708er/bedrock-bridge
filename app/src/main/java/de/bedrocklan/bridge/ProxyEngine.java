package de.bedrocklan.bridge;

import android.content.Context;

import org.webrtc.PeerConnectionFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import de.bedrocklan.bridge.bedrock.BedrockBridge;
import de.bedrocklan.bridge.discovery.Discovery;
import de.bedrocklan.bridge.discovery.LanClient;
import de.bedrocklan.bridge.nethernet.NetherNetClient;

/**
 * Lässt einen entfernten Bedrock-Server im LAN erscheinen — komplett ohne RakNet:
 * - LAN-Discovery auf UDP 7551 (verschlüsselte NetherNet-Broadcasts)
 * - Geräte (PC/Konsole) verbinden sich per WebRTC über Discovery-Signaling
 * - Pro Gerät wird ein eigener NetherNet-Upstream (WebRTC) zum echten Server
 *   aufgebaut und 1:1 durchgebrückt
 */
final class ProxyEngine implements LanClient.Host, BedrockBridge.Listener {
    private static final long IDLE_MS = 120_000;

    private final Context appContext;
    private final String host;
    private final int port;
    private final long networkId = new Random().nextLong();

    private final Map<Long, LanClient> clients = new ConcurrentHashMap<>();       // connId → Gerät
    private final Map<Long, BedrockBridge> bridges = new ConcurrentHashMap<>();    // connId → Bridge
    private final Map<Long, InetSocketAddress> peers = new ConcurrentHashMap<>(); // Geräte-Netz-ID → Adresse

    private volatile boolean running;
    private volatile String problem = "Starte …";
    private DatagramSocket socket;
    private PeerConnectionFactory factory;
    private NetherNetClient upstreamStatus;
    private volatile Discovery.ServerData serverData;

    ProxyEngine(Context appContext, String host, int port) {
        this.appContext = appContext;
        this.host = host;
        this.port = port;
    }

    void start() throws IOException {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions());
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
        upstreamStatus = new NetherNetClient(host, port);

        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new InetSocketAddress(Discovery.PORT));
        socket = s;
        running = true;
        spawn(this::statusLoop, "status");
        spawn(this::mainLoop, "main");
        spawn(this::cleanupLoop, "cleanup");
    }

    void stop() {
        running = false;
        close(socket);
        for (BedrockBridge b : bridges.values()) b.close("beendet");
        bridges.clear();
        clients.clear();
        peers.clear();
        if (factory != null) factory.dispose();
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
            } catch (RuntimeException e) {
                // ein kaputtes Paket darf die Schleife nie beenden
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
        if (parts.length != 3) return;
        String type = parts[0];
        long connId;
        try {
            connId = Long.parseUnsignedLong(parts[1].trim());
        } catch (NumberFormatException e) {
            return;
        }
        switch (type) {
            case "CONNECTREQUEST": {
                LanClient existing = clients.get(connId);
                if (existing == null) {
                    LanClient client = new LanClient(factory, this, connId, senderId);
                    clients.put(connId, client);
                    client.handleOffer(parts[2]);
                }
                break;
            }
            case "CANDIDATEADD": {
                LanClient client = clients.get(connId);
                if (client != null) client.addCandidate(parts[2]);
                break;
            }
            case "CONNECTERROR": {
                LanClient client = clients.remove(connId);
                BedrockBridge bridge = bridges.remove(connId);
                if (bridge != null) bridge.close("Gerät: CONNECTERROR");
                else if (client != null) client.close("CONNECTERROR", false);
                break;
            }
            default:
                break;
        }
    }

    private void statusLoop() {
        while (running) {
            try {
                NetherNetClient.Status status = upstreamStatus.fetchStatus(2500);
                if (status != null) {
                    serverData = buildServerData(status);
                    problem = null;
                } else {
                    problem = "Server bietet kein NetherNet an (HTTP-Endpunkt fehlt).";
                }
            } catch (IOException e) {
                if (!running) return;
                problem = "Server antwortet nicht. Adresse und Port prüfen.";
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
            for (LanClient c : clients.values()) c.tick(IDLE_MS);
            publishStatus();
        }
    }

    // ------------------------------------------------------------- LanClient.Host

    @Override
    public void sendSignal(LanClient client, String type, String data) {
        InetSocketAddress to = peers.get(client.peerNetworkId());
        if (to == null) return;
        String signal = type + " " + Long.toUnsignedString(client.connectionId()) + " " + data;
        byte[] out = Discovery.marshalMessage(networkId, client.peerNetworkId(), signal);
        send(to, out);
    }

    @Override
    public void onOpen(LanClient client) {
        if (!running) return;
        BedrockBridge bridge = new BedrockBridge(factory, client, host, port, this);
        bridges.put(client.connectionId(), bridge);
        bridge.start();
        publishStatus();
    }

    @Override
    public void onMessage(LanClient client, byte[] message) {
        BedrockBridge bridge = bridges.get(client.connectionId());
        if (bridge != null) bridge.fromClient(message);
    }

    @Override
    public void onClosed(LanClient client, String reason) {
        clients.remove(client.connectionId());
        BedrockBridge bridge = bridges.get(client.connectionId());
        if (bridge != null) {
            bridge.close("Gerät: " + reason);
        }
        publishStatus();
    }

    // ------------------------------------------------------------- BedrockBridge.Listener

    @Override
    public void onBridgeClosed(BedrockBridge bridge, String reason) {
        clients.remove(bridge.client().connectionId());
        bridges.remove(bridge.client().connectionId());
        publishStatus();
    }

    // ---------------------------------------------------------------- Status & Hilfen

    private void publishStatus() {
        if (!running) return;
        int n = bridges.size();
        State.clients = n;
        String p = problem;
        if (p != null) {
            State.status = p;
        } else if (n > 0) {
            State.status = "Läuft. Gerät ist verbunden und spielt.";
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
