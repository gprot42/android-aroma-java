package com.example.aroma;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {
    private WebServer server;
    private NgrokClient ngrokClient;
    private Tunnel ngrokTunnel;
    private Button toggleButton;
    private TextView statusText;
    private TextView storageInfoText;
    private CheckBox useTunnelCheckBox;
    private EditText authTokenEditText;
    private RecyclerView fileListRecyclerView;
    private static final int PORT = 8080;
    private static final int STORAGE_PERMISSION_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toggleButton = findViewById(R.id.toggle_button);
        statusText = findViewById(R.id.status_text);
        storageInfoText = findViewById(R.id.storage_info);
        useTunnelCheckBox = findViewById(R.id.use_tunnel_checkbox);
        authTokenEditText = findViewById(R.id.auth_token_edit);
        fileListRecyclerView = findViewById(R.id.file_list);

        // Request storage permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
        } else {
            initializeUI();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
                initializeUI();
            } else {
                Toast.makeText(this, "Storage permission denied; using fallback storage", Toast.LENGTH_SHORT).show();
                initializeUI();  // Proceed with fallback
            }
        }
    }

    private void initializeUI() {
        // Set up file browser
        fileListRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        updateFileList();

        // Display storage info with error handling
        updateStorageInfo();

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (server == null || !server.isAlive()) {
                    startServer();
                } else {
                    stopServer();
                }
            }
        });
    }

    private void startServer() {
        File rootDir = getStorageDir();  // Get writable dir with fallback
        Log.d("AROMA", "Starting server at root: " + rootDir.getAbsolutePath());
        server = new WebServer(PORT, rootDir, this);  // Pass context for MediaScanner
        try {
            server.start();
            String ip = getLocalIpAddress();
            String status = "Server running at http://" + ip + ":" + PORT;

            boolean useTunnel = useTunnelCheckBox.isChecked();
            if (useTunnel) {
                String authToken = authTokenEditText.getText().toString().trim();
                if (authToken.isEmpty()) {
                    Toast.makeText(this, R.string.enter_ngrok_token_prompt, Toast.LENGTH_SHORT).show();
                    stopServer();
                    return;
                }
                JavaNgrokConfig javaNgrokConfig = new JavaNgrokConfig.Builder()
                        .withAuthToken(authToken)
                        .build();
                ngrokClient = new NgrokClient.Builder()
                        .withJavaNgrokConfig(javaNgrokConfig)
                        .build();
                CreateTunnel createTunnel = new CreateTunnel.Builder()
                        .withProto(Proto.HTTP)
                        .withAddr(PORT)
                        .build();
                ngrokTunnel = ngrokClient.connect(createTunnel);
                String publicUrl = ngrokTunnel.getPublicUrl();
                status += "\nPublic URL (for mobile/remote access): " + publicUrl;
            }

            statusText.setText(status);
            toggleButton.setText(R.string.stop_server);
            Toast.makeText(this, R.string.server_started, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e("AROMA", "Server start failed: " + e.getMessage());
            statusText.setText(getString(R.string.failed_to_start_server) + e.getMessage());
            Toast.makeText(this, "Server start failed: Check logs", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e("AROMA", "Tunnel start failed: " + e.getMessage());
            statusText.setText(getString(R.string.failed_to_start_tunnel) + e.getMessage());
            stopServer();
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
        }
        if (ngrokClient != null && ngrokTunnel != null) {
            ngrokClient.disconnect(ngrokTunnel.getPublicUrl());
        }
        statusText.setText("Server stopped");
        toggleButton.setText(R.string.start_server);
        Toast.makeText(this, R.string.server_stopped, Toast.LENGTH_SHORT).show();
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("AROMA", "IP lookup failed: " + ex.getMessage());
        }
        return "Unknown IP";
    }

    private File getStorageDir() {
        File externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (externalDir.mkdirs() || externalDir.exists()) {
            Log.d("AROMA", "Using external storage: " + externalDir.getAbsolutePath());
            return externalDir;
        } else {
            Log.w("AROMA", "External storage not writable; falling back to internal");
            return getExternalFilesDir(null);  // Fallback to app-specific
        }
    }

    private void updateFileList() {
        File rootDir = getStorageDir();
        if (rootDir != null) {
            List<File> files = Arrays.asList(rootDir.listFiles());
            FileAdapter adapter = new FileAdapter(files, new FileAdapter.OnFileClickListener() {
                @Override
                public void onFileClick(File file) {
                    Toast.makeText(MainActivity.this, getString(R.string.selected_file) + file.getName(), Toast.LENGTH_SHORT).show();
                    // Extend for open/download if needed
                }
            });
            fileListRecyclerView.setAdapter(adapter);
        }
    }

    private void updateStorageInfo() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long totalBytes = stat.getTotalBytes();
            long freeBytes = stat.getAvailableBytes();
            long usedBytes = totalBytes - freeBytes;
            storageInfoText.setText(getString(R.string.storage_calculating) + readableFileSize(usedBytes) + getString(R.string.storage_used) + readableFileSize(totalBytes) + getString(R.string.storage_total));
        } catch (Exception e) {
            Log.e("AROMA", "Storage calculation failed: " + e.getMessage());
            storageInfoText.setText(R.string.storage_error);
            Toast.makeText(this, R.string.storage_calc_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String readableFileSize(long size) {
        if (size <= 0) return "0 GB";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    @Override
    protected void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
