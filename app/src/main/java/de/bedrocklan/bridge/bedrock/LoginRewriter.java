package de.bedrocklan.bridge.bedrock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import de.bedrocklan.bridge.auth.AccountManager;
import de.bedrocklan.bridge.nethernet.NetherIdentity;

/**
 * Schreibt das Login-Paket des Geräts auf das App-Konto um, bevor es den
 * Server erreicht. Das passiert immer und ohne Ausnahme: scheitert die
 * Umschreibung (z. B. kein gültiges App-Konto-Token), wirft process()
 * und die Verbindung wird geschlossen.
 *
 * - Envelope: {"AuthenticationType":0,"Token":"<App-Multiplayer-Token>","Certificate":""}
 * - ClientData: Header x5u auf den App-Schlüssel, Payload unverändert, neu signiert.
 *
 * Wire-Format (LoginSerializer_v291/v818): [varint PacketID=0x01]
 * [i32 LE Protokoll][varuint Blob-Länge][i32 LE Envelope-Länge][Envelope]
 * [i32 LE ClientData-Länge][ClientData-JWT]; Batch davor [algo][zlib(Entries)],
 * Entries = [varuint Länge][Paket].
 */
public final class LoginRewriter {
    /**
     * Umschreibung nötig, aber fehlgeschlagen. Das Original darf nie
     * weitergesendet werden; Aufrufer müssen die Verbindung schließen.
     */
    public static final class LoginFailedException extends Exception {
        LoginFailedException(String reason) {
            super(reason);
        }
    }

    /** Liefert das Multiplayer-Token; nie blockierend im Callback-Thread. */
    public interface TokenProvider {
        String multiplayerToken(PublicKey key) throws Exception;
    }

    private static final int LOGIN_PACKET_ID = 0x01;
    private static final int ALGO_DEFLATE = 0x00;
    private static final int ALGO_NONE = (byte) 0xff & 0xff;
    private static final int MAX_BATCH = 8 << 20;

    private final TokenProvider tokens;
    private final NetherIdentity identity;
    private boolean done;

    public LoginRewriter(final AccountManager account, NetherIdentity identity) {
        this(key -> {
            String token = account.cachedMultiplayerToken(key);
            if (token == null) account.prewarm(key);
            return token;
        }, identity);
    }

    LoginRewriter(TokenProvider tokens, NetherIdentity identity) {
        this.tokens = tokens;
        this.identity = identity;
    }

    public boolean isDone() {
        return done;
    }

    /**
     * Verarbeitet eine Nachricht vom Gerät Richtung Server. Liefert null,
     * wenn nichts umgeschrieben wurde (Nachricht unverändert weiterleiten),
     * sonst die neue Nachricht. Wirft, wenn ein Login erkannt, aber nicht
     * umgeschrieben werden konnte.
     */
    public byte[] process(byte[] message) throws LoginFailedException {
        if (done || message.length < 2) return null;
        int algo = message[0] & 0xff;
        byte[] entries;
        if (algo == ALGO_DEFLATE) {
            entries = inflate(message, 1);
            if (entries == null) {
                // Ein nicht entpackbarer Batch kann nicht auf ein Login geprüft
                // werden — kein Passthrough: Original verwerfen, Verbindung schließen.
                throw new LoginFailedException(
                        "Login-Batch nicht entpackbar – Umschreibung nicht prüfbar, "
                                + "Original wird nicht weitergesendet");
            }
        } else if (algo == ALGO_NONE) {
            entries = slice(message, 1);
        } else {
            // Vor den NetworkSettings gibt es kein algo-Byte — nichts umzuschreiben.
            return null;
        }
        byte[] rewritten = rewriteEntries(entries);
        if (rewritten == null) return null;
        done = true;
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
    private byte[] rewriteEntries(byte[] entries) throws LoginFailedException {
        List<byte[]> out = new ArrayList<>();
        int off = 0;
        boolean found = false;
        while (off < entries.length) {
            int[] pos = {off};
            int len = getVaruint(entries, pos);
            if (len <= 0 || pos[0] + len > entries.length) {
                throw new LoginFailedException("Login-Batch-Eintrag ungültig");
            }
            byte[] entry = slice(entries, pos[0], pos[0] + len);
            off = pos[0] + len;
            byte[] replacement = rewriteEntry(entry);
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
    private byte[] rewriteEntry(byte[] entry) throws LoginFailedException {
        int[] pos = {0};
        int packetId = getVaruint(entry, pos);
        if (packetId != LOGIN_PACKET_ID) return null;
        if (pos[0] + 4 > entry.length) throw new LoginFailedException("Login-Paket abgeschnitten");
        int protocol = getIntLE(entry, pos[0]);
        pos[0] += 4;
        int blobLen = getVaruint(entry, pos);
        if (blobLen < 0 || pos[0] + blobLen > entry.length) {
            throw new LoginFailedException("Login-Paket-Blob ungültig");
        }
        int blobStart = pos[0];
        if (blobStart + 4 > entry.length) throw new LoginFailedException("Login-Paket abgeschnitten");
        int authLen = getIntLE(entry, blobStart);
        if (authLen < 0 || blobStart + 4 + authLen > entry.length) {
            throw new LoginFailedException("Login-Envelope ungültig");
        }
        int clientStart = blobStart + 4 + authLen;
        if (clientStart + 4 > entry.length) throw new LoginFailedException("Login-Paket abgeschnitten");
        int clientLen = getIntLE(entry, clientStart);
        if (clientLen < 0 || clientStart + 4 + clientLen > entry.length) {
            throw new LoginFailedException("Login-ClientData ungültig");
        }
        String clientJwt = new String(entry, clientStart + 4, clientLen, StandardCharsets.UTF_8);

        String token;
        try {
            token = tokens.multiplayerToken(identity.publicKey());
        } catch (Exception e) {
            throw new LoginFailedException("Multiplayer-Token nicht verfügbar");
        }
        if (token == null || token.isEmpty()) {
            throw new LoginFailedException("App-Konto-Token nicht bereit — Anmeldung prüfen");
        }
        String newAuth;
        try {
            newAuth = new JSONObject()
                    .put("AuthenticationType", 0)
                    .put("Token", token)
                    .put("Certificate", "")
                    .toString();
        } catch (org.json.JSONException e) {
            throw new LoginFailedException("Login-Envelope nicht baubar");
        }
        String newClient = resignClientData(clientJwt);
        if (newClient == null) throw new LoginFailedException("ClientData-Neusignierung fehlgeschlagen");

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
            return null;
        }
    }

    // ---------------------------------------------------------------- Hilfen

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

    static int getVaruint(byte[] b, int[] pos) {
        int result = 0;
        int shift = 0;
        int p = pos[0];
        while (true) {
            if (p >= b.length) return -1;
            byte c = b[p++];
            result |= (c & 0x7f) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) return -1;
        }
        pos[0] = p;
        return result;
    }

    static void writeVaruint(ByteArrayOutputStream out, int v) {
        byte[] buf = varuintBytes(v);
        out.write(buf, 0, buf.length);
    }

    static byte[] varuintBytes(int v) {
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
            // Bedrock „ZLIB“ ist on the wire raw deflate ohne zlib-Header
            // (Geyser: ZlibCompression(Zlib.RAW)).
            Inflater inf = new Inflater(true);
            inf.setInput(data, from, data.length - from);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[8192];
            int total = 0;
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput()) {
                        return null;
                    }
                    continue;
                }
                total += n;
                if (total > MAX_BATCH) {
                    return null;
                }
                out.write(buf, 0, n);
            }
            inf.end();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater def = new Deflater(7, true);
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
