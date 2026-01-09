package com.example.aroma;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebServer extends NanoHTTPD {
    private final File rootDir;
    private final Context context;
    private final String username;
    private final String password;
    private ServerEventListener eventListener;

    public WebServer(int port, File wwwRoot, Context ctx, String username, String password) {
        super(port);
        this.rootDir = wwwRoot;
        this.context = ctx;
        this.username = username;
        this.password = password;
    }

    public void setEventListener(ServerEventListener listener) {
        this.eventListener = listener;
    }

    private String getClientIp(IHTTPSession session) {
        String ip = session.getHeaders().get("x-forwarded-for");
        if (ip == null || ip.isEmpty()) {
            ip = session.getRemoteIpAddress();
        }
        return ip != null ? ip : "unknown";
    }

    @Override
    public Response serve(IHTTPSession session) {
        String auth = session.getHeaders().get("authorization");
        if (auth == null || !auth.toLowerCase().startsWith("basic")) {
            Response response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized");
            response.addHeader("WWW-Authenticate", "Basic realm=\"Protected\"");
            return response;
        }
        String base64 = auth.substring(6);
        String decoded = new String(Base64.getDecoder().decode(base64));
        if (!decoded.equals(username + ":" + password)) {
            Response response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized");
            response.addHeader("WWW-Authenticate", "Basic realm=\"Protected\"");
            return response;
        }
        String uri = session.getUri();
        if (uri.isEmpty() || uri.equals("/")) uri = "/";
        Method method = session.getMethod();
        File currentDir;
        if (uri.equals("/")) {
            currentDir = rootDir;
        } else {
            currentDir = new File(rootDir, uri.substring(1));
        }

        if (method == Method.GET) {
            if (eventListener != null) {
                eventListener.onClientConnected(getClientIp(session));
            }
            return handleGet(session, currentDir, uri);
        } else if (method == Method.POST) {
            return handlePost(session, currentDir, uri);
        }
        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed");
    }

    private Response handleGet(IHTTPSession session, File file, String uri) {
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        if (file.isDirectory()) {
            return serveDirectoryListing(file, uri, session);
        } else {
            return serveFile(file, session);
        }
    }

    private Response serveDirectoryListing(File dir, String uri, IHTTPSession session) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>");
        html.append("<style>");
        html.append("*{box-sizing:border-box}");
        html.append("body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#eee;margin:0;padding:20px}");
        html.append("h1{color:#fff;margin:0 0 5px 0;font-size:1.5em}");
        html.append(".path{color:#888;font-size:0.9em;margin-bottom:20px}");
        html.append(".path a{color:#4da6ff;text-decoration:none}");
        html.append(".container{display:grid;grid-template-columns:1fr 280px;gap:20px}");
        html.append("@media(max-width:768px){.container{grid-template-columns:1fr}}");
        html.append(".panel{background:#16213e;border-radius:12px;padding:20px}");
        html.append(".panel h2{margin:0 0 15px 0;font-size:1.1em;color:#4da6ff;border-bottom:1px solid #333;padding-bottom:10px}");
        html.append(".file-list{background:#16213e;border-radius:12px;overflow:hidden}");
        html.append(".file-item{display:flex;align-items:center;padding:12px 15px;border-bottom:1px solid #252a40}");
        html.append(".file-item:last-child{border-bottom:none}");
        html.append(".file-item:hover{background:#1f2b4d}");
        html.append(".file-item input[type=checkbox]{width:18px;height:18px;margin-right:12px;accent-color:#4da6ff}");
        html.append(".file-icon{font-size:1.3em;margin-right:10px}");
        html.append(".file-info{flex:1;min-width:0}");
        html.append(".file-name{color:#fff;text-decoration:none;font-weight:500;display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}");
        html.append(".file-name:hover{color:#4da6ff}");
        html.append(".file-meta{color:#666;font-size:0.8em;margin-top:2px}");
        html.append(".file-actions{display:flex;gap:8px}");
        html.append(".btn{padding:6px 12px;border-radius:6px;text-decoration:none;font-size:0.8em;border:none;cursor:pointer;display:inline-block}");
        html.append(".btn-primary{background:#4da6ff;color:#fff}");
        html.append(".btn-success{background:#28a745;color:#fff}");
        html.append(".btn-secondary{background:#555;color:#fff}");
        html.append(".btn-danger{background:#dc3545;color:#fff}");
        html.append(".btn:hover{opacity:0.85}");
        html.append("input[type=text],input[type=file]{width:100%;padding:10px;border:1px solid #333;border-radius:6px;background:#1a1a2e;color:#fff;margin-bottom:10px}");
        html.append("input[type=text]::placeholder{color:#666}");
        html.append(".form-group{margin-bottom:15px}");
        html.append(".form-group:last-child{margin-bottom:0}");
        html.append(".empty{color:#666;text-align:center;padding:40px}");
        html.append(".actions-bar{background:#16213e;border-radius:12px;padding:15px;margin-top:15px;display:flex;gap:10px;flex-wrap:wrap}");
        html.append(".header{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}");
        html.append(".header h1{margin:0}");
        html.append(".about-btn{background:#16213e;color:#4da6ff;border:1px solid #4da6ff;padding:8px 16px;border-radius:6px;cursor:pointer;font-size:0.9em;text-decoration:none}");
        html.append(".about-btn:hover{background:#4da6ff;color:#fff}");
        html.append(".modal{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.8);z-index:1000;justify-content:center;align-items:center}");
        html.append(".modal.show{display:flex}");
        html.append(".modal-content{background:#16213e;border-radius:12px;padding:30px;max-width:600px;width:90%;max-height:80vh;overflow-y:auto}");
        html.append(".modal-content h2{color:#4da6ff;margin-top:0}");
        html.append(".modal-content h3{color:#fff;margin-top:20px;margin-bottom:10px}");
        html.append(".modal-content p,.modal-content li{color:#ccc;line-height:1.6}");
        html.append(".modal-content ul{padding-left:20px}");
        html.append(".modal-content code{background:#0d0d1a;padding:2px 6px;border-radius:4px;color:#4da6ff}");
        html.append(".close-btn{float:right;background:none;border:none;color:#888;font-size:24px;cursor:pointer}");
        html.append(".close-btn:hover{color:#fff}");
        html.append("</style></head><body>");
        
        html.append("<div class='header'>");
        html.append("<h1>AROMA File Manager</h1>");
        html.append("<a href='#' class='about-btn' onclick='document.getElementById(\"aboutModal\").classList.add(\"show\");return false;'>About / Help</a>");
        html.append("</div>");
        html.append("<div class='path'>");
        if (uri.equals("/")) {
            html.append("/ (Root)");
        } else {
            html.append("<a href='/'>Root</a>");
            String[] parts = uri.substring(1).split("/");
            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                pathBuilder.append("/").append(parts[i]);
                if (i < parts.length - 1) {
                    html.append(" / <a href='").append(pathBuilder).append("/'>").append(parts[i]).append("</a>");
                } else {
                    html.append(" / ").append(parts[i]);
                }
            }
        }
        html.append("</div>");
        
        html.append("<div class='container'>");
        
        html.append("<div class='main'>");
        html.append("<form method='post' id='fileForm'>");
        html.append("<input type='hidden' name='action' value='delete'>");
        html.append("<div class='file-list'>");

        File[] files = dir.listFiles();
        Log.d("AROMA", "Listing directory: " + dir.getAbsolutePath() + ", exists: " + dir.exists() + ", canRead: " + dir.canRead() + ", files: " + (files != null ? files.length : "null"));
        if (files != null && files.length > 0) {
            java.util.Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File f : files) {
                String name = f.getName();
                String link = uri + (uri.endsWith("/") ? "" : "/") + name;
                html.append("<div class='file-item'>");
                html.append("<input type='checkbox' name='selected' value='").append(name).append("'>");
                if (f.isDirectory()) {
                    html.append("<span class='file-icon'>&#128193;</span>");
                    html.append("<div class='file-info'>");
                    html.append("<a class='file-name' href='").append(link).append("/'>").append(name).append("</a>");
                    html.append("<div class='file-meta'>Folder</div>");
                    html.append("</div>");
                } else {
                    html.append("<span class='file-icon'>&#128196;</span>");
                    html.append("<div class='file-info'>");
                    html.append("<a class='file-name' href='").append(link).append("'>").append(name).append("</a>");
                    html.append("<div class='file-meta'>").append(formatFileSize(f.length())).append("</div>");
                    html.append("</div>");
                    html.append("<div class='file-actions'>");
                    html.append("<a class='btn btn-secondary' href='").append(link).append("?preview'>Preview</a>");
                    html.append("<a class='btn btn-success' href='").append(link).append("?download'>Download</a>");
                    html.append("</div>");
                }
                html.append("</div>");
            }
        } else {
            if (files == null) {
                html.append("<div class='empty'>Cannot read directory: ").append(dir.getAbsolutePath()).append("</div>");
            } else {
                html.append("<div class='empty'>This folder is empty</div>");
            }
        }

        html.append("</div>");
        html.append("<div class='actions-bar'>");
        html.append("<button type='submit' class='btn btn-danger'>Delete Selected</button>");
        html.append("</div>");
        html.append("</form>");
        html.append("</div>");
        
        html.append("<div class='sidebar'>");
        
        html.append("<div class='panel'>");
        html.append("<h2>Upload File</h2>");
        html.append("<form method='post' enctype='multipart/form-data'>");
        html.append("<div class='form-group'>");
        html.append("<input type='file' name='uploadedFile'>");
        html.append("</div>");
        html.append("<button type='submit' class='btn btn-primary'>Upload</button>");
        html.append("</form>");
        html.append("</div>");
        
        html.append("<div class='panel'>");
        html.append("<h2>Create Folder</h2>");
        html.append("<form method='post'>");
        html.append("<input type='hidden' name='action' value='create_folder'>");
        html.append("<div class='form-group'>");
        html.append("<input type='text' name='folder_name' placeholder='Folder name'>");
        html.append("</div>");
        html.append("<button type='submit' class='btn btn-primary'>Create</button>");
        html.append("</form>");
        html.append("</div>");
        
        html.append("<div class='panel'>");
        html.append("<h2>Rename Item</h2>");
        html.append("<form method='post'>");
        html.append("<input type='hidden' name='action' value='rename'>");
        html.append("<div class='form-group'>");
        html.append("<input type='text' name='selected' placeholder='Current name'>");
        html.append("</div>");
        html.append("<div class='form-group'>");
        html.append("<input type='text' name='new_name' placeholder='New name'>");
        html.append("</div>");
        html.append("<button type='submit' class='btn btn-primary'>Rename</button>");
        html.append("</form>");
        html.append("</div>");
        
        html.append("</div>");
        html.append("</div>");
        
        // About Modal
        html.append("<div id='aboutModal' class='modal' onclick='if(event.target===this)this.classList.remove(\"show\")'>");
        html.append("<div class='modal-content'>");
        html.append("<button class='close-btn' onclick='document.getElementById(\"aboutModal\").classList.remove(\"show\")'>&times;</button>");
        html.append("<h2>AROMA File Manager</h2>");
        html.append("<p>Version 0.2 - Android Remote Online Management App</p>");
        
        html.append("<h3>Quick Start</h3>");
        html.append("<ul>");
        html.append("<li><strong>Upload:</strong> Use the Upload panel on the right to add files</li>");
        html.append("<li><strong>Download:</strong> Click the green Download button next to any file</li>");
        html.append("<li><strong>Delete:</strong> Check files and click Delete Selected</li>");
        html.append("<li><strong>Create Folder:</strong> Use the panel on the right</li>");
        html.append("<li><strong>Navigate:</strong> Click folder names or use breadcrumbs</li>");
        html.append("</ul>");
        
        html.append("<h3>Troubleshooting</h3>");
        html.append("<ul>");
        html.append("<li><strong>Can't see files?</strong> Check the storage folder in AROMA app Settings. Make sure it's set to the correct folder (Downloads, Documents, etc.)</li>");
        html.append("<li><strong>Upload fails?</strong> Check that the folder has write permissions and there's enough storage space</li>");
        html.append("<li><strong>Connection refused?</strong> Ensure you're on the same WiFi network as the Android device</li>");
        html.append("<li><strong>Can't connect remotely?</strong> Use ngrok tunnel in the AROMA app for access outside your local network</li>");
        html.append("<li><strong>Slow uploads?</strong> Large files may take time. Check your network speed</li>");
        html.append("<li><strong>File exists error?</strong> Delete or rename the existing file first</li>");
        html.append("</ul>");
        
        html.append("<h3>Security</h3>");
        html.append("<p>This server uses HTTP Basic Authentication. Default credentials are <code>admin</code> / <code>password</code>. Change these in the AROMA app Settings for better security.</p>");
        
        html.append("<h3>About</h3>");
        html.append("<p>AROMA is an open-source Android app that turns your device into a file server. Access your files from any browser on your network.</p>");
        html.append("<p style='color:#666;font-size:0.9em;margin-top:20px'>Made with NanoHTTPD | <a href='https://github.com' style='color:#4da6ff'>GitHub</a></p>");
        
        html.append("</div></div>");
        
        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString());
    }

    private Response serveFile(File file, IHTTPSession session) {
        try {
            String uri = session.getUri();
            String queryString = session.getQueryParameterString();
            
            if ("preview".equals(queryString)) {
                String filename = file.getName();
                if (filename.endsWith(".txt")) {
                    String content = readTextFile(file);
                    return newFixedLengthResponse(Response.Status.OK, "text/html", "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body style='background-color:#f5f5f5;padding:20px;font-family:monospace'><pre>" + escapeHtml(content) + "</pre></body></html>");
                } else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".png") || filename.endsWith(".gif")) {
                    return newFixedLengthResponse(Response.Status.OK, "text/html", "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body style='background-color:#f5f5f5;padding:20px;text-align:center'><img src='" + uri + "' alt='Preview' style='max-width:100%;height:auto'></body></html>");
                } else {
                    return newFixedLengthResponse(Response.Status.OK, "text/html", "<!DOCTYPE html><html><body style='background-color:#f5f5f5;padding:20px'><p>Preview not available for this file type.</p><p><a href='" + uri + "?download'>Download instead</a></p></body></html>");
                }
            }
            
            if ("download".equals(queryString)) {
                if (eventListener != null) {
                    eventListener.onFileDownloaded(file.getName(), getClientIp(session));
                }
                FileInputStream fis = new FileInputStream(file);
                Response response = newChunkedResponse(Response.Status.OK, "application/octet-stream", fis);
                response.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
                response.addHeader("Content-Length", String.valueOf(file.length()));
                return response;
            }
            
            FileInputStream fis = new FileInputStream(file);
            return newChunkedResponse(Response.Status.OK, getMimeTypeForFile(file.getName()), fis);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString());
        }
    }
    
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String readTextFile(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        InputStream is = new FileInputStream(file);
        int ch;
        while ((ch = is.read()) != -1) {
            content.append((char) ch);
        }
        is.close();
        return content.toString();
    }

    private Response handlePost(IHTTPSession session, File currentDir, String uri) {
        Log.d("AROMA", "POST request received for URI: " + uri);
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            Log.d("AROMA", "Parsed body files: " + files.size());
        } catch (Exception e) {
            Log.e("AROMA", "Parse body failed: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString());
        }

        Map<String, List<String>> params = session.getParameters();

        if (params.containsKey("action") && "delete".equals(params.get("action").get(0))) {
            List<String> selected = params.get("selected");
            if (selected == null || selected.isEmpty()) {
                return buildErrorResponse("Delete Failed", "No files selected for deletion.", uri);
            }
            StringBuilder results = new StringBuilder();
            int successCount = 0;
            int failCount = 0;
            for (String name : selected) {
                File toDelete = new File(currentDir, name);
                if (!toDelete.exists()) {
                    results.append("Not found: ").append(name).append("\n");
                    failCount++;
                } else if (toDelete.isDirectory() && toDelete.list() != null && toDelete.list().length > 0) {
                    if (deleteRecursively(toDelete)) {
                        results.append("Deleted folder: ").append(name).append("\n");
                        successCount++;
                    } else {
                        results.append("Failed to delete folder: ").append(name).append("\n");
                        failCount++;
                    }
                } else if (toDelete.delete()) {
                    results.append("Deleted: ").append(name).append("\n");
                    successCount++;
                    if (eventListener != null) {
                        eventListener.onFileDeleted(name, getClientIp(session));
                    }
                } else {
                    results.append("Failed to delete: ").append(name).append(" (permission denied or in use)\n");
                    failCount++;
                }
            }
            String title = failCount == 0 ? "Delete Successful" : "Delete Completed with Errors";
            return buildResultResponse(title, "Deleted: " + successCount + ", Failed: " + failCount, results.toString(), uri, failCount == 0);
        }

        if (params.containsKey("action") && "create_folder".equals(params.get("action").get(0))) {
            List<String> folderNames = params.get("folder_name");
            if (folderNames == null || folderNames.isEmpty() || folderNames.get(0).trim().isEmpty()) {
                return buildErrorResponse("Create Folder Failed", "Please enter a folder name.", uri);
            }
            String folderName = folderNames.get(0).trim();
            if (folderName.contains("/") || folderName.contains("\\") || folderName.contains("..")) {
                return buildErrorResponse("Create Folder Failed", "Invalid folder name. Cannot contain / \\ or ..", uri);
            }
            File newFolder = new File(currentDir, folderName);
            if (newFolder.exists()) {
                return buildErrorResponse("Create Folder Failed", "A file or folder named '" + folderName + "' already exists.", uri);
            }
            if (newFolder.mkdir()) {
                if (eventListener != null) {
                    eventListener.onFolderCreated(folderName, getClientIp(session));
                }
                return buildResultResponse("Folder Created", "Successfully created folder:", folderName, uri, true);
            } else {
                return buildErrorResponse("Create Folder Failed", "Could not create folder '" + folderName + "'. Check storage permissions.", uri);
            }
        }

        if (params.containsKey("action") && "rename".equals(params.get("action").get(0))) {
            List<String> selectedList = params.get("selected");
            List<String> newNameList = params.get("new_name");
            if (selectedList == null || selectedList.isEmpty() || selectedList.get(0).trim().isEmpty()) {
                return buildErrorResponse("Rename Failed", "Please enter the current file/folder name.", uri);
            }
            if (newNameList == null || newNameList.isEmpty() || newNameList.get(0).trim().isEmpty()) {
                return buildErrorResponse("Rename Failed", "Please enter a new name.", uri);
            }
            String selected = selectedList.get(0).trim();
            String newName = newNameList.get(0).trim();
            if (newName.contains("/") || newName.contains("\\") || newName.contains("..")) {
                return buildErrorResponse("Rename Failed", "Invalid new name. Cannot contain / \\ or ..", uri);
            }
            File toRename = new File(currentDir, selected);
            if (!toRename.exists()) {
                return buildErrorResponse("Rename Failed", "File or folder '" + selected + "' not found.", uri);
            }
            File destination = new File(currentDir, newName);
            if (destination.exists()) {
                return buildErrorResponse("Rename Failed", "A file or folder named '" + newName + "' already exists.", uri);
            }
            if (toRename.renameTo(destination)) {
                return buildResultResponse("Rename Successful", "Renamed:", selected + " -> " + newName, uri, true);
            } else {
                return buildErrorResponse("Rename Failed", "Could not rename '" + selected + "'. File may be in use or permission denied.", uri);
            }
        }

        // Handle upload
        String uploadKey = "uploadedFile";
        if (files.containsKey(uploadKey)) {
            String tempLocation = files.get(uploadKey);
            String originalName = session.getParameters().get(uploadKey).get(0);
            if (originalName == null || originalName.isEmpty()) originalName = "uploaded_file";
            File tempFile = new File(tempLocation);
            File targetFile = new File(currentDir, originalName);
            
            if (targetFile.exists()) {
                tempFile.delete();
                return buildErrorResponse("Upload Failed", "A file named '" + originalName + "' already exists. Delete or rename the existing file first.", uri);
            }
            
            Log.d("AROMA", "Temp file exists: " + tempFile.exists() + ", can read: " + tempFile.canRead());
            Log.d("AROMA", "Target dir writable: " + currentDir.canWrite() + ", path: " + currentDir.getAbsolutePath());
            boolean success = false;
            try (InputStream is = new FileInputStream(tempFile);
                 OutputStream os = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                success = true;
                tempFile.delete();  // Clean up temp file
            } catch (Exception e) {
                Log.e("AROMA", "Copy failed: " + e.getMessage());
            }
            Log.d("AROMA", "Upload attempt: " + originalName + ", success: " + success);
            if (success) {
                Log.d("AROMA", "File uploaded to: " + targetFile.getAbsolutePath());
                if (eventListener != null) {
                    eventListener.onFileUploaded(originalName, getClientIp(session));
                }
                MediaScannerConnection.scanFile(context, new String[]{targetFile.getAbsolutePath()}, null, null);
                long fileSize = targetFile.length();
                String sizeStr = formatFileSize(fileSize);
                String successHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                        "<meta http-equiv='refresh' content='3;url=" + uri + "'>" +
                        "<style>" +
                        "body{font-family:system-ui,sans-serif;background:#d4edda;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}" +
                        ".card{background:#fff;border-radius:12px;padding:40px;box-shadow:0 4px 20px rgba(0,0,0,0.1);text-align:center;max-width:500px}" +
                        ".icon{font-size:64px;margin-bottom:16px}" +
                        ".title{color:#155724;font-size:24px;margin-bottom:8px}" +
                        ".filename{color:#333;font-size:18px;word-break:break-all;background:#f8f9fa;padding:12px;border-radius:8px;margin:16px 0}" +
                        ".size{color:#666;font-size:14px}" +
                        ".redirect{color:#666;font-size:12px;margin-top:20px}" +
                        "</style></head><body>" +
                        "<div class='card'>" +
                        "<div class='icon'>&#10004;</div>" +
                        "<div class='title'>Upload Successful!</div>" +
                        "<div class='filename'>" + originalName + "</div>" +
                        "<div class='size'>Size: " + sizeStr + "</div>" +
                        "<div class='redirect'>Redirecting in 3 seconds...</div>" +
                        "</div></body></html>";
                return newFixedLengthResponse(Response.Status.OK, "text/html", successHtml);
            } else {
                Log.e("AROMA", "Upload failed for " + originalName + ": File copy failed");
                String errorHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                        "<style>" +
                        "body{font-family:system-ui,sans-serif;background:#f8d7da;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}" +
                        ".card{background:#fff;border-radius:12px;padding:40px;box-shadow:0 4px 20px rgba(0,0,0,0.1);text-align:center;max-width:500px}" +
                        ".icon{font-size:64px;margin-bottom:16px}" +
                        ".title{color:#721c24;font-size:24px;margin-bottom:8px}" +
                        ".back{margin-top:20px}" +
                        ".back a{color:#721c24;text-decoration:underline}" +
                        "</style></head><body>" +
                        "<div class='card'>" +
                        "<div class='icon'>&#10008;</div>" +
                        "<div class='title'>Upload Failed</div>" +
                        "<div class='back'><a href='" + uri + "'>Go Back</a></div>" +
                        "</div></body></html>";
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", errorHtml);
            }
        }

        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid POST request");
    }

    public static String getMimeTypeForFile(String filename) {
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "text/html";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private Response buildErrorResponse(String title, String message, String backUri) {
        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>" +
                "body{font-family:system-ui,sans-serif;background:#1a1a2e;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;padding:20px;box-sizing:border-box}" +
                ".card{background:#2d1b1b;border:1px solid #dc3545;border-radius:12px;padding:40px;text-align:center;max-width:500px;width:100%}" +
                ".icon{font-size:48px;margin-bottom:16px}" +
                ".title{color:#ff6b6b;font-size:20px;font-weight:bold;margin-bottom:12px}" +
                ".message{color:#ccc;font-size:14px;margin-bottom:20px;line-height:1.5}" +
                ".back a{color:#4da6ff;text-decoration:none;padding:10px 20px;border:1px solid #4da6ff;border-radius:6px;display:inline-block}" +
                ".back a:hover{background:#4da6ff;color:#fff}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<div class='icon'>&#9888;</div>" +
                "<div class='title'>" + escapeHtml(title) + "</div>" +
                "<div class='message'>" + escapeHtml(message) + "</div>" +
                "<div class='back'><a href='" + backUri + "'>Go Back</a></div>" +
                "</div></body></html>";
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/html", html);
    }

    private Response buildResultResponse(String title, String subtitle, String details, String backUri, boolean success) {
        String bgColor = success ? "#1b2d1b" : "#2d2d1b";
        String borderColor = success ? "#28a745" : "#ffc107";
        String titleColor = success ? "#6bff6b" : "#ffc107";
        String icon = success ? "&#10004;" : "&#9888;";
        String html = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<meta http-equiv='refresh' content='3;url=" + backUri + "'>" +
                "<style>" +
                "body{font-family:system-ui,sans-serif;background:#1a1a2e;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;padding:20px;box-sizing:border-box}" +
                ".card{background:" + bgColor + ";border:1px solid " + borderColor + ";border-radius:12px;padding:40px;text-align:center;max-width:500px;width:100%}" +
                ".icon{font-size:48px;margin-bottom:16px}" +
                ".title{color:" + titleColor + ";font-size:20px;font-weight:bold;margin-bottom:8px}" +
                ".subtitle{color:#ccc;font-size:14px;margin-bottom:12px}" +
                ".details{color:#888;font-size:12px;background:#16213e;padding:12px;border-radius:6px;text-align:left;white-space:pre-wrap;word-break:break-all;max-height:200px;overflow-y:auto}" +
                ".redirect{color:#666;font-size:11px;margin-top:16px}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<div class='icon'>" + icon + "</div>" +
                "<div class='title'>" + escapeHtml(title) + "</div>" +
                "<div class='subtitle'>" + escapeHtml(subtitle) + "</div>" +
                (details.isEmpty() ? "" : "<div class='details'>" + escapeHtml(details) + "</div>") +
                "<div class='redirect'>Redirecting in 3 seconds...</div>" +
                "</div></body></html>";
        return newFixedLengthResponse(success ? Response.Status.OK : Response.Status.BAD_REQUEST, "text/html", html);
    }
}
