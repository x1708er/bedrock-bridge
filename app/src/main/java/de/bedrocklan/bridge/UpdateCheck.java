package de.bedrocklan.bridge;

/** Versionsvergleich und Intervall-Logik für den Update-Check. */
public final class UpdateCheck {

    public static final long MIN_INTERVAL_MS = 3_600_000L;

    private UpdateCheck() {}

    /** Liefert true, wenn seit dem letzten Check mindestens eine Stunde vergangen ist. */
    public static boolean due(long now, long lastCheck) {
        return now - lastCheck >= MIN_INTERVAL_MS;
    }

    public static boolean isNewer(String candidate, String current) {
        return compareVersions(candidate, current) > 0;
    }

    /** Vergleicht bis zu drei numerische Segmente; „v“-Prefix wird ignoriert. */
    public static int compareVersions(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        }
        return 0;
    }

    private static int[] parts(String v) {
        int[] out = new int[3];
        if (v == null) return out;
        String[] p = v.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Integer.parseInt(p[i]);
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
