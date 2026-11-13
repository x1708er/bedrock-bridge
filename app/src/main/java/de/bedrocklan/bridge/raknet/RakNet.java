package de.bedrocklan.bridge.raknet;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Byte-Helfer für RakNet: Little-Endian-Triads, Adressen, Varints, zlib. */
public final class RakNet {
    public static final byte ID_CONNECTED_PING = 0x00;
    public static final byte ID_UNCONNECTED_PING = 0x01;
    public static final byte ID_CONNECTED_PONG = 0x03;
    public static final byte ID_OPEN_CONNECTION_REQUEST_1 = 0x05;
    public static final byte ID_OPEN_CONNECTION_REPLY_1 = 0x06;
    public static final byte ID_OPEN_CONNECTION_REQUEST_2 = 0x07;
    public static final byte ID_OPEN_CONNECTION_REPLY_2 = 0x08;
    public static final byte ID_CONNECTION_REQUEST = 0x09;
    public static final byte ID_CONNECTION_REQUEST_ACCEPTED = 0x10;
    public static final byte ID_NEW_INCOMING_CONNECTION = 0x13;
    public static final byte ID_DISCONNECTION_NOTIFICATION = 0x15;
    public static final byte ID_INCOMPATIBLE_PROTOCOL_VERSION = 0x19;
    public static final byte ID_UNCONNECTED_PONG = 0x1c;

    public static final byte FLAG_DATAGRAM = (byte) 0x80;
    public static final byte FLAG_ACK = 0x40;
    public static final byte FLAG_NAK = 0x20;
    public static final byte FLAG_NEEDS_B_AND_AS = 0x04;

    public static final int PROTOCOL_VERSION = 11;
    public static final int MIN_MTU = 576;
    public static final int MAX_MTU = 1492;

    public static final byte[] MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};

    private RakNet() {}

    static void putLE24(ByteBuffer b, int v) {
        b.put((byte) v);
        b.put((byte) (v >> 8));
        b.put((byte) (v >> 16));
    }

    static int getLE24(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16);
    }

    static void putVaruint(ByteBuffer b, int v) {
        while ((v & ~0x7f) != 0) {
            b.put((byte) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        b.put((byte) v);
    }

    static int varuintSize(int v) {
        int n = 1;
        while ((v & ~0x7f) != 0) {
            v >>>= 7;
            n++;
        }
        return n;
    }

    static int getVaruint(byte[] b, int[] pos) {
        int result = 0;
        int shift = 0;
        int p = pos[0];
        while (true) {
            if (p >= b.length) throw new IllegalArgumentException("varint endet im Leeren");
            byte c = b[p++];
            result |= (c & 0x7f) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
        }
        pos[0] = p;
        return result;
    }

    static void putAddress(ByteBuffer b, InetSocketAddress a) {
        byte[] raw = a.getAddress().getAddress();
        if (a.getAddress() instanceof Inet4Address) {
            b.put((byte) 4);
            for (byte x : raw) b.put((byte) ~x);
            b.putShort((short) a.getPort());
        } else {
            b.put((byte) 6);
            b.putShort((byte) 0); // AF_INET6
            b.putShort((short) a.getPort());
            b.putInt(0);
            b.put(raw);
            b.putInt(((Inet6Address) a.getAddress()).getScopeId());
        }
    }

    static InetSocketAddress getAddress(byte[] b, int[] pos) {
        int p = pos[0];
        int type = b[p++] & 0xff;
        try {
            if (type == 4) {
                byte[] raw = new byte[4];
                for (int i = 0; i < 4; i++) raw[i] = (byte) ~b[p + i];
                p += 4;
                int port = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
                p += 2;
                pos[0] = p;
                return new InetSocketAddress(java.net.InetAddress.getByAddress(raw), port);
            } else if (type == 6) {
                p += 2; // family
                int port = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
                p += 2 + 4;
                byte[] raw = new byte[16];
                System.arraycopy(b, p, raw, 0, 16);
                p += 16 + 4;
                pos[0] = p;
                return new InetSocketAddress(
                        java.net.InetAddress.getByAddress(raw), port);
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
        throw new IllegalArgumentException("unbekannter Adresstyp " + type);
    }

    /** zlib entpacken (mit Header). */
    static byte[] inflate(byte[] data, int max) {
        if (data.length == 0) return new byte[0];
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
        byte[] buf = new byte[8192];
        int total = 0;
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) throw new IllegalArgumentException("zlib unvollständig");
                    break;
                }
                total += n;
                if (total > max) throw new IllegalArgumentException("entpackt zu groß");
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("zlib defekt", e);
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    static int reliabilityOf(int flags) {
        return (flags & 0xe0) >>> 5;
    }

    static boolean isReliable(int r) {
        return r == 2 || r == 3 || r == 4;
    }

    static boolean isSequenced(int r) {
        return r == 1 || r == 4;
    }

    static boolean isOrdered(int r) {
        return r == 1 || r == 3 || r == 4;
    }
}
