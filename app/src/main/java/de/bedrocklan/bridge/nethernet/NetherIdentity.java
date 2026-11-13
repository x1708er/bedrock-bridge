package de.bedrocklan.bridge.nethernet;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Selbstsignierte NetherNet-Identität nach go-nethernet/identity.go:
 * ES384-JWT mit x5u-Header und cpk-Claim (Base64-DER-Public-Key) plus
 * Detached-JWS über die DTLS-Fingerprints des eigenen SDP.
 */
public final class NetherIdentity {
    private static final String AUTH_DOMAIN = "https://authorization.franchise.minecraft-services.net";
    private static final String SELF_DOMAIN = "self";

    private final KeyPair keyPair;

    private NetherIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static NetherIdentity generate() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp384r1"));
        return new NetherIdentity(g.generateKeyPair());
    }

    /** Öffentlicher Schlüssel der Identität (für den cpk-Claim beim Multiplayer-Token). */
    public java.security.PublicKey publicKey() {
        return keyPair.getPublic();
    }

    /** Attributwert (Base64 des JSON) für ein Client-Offer mit selbstsigniertem Token. */
    public String clientAttribute(String sdp) throws GeneralSecurityException {
        return sdpAttribute(sdp, token(System.currentTimeMillis() / 1000), AUTH_DOMAIN);
    }

    /**
     * Attributwert für ein Client-Offer mit echtem Multiplayer-Token.
     * Das Token muss den öffentlichen Schlüssel dieser Identität als
     * cpk-Claim tragen (AccountManager.multiplayerToken).
     */
    public String clientAttribute(String sdp, String multiplayerToken) throws GeneralSecurityException {
        return sdpAttribute(sdp, multiplayerToken, AUTH_DOMAIN);
    }

    /** Attributwert für ein Server-Answer (Domain „self“ wie beim BDS). */
    public String serverAttribute(String sdp) throws GeneralSecurityException {
        return sdpAttribute(sdp, token(System.currentTimeMillis() / 1000), SELF_DOMAIN);
    }

    private String sdpAttribute(String sdp, String jwt, String domain) throws GeneralSecurityException {
        List<String[]> fingerprints = parseFingerprints(sdp);
        if (fingerprints.isEmpty()) return null;
        String detached = fingerprintAssertion(fingerprints);
        String assertion = "{\"fingerprints\":\"" + detached + "\",\"token\":\"" + jwt + "\"}";
        String full = "{\"assertion\":" + quote(assertion)
                + ",\"idp\":{\"domain\":\"" + domain + "\",\"protocol\":\"default\"}}";
        return Base64.getEncoder().encodeToString(full.getBytes(StandardCharsets.UTF_8));
    }

    private String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private String token(long nowSec) throws GeneralSecurityException {
        String header = "{\"alg\":\"ES384\",\"x5u\":\"" + publicKeyBase64() + "\"}";
        String payload = "{\"exp\":" + (nowSec + 60) + ",\"iat\":" + nowSec
                + ",\"cpk\":\"" + publicKeyBase64() + "\"}";
        String h = b64url(header);
        String p = b64url(payload);
        return h + "." + p + "." + sign((h + "." + p).getBytes(StandardCharsets.UTF_8));
    }

    private String fingerprintAssertion(List<String[]> fingerprints) throws GeneralSecurityException {
        StringBuilder payload = new StringBuilder("{\"fingerprint\":[");
        for (int i = 0; i < fingerprints.size(); i++) {
            if (i > 0) payload.append(',');
            payload.append("{\"algorithm\":\"").append(fingerprints.get(i)[0])
                    .append("\",\"digest\":\"").append(fingerprints.get(i)[1]).append("\"}");
        }
        payload.append("]}");
        String h = b64url("{\"alg\":\"ES384\"}");
        String s = sign(payload.toString().getBytes(StandardCharsets.UTF_8));
        return h + ".." + s;
    }

    private String sign(byte[] data) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("SHA384withECDSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(derToRaw(sig.sign(), 48));
    }

    /** ECDSA-DER-Signatur (SEQUENCE r, s) in rohes R||S mit je „half“ Bytes umwandeln. */
    private static byte[] derToRaw(byte[] der, int half) {
        int off = 2; // SEQUENCE-Header überspringen
        if ((der[1] & 0xff) > 0x80) off += (der[1] & 0xff) & 0x7f; // Längen-Longform
        if (der[off] != 0x02) throw new IllegalArgumentException("keine DER-Sequenz");
        off++;
        int rLen = der[off] & 0xff;
        off++;
        byte[] r = new byte[half];
        int rStart = Math.max(0, rLen - half);
        System.arraycopy(der, off + rStart, r, half - (rLen - rStart), rLen - rStart);
        off += rLen;
        off++; // 0x02
        int sLen = der[off] & 0xff;
        off++;
        byte[] s = new byte[half];
        int sStart = Math.max(0, sLen - half);
        System.arraycopy(der, off + sStart, s, half - (sLen - sStart), sLen - sStart);
        byte[] out = new byte[half * 2];
        System.arraycopy(r, 0, out, 0, half);
        System.arraycopy(s, 0, out, half, half);
        return out;
    }

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        out.append('"');
        return out.toString();
    }

    /** Alle a=fingerprint-Zeilen eines SDP als [Algorithmus, Digest]-Paare. */
    private static List<String[]> parseFingerprints(String sdp) {
        List<String[]> out = new ArrayList<>();
        for (String line : sdp.split("\n")) {
            line = line.trim();
            if (line.startsWith("a=fingerprint:")) {
                String[] parts = line.substring("a=fingerprint:".length()).trim().split(" ", 2);
                if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                    out.add(new String[]{parts[0], parts[1].trim()});
                }
            }
        }
        return out;
    }

    /**
     * a=identity-Zeilen aus Remote-SDPs entfernen (libwebrtc kennt sie nicht)
     * und Leerzeilen normalisieren: libwebrtcs Parser lehnt SDPs mit Leerzeilen
     * mit "Invalid SDP line." ab (parse Bricht ab, desc wird null).
     */
    public static String stripIdentity(String sdp) {
        StringBuilder out = new StringBuilder(sdp.length());
        for (String line : sdp.split("\n", -1)) {
            String t = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (t.startsWith("a=identity:")) continue;
            if (t.isEmpty()) continue;
            out.append(t).append('\n');
        }
        return out.toString();
    }

    /** Sitzungsattribut vor die erste m=-Zeile setzen. */
    public static String insertSessionAttribute(String sdp, String attribute) {
        int idx = -1;
        int pos = 0;
        for (String line : sdp.split("\n", -1)) {
            if (line.startsWith("m=")) {
                idx = pos;
                break;
            }
            pos += line.length() + 1;
        }
        if (idx < 0) return sdp + attribute + '\n';
        return sdp.substring(0, idx) + attribute + '\n' + sdp.substring(idx);
    }
}
