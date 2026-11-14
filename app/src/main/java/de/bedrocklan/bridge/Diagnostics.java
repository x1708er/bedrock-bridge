package de.bedrocklan.bridge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnose: thread-sicherer, redigierter Ringpuffer (keine Token/SDP-Details;
 * Serveradressen sind erlaubt) plus Export als Datei und crash.log-Rotation.
 */
public final class Diagnostics {
    static final int MAX_ENTRIES = 500;
    static final long MAX_LOG_BYTES = 256 * 1024;

    private static final Pattern SDP = Pattern.compile("(?m)^[a-z]=.*$");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-fA-F]{16,}\\b");
    /** Base64/Base64url-Zeichensatz; reine Wörter dieser Länge bleiben unangetastet. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9+/=_-]{24,}");

    private static final ArrayDeque<String> RING = new ArrayDeque<>();

    private Diagnostics() {}

    public static synchronized void log(String tag, String msg) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        RING.addLast(time + " [" + Thread.currentThread().getName() + "] " + tag + ": " + redact(msg));
        while (RING.size() > MAX_ENTRIES) RING.removeFirst();
    }

    public static synchronized String exportText() {
        StringBuilder sb = new StringBuilder();
        for (String line : RING) sb.append(line).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        RING.clear();
    }

    /**
     * Redigiert SDP-Zeilen, Hex-IDs und Base64-artige Token; normale Texte
     * und Serveradressen bleiben lesbar. Ein Token wird auch ohne Ziffer
     * redigiert, wenn es Base64-Sonderzeichen ( + / = - _ ) enthält — reine
     * Buchstabenwörter (auch lange) werden nicht zerstört.
     */
    public static String redact(String s) {
        if (s == null) return "";
        Matcher m = SDP.matcher(s);
        if (m.find()) s = m.replaceAll("[sdp redigiert]");
        s = HEX.matcher(s).replaceAll("[token]");
        Matcher t = TOKEN.matcher(s);
        StringBuffer out = new StringBuffer();
        while (t.find()) {
            String g = t.group();
            boolean tokenLike = g.matches(".*\\d.*") || g.matches(".*[+/=_\\-].*");
            t.appendReplacement(out, tokenLike
                    ? Matcher.quoteReplacement("[token]") : Matcher.quoteReplacement(g));
        }
        t.appendTail(out);
        return out.toString();
    }

    /** Verschiebt eine zu große Logdatei nach <name>.1 (ersetzt eine bestehende .1-Datei). */
    public static void rotate(File file, long maxBytes) {
        if (file == null || !file.isFile() || file.length() <= maxBytes) return;
        File backup = new File(file.getParentFile(), file.getName() + ".1");
        if (backup.exists() && !backup.delete()) return;
        if (!file.renameTo(backup)) file.delete();
    }

    /** Schreibt den Diagnosetext in das gegebene Verzeichnis (cache/external-files) und liefert die Datei zurück. */
    public static File writeReport(File dir) throws java.io.IOException {
        File out = new File(dir, "diagnose.txt");
        rotate(out, MAX_LOG_BYTES);
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(exportText());
        }
        return out;
    }
}