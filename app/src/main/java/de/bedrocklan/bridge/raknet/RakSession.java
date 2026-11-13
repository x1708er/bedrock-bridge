package de.bedrocklan.bridge.raknet;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Eine einzelne Konsolen-Verbindung: die App terminiert RakNet vollständig
 * (Offline-Handshake, Frame-Sets, ACK/NAK, Split-Reassembly, Reliability-Resend),
 * statt Pakete wie früher nur blind weiterzuleiten.
 */
public final class RakSession {
    public interface Host {
        void sendUdp(InetSocketAddress to, byte[] data);

        long serverGuid();

        /** Ein fertiges Bedrock-Batch-Paket [0xFE][algo][zlib...] der Konsole. */
        void onGameBatch(RakSession session, byte[] batch, int len);

        void onDisconnect(RakSession session, String reason);

        void onLog(String msg);
    }

    private static final int RESEND_INTERVAL_MS = 1_000;
    private static final int MAX_RESENDS = 10;
    private static final int IDLE_TIMEOUT_MS = 120_000;
    private static final int MAX_SPLIT_PARTS = 512;
    private static final int MAX_CONCURRENT_SPLITS = 16;
    private static final int MAX_SPLIT_BYTES = 2 << 20;
    private static final int FRAME_OVERHEAD = 10;   // flags+len+msgIndex+orderIndex+channel
    private static final int SPLIT_OVERHEAD = 10;
    private static final int DATAGRAM_OVERHEAD = 4; // id+seq24
    private static final int CHANNELS = 32;

    private final Host host;
    private final InetSocketAddress client;

    private int mtu = RakNet.MAX_MTU;
    private long clientGuid;
    private boolean online;
    private volatile long lastSeen = System.currentTimeMillis();

    // Empfang
    private int highestDatagramSeq = -1;
    private int lastReliableIndex = -1;
    private final int[] lastOrderIndex = new int[CHANNELS];
    private final int[] lastSequencedIndex = new int[CHANNELS];
    private final Map<Integer, TreeMap<Integer, byte[]>> orderBuffers = new HashMap<>();
    private final Map<Integer, TreeMap<Integer, byte[]>> splits = new HashMap<>();

    // Senden
    private int nextDatagramSeq;
    private int nextReliableIndex;
    private int nextOrderIndex;
    private int nextSplitId;
    private final Map<Integer, byte[]> recovery = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, long[]> recoveryTime = new java.util.concurrent.ConcurrentHashMap<>(); // [Zeitpunkt, Versuche]

    public RakSession(Host host, InetSocketAddress client) {
        this.host = host;
        this.client = client;
        for (int i = 0; i < CHANNELS; i++) {
            lastOrderIndex[i] = -1;
            lastSequencedIndex[i] = -1;
        }
    }

    public InetSocketAddress client() {
        return client;
    }

    public boolean isOnline() {
        return online;
    }

    public long clientGuid() {
        return clientGuid;
    }

    // ---------------------------------------------------------------- Offline-Phase

    /** Behandelt OpenConnectionRequest1/2; liefert false, wenn das Paket nicht dazugehört. */
    public boolean handleOffline(byte[] buf, int len) {
        lastSeen = System.currentTimeMillis();
        int id = buf[0] & 0xff;
        if (id == RakNet.ID_OPEN_CONNECTION_REQUEST_1 && len >= 17) {
            for (int i = 0; i < 16; i++) {
                if (buf[1 + i] != RakNet.MAGIC[i]) return false;
            }
            int mtu = len + 28; // Rest des Pakets ist Padding zur MTU-Erkennung
            this.mtu = Math.max(RakNet.MIN_MTU, Math.min(RakNet.MAX_MTU, mtu));
            ByteBuffer b = ByteBuffer.allocate(28);
            b.put(RakNet.ID_OPEN_CONNECTION_REPLY_1);
            b.put(RakNet.MAGIC);
            b.putLong(host.serverGuid());
            b.put((byte) 0); // keine Verschlüsselung, kein Cookie
            b.putShort((short) this.mtu);
            host.sendUdp(client, b.array());
            return true;
        }
        if (id == RakNet.ID_OPEN_CONNECTION_REQUEST_2 && len >= 17) {
            for (int i = 0; i < 16; i++) {
                if (buf[1 + i] != RakNet.MAGIC[i]) return false;
            }
            int[] pos = {17};
            RakNet.getAddress(buf, pos); // Serveradresse, uninteressant
            int mtu = ((buf[pos[0]] & 0xff) << 8) | (buf[pos[0] + 1] & 0xff);
            if (mtu >= RakNet.MIN_MTU && mtu <= RakNet.MAX_MTU) this.mtu = mtu;
            clientGuid = getLE64(buf, pos[0]);
            ByteBuffer b = ByteBuffer.allocate(31 + 22);
            b.put(RakNet.ID_OPEN_CONNECTION_REPLY_2);
            b.put(RakNet.MAGIC);
            b.putLong(host.serverGuid());
            RakNet.putAddress(b, client);
            b.putShort((short) this.mtu);
            b.put((byte) 0); // keine Verschlüsselung
            byte[] out = new byte[b.position()];
            b.position(0);
            b.get(out);
            host.sendUdp(client, out);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Online-Phase

    /** Behandelt Frame-Sets, ACK und NAK der Konsole. */
    public void handleDatagram(byte[] buf, int len) {
        lastSeen = System.currentTimeMillis();
        int id = buf[0] & 0xff;
        if (id == RakNet.FLAG_ACK || id == RakNet.FLAG_NAK) {
            handleAcknowledge(buf, len, id == RakNet.FLAG_ACK);
            return;
        }
        if ((id & 0x80) == 0) return;
        int seq = RakNet.getLE24(buf, 1);
        if (seq == highestDatagramSeq) {
            sendAck(seq); // Duplikat trotzdem bestätigen
            return;
        }
        if (highestDatagramSeq >= 0 && (seq > highestDatagramSeq
                ? seq - highestDatagramSeq < 0x800000
                : highestDatagramSeq - seq > 0x800000)) {
            // älter als das höchste empfangene Frame-Set: verwerfen, aber bestätigen
            sendAck(seq);
            return;
        }
        highestDatagramSeq = seq;
        sendAck(seq);

        int off = 4;
        while (off + 3 <= len) {
            int consumed = handleFrame(buf, off, len);
            if (consumed <= 0) break;
            off += consumed;
        }
    }

    private void sendAck(int seq) {
        ByteBuffer b = ByteBuffer.allocate(7);
        b.put(RakNet.FLAG_ACK);
        b.putShort((short) 1); // ein Record
        b.put((byte) 0); // einzelner Wert
        RakNet.putLE24(b, seq);
        host.sendUdp(client, b.array());
    }

    private void handleAcknowledge(byte[] buf, int len, boolean ack) {
        if (len < 4) return;
        int records = ((buf[1] & 0xff) << 8) | (buf[2] & 0xff);
        int off = 3;
        for (int r = 0; r < records && off + 4 <= len; r++) {
            boolean range = (buf[off] & 1) != 0;
            off++;
            int start = RakNet.getLE24(buf, off);
            off += 3;
            int end = start;
            if (range && off + 3 <= len) {
                end = RakNet.getLE24(buf, off);
                off += 3;
            }
            if (ack) {
                for (int s = start; s <= end && s - start < 4096; s++) {
                    recovery.remove(s);
                    recoveryTime.remove(s);
                }
            } else {
                for (int s = start; s <= end && s - start < 4096; s++) resendNow(s);
            }
        }
    }

    private void resendNow(int seq) {
        byte[] d = recovery.get(seq);
        long[] t = recoveryTime.get(seq);
        if (d != null) {
            host.sendUdp(client, d);
            if (t != null) t[0] = System.currentTimeMillis();
        }
    }

    /**
     * Verarbeitet einen Frame ab Offset. Liefert die konsumierte Bytezahl,
     * 0 bei "Puffergrenze" und -1 bei Defekt (Abbruch des Frame-Sets).
     */
    private int handleFrame(byte[] buf, int off, int len) {
        int flags = buf[off] & 0xff;
        int reliability = RakNet.reliabilityOf(flags);
        boolean split = (flags & 0x10) != 0;
        int sizeBits = ((buf[off + 1] & 0xff) << 8) | (buf[off + 2] & 0xff);
        int size = (sizeBits + 7) >>> 3;
        if (size <= 0) return -1;
        off += 3;

        int reliableIndex = -1;
        int sequencedIndex = -1;
        int orderIndex = -1;
        int orderChannel = 0;
        boolean duplicate = false;

        if (RakNet.isReliable(reliability)) {
            if (off + 3 > len) return -1;
            reliableIndex = RakNet.getLE24(buf, off);
            off += 3;
            if (reliableIndex <= lastReliableIndex) duplicate = true;
            else lastReliableIndex = reliableIndex;
        }
        if (RakNet.isSequenced(reliability)) {
            if (off + 3 > len) return -1;
            sequencedIndex = RakNet.getLE24(buf, off);
            off += 3;
        }
        if (RakNet.isOrdered(reliability)) {
            if (off + 4 > len) return -1;
            orderIndex = RakNet.getLE24(buf, off);
            orderChannel = buf[off + 3] & 0x1f;
            off += 4;
        }
        int splitCount = 0;
        int splitId = 0;
        int splitIndex = 0;
        if (split) {
            if (off + 10 > len) return -1;
            splitCount = getLE32(buf, off);
            splitId = ((buf[off + 4] & 0xff) << 8) | (buf[off + 5] & 0xff);
            splitIndex = getLE32(buf, off + 6);
            off += 10;
        }
        if (off + size > len) return -1;
        if (duplicate) return off + size;

        byte[] content = new byte[size];
        System.arraycopy(buf, off, content, 0, size);

        if (split) {
            // Fragmente verbrauchen eigene orderIndexes: Sequenz fortschreiben,
            // damit nachfolgende geordnete Pakete nicht im Puffer hängen bleiben.
            if (RakNet.isOrdered(reliability) && orderIndex > lastOrderIndex[orderChannel]) {
                lastOrderIndex[orderChannel] = orderIndex;
            }
            byte[] joined = reassemble(splitCount, splitId, splitIndex, content);
            if (joined == null) return off + size; // auf weitere Teile warten
            deliver(joined);
            return off + size;
        }

        switch (reliability) {
            case 0: // unreliable
                deliver(content);
                break;
            case 1: // unreliable sequenced
                if (sequencedIndex > lastSequencedIndex[orderChannel]) {
                    lastSequencedIndex[orderChannel] = sequencedIndex;
                    deliver(content);
                }
                break;
            case 2: // reliable
                deliver(content);
                break;
            case 3: // reliable ordered
                if (orderIndex <= lastOrderIndex[orderChannel]) break;
                if (orderIndex != lastOrderIndex[orderChannel] + 1) {
                    orderBuffers.computeIfAbsent(orderChannel, k -> new TreeMap<>())
                            .put(orderIndex, content);
                    break;
                }
                lastOrderIndex[orderChannel] = orderIndex;
                deliver(content);
                flushOrders(orderChannel);
                break;
            case 4: // reliable sequenced
                if (sequencedIndex > lastSequencedIndex[orderChannel]) {
                    lastSequencedIndex[orderChannel] = sequencedIndex;
                    lastOrderIndex[orderChannel] = Math.max(lastOrderIndex[orderChannel], orderIndex);
                    deliver(content);
                }
                break;
            default:
                break;
        }
        return off + size;
    }

    private void flushOrders(int channel) {
        TreeMap<Integer, byte[]> buffer = orderBuffers.get(channel);
        if (buffer == null) return;
        while (!buffer.isEmpty()) {
            int next = lastOrderIndex[channel] + 1;
            byte[] c = buffer.remove(next);
            if (c == null) return;
            lastOrderIndex[channel] = next;
            deliver(c);
        }
    }

    private byte[] reassemble(int count, int id, int index, byte[] part) {
        if (count > MAX_SPLIT_PARTS || index >= count) return null;
        TreeMap<Integer, byte[]> splitMap = splits.get(id);
        if (splitMap == null) {
            if (splits.size() >= MAX_CONCURRENT_SPLITS) return null;
            splitMap = new TreeMap<>();
            splits.put(id, splitMap);
        }
        splitMap.put(index, part);
        int total = 0;
        for (byte[] p : splitMap.values()) total += p.length;
        if (total > MAX_SPLIT_BYTES) {
            splits.remove(id);
            return null;
        }
        if (splitMap.size() < count) return null;
        splits.remove(id);
        byte[] joined = new byte[total];
        int pos = 0;
        for (byte[] p : splitMap.values()) {
            System.arraycopy(p, 0, joined, pos, p.length);
            pos += p.length;
        }
        return joined;
    }

    /** RakNet-Meldungen (0x00, 0x09, 0x13, 0x15) und Bedrock-Batches weiterleiten. */
    private void deliver(byte[] content) {
        if (content.length < 1) return;
        int pid = content[0] & 0xff;
        switch (pid) {
            case RakNet.ID_CONNECTED_PING: {
                if (content.length < 9) return;
                long time = getLE64(content, 1);
                ByteBuffer pong = ByteBuffer.allocate(17);
                pong.put(RakNet.ID_CONNECTED_PONG);
                pong.putLong(time);
                pong.putLong(System.currentTimeMillis());
                sendFrame(pong.array(), 0);
                return;
            }
            case RakNet.ID_CONNECTION_REQUEST: {
                if (content.length < 17) return;
                long time = getLE64(content, 9);
                int addrSize = client.getAddress().getAddress().length == 4 ? 7 : 29;
                ByteBuffer out = ByteBuffer.allocate(19 + 11 * addrSize);
                out.put(RakNet.ID_CONNECTION_REQUEST_ACCEPTED);
                RakNet.putAddress(out, client);
                out.putShort((short) 0); // Systemindex
                for (int i = 0; i < 10; i++) {
                    out.put((byte) 4);
                    if (i == 0) {
                        out.put((byte) ~127).put((byte) ~0).put((byte) ~0).put((byte) ~1);
                    } else {
                        out.put((byte) ~0).put((byte) ~0).put((byte) ~0).put((byte) ~0);
                    }
                    out.putShort((short) 0);
                }
                out.putLong(time);
                out.putLong(System.currentTimeMillis());
                byte[] accepted = new byte[out.position()];
                out.position(0);
                out.get(accepted);
                sendFrame(accepted, 0); // UNRELIABLE wie der Vanilla-Server
                return;
            }
            case RakNet.ID_NEW_INCOMING_CONNECTION: {
                online = true;
                return;
            }
            case RakNet.ID_DISCONNECTION_NOTIFICATION: {
                host.onDisconnect(this, "Konsole hat getrennt");
                return;
            }
            default:
                if (pid == 0xfe) {
                    host.onGameBatch(this, content, content.length);
                } else {
                    host.onLog("unbekanntes Frame-Paket 0x" + Integer.toHexString(pid));
                }
        }
    }

    // ---------------------------------------------------------------- Senden

    /** Sendet beliebigen Frame-Inhalt mit MTU-Splitting und Reliability-Resend. */
    public synchronized void sendFrame(byte[] content, int reliability) {
        int usable = mtu - 28 - DATAGRAM_OVERHEAD - FRAME_OVERHEAD - SPLIT_OVERHEAD;
        if (usable < 64) usable = 64;
        if (content.length <= usable) {
            sendDatagram(new Frame[]{new Frame(content, reliability, -1, 0, 0)});
            return;
        }
        int parts = (content.length + usable - 1) / usable;
        if (parts > MAX_SPLIT_PARTS) return;
        int splitId = nextSplitId++ & 0xffff;
        Frame[] frames = new Frame[parts];
        for (int i = 0; i < parts; i++) {
            int from = i * usable;
            int to = Math.min(content.length, from + usable);
            byte[] chunk = new byte[to - from];
            System.arraycopy(content, from, chunk, 0, chunk.length);
            frames[i] = new Frame(chunk, reliability, splitId, i, parts);
        }
        // Fragmente einzeln senden, damit jedes sein eigenes Frame-Set erhält.
        for (Frame f : frames) sendDatagram(new Frame[]{f});
    }

    private void sendDatagram(Frame[] frames) {
        int size = DATAGRAM_OVERHEAD;
        for (Frame f : frames) {
            size += 3 + FRAME_OVERHEAD + (f.isSplit ? SPLIT_OVERHEAD : 0) + f.content.length;
        }
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put((byte) 0x84); // FLAG_VALID | FLAG_NEEDS_B_AND_AS
        int seq = nextDatagramSeq;
        RakNet.putLE24(b, seq);
        nextDatagramSeq = (nextDatagramSeq + 1) & 0xffffff;
        for (Frame f : frames) {
            int flags = (f.reliability << 5) | (f.isSplit ? 0x10 : 0);
            b.put((byte) flags);
            b.putShort((short) (f.content.length << 3));
            if (RakNet.isReliable(f.reliability)) {
                RakNet.putLE24(b, nextReliableIndex);
                nextReliableIndex = (nextReliableIndex + 1) & 0xffffff;
            }
            if (RakNet.isOrdered(f.reliability)) {
                RakNet.putLE24(b, nextOrderIndex);
                nextOrderIndex = (nextOrderIndex + 1) & 0xffffff;
                b.put((byte) 0);
            }
            if (f.isSplit) {
                b.putInt(f.splitCount);
                b.putShort((short) f.splitId);
                b.putInt(f.splitIndex);
            }
            b.put(f.content);
        }
        byte[] data = new byte[b.position()];
        b.position(0);
        b.get(data);
        if (RakNet.isReliable(frames[0].reliability)) {
            recovery.put(seq, data);
            recoveryTime.put(seq, new long[]{System.currentTimeMillis(), 0});
        }
        host.sendUdp(client, data);
    }

    // ---------------------------------------------------------------- Lebenszyklus

    /** Periodische Wartung: Resend unbestätigter Pakete und Leerlauf-Timeout. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, long[]> e : recoveryTime.entrySet()) {
            long[] t = e.getValue();
            byte[] d = recovery.get(e.getKey());
            if (d == null) continue;
            if (now - t[0] > RESEND_INTERVAL_MS * (t[1] + 1)) {
                if (t[1] >= MAX_RESENDS) {
                    host.onDisconnect(this, "keine Bestätigung der Konsole");
                    return;
                }
                t[1]++;
                t[0] = now;
                host.sendUdp(client, d);
            }
        }
        if (online && now - lastSeen > IDLE_TIMEOUT_MS) {
            host.onDisconnect(this, "Leerlauf-Timeout");
        }
    }

    public void close() {
        if (online) {
            sendFrame(new byte[]{RakNet.ID_DISCONNECTION_NOTIFICATION}, 0);
        }
        recovery.clear();
        recoveryTime.clear();
        splits.clear();
        orderBuffers.clear();
    }

    // ---------------------------------------------------------------- Hilfen

    private static long getLE64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xffL);
        }
        return v;
    }

    private static int getLE32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static final class Frame {
        final byte[] content;
        final int reliability;
        final int splitId;
        final int splitIndex;
        final int splitCount;
        final boolean isSplit;

        Frame(byte[] content, int reliability, int splitId, int splitIndex, int splitCount) {
            this.content = content;
            this.reliability = reliability;
            this.splitId = splitId;
            this.splitIndex = splitIndex;
            this.splitCount = splitCount;
            this.isSplit = splitId >= 0;
        }
    }
}
