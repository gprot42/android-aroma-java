package com.example.aroma;

import android.content.Context;
import android.content.SharedPreferences;

public class CredentialsManager {
    private static final String PREFS_NAME = "aroma_settings";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_PORT = "port";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_SAVE_TOKEN = "save_token";
    private static final String KEY_NGROK_TOKEN = "ngrok_token";
    private static final String KEY_FOLDER_TYPE = "folder_type";
    private static final String KEY_THEME = "theme";
    
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "password";
    private static final int DEFAULT_PORT = 8080;
    public static final int FOLDER_DOWNLOADS = 0;
    public static final int FOLDER_DOCUMENTS = 1;
    public static final int FOLDER_PICTURES = 2;
    public static final int FOLDER_MUSIC = 3;
    public static final int FOLDER_MOVIES = 4;
    
    public static final int THEME_DARK = 0;
    public static final int THEME_LIGHT = 1;

    private final SharedPreferences prefs;

    public CredentialsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, DEFAULT_USERNAME);
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD);
    }

    public void setCredentials(String username, String password) {
        prefs.edit()
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .apply();
    }

    public int getPort() {
        return prefs.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(int port) {
        prefs.edit().putInt(KEY_PORT, port).apply();
    }

    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }

    public void setAutoStart(boolean autoStart) {
        prefs.edit().putBoolean(KEY_AUTO_START, autoStart).apply();
    }

    public boolean isSaveToken() {
        return prefs.getBoolean(KEY_SAVE_TOKEN, false);
    }

    public void setSaveToken(boolean saveToken) {
        prefs.edit().putBoolean(KEY_SAVE_TOKEN, saveToken).apply();
    }

    public String getNgrokToken() {
        return prefs.getString(KEY_NGROK_TOKEN, "");
    }

    public void setNgrokToken(String token) {
        prefs.edit().putString(KEY_NGROK_TOKEN, token).apply();
    }

    public boolean hasCustomCredentials() {
        return prefs.contains(KEY_USERNAME) && prefs.contains(KEY_PASSWORD);
    }

    public int getFolderType() {
        return prefs.getInt(KEY_FOLDER_TYPE, FOLDER_DOWNLOADS);
    }

    public void setFolderType(int folderType) {
        prefs.edit().putInt(KEY_FOLDER_TYPE, folderType).apply();
    }

    public int getTheme() {
        return prefs.getInt(KEY_THEME, THEME_DARK);
    }

    public void setTheme(int theme) {
        prefs.edit().putInt(KEY_THEME, theme).apply();
    }
}
