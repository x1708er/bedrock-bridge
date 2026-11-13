package de.bedrocklan.bridge.nethernet;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebRTC-Datenkanal zum Bedrock-Server (NetherNet): SDP-Aushandlung über den
 * HTTP-Endpunkt des Servers, dann ReliableDataChannel mit Segment-Framing.
 */
public final class WebRtcUpstream {
    public interface Listener {
        void onMessage(byte[] netherNetMessage);

        void onClosed(String reason);

        void onLog(String msg);
    }

    private final PeerConnectionFactory factory;
    private final NetherNetClient signaling;
    private final Listener listener;

    private PeerConnection pc;
    private DataChannel reliable;

    private final List<String> candidates = new ArrayList<>();
    private final Framer framer = new Framer();

    private volatile boolean closed;

    public WebRtcUpstream(PeerConnectionFactory factory, NetherNetClient signaling, Listener listener) {
        this.factory = factory;
        this.signaling = signaling;
        this.listener = listener;
    }

    /** Baut die WebRTC-Verbindung auf (blockiert bis DataChannel offen oder Fehler). */
    public void connect() throws IOException, InterruptedException {
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
                byte[] message;
                try {
                    message = framer.feed(data);
                } catch (RuntimeException e) {
                    closeUpstream("Nachricht defekt: " + e.getMessage());
                    return;
                }
                if (message != null) listener.onMessage(message);
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

    /** Sendet eine NetherNet-Nachricht mit Segment-Framing. */
    public void sendMessage(byte[] message) {
        if (closed) return;
        Framer.send(reliable, message);
    }

    public void closeUpstream(String reason) {
        if (closed) return;
        closed = true;
        DataChannel dc = reliable;
        PeerConnection p = pc;
        reliable = null;
        pc = null;
        try {
            if (dc != null) dc.close();
        } catch (RuntimeException ignored) {
        }
        try {
            if (p != null) p.close();
        } catch (RuntimeException ignored) {
        }
        listener.onClosed(reason);
    }
}
