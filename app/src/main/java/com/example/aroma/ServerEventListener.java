package com.example.aroma;

public interface ServerEventListener {
    void onClientConnected(String ipAddress);
    void onFileDownloaded(String filename, String clientIp);
    void onFileUploaded(String filename, String clientIp);
    void onFileDeleted(String filename, String clientIp);
    void onFolderCreated(String folderName, String clientIp);
}
