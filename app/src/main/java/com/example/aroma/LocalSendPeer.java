package com.example.aroma;

/** Represents a discovered LocalSend-compatible peer on the local network. */
public class LocalSendPeer {
    public final String alias;
    public final String fingerprint;
    public final String ip;
    public final int port;
    public final String deviceModel;
    public final String deviceType; // "mobile", "desktop", "tablet", "headless"
    public long lastSeen;

    public LocalSendPeer(String alias, String fingerprint, String ip, int port,
                         String deviceModel, String deviceType) {
        this.alias = alias;
        this.fingerprint = fingerprint;
        this.ip = ip;
        this.port = port;
        this.deviceModel = deviceModel != null ? deviceModel : "";
        this.deviceType = deviceType != null ? deviceType : "mobile";
        this.lastSeen = System.currentTimeMillis();
    }

    public String getBaseUrl() {
        return "https://" + ip + ":" + port;
    }

    @Override public String toString() { return alias + " [" + ip + ":" + port + "]"; }
}
