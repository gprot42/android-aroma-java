package com.example.aroma;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Shows an incoming LocalSend transfer request to the user, listing
 * the files and sender, and offering Accept / Decline.
 */
public class LocalSendReceiveDialog {

    private final Context context;
    private final LocalSendService service;
    private Dialog dialog;

    public LocalSendReceiveDialog(Context context, LocalSendService service) {
        this.context = context;
        this.service = service;
    }

    public void show(LocalSendService.InboundSession session) {
        new Handler(Looper.getMainLooper()).post(() -> buildAndShow(session));
    }

    private void buildAndShow(LocalSendService.InboundSession session) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Incoming files from " + session.sender.alias);
        builder.setCancelable(false);

        // Build file list summary
        StringBuilder sb = new StringBuilder();
        long totalBytes = 0;
        List<LocalSendService.PendingFile> files = session.files;
        for (LocalSendService.PendingFile pf : files) {
            sb.append("• ").append(pf.fileName);
            if (pf.size > 0) sb.append("  (").append(readableSize(pf.size)).append(")");
            sb.append("\n");
            totalBytes += pf.size;
        }
        if (totalBytes > 0) {
            sb.append("\nTotal: ").append(readableSize(totalBytes));
        }
        sb.append("\nSave to: Downloads/LocalSend/").append(session.sender.alias);

        builder.setMessage(sb.toString());

        builder.setPositiveButton("Accept", (d, w) -> {
            service.respondToSession(session, true);
            dismiss();
        });
        builder.setNegativeButton("Decline", (d, w) -> {
            service.respondToSession(session, false);
            dismiss();
        });

        dialog = builder.create();
        dialog.show();
    }

    private void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }

    private static String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
