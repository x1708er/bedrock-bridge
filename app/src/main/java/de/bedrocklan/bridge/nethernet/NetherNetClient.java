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
 * NetherNet-Signaling über den HTTP-Endpunkt des Servers (GET/POST /v1/join),
 * wie ihn der Vanilla-Client für "externe Server" verwendet (df-mc/nethernet-spec).
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

    /** Server-Status abfragen; liefert null, wenn der Server kein NetherNet anbietet. */
    public Status fetchStatus(int timeoutMs) throws IOException {
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
     * {@link #fetchStatus(int)} muss vorher erfolgreich gewesen sein.
     */
    public String exchangeSdp(String offerSdp, int timeoutMs) throws IOException {
        if (endpointBase == null && fetchStatus(timeoutMs) == null) {
            throw new IOException("Server bietet kein NetherNet an");
        }
        String url = endpointBase + "/v1/join/" + networkId;
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
                throw new IOException("Server lehnt ab: HTTP " + code);
            }
            String answer = new String(readAll(c.getInputStream(), 512 * 1024), StandardCharsets.UTF_8);
            if (answer.isEmpty()) throw new IOException("leeres SDP-Answer");
            return answer;
        } finally {
            c.disconnect();
        }
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

    /** Reihenfolge wie der Vanilla-Client: erst HTTPS, dann HTTP (endpoint/doc.go). */
    private List<String> candidateBases() {
        List<String> l = new ArrayList<>();
        int p = port == 0 ? 19132 : port;
        if (p == 19132) {
            l.add("https://" + host(p));
            l.add("https://" + host);
            l.add("http://" + host(p));
            l.add("http://" + host);
        } else {
            l.add("https://" + host(p));
            l.add("http://" + host(p));
        }
        return l;
    }

    private String host(int p) {
        return host + ":" + p;
    }

    public static Status parseStatus(String json) {
        JSONObject o = new JSONObject(json);
        Status s = new Status();
        s.name = optString(o, "name", "Unbekannt");
        s.protocol = o.optInt("protocol", 0);
        s.version = optString(o, "version", "?");
        s.level = optString(o, "level", "");
        s.players = o.optInt("players", 0);
        s.maxPlayers = o.optInt("maxPlayers", 0);
        s.gameType = o.optInt("gameType", 0);
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

        /** RakNet-Pong-MOTD wie Status.RakNet() aus df-mc/go-nethernet (Port immer 19132). */
        public String rakNetMotd(long serverId) {
            String mode;
            switch (gameType) {
                case 0: mode = "Survival"; break;
                case 1: mode = "Creative"; break;
                case 2: mode = "Adventure"; break;
                default: mode = "Unknown"; break;
            }
            return "MCPE;" + clean(name) + ";" + protocol + ";" + clean(version) + ";"
                    + players + ";" + maxPlayers + ";" + Long.toUnsignedString(serverId) + ";"
                    + clean(level) + ";" + mode + ";" + gameType + ";19132;19132;0;";
        }

        private static String clean(String s) {
            return s == null ? "" : s.replace(";", " ");
        }
    }
}
