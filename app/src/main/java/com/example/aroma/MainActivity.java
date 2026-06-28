package com.example.aroma;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements ServerEventListener {
    private static final String TAG = "AROMA";
    private static final int STORAGE_PERMISSION_CODE  = 1;
    private static final int NOTIFICATION_PERMISSION_CODE = 2;
    private static final int HOTSPOT_PERMISSION_CODE  = 3;

    private ServerService serverService;
    private boolean serviceBound = false;

    private Button toggleButton;
    private Button qrButton;
    private Button debugButton;
    private Button settingsButton;
    private Button hotspotButton;
    private Button hotspotQrButton;
    private Button copyPasswordButton;
    private View statusIndicator;
    private View hotspotActions;
    private TextView statusLabel;
    private TextView urlText;
    private TextView publicUrlText;
    private TextView macTransferText;
    private TextView hotspotInfoText;
    private TextView folderPathText;
    private TextView storageInfoText;
    private CheckBox useTunnelCheckBox;
    private EditText authTokenEditText;
    private RecyclerView fileListRecyclerView;
    private ImageView qrImageView;
    private TextView activityLogText;
    private android.widget.ScrollView activityLogScroll;
    private final StringBuilder activityLogBuffer = new StringBuilder();
    private static final int MAX_LOG_LINES = 50;

    private HotspotManager hotspotManager;
    private String hotspotSsid;
    private String hotspotPassword;
    private String hotspotUrl;
    private boolean hotspotQrVisible;
    private boolean pendingStartServerAfterBind;
    private String pendingHotspotIp;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ServerService.LocalBinder binder = (ServerService.LocalBinder) service;
            serverService = binder.getService();
            serverService.setEventListener(MainActivity.this);
            serviceBound = true;
            if (pendingStartServerAfterBind) {
                pendingStartServerAfterBind = false;
                startServer();
            }
            updateUIState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            serverService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyTheme();
        setContentView(R.layout.activity_main);

        toggleButton = findViewById(R.id.toggle_button);
        qrButton = findViewById(R.id.qr_button);
        debugButton = findViewById(R.id.debug_button);
        settingsButton = findViewById(R.id.settings_button);
        statusIndicator = findViewById(R.id.status_indicator);
        hotspotActions = findViewById(R.id.hotspot_actions);
        statusLabel = findViewById(R.id.status_label);
        urlText = findViewById(R.id.url_text);
        publicUrlText = findViewById(R.id.public_url_text);
        macTransferText = findViewById(R.id.mac_transfer_text);
        hotspotInfoText = findViewById(R.id.hotspot_info);
        hotspotButton = findViewById(R.id.hotspot_button);
        hotspotQrButton = findViewById(R.id.hotspot_qr_button);
        copyPasswordButton = findViewById(R.id.copy_password_button);
        folderPathText = findViewById(R.id.folder_path);
        storageInfoText = findViewById(R.id.storage_info);
        useTunnelCheckBox = findViewById(R.id.use_tunnel_checkbox);
        authTokenEditText = findViewById(R.id.auth_token_edit);
        fileListRecyclerView = findViewById(R.id.file_list);
        qrImageView = findViewById(R.id.qr_image);
        activityLogText = findViewById(R.id.activity_log);
        activityLogScroll = findViewById(R.id.activity_log_scroll);

        requestPermissions();
        handleIncomingShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShare(intent);
    }

    private void applyTheme() {
        CredentialsManager cm = new CredentialsManager(this);
        int theme = cm.getTheme();
        if (theme == CredentialsManager.THEME_LIGHT) {
            setTheme(R.style.Theme_Aroma_Light);
        } else {
            setTheme(R.style.Theme_Aroma_Dark);
        }
    }

    private void applyThemeColors() {
        CredentialsManager cm = new CredentialsManager(this);
        boolean isDark = cm.getTheme() == CredentialsManager.THEME_DARK;
        
        View rootView = findViewById(android.R.id.content);
        if (rootView != null) {
            View mainLayout = ((android.view.ViewGroup) rootView).getChildAt(0);
            if (mainLayout != null) {
                mainLayout.setBackgroundColor(isDark ? 0xFF1a1a2e : 0xFFf5f5f5);
            }
        }
        
        int panelColor = isDark ? 0xFF16213e : 0xFFffffff;
        int textColor = isDark ? 0xFFffffff : 0xFF111111;
        int textSecondary = isDark ? 0xFFC2CAE0 : 0xFF666666;
        int inputBg = isDark ? 0xFF0d0d1a : 0xFFffffff;
        
        View statusCard = findViewById(R.id.status_card);
        if (statusCard != null) statusCard.setBackgroundColor(panelColor);
        
        View activityContainer = findViewById(R.id.activity_log_container);
        if (activityContainer != null) activityContainer.setBackgroundColor(panelColor);
        
        fileListRecyclerView.setBackgroundColor(panelColor);
        
        View controlsPanel = toggleButton.getParent() instanceof LinearLayout ? (LinearLayout) toggleButton.getParent() : null;
        if (controlsPanel != null) controlsPanel.setBackgroundColor(panelColor);
        
        authTokenEditText.setBackgroundColor(inputBg);
        authTokenEditText.setTextColor(textColor);
        authTokenEditText.setHintTextColor(textSecondary);
        
        useTunnelCheckBox.setTextColor(textSecondary);
        storageInfoText.setTextColor(textSecondary);
        activityLogText.setTextColor(textSecondary);
        
        settingsButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(panelColor));
        qrButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(panelColor));
        debugButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(panelColor));
    }

    private void requestPermissions() {
        // Check for MANAGE_EXTERNAL_STORAGE on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("Storage Permission Required")
                        .setMessage("AROMA needs access to all files to work properly. Please grant 'All files access' permission in the next screen.")
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> {
                            Toast.makeText(this, "Permission denied - file list may be empty", Toast.LENGTH_LONG).show();
                            initializeUI();
                        })
                        .show();
                return;
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_CODE);
                return;
            }
        }

        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE);
                return;
            }
        }

        initializeUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            initializeUI();
        } else if (requestCode == HOTSPOT_PERMISSION_CODE) {
            boolean granted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
            }
            if (granted) {
                startHotspot();
            } else {
                Toast.makeText(this, R.string.hotspot_permission_needed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void toggleHotspot() {
        if (hotspotManager != null && hotspotManager.isActive()) {
            hotspotButton.setEnabled(false);
            hotspotButton.setText(R.string.hotspot_stopping);
            Toast.makeText(this, R.string.hotspot_stopping, Toast.LENGTH_SHORT).show();
            hotspotManager.stop();
            return;
        }
        String[] needed = requiredHotspotPermissions();
        java.util.List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), HOTSPOT_PERMISSION_CODE);
            return;
        }
        startHotspot();
    }

    private String[] requiredHotspotPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{ Manifest.permission.NEARBY_WIFI_DEVICES };
        }
        return new String[]{ Manifest.permission.ACCESS_FINE_LOCATION };
    }

    private void startHotspot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Hotspot transfer requires Android 8.0+", Toast.LENGTH_LONG).show();
            return;
        }
        hotspotButton.setEnabled(false);
        hotspotButton.setText(R.string.hotspot_starting);
        if (hotspotManager == null) {
            hotspotManager = new HotspotManager(this);
        }
        hotspotManager.start(new HotspotManager.Listener() {
            @Override
            public void onHotspotStarted(String ssid, String password, String ipAddress) {
                runOnUiThread(() -> {
                    hotspotButton.setEnabled(true);
                    hotspotButton.setText(R.string.hotspot_stop);
                    hotspotSsid = ssid;
                    hotspotPassword = password;
                    hotspotQrVisible = false;
                    pendingHotspotIp = ipAddress;
                    addLogEntry("Hotspot started: " + ssid);
                    if (serviceBound && serverService != null) {
                        // Do NOT override the server URL with the hotspot IP —
                        // the guessed 192.168.49.1 may not be reachable. Keep
                        // the server's real bound URL and show all IPs in the
                        // hotspot info panel so the user can pick one.
                        if (!serverService.isServerRunning()) {
                            startServer();
                        } else {
                            updateHotspotUiFromServer();
                        }
                    } else {
                        pendingStartServerAfterBind = true;
                        Intent serviceIntent = new Intent(MainActivity.this, ServerService.class);
                        startService(serviceIntent);
                        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
                    }
                });
            }

            @Override
            public void onHotspotFailed(String reason) {
                runOnUiThread(() -> {
                    pendingStartServerAfterBind = false;
                    pendingHotspotIp = null;
                    hotspotButton.setEnabled(true);
                    hotspotButton.setText(R.string.hotspot_start);
                    hotspotSsid = null;
                    hotspotPassword = null;
                    hotspotUrl = null;
                    hotspotQrVisible = false;
                    hotspotInfoText.setText("Hotspot failed\n" + reason);
                    hotspotInfoText.setVisibility(View.VISIBLE);
                    hotspotActions.setVisibility(View.GONE);
                    qrImageView.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Hotspot failed", Toast.LENGTH_SHORT).show();
                    addLogEntry("Hotspot failed: " + reason);
                });
            }

            @Override
            public void onHotspotStopped() {
                runOnUiThread(() -> {
                    pendingStartServerAfterBind = false;
                    pendingHotspotIp = null;
                    if (serviceBound && serverService != null) {
                        serverService.setPreferredLocalIp(null);
                    }
                    hotspotButton.setEnabled(true);
                    hotspotButton.setText(R.string.hotspot_start);
                    hotspotSsid = null;
                    hotspotPassword = null;
                    hotspotUrl = null;
                    hotspotQrVisible = false;
                    hotspotInfoText.setVisibility(View.GONE);
                    hotspotActions.setVisibility(View.GONE);
                    qrImageView.setVisibility(View.GONE);
                    addLogEntry("Hotspot stopped");
                    Toast.makeText(MainActivity.this, R.string.hotspot_stopped, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String buildHotspotInfoText(String ssid, String password, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ad-hoc WiFi (no router needed)\n");
        sb.append("SSID\n").append(ssid).append("\n\n");
        sb.append("PASSWORD\n")
                .append(password == null || password.isEmpty() ? "No password" : password)
                .append("\n\n");
        sb.append("OPEN ON MAC\n");
        java.util.List<String> ips = HotspotManager.listLocalIpv4();
        int port = 8080;
        try {
            if (url != null) {
                int colon = url.lastIndexOf(':');
                if (colon > 0 && colon < url.length() - 1) {
                    port = Integer.parseInt(url.substring(colon + 1).replaceAll("[^0-9].*", ""));
                }
            }
        } catch (Throwable ignore) { }
        if (ips.isEmpty()) {
            sb.append(url == null ? "(no address)" : url);
        } else {
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append("http://").append(ips.get(i)).append(':').append(port);
            }
        }
        return sb.toString();
    }

    private void updateHotspotUiFromServer() {
        if (hotspotSsid == null) {
            return;
        }
        String url = null;
        if (serviceBound && serverService != null) {
            url = serverService.getCurrentUrl();
        }
        if (url == null || url.isEmpty()) {
            return;
        }
        hotspotUrl = url;
        hotspotInfoText.setText(buildHotspotInfoText(hotspotSsid, hotspotPassword, url));
        hotspotInfoText.setVisibility(View.VISIBLE);
        hotspotActions.setVisibility(View.VISIBLE);
        hotspotQrButton.setText(R.string.show_hotspot_qr);
        copyPasswordButton.setEnabled(hotspotPassword != null && !hotspotPassword.isEmpty());
        qrImageView.setVisibility(View.GONE);
    }

    private void toggleHotspotQr() {
        if (hotspotUrl == null || hotspotSsid == null) {
            return;
        }
        if (hotspotQrVisible) {
            hotspotQrVisible = false;
            hotspotQrButton.setText(R.string.show_hotspot_qr);
            qrImageView.setVisibility(View.GONE);
            return;
        }
        String qrText = "AROMA HOTSPOT\n"
                + "SSID: " + hotspotSsid + "\n"
                + "PASSWORD: " + (hotspotPassword == null || hotspotPassword.isEmpty() ? "None" : hotspotPassword) + "\n"
                + "URL: " + hotspotUrl;
        try {
            Bitmap qrBitmap = generateQrCode(qrText, 500, 500);
            qrImageView.setImageBitmap(qrBitmap);
            qrImageView.setVisibility(View.VISIBLE);
            hotspotQrVisible = true;
            hotspotQrButton.setText(R.string.hide_hotspot_qr);
        } catch (Exception e) {
            Log.e(TAG, "Hotspot QR generation failed: " + e.getMessage());
            Toast.makeText(this, "Failed to generate hotspot QR", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyHotspotPassword() {
        if (hotspotPassword == null || hotspotPassword.isEmpty()) {
            return;
        }
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AROMA hotspot password", hotspotPassword));
            Toast.makeText(this, R.string.password_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private String extractPort(String url) {
        if (url == null) return "8080";
        int colon = url.lastIndexOf(':');
        if (colon < 0 || colon == url.indexOf(':')) return "8080";
        String tail = url.substring(colon + 1);
        int slash = tail.indexOf('/');
        return slash > 0 ? tail.substring(0, slash) : tail;
    }

    private void initializeUI() {
        fileListRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        updateFileList();
        updateStorageInfo();
        
        File storageDir = getStorageDir();
        folderPathText.setText("Folder: " + (storageDir != null ? storageDir.getAbsolutePath() : "Not available"));

        toggleButton.setOnClickListener(v -> toggleServer());
        qrButton.setOnClickListener(v -> toggleQrCode());
        debugButton.setOnClickListener(v -> openDiagnostics());
        settingsButton.setOnClickListener(v -> openSettings());
        hotspotButton.setOnClickListener(v -> toggleHotspot());
        hotspotQrButton.setOnClickListener(v -> toggleHotspotQr());
        copyPasswordButton.setOnClickListener(v -> copyHotspotPassword());

        updateHotspotVisibility();

        applyThemeColors();

        Intent serviceIntent = new Intent(this, ServerService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void toggleServer() {
        if (!serviceBound) {
            Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        if (serverService.isServerRunning()) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void startServer() {
        boolean useTunnel = useTunnelCheckBox.isChecked();
        String authToken = authTokenEditText.getText().toString().trim();

        if (useTunnel && authToken.isEmpty()) {
            Toast.makeText(this, R.string.enter_ngrok_token_prompt, Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                serverService.startServer(useTunnel, authToken);
                runOnUiThread(() -> {
                    updateUIState();
                    updateHotspotUiFromServer();
                    Toast.makeText(this, R.string.server_started, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Server start failed: " + e.getMessage());
                runOnUiThread(() -> {
                    statusLabel.setText(getString(R.string.failed_to_start_server) + " " + e.getMessage());
                    statusLabel.setTextColor(0xFFff6b6b);
                    Toast.makeText(this, "Server start failed", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void stopServer() {
        serverService.stopServer();
        updateUIState();
        Toast.makeText(this, R.string.server_stopped, Toast.LENGTH_SHORT).show();
    }

    private void updateUIState() {
        if (!serviceBound || serverService == null) {
            toggleButton.setText(R.string.start_server);
            qrButton.setEnabled(false);
            qrButton.setVisibility(View.GONE);
            debugButton.setEnabled(false);
            debugButton.setVisibility(View.GONE);
            qrImageView.setVisibility(View.GONE);
            statusLabel.setText(R.string.server_not_running);
            statusLabel.setTextColor(0xFF888888);
            statusIndicator.setBackgroundResource(R.drawable.status_dot_offline);
            urlText.setVisibility(View.GONE);
            publicUrlText.setVisibility(View.GONE);
            macTransferText.setVisibility(View.GONE);
            return;
        }

        boolean running = serverService.isServerRunning();
        toggleButton.setText(running ? R.string.stop_server : R.string.start_server);
        toggleButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                running ? 0xFFdc3545 : 0xFF4da6ff));

        if (running) {
            statusLabel.setText(R.string.server_running);
            statusLabel.setTextColor(0xFF28a745);
            statusIndicator.setBackgroundResource(R.drawable.status_dot_online);
            qrButton.setEnabled(true);
            qrButton.setVisibility(View.VISIBLE);
            debugButton.setEnabled(true);
            debugButton.setVisibility(View.VISIBLE);

            String url = serverService.getCurrentUrl();
            urlText.setText("Local: " + url);
            urlText.setVisibility(View.VISIBLE);
            macTransferText.setText("Mac Finder: Connect to Server… and use " + url);
            macTransferText.setVisibility(View.VISIBLE);

            String publicUrl = serverService.getPublicUrl();
            if (publicUrl != null) {
                publicUrlText.setText("Public: " + publicUrl);
                publicUrlText.setVisibility(View.VISIBLE);
            } else {
                publicUrlText.setVisibility(View.GONE);
            }
        } else {
            statusLabel.setText(R.string.server_not_running);
            statusLabel.setTextColor(0xFF888888);
            statusIndicator.setBackgroundResource(R.drawable.status_dot_offline);
            qrButton.setEnabled(false);
            qrButton.setVisibility(View.GONE);
            debugButton.setEnabled(false);
            debugButton.setVisibility(View.GONE);
            qrImageView.setVisibility(View.GONE);
            urlText.setVisibility(View.GONE);
            publicUrlText.setVisibility(View.GONE);
            macTransferText.setVisibility(View.GONE);
        }
    }

    private void toggleQrCode() {
        if (qrImageView.getVisibility() == View.VISIBLE) {
            qrImageView.setVisibility(View.GONE);
        } else {
            showQrCode();
        }
    }

    private void showQrCode() {
        if (!serviceBound || !serverService.isServerRunning()) {
            return;
        }

        String url = serverService.getPublicUrl();
        if (url == null) {
            url = serverService.getCurrentUrl();
        }

        try {
            Bitmap qrBitmap = generateQrCode(url, 400, 400);
            qrImageView.setImageBitmap(qrBitmap);
            qrImageView.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Log.e(TAG, "QR generation failed: " + e.getMessage());
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap generateQrCode(String content, int width, int height) throws Exception {
        BitMatrix bitMatrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openDiagnostics() {
        if (!serviceBound || serverService == null || !serverService.isServerRunning()) {
            Toast.makeText(this, "Start the server first", Toast.LENGTH_SHORT).show();
            return;
        }
        String baseUrl = serverService.getCurrentUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            Toast.makeText(this, "Diagnostics URL unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        String diagnosticsUrl = baseUrl + "/_aroma_diag";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(diagnosticsUrl)));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open diagnostics: " + e.getMessage());
            Toast.makeText(this, diagnosticsUrl, Toast.LENGTH_LONG).show();
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
            case CredentialsManager.FOLDER_DCIM:
                dirType = Environment.DIRECTORY_DCIM;
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

    private void updateFileList() {
        File rootDir = getStorageDir();
        if (rootDir != null) {
            File[] filesArray = rootDir.listFiles();
            List<File> files = filesArray != null ? Arrays.asList(filesArray) : new ArrayList<>();
            FileAdapter adapter = new FileAdapter(this, files, new FileAdapter.OnFileClickListener() {
                @Override
                public void onFileClick(File file) {
                    Toast.makeText(MainActivity.this, getString(R.string.selected_file) + file.getName(), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFileLongClick(View anchor, File file) {
                    showFileActions(anchor, file);
                }
            });
            fileListRecyclerView.setAdapter(adapter);
        }
    }

    private void showFileActions(View anchor, File file) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        if (file.isFile()) {
            menu.getMenu().add(0, 1, 0, "Share");
        }
        menu.getMenu().add(0, 2, 1, "Copy path");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                shareFile(file);
                return true;
            }
            if (item.getItemId() == 2) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AROMA path", file.getAbsolutePath()));
                    Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(getMimeType(file));
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_TITLE, file.getName());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share file"));
        } catch (Exception e) {
            Log.e(TAG, "Share failed: " + e.getMessage());
            Toast.makeText(this, "Unable to share file", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(File file) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(file.getName());
        if (extension != null) {
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
            if (mimeType != null) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }

    private void handleIncomingShare(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        String type = intent.getType();
        if (type == null) {
            return;
        }
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                importSharedUris(new ArrayList<>(java.util.Collections.singletonList(uri)));
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) {
                importSharedUris(uris);
            }
        }
    }

    private void importSharedUris(List<Uri> uris) {
        File targetDir = getStorageDir();
        if (targetDir == null) {
            Toast.makeText(this, "Storage not available", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            int importedCount = 0;
            List<String> failures = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    String fileName = queryDisplayName(uri);
                    if (fileName == null || fileName.trim().isEmpty()) {
                        fileName = "shared_" + System.currentTimeMillis();
                    }
                    File destination = makeUniqueFile(targetDir, fileName);
                    try (InputStream inputStream = getContentResolver().openInputStream(uri);
                         FileOutputStream outputStream = new FileOutputStream(destination)) {
                        if (inputStream == null) {
                            throw new IOException("Cannot open shared content");
                        }
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                        }
                        outputStream.flush();
                    }
                    importedCount++;
                } catch (Exception e) {
                    Log.e(TAG, "Import share failed: " + e.getMessage());
                    failures.add(uri.toString());
                }
            }
            int finalImportedCount = importedCount;
            runOnUiThread(() -> {
                if (finalImportedCount > 0) {
                    updateFileList();
                    Toast.makeText(this, "Imported " + finalImportedCount + " shared file(s)", Toast.LENGTH_SHORT).show();
                }
                if (!failures.isEmpty()) {
                    Toast.makeText(this, "Failed to import " + failures.size() + " item(s)", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private String queryDisplayName(Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Display name query failed: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private File makeUniqueFile(File dir, String originalName) {
        File candidate = new File(dir, originalName);
        if (!candidate.exists()) {
            return candidate;
        }
        String baseName = originalName;
        String extension = "";
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            baseName = originalName.substring(0, dot);
            extension = originalName.substring(dot);
        }
        int index = 1;
        while (candidate.exists()) {
            candidate = new File(dir, baseName + " (" + index + ")" + extension);
            index++;
        }
        return candidate;
    }

    private void updateStorageInfo() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long totalBytes = stat.getTotalBytes();
            long freeBytes = stat.getAvailableBytes();
            long usedBytes = totalBytes - freeBytes;
            storageInfoText.setText("Storage: " + readableFileSize(usedBytes) + " used / " + readableFileSize(totalBytes) + " total");
        } catch (Exception e) {
            Log.e(TAG, "Storage calculation failed: " + e.getMessage());
            storageInfoText.setText(R.string.storage_error);
        }
    }

    private String readableFileSize(long size) {
        if (size <= 0) return "0 GB";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private void updateHotspotVisibility() {
        boolean hotspotEnabled = new CredentialsManager(this).isHotspotEnabled();
        hotspotButton.setVisibility(hotspotEnabled ? View.VISIBLE : View.GONE);
        if (!hotspotEnabled) {
            hotspotInfoText.setVisibility(View.GONE);
            hotspotActions.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check permissions when returning from Settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                if (!serviceBound) {
                    initializeUI();
                } else {
                    updateFileList();
                    File storageDir = getStorageDir();
                    folderPathText.setText("Folder: " + (storageDir != null ? storageDir.getAbsolutePath() : "Not available"));
                    updateUIState();
                    applyThemeColors();
                    updateHotspotVisibility();
                }
            }
        } else {
            if (!serviceBound) {
                initializeUI();
            } else {
                updateFileList();
                updateUIState();
                applyThemeColors();
                updateHotspotVisibility();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (hotspotManager != null) {
            hotspotManager.stop();
        }
        if (serviceBound) {
            if (serverService != null) {
                serverService.setEventListener(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    private void addLogEntry(String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String entry = timestamp + " " + message + "\n";
        activityLogBuffer.insert(0, entry);
        
        String[] lines = activityLogBuffer.toString().split("\n");
        if (lines.length > MAX_LOG_LINES) {
            activityLogBuffer.setLength(0);
            for (int i = 0; i < MAX_LOG_LINES; i++) {
                activityLogBuffer.append(lines[i]).append("\n");
            }
        }
        
        runOnUiThread(() -> {
            activityLogText.setText(activityLogBuffer.toString().trim());
        });
    }

    @Override
    public void onClientConnected(String ipAddress) {
        addLogEntry("Connected: " + ipAddress);
    }

    @Override
    public void onFileDownloaded(String filename, String clientIp) {
        addLogEntry("Download: " + filename + " → " + clientIp);
        runOnUiThread(this::updateFileList);
    }

    @Override
    public void onFileUploaded(String filename, String clientIp) {
        addLogEntry("Upload: " + filename + " ← " + clientIp);
        runOnUiThread(this::updateFileList);
    }

    @Override
    public void onFileDeleted(String filename, String clientIp) {
        addLogEntry("Deleted: " + filename + " by " + clientIp);
        runOnUiThread(this::updateFileList);
    }

    @Override
    public void onFolderCreated(String folderName, String clientIp) {
        addLogEntry("New folder: " + folderName + " by " + clientIp);
        runOnUiThread(this::updateFileList);
    }
}
