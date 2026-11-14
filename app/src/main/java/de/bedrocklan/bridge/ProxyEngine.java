package de.bedrocklan.bridge;

import android.content.Context;

import org.webrtc.PeerConnectionFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Random;

import de.bedrocklan.bridge.bedrock.BedrockBridge;
import de.bedrocklan.bridge.discovery.Discovery;
import de.bedrocklan.bridge.discovery.LanClient;
import de.bedrocklan.bridge.nethernet.NetherNetClient;

/**
 * Lässt einen entfernten Bedrock-Server im LAN erscheinen — komplett ohne RakNet:
 * - LAN-Discovery auf UDP 7551 (verschlüsselte NetherNet-Broadcasts)
 * - Das Gerät verbindet sich per WebRTC über Discovery-Signaling
 * - Ein NetherNet-Upstream (WebRTC) zum echten Server wird 1:1 durchgebrückt
 *
 * Designentscheidung: genau EIN verbundenes Gerät. Ein zweiter
 * Verbindungswunsch wird mit CONNECTERROR abgelehnt; nur ein Rejoin
 * desselben Geräts (gleiche connId vom selben Absender) wird nach
 * sauberem Aufräumen akzeptiert.
 */
final class ProxyEngine implements LanClient.Host, BedrockBridge.Listener {
    private static final long IDLE_MS = 120_000;
    private static final long CONNECT_DEADLINE_MS = 30_000;

    private final Context appContext;
    private final String host;
    private final int port;
    private final long networkId = new Random().nextLong();

    // Aktive Verbindung: client bis zum Ende (auch parallel zur Bridge), bridge ab onOpen.
    private final Object connLock = new Object();
    private LanClient client;
    private BedrockBridge bridge;
    private InetSocketAddress peerAddress; // pro Verbindung gepinnte Signaling-Adresse
    private long peerId = -1;
    private long clientCreatedAt;

    private volatile boolean running;
    private volatile String problem;
    private DatagramSocket socket;
    private PeerConnectionFactory factory;
    private NetherNetClient upstreamStatus;
    private volatile Discovery.ServerData serverData;
    private de.bedrocklan.bridge.nethernet.NetherIdentity identity;
    private de.bedrocklan.bridge.auth.AccountManager account;
    private volatile String joinProblem;
    private volatile long joinProblemAt;

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
        account = new de.bedrocklan.bridge.auth.AccountManager(appContext);
        try {
            identity = de.bedrocklan.bridge.nethernet.NetherIdentity.generate();
        } catch (Throwable e) {
            Guard.logError("Identitätsschlüssel fehlgeschlagen", e);
        }
        if (identity != null) {
            account.prewarm(identity.publicKey());
        }

        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setBroadcast(true);
        s.bind(new InetSocketAddress(Discovery.PORT));
        socket = s;
        running = true;
        problem = appContext.getString(R.string.status_starting);
        spawn(this::statusLoop, "status");
        spawn(this::mainLoop, "main");
        spawn(this::cleanupLoop, "cleanup");
    }

    void stop() {
        running = false;
        synchronized (connLock) {
            teardownLocked("beendet");
        }
        close(socket);
        final org.webrtc.PeerConnectionFactory f = factory;
        factory = null;
        if (f != null) Guard.closeLater(f::dispose);
        State.clients = 0;
        State.clearServerInfo();
    }

    private static String d(String s) {
        return s == null ? "" : s;
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
                    long activePeer;
                    synchronized (connLock) {
                        activePeer = peerId;
                        if (activePeer == pk.senderId && peerAddress != null) {
                            peerAddress = from;
                        }
                    }
                    if (activePeer == pk.senderId) {
                        handleSignal(pk.senderId, from, pk.messageData);
                    }
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
        Guard.log("Discovery-Anfrage beantwortet an " + to);
    }

    private void handleSignal(long senderId, InetSocketAddress from, String data) {
        String[] parts = data.split(" ", 3);
        if (parts.length != 3) {
            Guard.log("Unverständliches Signal (" + parts.length + " Teilen)");
            return;
        }
        String type = parts[0];
        long connId;
        try {
            connId = Long.parseUnsignedLong(parts[1].trim());
        } catch (NumberFormatException e) {
            return;
        }
        switch (type) {
            case "CONNECTREQUEST": {
                // Annahme-Entscheidung und Zuweisung unter connLock; die native
                // WebRTC-Arbeit (handleOffer) läuft danach außerhalb des Locks.
                // Das ist sicher, weil handleSignal ausschließlich vom mainLoop-
                // Thread aufgerufen wird (Signaling ist damit serialisiert) und
                // kein anderer connLock-Nutzer den frisch angelegten LanClient
                // vor dem Offer sichtbar zerlegen kann (Timeouts greifen erst
                // nach CONNECT_DEADLINE_MS, native Callbacks gibt es vor dem
                // Offer noch nicht).
                LanClient newClient = null;
                synchronized (connLock) {
                    if (client != null || bridge != null) {
                        if (senderId == peerId) {
                            // Rejoin desselben Geräts (auch mit neuer connId):
                            // alte Verbindung sauber aufräumen, dann annehmen.
                            teardownLocked("Neuverbindung des Geräts");
                            newClient = acceptLocked(senderId, connId, from);
                        } else {
                            Guard.log("Weiteres Gerät abgelehnt (bereits verbunden)");
                            setJoinProblem("Weiteres Gerät abgelehnt: bereits ein Gerät verbunden.");
                        }
                    } else {
                        newClient = acceptLocked(senderId, connId, from);
                    }
                }
                if (newClient != null) {
                    newClient.handleOffer(parts[2]);
                } else {
                    String signal = "CONNECTERROR " + Long.toUnsignedString(connId)
                            + " bereits ein Gerät verbunden";
                    send(from, Discovery.marshalMessage(networkId, senderId, signal));
                }
                publishStatus();
                break;
            }
            case "CANDIDATEADD": {
                LanClient c;
                synchronized (connLock) {
                    c = senderId == peerId ? client : null;
                    if (c != null && c.connectionId() != connId) c = null;
                }
                if (c != null) c.addCandidate(parts[2]); // native Arbeit außerhalb connLock
                break;
            }
            case "CONNECTERROR": {
                synchronized (connLock) {
                    if (senderId == peerId && (client != null || bridge != null)
                            && connId == activeConnIdLocked()) {
                        teardownLocked("Gerät: CONNECTERROR");
                    }
                }
                publishStatus();
                break;
            }
            default:
                break;
        }
    }

    private long activeConnIdLocked() {
        if (client != null) return client.connectionId();
        if (bridge != null) return bridge.client().connectionId();
        return -1;
    }

    /** Legt den LanClient für einen angenommenen Verbindungswunsch an (nur unter connLock). */
    private LanClient acceptLocked(long senderId, long connId, InetSocketAddress from) {
        Guard.log("Verbindungswunsch von Gerät " + Long.toUnsignedString(senderId)
                + " (Verbindung " + Long.toUnsignedString(connId)
                + "), Offer wartet");
        client = new LanClient(factory, this, connId, senderId);
        peerAddress = from;
        peerId = senderId;
        clientCreatedAt = System.currentTimeMillis();
        return client;
    }

    /** Räumt die aktive Verbindung komplett auf (nur unter connLock). */
    private void teardownLocked(String reason) {
        LanClient c = client;
        BedrockBridge b = bridge;
        client = null;
        bridge = null;
        peerAddress = null;
        peerId = -1;
        if (b != null) b.close(reason);
        else if (c != null) c.close(reason, false);
    }

    private void statusLoop() {
        while (running) {
            try {
                NetherNetClient.Status status = upstreamStatus.fetchStatus(2500);
                if (status != null) {
                    serverData = buildServerData(status);
                    State.setServerInfo(d(serverData.serverName), d(serverData.levelName),
                            serverData.playerCount, serverData.maxPlayerCount, d(serverData.version));
                    problem = null;
                } else {
                    problem = appContext.getString(R.string.status_no_nethernet);
                }
            } catch (IOException e) {
                if (!running) return;
                problem = appContext.getString(R.string.status_unreachable);
            } catch (Throwable e) {
                if (!running) return;
                Guard.logError("Status-Abruf", e);
                problem = appContext.getString(R.string.status_probe_error);
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
            try {
                long now = System.currentTimeMillis();
                LanClient c;
                boolean expired = false;
                synchronized (connLock) {
                    c = client;
                    if (c != null && !c.isOpen() && now - clientCreatedAt > CONNECT_DEADLINE_MS) {
                        expired = true;
                        teardownLocked("Verbindungsaufbau vom Gerät nicht abgeschlossen");
                    }
                }
                if (expired) {
                    setJoinProblem("Gerät hat die Verbindung nicht fertig aufgebaut (Timeout).");
                }
                if (c != null && c.isOpen()) c.tick(IDLE_MS);
            } catch (Throwable e) {
                Guard.logError("Aufräumen", e);
            }
            publishStatus();
        }
    }

    private void setJoinProblem(String reason) {
        joinProblem = reason;
        joinProblemAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------- LanClient.Host

    @Override
    public void sendSignal(LanClient from, String type, String data) {
        InetSocketAddress to;
        synchronized (connLock) {
            if (client != from) return;
            to = peerAddress;
        }
        if (to == null) return;
        String signal = type + " " + Long.toUnsignedString(from.connectionId()) + " " + data;
        byte[] out = Discovery.marshalMessage(networkId, from.peerNetworkId(), signal);
        send(to, out);
    }

    @Override
    public void onOpen(LanClient opened) {
        if (!running) return;
        try {
            Guard.log("Gerät verbunden, verbinde zum Server …");
            BedrockBridge b = new BedrockBridge(factory, opened, upstreamStatus, identity, account, this);
            synchronized (connLock) {
                if (client != opened || bridge != null) {
                    b.close("Verbindung wurde ersetzt");
                    return;
                }
                bridge = b;
            }
            b.start();
        } catch (Throwable e) {
            Guard.logError("Brückenstart", e);
        }
        publishStatus();
    }

    @Override
    public void onMessage(LanClient from, byte[] message) {
        try {
            BedrockBridge b;
            synchronized (connLock) {
                b = (client == from) ? bridge : null;
            }
            if (b != null) b.fromClient(message);
        } catch (Throwable e) {
            Guard.logError("Weiterleitung zum Server", e);
        }
    }

    @Override
    public void onClosed(LanClient closed, String reason) {
        Guard.log("Gerät getrennt: " + reason);
        BedrockBridge b = null;
        synchronized (connLock) {
            if (client == closed) {
                client = null;
                b = bridge;
                if (b != null) bridge = null;
            }
        }
        if (b != null) b.close("Gerät: " + reason);
        publishStatus();
    }

    // ------------------------------------------------------------- BedrockBridge.Listener

    @Override
    public void onBridgeClosed(BedrockBridge closed, String reason) {
        boolean wasActive;
        synchronized (connLock) {
            wasActive = bridge == closed || client == closed.client();
            if (bridge == closed) {
                bridge = null;
            }
            if (client == closed.client()) {
                client = null;
                peerAddress = null;
                peerId = -1;
            }
        }
        if (wasActive && !closed.isReady() && reason != null && !reason.contains("beendet")) {
            setJoinProblem(reason);
        }
        publishStatus();
    }

    // ---------------------------------------------------------------- Status & Hilfen

    private void publishStatus() {
        if (!running) return;
        int n;
        boolean playing;
        synchronized (connLock) {
            n = bridge != null ? 1 : 0;
            playing = bridge != null && bridge.isReady();
        }
        State.clients = n;
        String p = problem;
        if (joinProblem != null && System.currentTimeMillis() - joinProblemAt < 30_000) {
            State.status = joinProblem;
            return;
        }
        if (p != null) {
            State.status = p;
        } else if (n > 0) {
            State.status = playing
                    ? appContext.getString(R.string.status_active)
                    : appContext.getString(R.string.status_linked);
        } else {
            State.status = appContext.getString(R.string.status_idle);
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
