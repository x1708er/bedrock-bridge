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

import de.bedrocklan.bridge.Guard;
import de.bedrocklan.bridge.nethernet.Framer;
import de.bedrocklan.bridge.nethernet.NetherIdentity;

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
    private final NetherIdentity identity;

    private final List<String> pendingCandidates = Collections.synchronizedList(new ArrayList<>());
    private boolean remoteSet;
    private volatile boolean open;
    private volatile boolean closed;
    volatile long lastSeen = System.currentTimeMillis();

    public LanClient(PeerConnectionFactory factory, Host host, long connectionId, long peerNetworkId) {
        this.factory = factory;
        this.host = host;
        this.connectionId = connectionId;
        this.peerNetworkId = peerNetworkId;
        NetherIdentity id;
        try {
            id = NetherIdentity.generate();
        } catch (Throwable e) {
            Guard.logError("Identitätsschlüssel fehlgeschlagen", e);
            id = null;
        }
        this.identity = id;
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
        Guard.log("handleOffer: " + (offerSdp == null ? -1 : offerSdp.length()) + " Bytes");
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(
                new ArrayList<PeerConnection.IceServer>());
        pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState s) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                try {
                    if (s == PeerConnection.IceConnectionState.FAILED) close("ICE FAILED", true);
                } catch (Throwable e) {
                    Guard.logError("ICE-Callback", e);
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}

            @Override
            public void onIceCandidate(IceCandidate candidate) {
                try {
                    // Trickle: eigene Kandidaten dem Gerät signalisieren.
                    host.sendSignal(LanClient.this, "CANDIDATEADD", candidate.sdp);
                } catch (Throwable e) {
                    Guard.logError("Kandidaten-Callback", e);
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
                try {
                    attach(dc);
                } catch (Throwable e) {
                    Guard.logError("Kanal-Callback", e);
                }
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
                try {
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
                                    try {
                                        String sdp = answer.description;
                                        // Selbstsignierte Server-Identität anhängen
                                        // (der Vanilla-Client lehnt Answers ohne ab).
                                        if (identity != null) {
                                            String attr = identity.serverAttribute(sdp);
                                            if (attr != null) {
                                                sdp = NetherIdentity.insertSessionAttribute(
                                                        sdp, "a=identity:" + attr);
                                            }
                                        }
                                        host.sendSignal(LanClient.this, "CONNECTRESPONSE", sdp);
                                    } catch (Throwable e) {
                                        Guard.logError("Answer fehlgeschlagen", e);
                                        close("Answer fehlgeschlagen", true);
                                    }
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
                } catch (Throwable e) {
                    Guard.logError("Offer-Verarbeitung fehlgeschlagen", e);
                    close("Offer-Verarbeitung fehlgeschlagen", true);
                }
            }

            @Override
            public void onCreateSuccess(SessionDescription d) {}

            @Override
            public void onCreateFailure(String s) {}

            @Override
            public void onSetFailure(String s) {
                close("setRemoteDescription: " + s, true);
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, NetherIdentity.stripIdentity(offerSdp)));
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
            Guard.log("ignoriere unbekannten Kanal " + label);
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
                try {
                    if (dc.state() == DataChannel.State.OPEN && isReliable && !open) {
                        // Der Reliable-Kanal trägt das Spiel; er reicht zum Start.
                        open = true;
                        host.onOpen(LanClient.this);
                    }
                    if (dc.state() == DataChannel.State.CLOSED) close("Kanal geschlossen", true);
                } catch (Throwable e) {
                    Guard.logError("Kanalzustand", e);
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                byte[] message;
                try {
                    byte[] data = new byte[buffer.data.remaining()];
                    buffer.data.get(data);
                    Framer framer = isReliable ? reliableFramer : unreliableFramer;
                    message = framer.feed(data);
                } catch (Throwable e) {
                    Guard.logError("Nachricht defekt", e);
                    close("Nachricht defekt: " + e.getMessage(), true);
                    return;
                }
                if (message == null || !open) return;
                lastSeen = System.currentTimeMillis();
                try {
                    host.onMessage(LanClient.this, message);
                } catch (Throwable e) {
                    Guard.logError("Weiterleitung fehlgeschlagen", e);
                }
            }
        });
    }

    /** Nachricht an das Gerät senden (nur Reliable, wie vom Spiel empfohlen). */
    public void sendMessage(byte[] message) {
        if (closed || !open) return;
        lastSeen = System.currentTimeMillis();
        try {
            Framer.send(reliable, message);
        } catch (Throwable e) {
            Guard.logError("senden an Gerät", e);
        }
    }

    public void tick(long idleMs) {
        if (open && System.currentTimeMillis() - lastSeen > idleMs) {
            close("Leerlauf-Timeout", true);
        }
    }

    public void close(String reason, boolean notifyHost) {
        if (closed) return;
        closed = true;
        final PeerConnection p = pc;
        pc = null;
        reliable = null;
        unreliable = null;
        if (p != null) Guard.closeLater(p::close);
        if (notifyHost) {
            try {
                host.onClosed(this, reason);
            } catch (Throwable e) {
                Guard.logError("onClosed", e);
            }
        }
    }
}
