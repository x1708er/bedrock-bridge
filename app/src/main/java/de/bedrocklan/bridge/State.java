package de.bedrocklan.bridge;

/** Gemeinsamer Zustand zwischen Service, Proxy und Oberfläche. */
public final class State {
    static volatile boolean running = false;
    static volatile String status = "Aus";
    static volatile int clients = 0;
    static volatile boolean netherNet;

    private State() {}
}
