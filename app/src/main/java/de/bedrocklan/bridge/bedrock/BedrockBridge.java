package de.bedrocklan.bridge.bedrock;

import android.content.Context;

import org.webrtc.PeerConnectionFactory;

import de.bedrocklan.bridge.Diagnostics;
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
    private final de.bedrocklan.bridge.nethernet.NetherIdentity identity;
    private final de.bedrocklan.bridge.auth.AccountManager account;
    private final LoginRewriter loginRewriter;

    private WebRtcUpstream upstream;
    private final List<byte[]> outbox = new ArrayList<>();
    private volatile boolean upstreamReady;
    private volatile boolean closed;

    public BedrockBridge(PeerConnectionFactory factory, LanClient client,
                         NetherNetClient signaling,
                         de.bedrocklan.bridge.nethernet.NetherIdentity identity,
                         de.bedrocklan.bridge.auth.AccountManager account,
                         Listener listener) {
        this.factory = factory;
        this.client = client;
        this.signaling = signaling;
        this.listener = listener;
        this.identity = identity;
        this.account = account;
        this.loginRewriter = new LoginRewriter(account, identity);
    }

    public LanClient client() {
        return client;
    }

    /** Baut den Upstream zum echten Server im Hintergrund auf. */
    public void start() {
        Thread t = new Thread(() -> {
            try {
                WebRtcUpstream up = new WebRtcUpstream(factory, signaling, identity, account, this);
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

    /**
     * Nachricht vom Gerät Richtung Server; das Login wird immer auf das
     * App-Konto umgeschrieben. Scheitert das, wird das Original-Paket nie
     * weitergesendet und die Verbindung mit Fehlergrund geschlossen.
     *
     * Senden und Outbox-Flush laufen beide unter demselben outbox-Lock:
     * upstreamReady wird gesetzt, die gestapelten Pakete werden in ihrer
     * Original-Reihenfolge geflusht und direkt gesendete Pakete reihen sich
     * danach sauber ein — die Paketreihenfolge kann nie vertauscht werden.
     */
    public void fromClient(byte[] message) {
        if (closed) return;
        try {
            byte[] rewritten = loginRewriter.process(message);
            if (rewritten != null) message = rewritten;
        } catch (LoginRewriter.LoginFailedException e) {
            Guard.logError("Login abgelehnt", e);
            close("Login abgelehnt: " + e.getMessage());
            return;
        } catch (Throwable e) {
            Guard.logError("Login-Umschreibung fehlgeschlagen", e);
            close("Login-Umschreibung fehlgeschlagen");
            return;
        }
        synchronized (outbox) {
            if (!upstreamReady) {
                if (outbox.size() < OUTBOX_LIMIT) {
                    outbox.add(message);
                } else {
                    Diagnostics.log("bridge", "Outbox übergelaufen ("
                            + outbox.size() + " Pakete) – Paket verworfen");
                    Guard.logError("Outbox übergelaufen (" + outbox.size()
                            + " Pakete) – Paket verworfen", null);
                }
                return;
            }
            WebRtcUpstream up = upstream;
            if (up == null) return;
            try {
                up.sendMessage(message);
            } catch (Throwable e) {
                Guard.logError("Senden zum Server", e);
            }
        }
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
