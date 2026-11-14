package de.bedrocklan.bridge;

/** Gemeinsamer Zustand zwischen Service, Proxy und Oberfläche. */
public final class State {

    public enum Phase { OFF, STARTING, RUNNING, ACTIVE, DEGRADED, ERROR }

    static volatile boolean running = false;
    static volatile String status = "Aus";
    static volatile int clients = 0;
    static volatile String serverName;
    static volatile String levelName;
    static volatile int players = -1;
    static volatile int maxPlayers = -1;
    static volatile String version;

    private static final Object PHASE_LOCK = new Object();
    private static volatile Phase phase = Phase.OFF;
    private static volatile String detail = "";
    private static volatile long lastChangeMillis = 0L;

    private State() {}

    public static Phase getPhase() {
        return phase;
    }

    public static String getDetail() {
        return detail;
    }

    /** Zeitstempel des letzten Phasenwechsels in ms seit Epoche. */
    public static long getLastChangeMillis() {
        return lastChangeMillis;
    }

    /** Setzt Phase, Detailtext und Zeitstempel atomar; Änderungen werden ins Diagnoseprotokoll geschrieben. */
    public static void setPhase(Phase next, String why) {
        Phase old;
        synchronized (PHASE_LOCK) {
            old = phase;
            phase = next;
            detail = why == null ? "" : why;
            lastChangeMillis = System.currentTimeMillis();
        }
        if (old != next) {
            Diagnostics.log("state", next + (detail.isEmpty() ? "" : " – " + detail));
        }
    }

    static void setServerInfo(String name, String level, int players, int maxPlayers, String version) {
        serverName = name;
        levelName = level;
        State.players = players;
        State.maxPlayers = maxPlayers;
        State.version = version;
    }

    static void clearServerInfo() {
        serverName = null;
        levelName = null;
        players = -1;
        maxPlayers = -1;
        version = null;
    }
}