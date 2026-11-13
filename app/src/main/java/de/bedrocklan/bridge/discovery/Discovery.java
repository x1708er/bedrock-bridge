package de.bedrocklan.bridge.discovery;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * NetherNet-LAN-Discovery auf UDP 7551 (df-mc/nethernet-spec / go-nethernet):
 * Pakete sind HMAC-SHA256-präfixiert und AES-ECB-verschlüsselt; der Schlüssel ist
 * SHA-256 der 0xdeadbeef als uint64 little-endian. Key = SHA-256(bytes EF BE AD DE 00 00 00 00).
 */
public final class Discovery {
    public static final int PORT = 7551;
    public static final int ID_REQUEST = 0;
    public static final int ID_RESPONSE = 1;
    public static final int ID_MESSAGE = 2;

    private static final int MAX_DECLARED_MESSAGE = 1140;
    private static final byte[] KEY = sha256(new byte[]{
            (byte) 0xef, (byte) 0xbe, (byte) 0xad, (byte) 0xde, 0, 0, 0, 0});

    private Discovery() {}

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encrypt(byte[] src) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"));
            return c.doFinal(src);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decrypt(byte[] src) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"));
            return c.doFinal(src);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hmac(byte[] payload) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return m.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Packet {
        public int id;
        public long senderId;
        public byte[] responseData;
        public long recipientId;
        public String messageData;
    }

    /** Dekodiert ein Discovery-Paket oder liefert null bei Defekt/Checksummenfehler. */
    public static Packet unmarshal(byte[] b, int len) {
        if (len < 32 + 16) return null;
        byte[] payload = decrypt(slice(b, 32, len));
        if (payload == null) return null;
        byte[] check = hmac(payload);
        for (int i = 0; i < 32; i++) {
            if (check[i] != b[i]) return null;
        }
        if (payload.length < 4) return null;
        int declared = (payload[0] & 0xff) | ((payload[1] & 0xff) << 8);
        if (payload.length < declared) return null;

        Packet p = new Packet();
        ByteBuffer r = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        r.getShort(); // deklarierte Länge
        p.id = r.getShort() & 0xffff;
        p.senderId = r.getLong();
        r.getLong(); // 8 Byte Padding
        if (p.id == ID_REQUEST) return p;
        if (p.id == ID_RESPONSE) {
            int hexLen = r.getInt();
            byte[] rest = new byte[r.remaining()];
            r.get(rest);
            String hex = new String(rest, StandardCharsets.ISO_8859_1);
            if (hexLen > hex.length()) return null;
            p.responseData = hexDecode(hex);
            return p;
        }
        if (p.id == ID_MESSAGE) {
            p.recipientId = r.getLong();
            r.getInt(); // deklarierte Datenlänge (vanilla kürzt ab 1140 ab)
            byte[] rest = new byte[r.remaining()];
            r.get(rest);
            p.messageData = new String(rest, StandardCharsets.UTF_8);
            return p;
        }
        return null;
    }

    public static byte[] marshalRequest(long senderId) {
        return marshal(ID_REQUEST, senderId, new byte[0], -1);
    }

    public static byte[] marshalResponse(long senderId, byte[] applicationData) {
        String hex = hexEncode(applicationData);
        byte[] hb = hex.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer b = ByteBuffer.allocate(4 + hb.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(hb.length);
        b.put(hb);
        return marshal(ID_RESPONSE, senderId, b.array(), -1);
    }

    public static byte[] marshalMessage(long senderId, long recipientId, String data) {
        byte[] str = data.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(12 + str.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(recipientId);
        b.putInt(Math.min(str.length, MAX_DECLARED_MESSAGE));
        b.put(str);
        return marshal(ID_MESSAGE, senderId, b.array(), 32 + Math.min(str.length, MAX_DECLARED_MESSAGE));
    }

    /** payload = [uint16 declared][uint16 id][uint64 sender][8x0][data]; wire = hmac + AES(payload). */
    private static byte[] marshal(int id, long senderId, byte[] data, int declaredOverride) {
        int total = 2 + 2 + 8 + 8 + data.length;
        int declared = declaredOverride >= 0 ? declaredOverride : total;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) declared);
        b.putShort((short) id);
        b.putLong(senderId);
        b.putLong(0);
        b.put(data);
        byte[] payload = b.array();
        byte[] enc = encrypt(payload);
        byte[] mac = hmac(payload);
        byte[] out = new byte[32 + enc.length];
        System.arraycopy(mac, 0, out, 0, 32);
        System.arraycopy(enc, 0, out, 32, enc.length);
        return out;
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }

    public static String hexEncode(byte[] in) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[in.length * 2];
        for (int i = 0; i < in.length; i++) {
            int v = in[i] & 0xff;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0xf];
        }
        return new String(out);
    }

    private static byte[] hexDecode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    /** ServerData v7 gemäß discovery/server_data.go. */
    public static final class ServerData {
        public String serverName = "";
        public int protocol;
        public String version = "";
        public String levelName = "";
        public int playerCount;
        public int maxPlayerCount;
        public int gameType;
        public boolean editorWorld;
        public boolean hardcore;
        public boolean onlineAuth = true;
        public boolean selfSignedAuth = true;
        public String nonce = "";
        public int connectionType = 4;

        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream(128);
            out.write(7);
            writeString(out, serverName);
            writeVarint32(out, protocol);
            writeString(out, version);
            writeString(out, levelName);
            writeVarint32(out, playerCount);
            writeVarint32(out, maxPlayerCount);
            writeVarint32(out, gameType);
            out.write(editorWorld ? 1 : 0);
            out.write(hardcore ? 1 : 0);
            out.write(onlineAuth ? 1 : 0);
            out.write(selfSignedAuth ? 1 : 0);
            writeString(out, nonce);
            writeVarint32(out, connectionType);
            return out.toByteArray();
        }
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVaruint(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeVarint32(ByteArrayOutputStream out, int v) {
        writeVaruint(out, (v << 1) ^ (v >> 31));
    }

    private static void writeVaruint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }
}
