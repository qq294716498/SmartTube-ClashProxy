package com.liskovsoft.smartyoutubetv2.common.proxy;

import java.util.concurrent.CopyOnWriteArrayList;

/** Shared startup signal; ordinary flavors remain OFF and never wait for Mihomo. */
public final class EmbeddedProxyStartup {
    public enum State { OFF, CONNECTING, READY, FAILED }
    public static final class Status {
        public final State state;
        public final String message;
        private Status(State state, String message) {
            this.state = state;
            this.message = message;
        }
    }
    private static volatile Status status = new Status(State.OFF, null);
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private EmbeddedProxyStartup() { }
    public static Status getStatus() { return status; }
    public static void addListener(Runnable listener) { LISTENERS.addIfAbsent(listener); }
    public static void removeListener(Runnable listener) { LISTENERS.remove(listener); }
    public static void connecting() { publish(State.CONNECTING, null); }
    public static void ready() { publish(State.READY, null); }
    public static void off() { publish(State.OFF, null); }
    public static void failed(String message) { publish(State.FAILED, message); }

    private static void publish(State state, String message) {
        status = new Status(state, message);
        for (Runnable listener : LISTENERS) listener.run();
    }
}
