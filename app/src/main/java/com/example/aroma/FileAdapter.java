package com.example.aroma;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

    private final List<File> files;
    private final OnFileClickListener listener;
    private final boolean isDarkTheme;

    private void sortFiles() {
        Collections.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
    }

    public interface OnFileClickListener {
        void onFileClick(File file);
    }

    public FileAdapter(Context context, List<File> files, OnFileClickListener listener) {
        this.files = files;
        this.listener = listener;
        CredentialsManager cm = new CredentialsManager(context);
        this.isDarkTheme = cm.getTheme() == CredentialsManager.THEME_DARK;
        sortFiles();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = files.get(position);
        holder.textView.setText(file.getName() + (file.isDirectory() ? "/" : ""));
        holder.textView.setTextColor(isDarkTheme ? 0xFFFFFFFF : 0xFF111111);
        holder.itemView.setBackgroundColor(isDarkTheme ? 0xFF16213e : 0xFFFFFFFF);
        holder.itemView.setOnClickListener(v -> listener.onFileClick(file));
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(android.R.id.text1);
        }
    }
}
