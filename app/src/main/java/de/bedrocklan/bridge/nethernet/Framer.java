package de.bedrocklan.bridge.nethernet;

import org.webrtc.DataChannel;

import java.nio.ByteBuffer;

/**
 * NetherNet-Segment-Framing über WebRTC-DataChannels:
 * Jede SCTP-Nachricht = [uint8 Restsegmente][Payload]; Nachrichten über
 * 262143 Bytes werden in Segmente mit absteigendem Zähler zerlegt.
 */
public final class Framer {
    public static final int SEGMENT_PAYLOAD = 262143;

    private byte[] pending;
    private int pendingRemaining = -1;

    /** Sendet eine Nachricht mit Segment-Framing über den Kanal. */
    public static void send(DataChannel dc, byte[] message) {
        if (dc == null) return;
        if (message.length == 0) {
            dc.send(new DataChannel.Buffer(ByteBuffer.wrap(new byte[1]), true));
            return;
        }
        int segments = (message.length + SEGMENT_PAYLOAD - 1) / SEGMENT_PAYLOAD;
        if (segments > 255) return;
        int remaining = segments - 1;
        for (int off = 0; off < message.length; off += SEGMENT_PAYLOAD) {
            int len = Math.min(SEGMENT_PAYLOAD, message.length - off);
            byte[] seg = new byte[len + 1];
            seg[0] = (byte) remaining;
            System.arraycopy(message, off, seg, 1, len);
            dc.send(new DataChannel.Buffer(ByteBuffer.wrap(seg), true));
            remaining--;
        }
    }

    /**
     * Speist eine empfangene SCTP-Nachricht ein. Liefert die vollständige
     * NetherNet-Nachricht oder null, wenn noch Segmente ausstehen.
     */
    public byte[] feed(byte[] data) {
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
            return complete;
        }
        return null;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
