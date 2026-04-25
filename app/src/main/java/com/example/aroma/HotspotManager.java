package com.example.aroma;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Starts a local-only WiFi hotspot so peers (e.g. a Mac) can connect directly
 * to this device without any existing WiFi network or internet.
 *
 * Uses WifiManager.startLocalOnlyHotspot() on API 26+. The resulting SSID and
 * password are shown to the user so the peer can join, then AROMA's HTTP/WebDAV
 * server is reachable at http://<hotspot-ip>:<port>.
 */
public class HotspotManager {
    private static final String TAG = "AROMA";

    public interface Listener {
        void onHotspotStarted(String ssid, String password, String ipAddress);
        void onHotspotFailed(String reason);
        void onHotspotStopped();
    }

    private final Context context;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean starting;

    public HotspotManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isActive() {
        return reservation != null;
    }

    public String getHotspotIpAddress() {
        return findHotspotIp();
    }

    public void start(Listener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            listener.onHotspotFailed("Android 8.0 or newer required for local hotspot");
            return;
        }
        if (starting || reservation != null) {
            listener.onHotspotFailed("Hotspot already starting or active");
            return;
        }
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            listener.onHotspotFailed("WifiManager not available");
            return;
        }
        starting = true;
        try {
            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    super.onStarted(res);
                    starting = false;
                    reservation = res;
                    String ssid = "AROMA-Direct";
                    String password = "";
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            android.net.wifi.SoftApConfiguration config = res.getSoftApConfiguration();
                            if (config != null) {
                                if (config.getSsid() != null) ssid = config.getSsid();
                                if (config.getPassphrase() != null) password = config.getPassphrase();
                            }
                        } else {
                            WifiConfiguration config = res.getWifiConfiguration();
                            if (config != null) {
                                if (config.SSID != null) ssid = config.SSID;
                                if (config.preSharedKey != null) password = config.preSharedKey;
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Hotspot config read failed: " + t.getMessage());
                    }
                    final String finalSsid = ssid;
                    final String finalPassword = password;
                    // IP can take a moment to appear on the hotspot interface.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        String ip = findHotspotIp();
                        listener.onHotspotStarted(finalSsid, finalPassword, ip);
                    }, 800);
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                    reservation = null;
                    starting = false;
                    listener.onHotspotStopped();
                }

                @Override
                public void onFailed(int reason) {
                    super.onFailed(reason);
                    reservation = null;
                    starting = false;
                    listener.onHotspotFailed(describeFailure(reason));
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException e) {
            starting = false;
            listener.onHotspotFailed("Permission denied: " + e.getMessage()
                    + " (grant Location / Nearby devices)");
        } catch (IllegalStateException e) {
            starting = false;
            listener.onHotspotFailed("Cannot start hotspot: " + e.getMessage()
                    + " (turn WiFi on and disconnect from tethering)");
        } catch (Throwable t) {
            starting = false;
            listener.onHotspotFailed("Hotspot error: " + t.getMessage());
        }
    }

    public void stop() {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (Throwable t) {
                Log.w(TAG, "Hotspot close failed: " + t.getMessage());
            }
            reservation = null;
        }
    }

    private String describeFailure(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "No available WiFi channel. Try turning WiFi off and on again.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
                return "Hotspot failed. Try turning regular hotspot off, turning airplane mode off, and retrying.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "Hotspot is blocked by the phone's current network mode. Turn off regular hotspot, Wi-Fi Direct/casting, and retry.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "Tethering not allowed on this device (carrier restriction)";
            default:
                return "Hotspot failed (code " + reason + "). Turn off regular hotspot/casting, leave airplane mode off, and retry.";
        }
    }

    /**
     * Find the IPv4 assigned to the hotspot interface. Android typically
     * assigns 192.168.49.1 on a softap/ap0/wlan1-style interface. We pick the
     * first non-loopback IPv4 that is not the normal Wi-Fi client address.
     */
    private String findHotspotIp() {
        String best = null;
        try {
            for (Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces(); ifs.hasMoreElements(); ) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase();
                for (Enumeration<InetAddress> addrs = nif.getInetAddresses(); addrs.hasMoreElements(); ) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    if (host == null) continue;
                    // Hotspot-likely interfaces: ap0, softap, swlan, wlan1, rndis, usb
                    if (name.startsWith("ap") || name.contains("softap")
                            || name.contains("swlan") || name.equals("wlan1")
                            || name.startsWith("rndis") || name.startsWith("usb")) {
                        return host;
                    }
                    // Keep the first reasonable IPv4 as a fallback.
                    if (best == null && host.startsWith("192.168.")) {
                        best = host;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Hotspot IP scan failed: " + t.getMessage());
        }
        return best != null ? best : "192.168.49.1";
    }
}
