package de.bedrocklan.bridge;

import org.junit.Test;

import de.bedrocklan.bridge.State.Phase;

/** JVM-Tests für Phasenmodell, Diagnostics-Ringpuffer, Redaktion und Rotation. */
public class StateTest {

    @Test
    public void phaseModel() {
        check(State.getPhase() == Phase.OFF, "Initialphase OFF");
        check(State.getLastChangeMillis() == 0L, "Zeitstempel initial 0");
        State.setPhase(Phase.OFF, null);

        long before = System.currentTimeMillis();
        State.setPhase(Phase.STARTING, "play.example.org:19132");
        check(State.getPhase() == Phase.STARTING, "STARTING gesetzt");
        check("play.example.org:19132".equals(State.getDetail()), "Detail gesetzt");
        check(State.getLastChangeMillis() >= before, "Zeitstempel aktualisiert");

        State.setPhase(Phase.ERROR, "Port belegt");
        check(State.getPhase() == Phase.ERROR && "Port belegt".equals(State.getDetail()), "ERROR gesetzt");

        State.setPhase(Phase.OFF, null);
        check("".equals(State.getDetail()), "null-Detail leert Text");
    }

    @Test
    public void ringBuffer() {
        Diagnostics.clear();
        for (int i = 0; i < 600; i++) Diagnostics.log("t", "zeile " + i);
        String text = Diagnostics.exportText();
        String[] lines = text.split("\n");
        check(lines.length == Diagnostics.MAX_ENTRIES, "Ringpuffer auf " + Diagnostics.MAX_ENTRIES + " begrenzt: " + lines.length);
        check(lines[0].contains("zeile 100"), "älteste Einträge verworfen: " + lines[0]);
        check(lines[lines.length - 1].contains("zeile 599"), "neuester Eintrag vorhanden");
        check(text.endsWith("\n"), "Export mit Zeilenende");
        for (String line : lines) {
            check(line.contains("] t: "), "Format time [thread] tag: " + line);
            check(!line.contains("zeile 99 "), "alter Eintrag 99 weg");
        }
    }

    @Test
    public void redaction() {
        String sdp = "v=0\r\no=- 1234 1 IN IP4 10.1.2.3\r\na=candidate:1 1 udp 1 10.0.0.1 1 typ host";
        check(!Diagnostics.redact(sdp).contains("10.1.2.3"), "SDP-Zeilen redigiert: " + Diagnostics.redact(sdp));
        check(Diagnostics.redact("id 0123456789abcdef0123").contains("[token]"), "Hex-ID redigiert");
        String jwt = "token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abc wert";
        check(Diagnostics.redact(jwt).contains("[token]"), "Base64-Token redigiert: " + Diagnostics.redact(jwt));
        check(Diagnostics.redact("id abcdef-ABCDEFGHIJKLMNOPQRST").contains("[token]"),
                "Base64url-Token ohne Ziffer redigiert");
        check(Diagnostics.redact("Wiederherstellungsvorgang läuft weiter").equals("Wiederherstellungsvorgang läuft weiter"),
                "normale Texte bleiben lesbar");
        check(Diagnostics.redact("Server 192.168.1.50:19132 erreicht").contains("192.168.1.50:19132"),
                "Serveradresse bleibt lesbar (kein SDP-Kontext)");
        check(Diagnostics.redact(null).isEmpty(), "null-Toleranz");
    }

    @Test
    public void rotation() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("diagtest").toFile();
        try {
            java.io.File log = new java.io.File(dir, "crash.log");
            write(log, 1024);
            Diagnostics.rotate(log, 256 * 1024);
            check(log.isFile(), "kleine Datei wird nicht rotiert");
            check(!new java.io.File(dir, "crash.log.1").exists(), "keine .1 bei kleiner Datei");

            write(log, 300 * 1024);
            java.io.File backup = new java.io.File(dir, "crash.log.1");
            write(backup, 1);
            Diagnostics.rotate(log, 256 * 1024);
            check(backup.isFile() && backup.length() == 300 * 1024, "alte .1 ersetzt, .1 hat großen Inhalt: " + backup.length());
            check(!log.isFile(), "crash.log weggeräumt");

            java.io.File report = new java.io.File(dir, "diagnose.txt");
            write(report, 300 * 1024);
            Diagnostics.rotate(report, Diagnostics.MAX_LOG_BYTES);
            check(new java.io.File(dir, "diagnose.txt.1").isFile(), "diagnose.txt rotiert");

            java.io.File out = Diagnostics.writeReport(dir);
            check(out.isFile() && out.length() > 0, "writeReport schreibt Datei");
            check(new java.io.File(dir, "diagnose.txt.1").isFile(), "writeReport rotiert zu große Exportdatei");
        } finally {
            delete(dir);
        }
    }

    @Test
    public void concurrency() throws Exception {
        Diagnostics.clear();
        State.setPhase(Phase.OFF, null);
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    Diagnostics.log("t" + i, "msg " + i);
                    State.setPhase(State.Phase.values()[i % 6], "d" + i);
                }
            }, "stresstest-" + t);
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        String[] lines = Diagnostics.exportText().split("\n");
        check(lines.length <= Diagnostics.MAX_ENTRIES, "Ringpuffer auch unter Last begrenzt: " + lines.length);
        check(State.getDetail() != null && State.getLastChangeMillis() > 0, "State nach Stresstest konsistent");
    }

    private static void write(java.io.File f, int size) throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            byte[] b = new byte[1024];
            for (int i = 0; i < size; i += b.length) out.write(b, 0, Math.min(b.length, size - i));
        }
    }

    private static void delete(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) delete(c);
        f.delete();
    }

    private static void check(boolean b, String msg) {
        if (!b) throw new AssertionError(msg);
    }
}