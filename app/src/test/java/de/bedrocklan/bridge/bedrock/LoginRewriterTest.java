package de.bedrocklan.bridge.bedrock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.zip.Deflater;

import de.bedrocklan.bridge.nethernet.NetherIdentity;

public class LoginRewriterTest {

    private static final int ALGO_NONE = 0xff;
    private static final int ALGO_DEFLATE = 0x00;

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildLoginEntry(int protocol, String auth, String clientJwt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LoginRewriter.writeVaruint(out, 0x01);
        writeIntLE(out, protocol);
        byte[] authBytes = auth.getBytes(StandardCharsets.UTF_8);
        byte[] clientBytes = clientJwt.getBytes(StandardCharsets.UTF_8);
        LoginRewriter.writeVaruint(out, 8 + authBytes.length + clientBytes.length);
        writeIntLE(out, authBytes.length);
        out.write(authBytes, 0, authBytes.length);
        writeIntLE(out, clientBytes.length);
        out.write(clientBytes, 0, clientBytes.length);
        return out.toByteArray();
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private static byte[] batch(int algo, byte[] entries) {
        byte[] out;
        if (algo == ALGO_DEFLATE) {
            Deflater def = new Deflater(7, true);
            def.setInput(entries);
            def.finish();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            while (!def.finished()) buf.write(b, 0, def.deflate(b));
            def.end();
            out = new byte[buf.size() + 1];
            System.arraycopy(buf.toByteArray(), 0, out, 1, buf.size());
        } else {
            out = new byte[entries.length + 1];
            System.arraycopy(entries, 0, out, 1, entries.length);
        }
        out[0] = (byte) algo;
        return out;
    }

    private static byte[] entryBatch(byte[] entry) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LoginRewriter.writeVaruint(out, entry.length);
        out.write(entry, 0, entry.length);
        return out.toByteArray();
    }

    private static byte[] plainEntry(int packetId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LoginRewriter.writeVaruint(out, packetId);
        writeIntLE(out, 0x12345678);
        out.write("payload".getBytes(StandardCharsets.UTF_8), 0, 7);
        return out.toByteArray();
    }

    private static String fakeJwt() {
        return b64url("{\"alg\":\"ES384\",\"x5u\":\"alt\"}") + "."
                + b64url("{\"ServerName\":\"Test\"}") + ".AAA";
    }

    private static byte[] parseEntry(byte[] entries, int[] pos) {
        int len = LoginRewriter.getVaruint(entries, pos);
        byte[] out = new byte[len];
        System.arraycopy(entries, pos[0], out, 0, len);
        pos[0] += len;
        return out;
    }

    // ------------------------------------------------------------------ Varint

    @Test
    public void varuint_roundtrip() {
        int[] values = {0, 1, 127, 128, 300, 16383, 16384, 0x123456, Integer.MAX_VALUE};
        for (int v : values) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LoginRewriter.writeVaruint(out, v);
            int[] pos = {0};
            assertEquals(v, LoginRewriter.getVaruint(out.toByteArray(), pos));
        }
    }

    @Test
    public void varuint_laengen() {
        assertEquals(1, LoginRewriter.varuintBytes(0).length);
        assertEquals(1, LoginRewriter.varuintBytes(127).length);
        assertEquals(2, LoginRewriter.varuintBytes(128).length);
        assertEquals(3, LoginRewriter.varuintBytes(16384).length);
        assertEquals(5, LoginRewriter.varuintBytes(Integer.MAX_VALUE).length);
    }

    @Test
    public void varuint_ueberlaenge_wirdAbgelehnt() {
        // Sechs varint-Bytes sind kein gültiger 32-Bit-Wert.
        byte[] b = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x00};
        int[] pos = {0};
        assertEquals(-1, LoginRewriter.getVaruint(b, pos));
    }

    // ------------------------------------------------------------------ Rewrites

    @Test
    public void login_wirdAufAppKontoUmgeschrieben() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> "tok-123", identity);
        byte[] login = buildLoginEntry(818,
                "{\"AuthenticationType\":1,\"Token\":\"geraet\",\"Certificate\":\"\"}", fakeJwt());
        byte[] message = batch(ALGO_NONE, entryBatch(login));
        byte[] result = rw.process(message);
        assertNotNull(result);
        assertEquals(ALGO_NONE, result[0] & 0xff);
        int[] pos = {1};
        byte[] rewritten = parseEntry(result, pos);
        assertEquals(result.length, pos[0]);

        int[] ep = {0};
        assertEquals(0x01, LoginRewriter.getVaruint(rewritten, ep));
        byte[] rest = new byte[rewritten.length - ep[0]];
        System.arraycopy(rewritten, ep[0], rest, 0, rest.length);
        int[] p2 = {0};
        int protocol = (rest[0] & 0xff) | ((rest[1] & 0xff) << 8)
                | ((rest[2] & 0xff) << 16) | ((rest[3] & 0xff) << 24);
        assertEquals(818, protocol);
        p2[0] = 4;
        int blobLen = LoginRewriter.getVaruint(rest, p2);
        int authLen = (rest[p2[0]] & 0xff) | ((rest[p2[0] + 1] & 0xff) << 8)
                | ((rest[p2[0] + 2] & 0xff) << 16) | ((rest[p2[0] + 3] & 0xff) << 24);
        JSONObject auth = new JSONObject(new String(rest, p2[0] + 4, authLen, StandardCharsets.UTF_8));
        assertEquals(0, auth.getInt("AuthenticationType"));
        assertEquals("tok-123", auth.getString("Token"));

        int clientStart = p2[0] + 4 + authLen;
        int clientLen = (rest[clientStart] & 0xff) | ((rest[clientStart + 1] & 0xff) << 8)
                | ((rest[clientStart + 2] & 0xff) << 16) | ((rest[clientStart + 3] & 0xff) << 24);
        String clientJwt = new String(rest, clientStart + 4, clientLen, StandardCharsets.UTF_8);
        String[] parts = clientJwt.split("\\.");
        JSONObject header = new JSONObject(new String(
                Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        assertEquals(identity.publicKeyBase64(), header.getString("x5u"));
        assertEquals(3, parts.length);
        assertEquals(blobLen, 8 + authLen + clientLen);
        assertTrue(rw.isDone());
    }

    @Test
    public void loginDeflate_wirdUmgeschrieben() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> "tok", identity);
        byte[] login = buildLoginEntry(818, "{}", fakeJwt());
        byte[] message = batch(ALGO_DEFLATE, entryBatch(login));
        assertNotNull(rw.process(message));
        assertTrue(rw.isDone());
    }

    @Test
    public void nichtLogin_wirdUnveraendertDurchgereicht() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> "tok", identity);
        byte[] other = entryBatch(plainEntry(0x02));
        assertNull(rw.process(batch(ALGO_NONE, other)));
        assertFalse(rw.isDone());
    }

    @Test
    public void tokenFehlt_loginWirdNieWeitersenden() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> null, identity);
        byte[] login = buildLoginEntry(818, "{}", fakeJwt());
        try {
            rw.process(batch(ALGO_NONE, entryBatch(login)));
            fail("LoginFailedException erwartet");
        } catch (LoginRewriter.LoginFailedException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void tokenProviderWirft_loginSchlaegtFehl() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> {
            throw new java.io.IOException("offline");
        }, identity);
        byte[] login = buildLoginEntry(818, "{}", fakeJwt());
        try {
            rw.process(batch(ALGO_NONE, entryBatch(login)));
            fail("LoginFailedException erwartet");
        } catch (LoginRewriter.LoginFailedException expected) {
        }
    }

    @Test
    public void kaputtesLogin_wirdAbgelehntNichtDurchgereicht() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> "tok", identity);
        byte[] truncated = {0x01, 0x00, 0x00};
        try {
            rw.process(batch(ALGO_NONE, entryBatch(truncated)));
            fail("LoginFailedException erwartet");
        } catch (LoginRewriter.LoginFailedException expected) {
        }
    }

    @Test
    public void unentpackbarerBatch_wirdAbgelehnt() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> "tok", identity);
        byte[] message = {0x00, 0x01, 0x02, 0x03}; // kein gültiges raw deflate
        try {
            rw.process(message);
            fail("LoginFailedException erwartet");
        } catch (LoginRewriter.LoginFailedException expected) {
        }
    }

    @Test
    public void nachErfolg_keineZweiteUmschreibung() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> "tok", identity);
        byte[] login = buildLoginEntry(818, "{}", fakeJwt());
        assertNotNull(rw.process(batch(ALGO_NONE, entryBatch(login))));
        assertNull(rw.process(batch(ALGO_NONE, entryBatch(login))));
    }

    @Test
    public void roheNachrichtOhneAlgo_wirdIgnoriert() throws Exception {
        NetherIdentity identity = NetherIdentity.generate();
        LoginRewriter rw = new LoginRewriter(key -> "tok", identity);
        byte[] raw = {0x10, 0x20, 0x30};
        assertNull(rw.process(raw));
    }

}
