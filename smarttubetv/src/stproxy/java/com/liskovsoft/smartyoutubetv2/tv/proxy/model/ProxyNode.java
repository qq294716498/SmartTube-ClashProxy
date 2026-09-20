package com.liskovsoft.smartyoutubetv2.tv.proxy.model;

public final class ProxyNode {
    public static final int DELAY_UNKNOWN = 0;
    public static final int DELAY_TESTING = -2;
    public static final int DELAY_TIMEOUT = -1;

    public final String name;
    public final String runtimeName;
    public final String type;
    public final boolean automatic;
    public volatile boolean selected;
    public volatile int delayMs;

    public ProxyNode(String name, String runtimeName, String type, boolean automatic) {
        this.name = name;
        this.runtimeName = runtimeName;
        this.type = type;
        this.automatic = automatic;
    }
}
