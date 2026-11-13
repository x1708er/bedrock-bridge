package de.bedrocklan.bridge.bedrock;

import android.content.Context;

import org.webrtc.PeerConnectionFactory;

import de.bedrocklan.bridge.Guard;
import de.bedrocklan.bridge.discovery.LanClient;
import de.bedrocklan.bridge.nethernet.NetherNetClient;
import de.bedrocklan.bridge.nethernet.WebRtcUpstream;

import java.util.ArrayList;
import java.util.List;

/**
 * Koppelt ein Gerät im LAN (WebRTC über NetherNet-Discovery) mit genau einem
 * NetherNet-Upstream (WebRTC zum echten Server). Beide Seiten sprechen dasselbe
 * Nachrichtenformat, daher werden vollständige NetherNet-Nachrichten 1:1
 * weitergereicht.
 */
public final class BedrockBridge implements WebRtcUpstream.Listener {
    public interface Listener {
        void onBridgeClosed(BedrockBridge bridge, String reason);
    }

    private static final int OUTBOX_LIMIT = 256;

    private final PeerConnectionFactory factory;
    private final LanClient client;
    private final NetherNetClient signaling;
    private final Listener listener;

    private WebRtcUpstream upstream;
    private final List<byte[]> outbox = new ArrayList<>();
    private volatile boolean upstreamReady;
    private volatile boolean closed;

    public BedrockBridge(PeerConnectionFactory factory, LanClient client,
                         String host, int port, Listener listener) {
        this.factory = factory;
        this.client = client;
        this.signaling = new NetherNetClient(host, port);
        this.listener = listener;
    }

    public LanClient client() {
        return client;
    }

    /** Baut den Upstream zum echten Server im Hintergrund auf. */
    public void start() {
        Thread t = new Thread(() -> {
            try {
                WebRtcUpstream up = new WebRtcUpstream(factory, signaling, this);
                upstream = up;
                up.connect();
                Guard.log("Upstream zum Server verbunden");
                synchronized (outbox) {
                    upstreamReady = true;
                    for (byte[] m : outbox) up.sendMessage(m);
                    outbox.clear();
                }
            } catch (Throwable e) {
                Guard.logError("Upstream fehlgeschlagen", e);
                close("NetherNet-Verbindung zum Server fehlgeschlagen: " + e.getMessage());
            }
        }, "nethernet-upstream");
        t.setDaemon(true);
        t.start();
    }

    /** Liefert true, sobald der Upstream zum Server offen ist. */
    public boolean isReady() {
        return upstreamReady;
    }

    /** Nachricht vom Gerät Richtung Server. */
    public void fromClient(byte[] message) {
        if (closed) return;
        synchronized (outbox) {
            if (!upstreamReady) {
                if (outbox.size() < OUTBOX_LIMIT) outbox.add(message);
                return;
            }
        }
        upstream.sendMessage(message);
    }

    /** Nachricht vom Server Richtung Gerät. */
    @Override
    public void onMessage(byte[] message) {
        if (closed) return;
        try {
            client.sendMessage(message);
        } catch (Throwable e) {
            Guard.logError("Weiterleitung zum Gerät", e);
        }
    }

    @Override
    public void onClosed(String reason) {
        close("Server: " + reason);
    }

    @Override
    public void onLog(String msg) {
        Guard.log(msg);
    }

    public void close(String reason) {
        if (closed) return;
        closed = true;
        WebRtcUpstream up = upstream;
        upstream = null;
        if (up != null) up.closeUpstream(reason);
        client.close(reason, false);
        listener.onBridgeClosed(this, reason);
    }
}
