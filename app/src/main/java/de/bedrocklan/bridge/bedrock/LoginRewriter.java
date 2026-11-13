package de.bedrocklan.bridge.bedrock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import de.bedrocklan.bridge.Guard;
import de.bedrocklan.bridge.auth.AccountManager;
import de.bedrocklan.bridge.nethernet.NetherIdentity;

/**
 * Schreibt das Login-Paket des Geräts auf das App-Konto um, bevor es den
 * Server erreicht — der einzige Weg, den Mojangs Transport-Binding
 * (Transport-Identität muss der Login-Schlüssel sein) für LAN-Clients offenhält:
 *
 * - Envelope: {"AuthenticationType":0,"Token":"<App-Multiplayer-Token>","Certificate":""}
 *   (Token ist ein echtes, von Mojang signiertes GameServerToken mit cpk=App-Schlüssel)
 * - ClientData: Header x5u auf den App-Schlüssel, Payload unverändert, neu signiert.
 *
 * Wire-Format (LoginSerializer_v291/v818): [varint PacketID=0x01]
 * [i32 LE Protokoll][varuint Blob-Länge][i32 LE Envelope-Länge][Envelope]
 * [i32 LE ClientData-Länge][ClientData-JWT]; Batch davor [algo][zlib(Entries)],
 * Entries = [varuint Länge][Paket].
 */
public final class LoginRewriter {
    private static final int LOGIN_PACKET_ID = 0x01;
    private static final int ALGO_DEFLATE = 0x00;
    private static final int ALGO_NONE = (byte) 0xff & 0xff;
    private static final int MAX_BATCH = 8 << 20;

    private final AccountManager account;
    private final NetherIdentity identity;
    private boolean done;

    public LoginRewriter(AccountManager account, NetherIdentity identity) {
        this.account = account;
        this.identity = identity;
    }

    public boolean isDone() {
        return done;
    }

    /**
     * Verarbeitet eine Nachricht vom Gerät Richtung Server. Liefert null,
     * wenn nichts umgeschrieben wurde (Nachricht unverändert weiterleiten),
     * sonst die neue Nachricht.
     */
    public byte[] process(byte[] message) {
        if (done || message.length < 2) return null;
        int algo = message[0] & 0xff;
        byte[] entries;
        if (algo == ALGO_DEFLATE) {
            entries = inflate(message, 1);
            if (entries == null) {
                Guard.log("Deko: inflate fehlgeschlagen (" + message.length + " Bytes)");
                return null;
            }
        } else if (algo == ALGO_NONE) {
            entries = slice(message, 1);
        } else {
            // Vor den NetworkSettings gibt es kein algo-Byte — nichts umzuschreiben.
            Guard.log("Deko: rohe Nachricht (" + message.length + " Bytes), Anfang: "
                    + hex(message, 0, Math.min(12, message.length)));
            return null;
        }
        Guard.log("Deko: algo=" + algo + ", " + message.length + " Bytes entpackt "
                + entries.length + " Bytes, Anfang: " + hex(entries, 0, Math.min(16, entries.length)));
        byte[] rewritten = rewriteEntries(entries);
        if (rewritten == null) return null;
        done = true;
        Guard.log("Login auf App-Konto umgeschrieben");
        if (algo == ALGO_DEFLATE) {
            byte[] compressed = deflate(rewritten);
            byte[] out = new byte[compressed.length + 1];
            out[0] = (byte) ALGO_DEFLATE;
            System.arraycopy(compressed, 0, out, 1, compressed.length);
            return out;
        }
        byte[] out = new byte[rewritten.length + 1];
        out[0] = (byte) ALGO_NONE;
        System.arraycopy(rewritten, 0, out, 1, rewritten.length);
        return out;
    }

    /** Geht die Batch-Einträge durch und ersetzt den Login-Eintrag. */
    private byte[] rewriteEntries(byte[] entries) {
        List<byte[]> out = new ArrayList<>();
        int off = 0;
        boolean found = false;
        while (off < entries.length) {
            int[] pos = {off};
            int len = getVaruint(entries, pos);
            if (len <= 0 || pos[0] + len > entries.length) return null;
            byte[] entry = slice(entries, pos[0], pos[0] + len);
            off = pos[0] + len;
            byte[] replacement = rewriteEntry(entry);
            Guard.log("Deko: Eintrag " + entry.length + " Bytes, Anfang: "
                    + hex(entry, 0, Math.min(8, entry.length)));
            out.add(replacement != null ? replacement : entry);
            if (replacement != null) found = true;
        }
        if (!found) return null;
        ByteArrayOutputStream result = new ByteArrayOutputStream(entries.length + 64);
        for (byte[] entry : out) {
            writeVaruint(result, entry.length);
            result.write(entry, 0, entry.length);
        }
        return result.toByteArray();
    }

    /** Baut einen Login-Eintrag neu; null, wenn es keiner ist. */
    private byte[] rewriteEntry(byte[] entry) {
        int[] pos = {0};
        int packetId = getVaruint(entry, pos);
        if (packetId != LOGIN_PACKET_ID) return null;
        if (pos[0] + 4 > entry.length) return null;
        int protocol = getIntLE(entry, pos[0]);
        pos[0] += 4;
        int blobLen = getVaruint(entry, pos);
        if (pos[0] + blobLen > entry.length) return null;
        int blobStart = pos[0];
        int authLen = getIntLE(entry, blobStart);
        if (blobStart + 4 + authLen > entry.length) return null;
        int clientStart = blobStart + 4 + authLen;
        int clientLen = getIntLE(entry, clientStart);
        if (clientStart + 4 + clientLen > entry.length) return null;
        String clientJwt = new String(entry, clientStart + 4, clientLen, StandardCharsets.UTF_8);

        String token;
        try {
            token = account.multiplayerToken(identity.publicKey());
        } catch (Exception e) {
            Guard.logError("Multiplayer-Token für Login-Umschreibung fehlgeschlagen", e);
            return null;
        }
        String newAuth;
        try {
            newAuth = new JSONObject()
                    .put("AuthenticationType", 0)
                    .put("Token", token)
                    .put("Certificate", "")
                    .toString();
        } catch (org.json.JSONException e) {
            Guard.logError("Envelope-Bau fehlgeschlagen", e);
            return null;
        }
        String newClient = resignClientData(clientJwt);
        if (newClient == null) return null;

        byte[] authBytes = newAuth.getBytes(StandardCharsets.UTF_8);
        byte[] clientBytes = newClient.getBytes(StandardCharsets.UTF_8);
        int newBlob = 8 + authBytes.length + clientBytes.length;
        byte[] blobLenBuf = varuintBytes(newBlob);
        ByteArrayOutputStream out = new ByteArrayOutputStream(entry.length + 256);
        writeVaruint(out, LOGIN_PACKET_ID);
        writeIntLE(out, protocol);
        out.write(blobLenBuf, 0, blobLenBuf.length);
        writeIntLE(out, authBytes.length);
        out.write(authBytes, 0, authBytes.length);
        writeIntLE(out, clientBytes.length);
        out.write(clientBytes, 0, clientBytes.length);
        return out.toByteArray();
    }

    /** ClientData-JWT: Header x5u auf den App-Schlüssel, Payload unverändert, neu signiert. */
    private String resignClientData(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return null;
            JSONObject header = new JSONObject(new String(
                    Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            header.put("x5u", identity.publicKeyBase64());
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            String newHeader = identity.b64url(header.toString());
            String newPayload = identity.b64urlBytes(payload);
            String signature = identity.sign((newHeader + "." + newPayload)
                    .getBytes(StandardCharsets.UTF_8));
            return newHeader + "." + newPayload + "." + signature;
        } catch (Throwable e) {
            Guard.logError("ClientData-Neusignierung fehlgeschlagen", e);
            return null;
        }
    }

    // ---------------------------------------------------------------- Hilfen

    private static String hex(byte[] b, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(String.format("%02x", b[i] & 0xff));
        }
        return out.toString();
    }

    private static byte[] slice(byte[] b, int from) {
        return slice(b, from, b.length);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }

    private static int getIntLE(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private static int getVaruint(byte[] b, int[] pos) {
        int result = 0;
        int shift = 0;
        int p = pos[0];
        while (true) {
            if (p >= b.length) return -1;
            byte c = b[p++];
            result |= (c & 0x7f) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
        }
        pos[0] = p;
        return result;
    }

    private static void writeVaruint(ByteArrayOutputStream out, int v) {
        byte[] buf = varuintBytes(v);
        out.write(buf, 0, buf.length);
    }

    private static byte[] varuintBytes(int v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5);
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] data, int from) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(data, from, data.length - from);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[8192];
            int total = 0;
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput()) return null;
                    continue;
                }
                total += n;
                if (total > MAX_BATCH) return null;
                out.write(buf, 0, n);
            }
            inf.end();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater def = new Deflater();
        def.setInput(data);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        byte[] buf = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(buf);
            out.write(buf, 0, n);
        }
        def.end();
        return out.toByteArray();
    }
}
