package de.bedrocklan.bridge.bedrock;

import android.content.Context;

import de.bedrocklan.bridge.nethernet.NetherNetClient;
import de.bedrocklan.bridge.nethernet.WebRtcUpstream;
import de.bedrocklan.bridge.raknet.RakSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Koppelt eine Konsole (RakNet, per RakSession terminiert) mit genau einem
 * NetherNet-Upstream (WebRTC) zum echten Server und schiebt Bedrock-Batches
 * zwischen den beiden Transporten um.
 *
 * Das Batch-Format ist auf beiden Seiten identisch (siehe gophertunnel):
 * RakNet:   [0xFE][algo][zlib([varuint len][pkt]...)]
 * NetherNet:       [algo][zlib([varuint len][pkt]...)]
 * Vor den NetworkSettings fehlt das algo-Byte auf beiden Seiten.
 */
public final class BedrockBridge implements WebRtcUpstream.Listener {
    public interface Listener {
        void onBridgeClosed(BedrockBridge bridge, String reason);
    }

    private static final int BATCH_HEADER = 0xfe;
    private static final int OUTBOX_LIMIT = 256;

    private final RakSession session;
    private final NetherNetClient signaling;
    private final WebRtcUpstream upstream;
    private final Listener listener;

    // Kompressionszustand wird nicht getrackt: Batches sind transparente Blobs,
    // beide Enden handeln NetworkSettings synchron über uns aus.
    private final List<byte[]> outbox = new ArrayList<>(); // bis der Kanal offen ist
    private final AtomicBoolean upstreamReady = new AtomicBoolean(false);
    private volatile boolean closed;

    public BedrockBridge(Context appContext, RakSession session, String host, int port, Listener listener) {
        this.session = session;
        this.signaling = new NetherNetClient(host, port);
        this.listener = listener;
        this.upstream = new WebRtcUpstream(appContext, signaling, this);
    }

    public RakSession session() {
        return session;
    }

    /** Startet den WebRTC-Aufbau im Hintergrund. */
    public void start() {
        Thread t = new Thread(() -> {
            try {
                upstream.connect();
                synchronized (outbox) {
                    upstreamReady.set(true);
                    for (byte[] m : outbox) upstream.sendMessage(m);
                    outbox.clear();
                }
            } catch (Exception e) {
                close("NetherNet-Verbindung fehlgeschlagen: " + e.getMessage());
            }
        }, "nethernet-upstream");
        t.setDaemon(true);
        t.start();
    }

    /** Batch-Paket der Konsole ([0xFE]...) Richtung Server. */
    public void fromConsole(byte[] batch) {
        if (closed || batch.length < 2 || (batch[0] & 0xff) != BATCH_HEADER) return;
        byte[] message = new byte[batch.length - 1];
        System.arraycopy(batch, 1, message, 0, message.length);
        sendUpstream(message);
    }

    /** NetherNet-Nachricht ([algo][zlib-entries] bzw. [entries]) Richtung Konsole. */
    @Override
    public void onMessage(byte[] message) {
        if (closed || message.length == 0) return;
        byte[] batch = new byte[message.length + 1];
        batch[0] = (byte) BATCH_HEADER;
        System.arraycopy(message, 0, batch, 1, message.length);
        session.sendFrame(batch, 3);
    }

    @Override
    public void onClosed(String reason) {
        close("Server: " + reason);
    }

    @Override
    public void onLog(String msg) {
        // Diagnoseausgaben werden aktuell nicht in der Oberfläche angezeigt.
    }

    private void sendUpstream(byte[] message) {
        synchronized (outbox) {
            if (!upstreamReady.get()) {
                if (outbox.size() < OUTBOX_LIMIT) outbox.add(message);
                return;
            }
        }
        upstream.sendMessage(message);
    }

    public void close(String reason) {
        if (closed) return;
        closed = true;
        upstream.closeUpstream(reason);
        session.close();
        listener.onBridgeClosed(this, reason);
    }
}
