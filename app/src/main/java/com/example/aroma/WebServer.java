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
    private final Context context;  // For MediaScanner
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password";

    public WebServer(int port, File wwwRoot, Context ctx) {
        super(port);
        this.rootDir = wwwRoot;
        this.context = ctx;
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
        if (!decoded.equals(USERNAME + ":" + PASSWORD)) {
            Response response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized");
            response.addHeader("WWW-Authenticate", "Basic realm=\"Protected\"");
            return response;
        }
        String uri = session.getUri();
        if (uri.isEmpty() || uri.equals("/")) uri = "/";
        Method method = session.getMethod();
        File currentDir = new File(rootDir, uri.substring(1));

        if (method == Method.GET) {
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
        html.append("<html><body style='background-color: #f0f0f0;'><h1>Directory: ").append(uri).append("</h1>");  // Light grey background
        html.append("<form method='post' enctype='multipart/form-data'>");
        html.append("<input type='file' name='uploadedFile'>");
        html.append("<input type='submit' value='Upload'>");
        html.append("</form>");
        html.append("<form method='post'>");
        html.append("<input type='hidden' name='action' value='create_folder'>");
        html.append("<input type='text' name='folder_name' placeholder='New Folder Name'>");
        html.append("<input type='submit' value='Create Folder'>");
        html.append("</form>");
        html.append("<form method='post'>");
        html.append("<input type='hidden' name='action' value='delete'>");
        html.append("<ul>");

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                String link = uri + (uri.endsWith("/") ? "" : "/") + name;
                if (f.isDirectory()) {
                    html.append("<li><input type='checkbox' name='selected' value='").append(name).append("'> <a href='").append(link).append("/'>").append(name).append("/</a></li>");
                } else {
                    html.append("<li><input type='checkbox' name='selected' value='").append(name).append("'> <a href='").append(link).append("'>").append(name).append("</a> <a href='").append(link).append("?preview'>Preview</a></li>");
                }
            }
        }

        html.append("</ul>");
        html.append("<input type='submit' value='Delete Selected'>");
        html.append("</form>");
        html.append("<form method='post'>");
        html.append("<input type='hidden' name='action' value='rename'>");
        html.append("<input type='text' name='selected' placeholder='File/Folder to Rename'>");
        html.append("<input type='text' name='new_name' placeholder='New Name'>");
        html.append("<input type='submit' value='Rename'>");
        html.append("</form>");
        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString());
    }

    private Response serveFile(File file, IHTTPSession session) {
        try {
            String uri = session.getUri();
            if (uri.endsWith("?preview")) {
                String filename = file.getName();
                if (filename.endsWith(".txt")) {
                    String content = readTextFile(file);
                    return newFixedLengthResponse(Response.Status.OK, "text/html", "<html><body style='background-color: #f0f0f0;'><pre>" + content + "</pre></body></html>");
                } else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".png")) {
                    return newFixedLengthResponse(Response.Status.OK, "text/html", "<html><body style='background-color: #f0f0f0;'><img src='" + uri.replace("?preview", "") + "' alt='Preview'></body></html>");
                }
            }
            FileInputStream fis = new FileInputStream(file);
            return newChunkedResponse(Response.Status.OK, getMimeTypeForFile(file.getName()), fis);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString());
        }
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
            if (selected != null) {
                for (String name : selected) {
                    File toDelete = new File(currentDir, name);
                    if (toDelete.exists()) {
                        toDelete.delete();
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "Deleted selected files/folders");
            }
        }

        if (params.containsKey("action") && "create_folder".equals(params.get("action").get(0))) {
            String folderName = params.get("folder_name").get(0);
            if (folderName != null && !folderName.isEmpty()) {
                new File(currentDir, folderName).mkdir();
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "Folder created: " + folderName);
            }
        }

        if (params.containsKey("action") && "rename".equals(params.get("action").get(0))) {
            String selected = params.get("selected").get(0);
            String newName = params.get("new_name").get(0);
            if (selected != null && newName != null && !selected.isEmpty() && !newName.isEmpty()) {
                File toRename = new File(currentDir, selected);
                if (toRename.exists()) {
                    toRename.renameTo(new File(currentDir, newName));
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "Renamed to: " + newName);
                }
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
                Log.d("AROMA", "File uploaded to: " + targetFile.getAbsolutePath());  // Print full path
                MediaScannerConnection.scanFile(context, new String[]{targetFile.getAbsolutePath()}, null, null);
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "File uploaded: " + originalName + " to " + targetFile.getAbsolutePath());
            } else {
                Log.e("AROMA", "Upload failed for " + originalName + ": File copy failed");
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upload failed");
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
}
