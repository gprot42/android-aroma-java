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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements ServerEventListener {
    private static final String TAG = "AROMA";
    private static final int STORAGE_PERMISSION_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_CODE = 2;

    private ServerService serverService;
    private boolean serviceBound = false;

    private Button toggleButton;
    private Button qrButton;
    private Button settingsButton;
    private View statusIndicator;
    private TextView statusLabel;
    private TextView urlText;
    private TextView publicUrlText;
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

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ServerService.LocalBinder binder = (ServerService.LocalBinder) service;
            serverService = binder.getService();
            serverService.setEventListener(MainActivity.this);
            serviceBound = true;
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
        setContentView(R.layout.activity_main);

        toggleButton = findViewById(R.id.toggle_button);
        qrButton = findViewById(R.id.qr_button);
        settingsButton = findViewById(R.id.settings_button);
        statusIndicator = findViewById(R.id.status_indicator);
        statusLabel = findViewById(R.id.status_label);
        urlText = findViewById(R.id.url_text);
        publicUrlText = findViewById(R.id.public_url_text);
        folderPathText = findViewById(R.id.folder_path);
        storageInfoText = findViewById(R.id.storage_info);
        useTunnelCheckBox = findViewById(R.id.use_tunnel_checkbox);
        authTokenEditText = findViewById(R.id.auth_token_edit);
        fileListRecyclerView = findViewById(R.id.file_list);
        qrImageView = findViewById(R.id.qr_image);
        activityLogText = findViewById(R.id.activity_log);
        activityLogScroll = findViewById(R.id.activity_log_scroll);

        requestPermissions();
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
        }
    }

    private void initializeUI() {
        fileListRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        updateFileList();
        updateStorageInfo();
        
        File storageDir = getStorageDir();
        folderPathText.setText("Folder: " + (storageDir != null ? storageDir.getAbsolutePath() : "Not available"));

        toggleButton.setOnClickListener(v -> toggleServer());
        qrButton.setOnClickListener(v -> toggleQrCode());
        settingsButton.setOnClickListener(v -> openSettings());

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
            qrImageView.setVisibility(View.GONE);
            statusLabel.setText(R.string.server_not_running);
            statusLabel.setTextColor(0xFF888888);
            statusIndicator.setBackgroundResource(R.drawable.status_dot_offline);
            urlText.setVisibility(View.GONE);
            publicUrlText.setVisibility(View.GONE);
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

            String url = serverService.getCurrentUrl();
            urlText.setText("Local: " + url);
            urlText.setVisibility(View.VISIBLE);

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
            qrImageView.setVisibility(View.GONE);
            urlText.setVisibility(View.GONE);
            publicUrlText.setVisibility(View.GONE);
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
        if (serviceBound && serverService != null && serverService.isServerRunning()) {
            Toast.makeText(this, "Stop server before changing settings", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, SettingsActivity.class));
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

    private void updateFileList() {
        File rootDir = getStorageDir();
        if (rootDir != null) {
            File[] filesArray = rootDir.listFiles();
            List<File> files = filesArray != null ? Arrays.asList(filesArray) : new ArrayList<>();
            FileAdapter adapter = new FileAdapter(files, file ->
                    Toast.makeText(MainActivity.this, getString(R.string.selected_file) + file.getName(), Toast.LENGTH_SHORT).show()
            );
            fileListRecyclerView.setAdapter(adapter);
        }
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
                }
            }
        } else {
            if (!serviceBound) {
                initializeUI();
            } else {
                updateFileList();
                updateUIState();
            }
        }
    }

    @Override
    protected void onDestroy() {
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
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
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
