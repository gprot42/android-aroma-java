package com.example.aroma;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Proto;
import com.github.alexdlaird.ngrok.protocol.Tunnel;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class ServerService extends Service {
    private static final String TAG = "AROMA";
    private static final String CHANNEL_ID = "aroma_server_channel";
    private static final int NOTIFICATION_ID = 1;

    private WebServer server;
    private NgrokClient ngrokClient;
    private Tunnel ngrokTunnel;
    private String currentUrl;
    private String publicUrl;
    private int currentPort;
    private ServerEventListener eventListener;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        ServerService getService() {
            return ServerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AROMA Server",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when AROMA server is running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("AROMA Server Running")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    public boolean startServer(boolean useTunnel, String authToken) throws Exception {
        File rootDir = getStorageDir();
        if (rootDir == null || !rootDir.exists()) {
            throw new IOException("Storage directory not accessible. Check storage permissions in Settings.");
        }
        if (!rootDir.canRead()) {
            throw new IOException("Cannot read storage directory. Grant storage permissions in Android Settings.");
        }
        if (!rootDir.canWrite()) {
            throw new IOException("Cannot write to storage directory. Check if storage is mounted read-only.");
        }

        CredentialsManager credentialsManager = new CredentialsManager(this);
        String username = credentialsManager.getUsername();
        String password = credentialsManager.getPassword();
        currentPort = credentialsManager.getPort();

        if (currentPort < 1024 || currentPort > 65535) {
            throw new IllegalArgumentException("Invalid port " + currentPort + ". Must be between 1024-65535.");
        }

        try {
            server = new WebServer(currentPort, rootDir, this, username, password);
            if (eventListener != null) {
                server.setEventListener(eventListener);
            }
            server.start();
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Address already in use")) {
                throw new IOException("Port " + currentPort + " is already in use. Try a different port in Settings.");
            }
            throw new IOException("Failed to start HTTP server: " + msg);
        }

        String ip = getLocalIpAddress();
        if ("127.0.0.1".equals(ip)) {
            Log.w(TAG, "Could not determine local IP address. Device may not be connected to a network.");
        }
        currentUrl = "http://" + ip + ":" + currentPort;

        if (useTunnel) {
            if (authToken == null || authToken.isEmpty()) {
                stopServer();
                throw new IllegalArgumentException("ngrok authtoken is required for tunneling. Get one at https://ngrok.com");
            }
            try {
                JavaNgrokConfig config = new JavaNgrokConfig.Builder()
                        .withAuthToken(authToken)
                        .build();
                ngrokClient = new NgrokClient.Builder()
                        .withJavaNgrokConfig(config)
                        .build();
                CreateTunnel createTunnel = new CreateTunnel.Builder()
                        .withProto(Proto.HTTP)
                        .withAddr(currentPort)
                        .build();
                ngrokTunnel = ngrokClient.connect(createTunnel);
                publicUrl = ngrokTunnel.getPublicUrl();
            } catch (Exception e) {
                stopServer();
                String msg = e.getMessage();
                if (msg != null && msg.contains("invalid authtoken")) {
                    throw new Exception("Invalid ngrok authtoken. Check your token at https://dashboard.ngrok.com");
                } else if (msg != null && msg.contains("tunnel session limit")) {
                    throw new Exception("ngrok tunnel limit reached. Close other tunnels or upgrade your plan.");
                } else if (msg != null && msg.contains("connection refused")) {
                    throw new Exception("Cannot connect to ngrok. Check your internet connection.");
                }
                throw new Exception("ngrok tunnel failed: " + msg);
            }
        }

        String notificationText = publicUrl != null ? publicUrl : currentUrl;
        startForeground(NOTIFICATION_ID, buildNotification(notificationText));

        Log.d(TAG, "Server started at: " + currentUrl + (publicUrl != null ? " (public: " + publicUrl + ")" : ""));
        return true;
    }

    public void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (ngrokClient != null && ngrokTunnel != null) {
            try {
                ngrokClient.disconnect(ngrokTunnel.getPublicUrl());
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting tunnel: " + e.getMessage());
            }
            ngrokClient = null;
            ngrokTunnel = null;
        }
        publicUrl = null;
        currentUrl = null;
        stopForeground(true);
        stopSelf();
    }

    public boolean isServerRunning() {
        return server != null && server.isAlive();
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setEventListener(ServerEventListener listener) {
        this.eventListener = listener;
        if (server != null) {
            server.setEventListener(listener);
        }
    }

    private File getStorageDir() {
        CredentialsManager cm = new CredentialsManager(this);
        int folderType = cm.getFolderType();
        String dirType;
        switch (folderType) {
            case CredentialsManager.FOLDER_DOCUMENTS:
                dirType = Environment.DIRECTORY_DOCUMENTS;
                break;
            case CredentialsManager.FOLDER_PICTURES:
                dirType = Environment.DIRECTORY_PICTURES;
                break;
            case CredentialsManager.FOLDER_MUSIC:
                dirType = Environment.DIRECTORY_MUSIC;
                break;
            case CredentialsManager.FOLDER_MOVIES:
                dirType = Environment.DIRECTORY_MOVIES;
                break;
            default:
                dirType = Environment.DIRECTORY_DOWNLOADS;
        }
        File externalDir = Environment.getExternalStoragePublicDirectory(dirType);
        if (externalDir.mkdirs() || externalDir.exists()) {
            return externalDir;
        } else {
            return getExternalFilesDir(null);
        }
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "IP lookup failed: " + ex.getMessage());
        }
        return "127.0.0.1";
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
