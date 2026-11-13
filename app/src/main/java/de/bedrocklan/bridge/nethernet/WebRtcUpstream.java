package de.bedrocklan.bridge.nethernet;

import android.content.Context;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebRTC-Datenkanal zum Bedrock-Server (NetherNet): SDP-Aushandlung über den
 * HTTP-Endpunkt des Servers, dann ReliableDataChannel mit NetherNet-Segment-Framing.
 */
public final class WebRtcUpstream {
    /** Vanilla-negotiierte maximale SCTP-Nachrichtengröße minus Segmentzähler-Byte. */
    private static final int SEGMENT_PAYLOAD = 262143;

    public interface Listener {
        void onMessage(byte[] netherNetMessage);

        void onClosed(String reason);

        void onLog(String msg);
    }

    private final Context appContext;
    private final NetherNetClient signaling;
    private final Listener listener;

    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private DataChannel reliable;

    private final List<String> candidates = new ArrayList<>();

    // Segment-Reassembly des empfangenen Datenstroms
    private byte[] pending;
    private int pendingRemaining = -1;

    private volatile boolean closed;

    public WebRtcUpstream(Context appContext, NetherNetClient signaling, Listener listener) {
        this.appContext = appContext;
        this.signaling = signaling;
        this.listener = listener;
    }

    /** Baut die WebRTC-Verbindung auf (blockiert bis DataChannel offen oder Fehler). */
    public void connect() throws IOException, InterruptedException {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions());
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        final CountDownLatch gatherComplete = new CountDownLatch(1);
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(
                Collections.singletonList(
                        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                                .createIceServer()));
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState s) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                if (s == PeerConnection.IceConnectionState.FAILED) {
                    closeUpstream("ICE FAILED");
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) gatherComplete.countDown();
            }

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                synchronized (candidates) {
                    candidates.add("a=" + candidate.sdp);
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] c) {}

            @Override
            public void onAddStream(MediaStream s) {}

            @Override
            public void onRemoveStream(MediaStream s) {}

            @Override
            public void onDataChannel(DataChannel dc) {
                // Der Server darf beim Client-Kanal keine Kanäle öffnen.
                closeUpstream("Server öffnete unerwartet Kanal " + dc.label());
            }

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
        });
        if (pc == null) throw new IOException("PeerConnection konnte nicht erzeugt werden");

        // Beide Kanäle wie der Vanilla-Client erzeugen (DCEP führt zum Server).
        DataChannel.Init reliableInit = new DataChannel.Init();
        reliableInit.ordered = true;
        DataChannel.Init unreliableInit = new DataChannel.Init();
        unreliableInit.ordered = false;
        unreliableInit.maxRetransmits = 0;

        reliable = pc.createDataChannel("ReliableDataChannel", reliableInit);
        DataChannel unreliable = pc.createDataChannel("UnreliableDataChannel", unreliableInit);
        if (reliable == null || unreliable == null) {
            throw new IOException("DataChannels konnten nicht erzeugt werden");
        }
        unreliable.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {}

            @Override
            public void onStateChange() {}

            @Override
            public void onMessage(DataChannel.Buffer b) {}
        });
        final CountDownLatch channelOpen = new CountDownLatch(1);
        reliable.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {}

            @Override
            public void onStateChange() {
                if (reliable.state() == DataChannel.State.OPEN) channelOpen.countDown();
                if (reliable.state() == DataChannel.State.CLOSED) closeUpstream("Kanal geschlossen");
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                byte[] data = new byte[buffer.data.remaining()];
                buffer.data.get(data);
                try {
                    handleIncoming(data);
                } catch (RuntimeException e) {
                    closeUpstream("Nachricht defekt: " + e.getMessage());
                }
            }
        });

        // Offer erzeugen und lokal setzen.
        final CountDownLatch offerDone = new CountDownLatch(1);
        final String[] error = new String[1];
        pc.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                pc.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription d) {}

                    @Override
                    public void onSetSuccess() {
                        offerDone.countDown();
                    }

                    @Override
                    public void onCreateFailure(String s) {}

                    @Override
                    public void onSetFailure(String s) {
                        error[0] = "setLocalDescription: " + s;
                        offerDone.countDown();
                    }
                }, offer);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String s) {
                error[0] = "createOffer: " + s;
                offerDone.countDown();
            }

            @Override
            public void onSetFailure(String s) {}
        }, new MediaConstraints());

        if (!offerDone.await(10, TimeUnit.SECONDS) || error[0] != null) {
            throw new IOException(error[0] != null ? error[0] : "createOffer-Timeout");
        }

        // Non-trickle ICE: auf vollständige Kandidatensammlung warten, dann alles ins SDP.
        if (!gatherComplete.await(10, TimeUnit.SECONDS)) {
            listener.onLog("ICE-Sammlung unvollständig, sende trotzdem");
        }

        String offerSdp = pc.getLocalDescription().description;
        StringBuilder munged = new StringBuilder(offerSdp);
        synchronized (candidates) {
            for (String c : candidates) {
                if (!offerSdp.contains(c.substring(2))) munged.append(c).append('\n');
            }
        }

        // Offer an den Server posten (CONNECTREQUEST-Äquivalent), Answer setzen.
        String answerSdp = signaling.exchangeSdp(munged.toString(), 12_000);
        final CountDownLatch remoteSet = new CountDownLatch(1);
        pc.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription d) {}

            @Override
            public void onSetSuccess() {
                remoteSet.countDown();
            }

            @Override
            public void onCreateFailure(String s) {}

            @Override
            public void onSetFailure(String s) {
                error[0] = "setRemoteDescription: " + s;
                remoteSet.countDown();
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, answerSdp));
        if (!remoteSet.await(10, TimeUnit.SECONDS) || error[0] != null) {
            throw new IOException(error[0] != null ? error[0] : "Answer-Timeout");
        }

        if (!channelOpen.await(20, TimeUnit.SECONDS)) {
            throw new IOException("DataChannel blieb zu (NAT/Firewall?)");
        }
        listener.onLog("NetherNet verbunden");
    }

    /** Sendet eine NetherNet-Nachricht ([algo][zlib-Payload]) mit Segment-Framing. */
    public synchronized void sendMessage(byte[] message) {
        DataChannel dc = reliable;
        if (dc == null || closed) return;
        int segments = (message.length + SEGMENT_PAYLOAD - 1) / SEGMENT_PAYLOAD;
        if (segments > 255) {
            closeUpstream("Nachricht zu groß");
            return;
        }
        int remaining = segments - 1;
        try {
            if (message.length == 0) {
                dc.send(new DataChannel.Buffer(ByteBuffer.wrap(new byte[1]), true));
                return;
            }
            for (int off = 0; off < message.length; off += SEGMENT_PAYLOAD) {
                int len = Math.min(SEGMENT_PAYLOAD, message.length - off);
                byte[] seg = new byte[len + 1];
                seg[0] = (byte) remaining;
                System.arraycopy(message, off, seg, 1, len);
                dc.send(new DataChannel.Buffer(ByteBuffer.wrap(seg), true));
                remaining--;
            }
        } catch (IllegalStateException e) {
            closeUpstream("send: " + e.getMessage());
        }
    }

    private void handleIncoming(byte[] data) {
        if (data.length < 2) throw new IllegalArgumentException("zu kurz");
        int remaining = data[0] & 0xff;
        byte[] payload = new byte[data.length - 1];
        System.arraycopy(data, 1, payload, 0, payload.length);
        if (pendingRemaining >= 0 && pendingRemaining - 1 != remaining) {
            throw new IllegalArgumentException("Segmentkette kaputt");
        }
        pending = pending == null ? payload : concat(pending, payload);
        pendingRemaining = remaining;
        if (pendingRemaining == 0) {
            byte[] complete = pending;
            pending = null;
            pendingRemaining = -1;
            listener.onMessage(complete);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public void closeUpstream(String reason) {
        if (closed) return;
        closed = true;
        DataChannel dc = reliable;
        PeerConnection p = pc;
        PeerConnectionFactory f = factory;
        reliable = null;
        pc = null;
        factory = null;
        try {
            if (dc != null) dc.close();
        } catch (RuntimeException ignored) {
        }
        try {
            if (p != null) p.close();
        } catch (RuntimeException ignored) {
        }
        try {
            if (f != null) f.dispose();
        } catch (RuntimeException ignored) {
        }
        listener.onClosed(reason);
    }
}
