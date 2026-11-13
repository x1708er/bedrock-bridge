package de.bedrocklan.bridge.discovery;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.bedrocklan.bridge.nethernet.Framer;

/**
 * Ein Gerät (PC/Konsole) im LAN: nimmt das WebRTC-Offer aus dem
 * NetherNet-Discovery-Signaling entgegen, antwortet mit einem Answer und
 * überträgt Bedrock-Nachrichten über die vom Gerät geöffneten DataChannels.
 */
public final class LanClient {
    public interface Host {
        /** Signal an das Gerät senden (CONNECTRESPONSE / CANDIDATEADD). */
        void sendSignal(LanClient client, String type, String data);

        /** Beide DataChannels sind offen und das Spiel kann Daten senden. */
        void onOpen(LanClient client);

        void onMessage(LanClient client, byte[] message);

        void onClosed(LanClient client, String reason);
    }

    private final PeerConnectionFactory factory;
    private final Host host;
    private final long connectionId;
    private final long peerNetworkId;

    private PeerConnection pc;
    private DataChannel reliable;
    private DataChannel unreliable;
    private final Framer reliableFramer = new Framer();
    private final Framer unreliableFramer = new Framer();

    private final List<String> pendingCandidates = Collections.synchronizedList(new ArrayList<>());
    private boolean remoteSet;
    private boolean reliableOpen;
    private boolean unreliableOpen;
    private volatile boolean open;
    private volatile boolean closed;
    volatile long lastSeen = System.currentTimeMillis();

    public LanClient(PeerConnectionFactory factory, Host host, long connectionId, long peerNetworkId) {
        this.factory = factory;
        this.host = host;
        this.connectionId = connectionId;
        this.peerNetworkId = peerNetworkId;
    }

    public long connectionId() {
        return connectionId;
    }

    public long peerNetworkId() {
        return peerNetworkId;
    }

    public boolean isOpen() {
        return open;
    }

    /** CONNECTREQUEST: Offer entgegennehmen und mit Answer antworten. */
    public void handleOffer(String offerSdp) {
        if (closed) return;
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(
                new ArrayList<PeerConnection.IceServer>());
        pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState s) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                if (s == PeerConnection.IceConnectionState.FAILED) close("ICE FAILED", true);
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                // Trickle: eigene Kandidaten dem Gerät signalisieren.
                host.sendSignal(LanClient.this, "CANDIDATEADD", candidate.sdp);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] c) {}

            @Override
            public void onAddStream(MediaStream s) {}

            @Override
            public void onRemoveStream(MediaStream s) {}

            @Override
            public void onDataChannel(DataChannel dc) {
                attach(dc);
            }

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
        });
        if (pc == null) {
            close("PeerConnection fehlgeschlagen", true);
            return;
        }
        pc.setRemoteDescription(new SdpObserver() {
            @Override
            public void onSetSuccess() {
                remoteSet = true;
                flushCandidates();
                pc.createAnswer(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription answer) {
                        pc.setLocalDescription(new SdpObserver() {
                            @Override
                            public void onCreateSuccess(SessionDescription d) {}

                            @Override
                            public void onSetSuccess() {
                                host.sendSignal(LanClient.this, "CONNECTRESPONSE", answer.description);
                            }

                            @Override
                            public void onCreateFailure(String s) {}

                            @Override
                            public void onSetFailure(String s) {
                                close("setLocalDescription: " + s, true);
                            }
                        }, answer);
                    }

                    @Override
                    public void onSetSuccess() {}

                    @Override
                    public void onCreateFailure(String s) {
                        close("createAnswer: " + s, true);
                    }

                    @Override
                    public void onSetFailure(String s) {}
                }, new MediaConstraints());
            }

            @Override
            public void onCreateSuccess(SessionDescription d) {}

            @Override
            public void onCreateFailure(String s) {}

            @Override
            public void onSetFailure(String s) {
                close("setRemoteDescription: " + s, true);
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, offerSdp));
    }

    /** CANDIDATEADD: ICE-Kandidat des Geräts hinzufügen. */
    public void addCandidate(String candidate) {
        lastSeen = System.currentTimeMillis();
        if (pc == null || closed) return;
        if (!remoteSet) {
            if (pendingCandidates.size() < 64) pendingCandidates.add(candidate);
            return;
        }
        applyCandidate(candidate);
    }

    private void flushCandidates() {
        synchronized (pendingCandidates) {
            for (String c : pendingCandidates) applyCandidate(c);
            pendingCandidates.clear();
        }
    }

    private void applyCandidate(String raw) {
        String s = raw.trim();
        if (s.startsWith("a=")) s = s.substring(2);
        pc.addIceCandidate(new IceCandidate("0", 0, s));
    }

    private void attach(DataChannel dc) {
        String label = dc.label();
        if (!"ReliableDataChannel".equals(label) && !"UnreliableDataChannel".equals(label)) {
            close("unbekannter Kanal " + label, true);
            return;
        }
        boolean isReliable = "ReliableDataChannel".equals(label);
        if (isReliable) reliable = dc;
        else unreliable = dc;
        dc.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {}

            @Override
            public void onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) {
                    if (isReliable) reliableOpen = true;
                    else unreliableOpen = true;
                    if (reliableOpen && unreliableOpen && !open) {
                        open = true;
                        host.onOpen(LanClient.this);
                    }
                }
                if (dc.state() == DataChannel.State.CLOSED) close("Kanal geschlossen", true);
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                byte[] data = new byte[buffer.data.remaining()];
                buffer.data.get(data);
                Framer framer = isReliable ? reliableFramer : unreliableFramer;
                byte[] message;
                try {
                    message = framer.feed(data);
                } catch (RuntimeException e) {
                    close("Nachricht defekt: " + e.getMessage(), true);
                    return;
                }
                if (message != null && open) host.onMessage(LanClient.this, message);
            }
        });
    }

    /** Nachricht an das Gerät senden (nur Reliable, wie vom Spiel empfohlen). */
    public void sendMessage(byte[] message) {
        if (closed || !open) return;
        Framer.send(reliable, message);
    }

    public void tick(long idleMs) {
        if (open && System.currentTimeMillis() - lastSeen > idleMs) {
            close("Leerlauf-Timeout", true);
        }
    }

    public void close(String reason, boolean notifyHost) {
        if (closed) return;
        closed = true;
        PeerConnection p = pc;
        pc = null;
        reliable = null;
        unreliable = null;
        try {
            if (p != null) p.close();
        } catch (RuntimeException ignored) {
        }
        if (notifyHost) host.onClosed(this, reason);
    }
}
