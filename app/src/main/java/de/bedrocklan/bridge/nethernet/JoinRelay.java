package de.bedrocklan.bridge.nethernet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.bedrocklan.bridge.Guard;

/**
 * Leitet den Verbindungswunsch eines Geräts (Konsole/PC) an den Server weiter:
 * Das Offer des Geräts wird um dessen per CANDIDATEADD nachgereichte
 * ICE-Kandidaten ergänzt und an POST /v1/join/<Geräte-ID> geschickt; das
 * Answer des Servers geht als CONNECTRESPONSE zurück. Danach verbinden sich
 * Gerät und Server direkt per WebRTC — die App ist nur Signalisierungs-
 * Vermittler und nicht Teil der Datenverbindung.
 */
public final class JoinRelay {
    private static final long CANDIDATE_QUIET_MS = 1500;
    private static final long CANDIDATE_MAX_MS = 4000;

    public interface Sender {
        void sendSignal(long peerNetId, String type, long connId, String data);
    }

    private final NetherNetClient signaling;
    private final Sender sender;
    private final long connId;
    private final long peerNetId;
    private final String offer;
    private final List<String> candidates = new ArrayList<>();
    private long lastCandidateAt;
    private volatile boolean closed;

    public JoinRelay(NetherNetClient signaling, Sender sender, long connId,
                     long peerNetId, String offer) {
        this.signaling = signaling;
        this.sender = sender;
        this.connId = connId;
        this.peerNetId = peerNetId;
        this.offer = offer;
        this.lastCandidateAt = System.currentTimeMillis();
    }

    public void addCandidate(String candidate) {
        if (closed) return;
        synchronized (candidates) {
            if (candidates.size() >= 32) return;
            candidates.add(candidate);
        }
        lastCandidateAt = System.currentTimeMillis();
    }

    /** Warten bis die Kandidaten des Geräts durchsickern, dann Offer an den Server. */
    public void run() {
        try {
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < CANDIDATE_MAX_MS) {
                if (System.currentTimeMillis() - lastCandidateAt >= CANDIDATE_QUIET_MS) break;
                Thread.sleep(150);
            }
            closed = true;
            List<String> collected = snapshotCandidates();
            String munged = mungeCandidates(offer, collected);
            Guard.log("Relay: Offer (" + munged.length() + " Bytes, "
                    + collected.size() + " Kandidaten) an Server");
            String answer = signaling.exchangeSdp(munged,
                    Long.toUnsignedString(peerNetId), 12_000);
            sender.sendSignal(peerNetId, "CONNECTRESPONSE", connId, answer);
            Guard.log("Relay: Answer an Gerät " + Long.toUnsignedString(peerNetId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            Guard.logError("Relay fehlgeschlagen", e);
            try {
                sender.sendSignal(peerNetId, "CONNECTERROR", connId, "4");
            } catch (Throwable ignored) {
            }
        }
    }

    private List<String> snapshotCandidates() {
        synchronized (candidates) {
            return new ArrayList<>(candidates);
        }
    }

    /**
     * Kandidaten als a=candidate-Zeilen ans Offer anhängen (Medien-Sektion,
     * da das Offer mit der m=-Zeile endet). Duplikate sind für ICE harmlos.
     */
    static String mungeCandidates(String offer, List<String> candidates) {
        StringBuilder sb = new StringBuilder(offer.length() + candidates.size() * 80);
        sb.append(offer);
        if (!offer.endsWith("\n")) sb.append('\n');
        for (String raw : candidates) {
            String c = raw.trim();
            if (c.startsWith("a=")) c = c.substring(2);
            if (c.isEmpty()) continue;
            if (!c.startsWith("candidate:")) c = "candidate:" + c;
            sb.append("a=").append(c).append('\n');
        }
        return sb.toString();
    }
}
