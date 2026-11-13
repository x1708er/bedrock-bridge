package de.bedrocklan.bridge;

/** Gemeinsamer Zustand zwischen Service, Proxy und Oberfläche. */
final class State {
    static volatile boolean running = false;
    static volatile String status = "Aus";
    static volatile int clients = 0;

    private State() {}
}
