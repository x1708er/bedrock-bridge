package de.bedrocklan.bridge;

import java.util.Locale;

/** Robuster Parser für Serveradressen inkl. IPv6-Literale. */
public final class ServerAddress {

    public static final int DEFAULT_PORT = 19132;

    public final String host;
    public final int port;

    private ServerAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Akzeptiert host, host:port, „host port“, Schema-Prefix, Pfad-Suffix und IPv6 (mit/ohne Klammern). */
    public static ServerAddress parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.isEmpty()) return null;

        String[] t = s.split("\\s+");
        String hostToken;
        int port = DEFAULT_PORT;
        if (t.length == 2) {
            hostToken = t[0];
            port = parsePort(t[1]);
            if (port <= 0) return null;
        } else if (t.length == 1) {
            hostToken = t[0];
        } else {
            return null;
        }

        if (hostToken.startsWith("[")) {
            int close = hostToken.indexOf(']');
            if (close < 2) return null;
            String host = hostToken.substring(1, close);
            if (!validHost(host) || (host.indexOf(':') >= 0 && !isValidIpv6(host))) return null;
            String rest = hostToken.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) return null;
                port = parsePort(rest.substring(1));
                if (port <= 0) return null;
            }
            return new ServerAddress(host, port);
        }

        int last = -1;
        int colons = 0;
        for (int i = 0; i < hostToken.length(); i++) {
            if (hostToken.charAt(i) == ':') {
                colons++;
                last = i;
            }
        }
        if (colons == 1) {
            int p = parsePort(hostToken.substring(last + 1));
            if (p <= 0) return null;
            String host = hostToken.substring(0, last);
            if (!validHost(host)) return null;
            return new ServerAddress(host, p);
        }
        // Multi-Colon ohne Klammern: nur als gültiges IPv6-Literal akzeptieren.
        if (colons >= 2 && !isValidIpv6(hostToken)) return null;
        if (!validHost(hostToken)) return null;
        return new ServerAddress(hostToken, port);
    }

    /**
     * Prüft ein IPv6-Literal ohne DNS-Auflösung: Gruppen aus 1-4 Hex-Ziffern,
     * maximal ein „::“, optional mit IPv4-Ende und Zone-ID. Offensichtlich
     * ungültige Multi-Colon-Hosts werden abgelehnt.
     */
    static boolean isValidIpv6(String s) {
        if (s == null || s.isEmpty()) return false;
        int zone = s.indexOf('%');
        if (zone >= 0) {
            if (zone == 0 || zone == s.length() - 1) return false;
            s = s.substring(0, zone);
        }
        if (s.isEmpty()) return false;
        if (s.startsWith(":") && !s.startsWith("::")) return false;
        if (s.endsWith(":") && !s.endsWith("::")) return false;
        int dc = s.indexOf("::");
        if (dc >= 0 && s.indexOf("::", dc + 1) >= 0) return false;
        if (dc < 0) {
            return countIpv6Groups(s, true) == 8;
        }
        int groups = 0;
        String head = s.substring(0, dc);
        String tail = s.substring(dc + 2);
        if (!head.isEmpty()) {
            int n = countIpv6Groups(head, false);
            if (n < 0) return false;
            groups += n;
        }
        if (!tail.isEmpty()) {
            int n = countIpv6Groups(tail, true);
            if (n < 0) return false;
            groups += n;
        }
        return groups <= 7;
    }

    /** Zählt die „:“-Gruppen eines Abschnitts; -1 wenn ungültig. IPv4 nur am Ende erlaubt. */
    private static int countIpv6Groups(String run, boolean ipv4AllowedLast) {
        String[] g = run.split(":", -1);
        for (int i = 0; i < g.length; i++) {
            boolean last = i == g.length - 1;
            if (isHexGroup(g[i])) continue;
            if (last && ipv4AllowedLast && isIpv4(g[i])) return g.length; // IPv4 zählt als 2 Gruppen
            return -1;
        }
        return g.length;
    }

    private static boolean isHexGroup(String g) {
        if (g.isEmpty() || g.length() > 4) return false;
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static boolean isIpv4(String g) {
        String[] p = g.split("\\.", -1);
        if (p.length != 4) return false;
        for (String part : p) {
            if (part.isEmpty() || part.length() > 3) return false;
            try {
                int v = Integer.parseInt(part);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean validHost(String h) {
        if (h.isEmpty()) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean ok = Character.isLetterOrDigit(c)
                    || c == '.' || c == ':' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static int parsePort(String s) {
        if (s.isEmpty()) return -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        long v;
        try {
            v = Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (v < 1 || v > 65535) return -1;
        return (int) v;
    }

    /** Kanonische Anzeige: Host klein, IPv6 in Klammern, Default-Port weggelassen; null bei ungültig. */
    public static String normalize(String raw) {
        ServerAddress a = parse(raw);
        if (a == null) return null;
        String host = a.host.toLowerCase(Locale.ROOT);
        String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return a.port == DEFAULT_PORT ? shown : shown + ":" + a.port;
    }
}
