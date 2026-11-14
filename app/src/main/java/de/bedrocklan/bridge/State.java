package de.bedrocklan.bridge;

/** Gemeinsamer Zustand zwischen Service, Proxy und Oberfläche. */
public final class State {
    static volatile boolean running = false;
    static volatile String status = "Aus";
    static volatile int clients = 0;
    static volatile String serverName;
    static volatile String levelName;
    static volatile int players = -1;
    static volatile int maxPlayers = -1;
    static volatile String version;

    private State() {}

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