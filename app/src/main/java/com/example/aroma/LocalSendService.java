package com.example.aroma;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

import fi.iki.elonen.NanoHTTPD;

/**
 * LocalSend-compatible HTTPS server + UDP multicast discovery service.
 *
 * Protocol: https://github.com/localsend/localsend/blob/main/PROTOCOL.md
 * License of this implementation: same as the rest of app-aroma-java (MIT/proprietary).
 * The protocol spec is Apache 2.0.
 */
public class LocalSendService extends Service {

    // ---- Logging / channel ----
    private static final String TAG = "LocalSendService";
    private static final String NOTIF_CHANNEL = "localsend";
    private static final int    NOTIF_FG_ID   = 3001;
    private static final int    NOTIF_RX_ID   = 3002;

    // ---- Network ----
    private static final int    ANNOUNCE_INTERVAL_MS = 5000;
    private static final int    PEER_TIMEOUT_MS      = 30_000;
    private static final int    UDP_BUF_SIZE         = 4096;

    // ---- State ----
    private LocalSendCertHelper         certHelper;
    private LsHttpsServer               httpsServer;
    private MulticastSocket             mcastSocket;
    private WifiManager.MulticastLock   multicastLock;
    private Thread              udpListenThread;
    private Thread              announceThread;
    private boolean             running = false;

    /** peers keyed by fingerprint */
    private final ConcurrentHashMap<String, LocalSendPeer> peers = new ConcurrentHashMap<>();

    /**
     * Active inbound sessions: token → File (temp path).
     * Populated when we accept a send-request; consumed as /send chunks arrive.
     */
    private final ConcurrentHashMap<String, PendingFile> pendingFiles = new ConcurrentHashMap<>();

    /** Session-level semaphore: non-null while we're waiting for user confirmation. */
    private volatile InboundSession pendingSession = null;

    private CredentialsManager credentialsManager;
    private ExecutorService    executor;
    private Handler            mainHandler;
    private Callback           callback;

    // ---- Binder ----
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public LocalSendService getService() { return LocalSendService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    // ---- Callback interface for MainActivity ----
    public interface Callback {
        void onPeerListChanged(List<LocalSendPeer> peers);
        void onIncomingRequest(InboundSession session);
        void onReceiveComplete(String senderAlias, List<String> fileNames);
        void onSendResult(boolean success, String message);
    }

    public void setCallback(Callback cb) { this.callback = cb; }

    // ---- Data classes ----

    public static class PendingFile {
        public final String fileId;
        public final String fileName;
        public final long   size;
        public final String mimeType;
        public       File   destFile;
        public boolean      received = false;

        public PendingFile(String fileId, String fileName, long size, String mimeType) {
            this.fileId = fileId; this.fileName = fileName;
            this.size = size; this.mimeType = mimeType;
        }
    }

    public static class InboundSession {
        public final String                 sessionId;
        public final LocalSendPeer          sender;
        public final List<PendingFile>      files;
        public volatile boolean             accepted = false;
        public volatile boolean             decided  = false;
        /** token map returned to sender: fileId → upload token */
        public final Map<String, String>    tokens = new HashMap<>();

        public InboundSession(String sessionId, LocalSendPeer sender, List<PendingFile> files) {
            this.sessionId = sessionId; this.sender = sender; this.files = files;
        }
    }

    // ---- Lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        credentialsManager = new CredentialsManager(this);
        certHelper = new LocalSendCertHelper(this);
        executor = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotifChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            startForeground(NOTIF_FG_ID, buildFgNotif());
            executor.submit(this::startAll);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAll();
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---- Start / stop ----

    private void startAll() {
        try {
            // Acquire WiFi multicast lock so Android doesn't filter UDP multicast packets
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            multicastLock = wm.createMulticastLock("localsend");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            Log.i(TAG, "Multicast lock acquired");

            SSLContext ssl = certHelper.getSSLContext();
            SSLServerSocketFactory ssf = ssl.getServerSocketFactory();
            int port = credentialsManager.getLocalSendPort();

            httpsServer = new LsHttpsServer(port);
            httpsServer.makeSecure(ssf, null);
            httpsServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "HTTPS server listening on :" + port);

            startUdp();
        } catch (Exception e) {
            Log.e(TAG, "startAll failed: " + e.getMessage(), e);
        }
    }

    private void stopAll() {
        running = false;
        try { sendGoodbye(); } catch (Exception ignored) {}
        if (multicastLock != null && multicastLock.isHeld()) { multicastLock.release(); }
        if (httpsServer != null) { httpsServer.stop(); httpsServer = null; }
        if (mcastSocket != null) { try { mcastSocket.close(); } catch (Exception ignored) {} mcastSocket = null; }
        if (udpListenThread != null) { udpListenThread.interrupt(); }
        if (announceThread  != null) { announceThread.interrupt(); }
    }

    // ---- UDP multicast ----

    private void startUdp() {
        try {
            InetAddress group = InetAddress.getByName(LocalSendProtocol.MULTICAST_GROUP);
            mcastSocket = new MulticastSocket(LocalSendProtocol.PORT);
            mcastSocket.setReuseAddress(true);
            mcastSocket.joinGroup(group);
            mcastSocket.setSoTimeout(0);
            Log.i(TAG, "Joined multicast group " + LocalSendProtocol.MULTICAST_GROUP);

            // Also join on every up, multicast-capable interface so we receive
            // announcements arriving on hotspot, regular WiFi, etc.
            java.net.InetSocketAddress groupSa = new java.net.InetSocketAddress(group, LocalSendProtocol.PORT);
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    mcastSocket.joinGroup(groupSa, ni);
                    Log.i(TAG, "Joined multicast on " + ni.getName());
                } catch (Exception ignored) {
                    // Likely already joined on this interface
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UDP init failed: " + e.getMessage(), e);
            return;
        }

        udpListenThread = new Thread(() -> {
            byte[] buf = new byte[UDP_BUF_SIZE];
            while (running) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    mcastSocket.receive(pkt);
                    String json = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    String fromIp = pkt.getAddress().getHostAddress();
                    handleUdpPacket(json, fromIp);
                } catch (Exception e) {
                    if (running) Log.w(TAG, "UDP recv: " + e.getMessage());
                }
            }
        }, "ls-udp-listen");
        udpListenThread.setDaemon(true);
        udpListenThread.start();

        announceThread = new Thread(() -> {
            while (running) {
                try {
                    sendAnnounce();
                    evictStalePeers();
                    Thread.sleep(ANNOUNCE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Announce: " + e.getMessage());
                }
            }
        }, "ls-announce");
        announceThread.setDaemon(true);
        announceThread.start();
    }

    private void sendAnnounce() throws Exception {
        String alias       = credentialsManager.getDeviceAlias();
        String fingerprint = certHelper.getFingerprint();
        int    port        = credentialsManager.getLocalSendPort();
        String model       = Build.MODEL;

        JSONObject json = LocalSendProtocol.buildAnnounceJson(alias, fingerprint, model,
                LocalSendProtocol.TYPE_MOBILE, port);
        sendUdp(json.toString());
    }

    private void sendGoodbye() throws Exception {
        String alias       = credentialsManager.getDeviceAlias();
        String fingerprint = certHelper.getFingerprint();
        String model       = Build.MODEL;
        JSONObject json = LocalSendProtocol.buildGoodbyeJson(alias, fingerprint, model,
                LocalSendProtocol.TYPE_MOBILE);
        sendUdp(json.toString());
    }

    private void sendUdp(String payload) throws Exception {
        InetAddress group = InetAddress.getByName(LocalSendProtocol.MULTICAST_GROUP);
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        if (mcastSocket == null || mcastSocket.isClosed()) return;

        // Send on every up, multicast-capable, non-loopback interface with an IPv4 address.
        // This ensures announcements reach peers over both regular WiFi and local hotspots.
        java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
        boolean sentAny = false;
        while (nis != null && nis.hasMoreElements()) {
            java.net.NetworkInterface ni = nis.nextElement();
            try {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                boolean hasIpv4 = false;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        hasIpv4 = true; break;
                    }
                }
                if (!hasIpv4) continue;
                mcastSocket.setNetworkInterface(ni);
                DatagramPacket pkt = new DatagramPacket(data, data.length, group, LocalSendProtocol.PORT);
                mcastSocket.send(pkt);
                sentAny = true;
            } catch (Exception e) {
                Log.d(TAG, "send on " + ni.getName() + " failed: " + e.getMessage());
            }
        }
        // Fallback: send on default interface if iteration yielded nothing.
        if (!sentAny) {
            DatagramPacket pkt = new DatagramPacket(data, data.length, group, LocalSendProtocol.PORT);
            mcastSocket.send(pkt);
        }
    }

    private void handleUdpPacket(String json, String fromIp) {
        try {
            JSONObject o = new JSONObject(json);
            String myFingerprint = certHelper.getFingerprint();

            // Ignore own packets
            if (myFingerprint.equals(o.optString("fingerprint", ""))) return;

            LocalSendPeer peer = LocalSendProtocol.parsePeer(o, fromIp);
            if (peer == null) {
                // Goodbye or parse error — remove peer
                String fp = o.optString("fingerprint", "");
                if (!fp.isEmpty()) {
                    peers.remove(fp);
                    notifyPeerListChanged();
                }
                return;
            }

            boolean isNew = !peers.containsKey(peer.fingerprint);
            peers.put(peer.fingerprint, peer);
            if (isNew) notifyPeerListChanged();

            // If it's an announce (not our own), reply via register
            if (o.optBoolean("announcement", false)) {
                replyRegister(peer);
            }
        } catch (Exception e) {
            Log.w(TAG, "handleUdp: " + e.getMessage());
        }
    }

    private void replyRegister(LocalSendPeer peer) {
        executor.submit(() -> {
            try {
                String alias       = credentialsManager.getDeviceAlias();
                String fingerprint = certHelper.getFingerprint();
                int    port        = credentialsManager.getLocalSendPort();
                String model       = Build.MODEL;

                JSONObject body = LocalSendProtocol.buildInfoJson(alias, fingerprint, model,
                        LocalSendProtocol.TYPE_MOBILE, port);

                postJson(peer.getBaseUrl() + LocalSendProtocol.EP_REGISTER, body.toString());
            } catch (Exception e) {
                Log.d(TAG, "replyRegister to " + peer + ": " + e.getMessage());
            }
        });
    }

    private void evictStalePeers() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Map.Entry<String, LocalSendPeer>> it = peers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, LocalSendPeer> e = it.next();
            if (now - e.getValue().lastSeen > PEER_TIMEOUT_MS) {
                it.remove();
                changed = true;
            }
        }
        if (changed) notifyPeerListChanged();
    }

    private void notifyPeerListChanged() {
        if (callback == null) return;
        List<LocalSendPeer> list = new ArrayList<>(peers.values());
        mainHandler.post(() -> callback.onPeerListChanged(list));
    }

    // ---- Public API ----

    public List<LocalSendPeer> getPeers() {
        return new ArrayList<>(peers.values());
    }

    /**
     * Accept or decline an inbound session (called by LocalSendReceiveDialog).
     */
    public void respondToSession(InboundSession session, boolean accept) {
        session.accepted = accept;
        session.decided  = true;
        synchronized (session) { session.notifyAll(); }
    }

    /**
     * Send files to a peer.  uri list is content:// or file:// URIs.
     */
    public void sendFilesToPeer(LocalSendPeer peer, List<android.net.Uri> uris) {
        executor.submit(() -> {
            try {
                doSendFiles(peer, uris);
            } catch (Exception e) {
                Log.e(TAG, "sendFiles failed: " + e.getMessage(), e);
                notifySendResult(false, e.getMessage());
            }
        });
    }

    // ---- HTTPS server ----

    private class LsHttpsServer extends NanoHTTPD {
        LsHttpsServer(int port) { super(port); }

        @Override
        public Response serve(IHTTPSession session) {
            String uri    = session.getUri();
            Method method = session.getMethod();

            try {
                if (method == Method.GET && uri.equals(LocalSendProtocol.EP_INFO)) {
                    return handleInfo();
                }
                if (method == Method.POST && uri.equals(LocalSendProtocol.EP_REGISTER)) {
                    return handleRegister(session);
                }
                if (method == Method.POST && (uri.equals(LocalSendProtocol.EP_SEND_REQUEST)
                        || uri.equals(LocalSendProtocol.EP_PREPARE_UPLOAD))) {
                    return handleSendRequest(session);
                }
                if (method == Method.POST && (uri.equals(LocalSendProtocol.EP_SEND)
                        || uri.equals(LocalSendProtocol.EP_UPLOAD))) {
                    return handleSend(session);
                }
                if (method == Method.POST && uri.equals(LocalSendProtocol.EP_CANCEL)) {
                    return handleCancel(session);
                }
            } catch (Exception e) {
                Log.e(TAG, "HTTP " + method + " " + uri + ": " + e.getMessage(), e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "text/plain", "Internal error: " + e.getMessage());
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
    }

    // ---- Handlers ----

    private NanoHTTPD.Response handleInfo() throws Exception {
        String alias       = credentialsManager.getDeviceAlias();
        String fingerprint = certHelper.getFingerprint();
        int    port        = credentialsManager.getLocalSendPort();
        JSONObject o = LocalSendProtocol.buildInfoJson(alias, fingerprint, Build.MODEL,
                LocalSendProtocol.TYPE_MOBILE, port);
        return jsonResponse(NanoHTTPD.Response.Status.OK, o.toString());
    }

    private NanoHTTPD.Response handleRegister(NanoHTTPD.IHTTPSession session) throws Exception {
        String body = readBody(session);
        if (body != null && !body.isEmpty()) {
            JSONObject o     = new JSONObject(body);
            String     fromIp = getRemoteIp(session);
            LocalSendPeer peer = LocalSendProtocol.parsePeer(o, fromIp);
            if (peer != null) {
                peers.put(peer.fingerprint, peer);
                notifyPeerListChanged();
            }
        }
        // Reply with our own info
        return handleInfo();
    }

    private NanoHTTPD.Response handleSendRequest(NanoHTTPD.IHTTPSession session) throws Exception {
        String fromIp  = getRemoteIp(session);
        String bodyStr = readBody(session);
        Log.i(TAG, "send-request from " + fromIp + " len=" + (bodyStr == null ? 0 : bodyStr.length()));
        if (bodyStr == null || bodyStr.isEmpty()) {
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "{\"message\":\"empty body\"}");
        }

        JSONObject body  = new JSONObject(bodyStr);
        JSONObject info  = body.optJSONObject("info");
        JSONObject files = body.optJSONObject("files");

        String senderAlias       = info != null ? info.optString("alias", fromIp) : fromIp;
        String senderFingerprint = info != null ? info.optString("fingerprint", "") : "";
        String senderModel       = info != null ? info.optString("deviceModel", "") : "";
        String senderType        = info != null ? info.optString("deviceType", LocalSendProtocol.TYPE_MOBILE) : LocalSendProtocol.TYPE_MOBILE;

        // Find or create peer entry
        LocalSendPeer sender = peers.get(senderFingerprint);
        if (sender == null) {
            sender = new LocalSendPeer(senderAlias, senderFingerprint, fromIp,
                    LocalSendProtocol.PORT, senderModel, senderType);
        }

        List<PendingFile> pendingList = new ArrayList<>();
        if (files != null) {
            Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String fileId = keys.next();
                JSONObject fi  = files.getJSONObject(fileId);
                String fileName = fi.optString("fileName", fileId);
                long   size     = fi.optLong("size", 0);
                String mime     = fi.optString("fileType", "application/octet-stream");
                pendingList.add(new PendingFile(fileId, fileName, size, mime));
            }
        }

        if (pendingList.isEmpty()) {
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "{\"message\":\"no files\"}");
        }

        String sessionId = UUID.randomUUID().toString();
        InboundSession inbound = new InboundSession(sessionId, sender, pendingList);

        // Ask user
        pendingSession = inbound;
        if (callback != null) {
            mainHandler.post(() -> callback.onIncomingRequest(inbound));
        }

        // Wait for user decision (max 60 s)
        Log.i(TAG, "send-request: waiting for user decision on " + pendingList.size() + " file(s)");
        long deadline = System.currentTimeMillis() + 60_000;
        while (!inbound.decided && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        pendingSession = null;
        if (!inbound.decided || !inbound.accepted) {
            Log.i(TAG, "send-request: declined/timeout (decided=" + inbound.decided + " accepted=" + inbound.accepted + ")");
            return jsonResponse(NanoHTTPD.Response.Status.FORBIDDEN, "{\"message\":\"declined\"}");
        }
        Log.i(TAG, "send-request: accepted");

        // Assign tokens and prepare dest dirs
        File saveDir = getSaveDir(senderAlias);
        saveDir.mkdirs();
        JSONObject tokensJson = new JSONObject();
        for (PendingFile pf : pendingList) {
            String token = UUID.randomUUID().toString().replace("-", "");
            inbound.tokens.put(token, pf.fileId);
            pf.destFile = getUniqueFile(saveDir, sanitize(pf.fileName));
            pendingFiles.put(token, pf);
            tokensJson.put(pf.fileId, token);
        }

        JSONObject resp = new JSONObject();
        resp.put("sessionId", sessionId);
        resp.put("files", tokensJson);
        String respBody = resp.toString();
        Log.i(TAG, "send-request: sending response body=" + respBody);
        return jsonResponse(NanoHTTPD.Response.Status.OK, respBody);
    }

    private NanoHTTPD.Response handleSend(NanoHTTPD.IHTTPSession session) throws Exception {
        Map<String, String> params = session.getParms();
        String token = params.get("token");
        Log.i(TAG, "upload: uri=" + session.getUri() + " token=" + (token == null ? "null" : "present") + " params=" + params.keySet());
        if (token == null || token.isEmpty()) {
            return jsonResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "{\"message\":\"missing token\"}");
        }

        PendingFile pf = pendingFiles.get(token);
        if (pf == null) {
            Log.w(TAG, "upload: unknown token " + token);
            return jsonResponse(NanoHTTPD.Response.Status.FORBIDDEN, "{\"message\":\"unknown token\"}");
        }

        // Stream body → file
        String lenStr = session.getHeaders().get("content-length");
        long expectedLen = lenStr != null ? Long.parseLong(lenStr.trim()) : -1;

        // Do NOT close session.getInputStream() — NanoHTTPD owns it and may
        // need it intact to maintain the keep-alive connection for the response.
        InputStream body = session.getInputStream();
        long written = 0;
        try (OutputStream fos = new FileOutputStream(pf.destFile)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((expectedLen < 0 || written < expectedLen)
                    && (n = body.read(buf, 0,
                           expectedLen > 0
                               ? (int) Math.min(buf.length, expectedLen - written)
                               : buf.length)) > 0) {
                fos.write(buf, 0, n);
                written += n;
            }
            fos.flush();
        }
        Log.i(TAG, "upload: wrote " + written + " bytes to " + pf.destFile.getName());

        pf.received = true;
        pendingFiles.remove(token);

        // Media-scan
        MediaScannerConnection.scanFile(this,
                new String[]{pf.destFile.getAbsolutePath()}, null, null);

        // Check if all files for this session are done
        checkSessionComplete();

        return jsonResponse(NanoHTTPD.Response.Status.OK, "{}");
    }

    private NanoHTTPD.Response handleCancel(NanoHTTPD.IHTTPSession session) {
        // Clean up any pending files from the cancelled session
        for (PendingFile pf : new ArrayList<>(pendingFiles.values())) {
            if (pf.destFile != null) pf.destFile.delete();
        }
        pendingFiles.clear();
        if (pendingSession != null) {
            pendingSession.decided = true;
            pendingSession.accepted = false;
            synchronized (pendingSession) { pendingSession.notifyAll(); }
            pendingSession = null;
        }
        return jsonResponse(NanoHTTPD.Response.Status.OK, "{}");
    }

    // ---- Outbound send ----

    private void doSendFiles(LocalSendPeer peer, List<android.net.Uri> uris) throws Exception {
        SSLContext trustAll = LocalSendCertHelper.buildTrustAllContext();

        // Build file map
        Map<String, LocalSendProtocol.FileInfo> fileMap = new java.util.LinkedHashMap<>();
        List<FileMeta> metas = new ArrayList<>();
        for (android.net.Uri uri : uris) {
            String name = getFileName(uri);
            long   size = getFileSize(uri);
            String mime = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            String fileId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            fileMap.put(fileId, new LocalSendProtocol.FileInfo(fileId, name, size, mime));
            metas.add(new FileMeta(fileId, uri, name, size, mime));
        }

        // POST send-request
        String alias       = credentialsManager.getDeviceAlias();
        String fingerprint = certHelper.getFingerprint();
        JSONObject reqBody = LocalSendProtocol.buildSendRequest(alias, fingerprint,
                Build.MODEL, LocalSendProtocol.TYPE_MOBILE, fileMap);

        String respStr = postJson(trustAll, peer.getBaseUrl() + LocalSendProtocol.EP_SEND_REQUEST,
                reqBody.toString());
        if (respStr == null) {
            notifySendResult(false, "No response from peer");
            return;
        }

        JSONObject resp = new JSONObject(respStr);
        JSONObject tokensObj = resp.optJSONObject("files");
        if (tokensObj == null) {
            notifySendResult(false, "Peer declined or returned no tokens");
            return;
        }

        // Upload each file
        for (FileMeta meta : metas) {
            String token = tokensObj.optString(meta.fileId, null);
            if (token == null) continue;
            String uploadUrl = peer.getBaseUrl() + LocalSendProtocol.EP_SEND + "?token=" + token;
            uploadFile(trustAll, uploadUrl, meta);
        }

        notifySendResult(true, "Sent " + metas.size() + " file(s) to " + peer.alias);
    }

    private void uploadFile(SSLContext ssl, String url, FileMeta meta) throws Exception {
        URL u = new URL(url);
        HttpsURLConnection conn = (HttpsURLConnection) u.openConnection();
        conn.setSSLSocketFactory(ssl.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", meta.mime);
        conn.setRequestProperty("Content-Length", String.valueOf(meta.size));
        conn.setFixedLengthStreamingMode(meta.size);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);

        try (InputStream in = getContentResolver().openInputStream(meta.uri);
             OutputStream out = conn.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }

        int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200) throw new Exception("Upload returned HTTP " + code + " for " + meta.name);
    }

    private void notifySendResult(boolean success, String msg) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onSendResult(success, msg));
    }

    private void checkSessionComplete() {
        if (!pendingFiles.isEmpty()) return;
        // Find last completed session — notify via callback
        if (callback != null) {
            mainHandler.post(() -> callback.onReceiveComplete("", Collections.emptyList()));
        }
    }

    // ---- Helpers ----

    private NanoHTTPD.Response jsonResponse(NanoHTTPD.Response.Status status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        NanoHTTPD.Response r = NanoHTTPD.newFixedLengthResponse(
                status,
                "application/json; charset=utf-8",
                new java.io.ByteArrayInputStream(bytes),
                bytes.length);
        r.addHeader("Access-Control-Allow-Origin", "*");
        return r;
    }

    private String readBody(NanoHTTPD.IHTTPSession session) throws Exception {
        String lenStr = session.getHeaders().get("content-length");
        if (lenStr == null) return "";
        int len = Integer.parseInt(lenStr.trim());
        if (len <= 0) return "";
        byte[] buf = new byte[len];
        int read = 0;
        InputStream is = session.getInputStream();
        while (read < len) {
            int n = is.read(buf, read, len - read);
            if (n < 0) break;
            read += n;
        }
        return new String(buf, 0, read, StandardCharsets.UTF_8);
    }

    private String getRemoteIp(NanoHTTPD.IHTTPSession session) {
        String hdr = session.getHeaders().get("x-forwarded-for");
        if (hdr != null && !hdr.isEmpty()) return hdr.split(",")[0].trim();
        return session.getHeaders().getOrDefault("remote-addr", "unknown");
    }

    private File getSaveDir(String senderAlias) {
        String safe = senderAlias.replaceAll("[^a-zA-Z0-9_. -]", "_");
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LocalSend/" + safe);
    }

    private File getUniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        int i = 1;
        while (f.exists()) { f = new File(dir, base + " (" + i++ + ")" + ext); }
        return f;
    }

    private String sanitize(String name) {
        return name.replaceAll("[\"?*:<>|]", "_");
    }

    // ---- HTTP helpers ----

    private String postJson(String url, String body) throws Exception {
        return postJson(LocalSendCertHelper.buildTrustAllContext(), url, body);
    }

    private String postJson(SSLContext ssl, String url, String body) throws Exception {
        URL u = new URL(url);
        HttpsURLConnection conn = (HttpsURLConnection) u.openConnection();
        conn.setSSLSocketFactory(ssl.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(15_000);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        conn.disconnect();
        return code >= 200 && code < 300 ? sb.toString() : null;
    }

    // ---- Content resolver helpers ----

    private String getFileName(android.net.Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (name == null) {
            name = uri.getLastPathSegment();
            if (name == null) name = "file";
        }
        return name;
    }

    private long getFileSize(android.net.Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (idx >= 0) return c.getLong(idx);
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static class FileMeta {
        final String fileId, name, mime;
        final long size;
        final android.net.Uri uri;
        FileMeta(String fileId, android.net.Uri uri, String name, long size, String mime) {
            this.fileId = fileId; this.uri = uri;
            this.name = name; this.size = size; this.mime = mime;
        }
    }

    // ---- Notifications ----

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL, "LocalSend (Nearby Transfer)",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildFgNotif() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, NOTIF_CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("AROMA – Nearby Transfer")
                .setContentText("Ready to receive files from nearby devices")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    public void showReceivedNotification(String senderAlias, int count) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, NOTIF_CHANNEL)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("Files received from " + senderAlias)
                .setContentText(count + " file(s) saved to Downloads/LocalSend/")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIF_RX_ID, n);
    }
}
