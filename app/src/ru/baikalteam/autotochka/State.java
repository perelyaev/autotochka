package ru.baikalteam.autotochka;

/** Общее состояние для сервиса и интерфейса. */
public final class State {

    public static volatile boolean serviceRunning;
    public static volatile boolean scanning;
    public static volatile boolean hotspotOn;
    public static volatile long lastSeen;
    public static volatile long lastScanStart;
    public static volatile int scans;
    public static volatile String lastDevice = "";
    public static volatile String lastResult = "";

    private State() {
    }
}
