package de.bedrocklan.bridge.nethernet;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * NetherNet-Signaling über den HTTPS-Endpunkt des Servers (GET/POST /v1/join),
 * wie ihn der Vanilla-Client für "externe Server" verwendet (df-mc/nethernet-spec).
 * HTTP wird nicht unterstützt.
 */
public final class NetherNetClient {
    private final String host;
    private final int port;
    private final String networkId = Long.toUnsignedString(new Random().nextLong() & Long.MAX_VALUE);

    private String endpointBase;

    public NetherNetClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String networkId() {
        return networkId;
    }

    /**
     * Server-Status abfragen; liefert null, wenn der Server kein NetherNet
     * anbietet. Synchronisiert: endpointBase wird nur hier gesetzt/geertet,
     * eine veraltete Basis wird neu ermittelt statt dauerhaft zu sperren.
     */
    public synchronized Status fetchStatus(int timeoutMs) throws IOException {
        String known = endpointBase;
        if (known != null) {
            try {
                return httpGetStatus(known + "/v1/join", timeoutMs);
            } catch (IOException e) {
                // bekannte Basis ist nicht mehr erreichbar: neu ermitteln
                endpointBase = null;
            }
        }
        for (String base : candidateBases()) {
            try {
                Status s = httpGetStatus(base + "/v1/join", timeoutMs);
                endpointBase = base;
                return s;
            } catch (IOException e) {
                // nächste Basis-URL probieren
            }
        }
        return null;
    }

    /**
     * SDP-Offer an den Server posten; liefert das SDP-Answer des Servers.
     * {@link #fetchStatus(int)} muss vorher erfolgreich gewesen sein. Ist die
     * gecachte Basis ungültig, wird sie verworfen, einmal neu ermittelt und
     * das Posten erneut versucht — statt an einer toden Basis dauerhaft
     * zu scheitern.
     */
    public synchronized String exchangeSdp(String offerSdp, int timeoutMs) throws IOException {
        if (endpointBase == null && fetchStatus(timeoutMs) == null) {
            throw new IOException("Server bietet kein NetherNet an");
        }
        try {
            return postSdp(offerSdp, timeoutMs);
        } catch (IOException e) {
            endpointBase = null;
            if (fetchStatus(timeoutMs) == null) {
                throw new IOException("Server bietet kein NetherNet an");
            }
            return postSdp(offerSdp, timeoutMs);
        }
    }

    private String postSdp(String offerSdp, int timeoutMs) throws IOException {
        String url = endpointBase + "/v1/join/" + networkId;
        if (!url.startsWith("https://")) throw new IOException("Nur HTTPS erlaubt: " + abbrevBase(endpointBase));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/sdp");
            c.setRequestProperty("User-Agent", "libhttpclient/1.0.0.0");
            byte[] body = offerSdp.getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("Server lehnt ab: HTTP " + code
                        + (code == 401 ? " (Identität nicht akzeptiert — verlangt ein Xbox-Kunden-Token?)"
                        : code == 403 ? " (vom Serverbetreiber gesperrt)" : ""));
            }
            String answer = new String(readAll(c.getInputStream(), 512 * 1024), StandardCharsets.UTF_8);
            if (answer.isEmpty()) throw new IOException("leeres SDP-Answer");
            return answer;
        } finally {
            c.disconnect();
        }
    }

    private static String abbrevBase(String url) {
        int scheme = url.indexOf("://");
        return scheme < 0 ? url : url.substring(0, scheme) + "://…";
    }

    private Status httpGetStatus(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "libhttpclient/1.0.0.0");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            byte[] body = readAll(c.getInputStream(), 256 * 1024);
            return parseStatus(new String(body, StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    /** Nur HTTPS-Kandidaten; HTTP wird grundsätzlich nicht unterstützt. */
    private List<String> candidateBases() {
        return httpsCandidateBases(host, port);
    }

    /**
     * HTTPS-Basis-URLs für einen Server. Explizit angegebenes „http://“ wird
     * eindeutig auf „https://“ normalisiert, unbekannte Schemata verworfen.
     */
    public static List<String> httpsCandidateBases(String host, int port) {
        List<String> l = new ArrayList<>();
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) return l;
        if (h.startsWith("http://")) h = h.substring("http://".length());
        else if (h.startsWith("https://")) h = h.substring("https://".length());
        else if (h.contains("://")) return l;
        int p = port == 0 ? 19132 : port;
        l.add("https://" + hostPort(h, p));
        if (p == 19132) l.add("https://" + bracketIpv6(h));
        return l;
    }

    /** IPv6-Literale (Host mit „:“) müssen in HTTPS-URLs geklammert werden. */
    static String bracketIpv6(String host) {
        if (host == null) return host;
        if (host.startsWith("[") && host.endsWith("]")) return host;
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private static String hostPort(String host, int port) {
        return bracketIpv6(host) + ":" + port;
    }

    public static Status parseStatus(String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("Status-JSON defekt", e);
        }
        Status s = new Status();
        s.name = optString(o, "name", "Unbekannt");
        s.protocol = o.optInt("protocol", 0);
        s.version = optString(o, "version", "?");
        s.level = optString(o, "level", "");
        s.players = o.optInt("players", 0);
        s.maxPlayers = o.optInt("maxPlayers", 0);
        s.gameType = o.optInt("gameType", 0);
        s.editor = o.optBoolean("editor", false);
        s.hardcore = o.optBoolean("hardcore", false);
        s.onlineAuth = o.optBoolean("onlineAuth", true);
        s.selfSignedAuth = o.optBoolean("selfSignedAuth", true);
        s.nonce = optString(o, "nonce", "");
        return s;
    }

    private static String optString(JSONObject o, String k, String def) {
        String v = o.optString(k, def);
        return v == null ? def : v;
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IOException("Antwort zu groß");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static final class Status {
        public String name;
        public int protocol;
        public String version;
        public String level;
        public int players;
        public int maxPlayers;
        public int gameType;
        public boolean editor;
        public boolean hardcore;
        public boolean onlineAuth;
        public boolean selfSignedAuth;
        public String nonce;

        /** Anzeigenamen bereinigen: Semikolons raus, Geyser-Quotes strippen. */
        public static String plain(String s) {
            if (s == null) return "";
            String v = s.replace(";", " ").trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1).trim();
            }
            return v;
        }
    }
}
