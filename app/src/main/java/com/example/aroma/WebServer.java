package com.example.aroma;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class WebServer extends NanoHTTPD {
    private final File rootDir;
    private final Context context;
    private final String username;
    private final String password;
    private ServerEventListener eventListener;
    // Last ~200 diagnostic events for /_aroma_diag
    private static final java.util.Deque<String> DIAG_LOG = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int DIAG_MAX = 300;

    static void diag(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + line;
        Log.i("AROMA_DIAG", stamped);
        DIAG_LOG.add(stamped);
        while (DIAG_LOG.size() > DIAG_MAX) DIAG_LOG.pollFirst();
    }

    /**
     * Decode the X-Upload-Path header value. The value must be the base64
     * encoding of the relative path as UTF-8 bytes. Base64 uses only ASCII
     * characters, so it travels cleanly through HTTP headers regardless of
     * what characters (", ?, Japanese, etc.) are in the original filename.
     * Returns null on decode failure or if the path tries to escape the root.
     */
    private String decodeUploadPathHeader(String headerValue) {
        try {
            byte[] raw = Base64.getDecoder().decode(headerValue.trim());
            String path = new String(raw, "UTF-8").replace('\\', '/').trim();
            while (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) return null;
            // Reject any path traversal attempts.
            for (String seg : path.split("/")) {
                if (seg.equals("..") || seg.equals(".") || seg.isEmpty()) return null;
            }
            return path;
        } catch (Throwable t) {
            diag("X-Upload-Path decode failed: " + t.getMessage());
            return null;
        }
    }

    private String sanitizeStoragePath(String path) {
        if (path == null) return null;
        String normalized = path.replace('\\', '/');
        String[] parts = normalized.split("/");
        List<String> sanitized = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            sanitized.add(sanitizeStorageSegment(part));
        }
        return String.join("/", sanitized);
    }

    private String sanitizeStorageSegment(String segment) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < segment.length(); index++) {
            char ch = segment.charAt(index);
            switch (ch) {
                case '"':
                    out.append('＂');
                    break;
                case '?':
                    out.append('？');
                    break;
                case '*':
                    out.append('＊');
                    break;
                case ':':
                    out.append('꞉');
                    break;
                case '<':
                    out.append('＜');
                    break;
                case '>':
                    out.append('＞');
                    break;
                case '|':
                    out.append('｜');
                    break;
                default:
                    if (ch < 32) {
                        out.append('_');
                    } else {
                        out.append(ch);
                    }
                    break;
            }
        }
        String result = out.toString().trim();
        if (result.isEmpty() || ".".equals(result) || "..".equals(result)) {
            return "_";
        }
        return result;
    }

    public WebServer(int port, File wwwRoot, Context ctx, String username, String password) {
        super("0.0.0.0", port);
        this.rootDir = wwwRoot;
        this.context = ctx;
        this.username = username;
        this.password = password;
        setTempFileManagerFactory(new RootTempFileManagerFactory(wwwRoot));
    }

    @Override
    public void start() throws IOException {
        start(SOCKET_READ_TIMEOUT, false);
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (getListeningPort() > 0) {
                return;
            }
            if (!isAlive()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (getListeningPort() <= 0) {
            stop();
            throw new IOException("Failed to bind to port");
        }
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

        // Diagnostic endpoint (no file system access, safe to call from browser)
        if (uri.equals("/_aroma_diag") && method == Method.GET) {
            StringBuilder body = new StringBuilder();
            for (String line : DIAG_LOG) body.append(line).append('\n');
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body.toString());
        }

        // Header-based PUT upload: any request with X-Upload-Path targets
        // a file at that relative path, bypassing the URL. This avoids URL
        // encoding headaches with filenames containing ", ?, or non-ASCII.
        if (method == Method.PUT) {
            String hdr = session.getHeaders().get("x-upload-path");
            if (hdr != null && !hdr.isEmpty()) {
                String relPath = decodeUploadPathHeader(hdr);
                String safeRelPath = sanitizeStoragePath(relPath);
                diag("PUT X-Upload-Path='" + hdr + "' decoded='" + relPath + "' safe='" + safeRelPath + "' len=" + session.getHeaders().get("content-length"));
                if (relPath == null || relPath.isEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad X-Upload-Path header");
                }
                File target = new File(rootDir, safeRelPath);
                return handlePut(session, target);
            }
            diag("PUT uri='" + uri + "' len=" + session.getHeaders().get("content-length"));
        }

        File currentDir;
        if (uri.equals("/")) {
            currentDir = rootDir;
        } else {
            currentDir = new File(rootDir, uri.substring(1));
        }

        if (isWebDavMethod(method)) {
            return handleWebDav(session, currentDir, uri);
        }

        if (uri.equals("/terminal")) {
            if (method == Method.GET) {
                return serveTerminal();
            }
        }
        
        if (uri.equals("/api/exec")) {
            if (method == Method.POST) {
                return handleExec(session);
            }
        }

        if (method == Method.GET) {
            if (eventListener != null) {
                eventListener.onClientConnected(getClientIp(session));
            }
            return handleGet(session, currentDir, uri);
        } else if (method == Method.HEAD) {
            return handleHead(currentDir, session);
        } else if (method == Method.POST) {
            return handlePost(session, currentDir, uri);
        }
        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed");
    }

    private boolean isWebDavMethod(Method method) {
        return method == Method.OPTIONS
                || method == Method.PROPFIND
                || method == Method.MKCOL
                || method == Method.MOVE
                || method == Method.COPY
                || method == Method.PUT
                || method == Method.DELETE;
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
        html.append(":root{--bg:#f5f5f5;--text:#333;--heading:#111;--panel:#fff;--panel-border:1px solid #ddd;--input-bg:#fff;--input-border:#ccc;--hover:#f0f0f0;--item-border:#eee;--meta:#888;--link:#0066cc;--empty:#999;--modal-bg:rgba(0,0,0,0.5);--code-bg:#e8e8e8}");
        html.append("[data-theme='dark']{--bg:#1a1a2e;--text:#eee;--heading:#fff;--panel:#16213e;--panel-border:none;--input-bg:#1a1a2e;--input-border:#333;--hover:#1f2b4d;--item-border:#252a40;--meta:#666;--link:#4da6ff;--empty:#666;--modal-bg:rgba(0,0,0,0.8);--code-bg:#0d0d1a}");
        html.append("*{box-sizing:border-box}");
        html.append("body{font-family:system-ui,sans-serif;background:var(--bg);color:var(--text);margin:0;padding:20px}");
        html.append("h1{color:var(--heading);margin:0 0 5px 0;font-size:1.5em}");
        html.append(".path{color:var(--meta);font-size:0.9em;margin-bottom:20px}");
        html.append(".path a{color:var(--link);text-decoration:none}");
        html.append(".container{display:grid;grid-template-columns:1fr 280px;gap:20px}");
        html.append("@media(max-width:768px){.container{grid-template-columns:1fr}.sidebar{order:-1}}");
        html.append(".panel{background:var(--panel);border:var(--panel-border);border-radius:12px;padding:20px;margin-bottom:15px}");
        html.append(".panel h2{margin:0 0 15px 0;font-size:1.1em;color:#4da6ff;border-bottom:1px solid var(--input-border);padding-bottom:10px}");
        html.append(".file-list{background:var(--panel);border:var(--panel-border);border-radius:12px;overflow:hidden}");
        html.append(".file-item{display:flex;align-items:center;padding:12px 15px;border-bottom:1px solid var(--item-border);cursor:context-menu}");
        html.append(".file-item.drag-over{outline:2px solid #4da6ff;background:var(--hover)}");
        html.append(".file-item:last-child{border-bottom:none}");
        html.append(".file-item:hover{background:var(--hover)}");
        html.append(".file-item.selected{background:var(--hover)}");
        html.append(".file-item input[type=checkbox]{width:18px;height:18px;margin-right:12px;accent-color:#4da6ff}");
        html.append(".file-icon{font-size:1.3em;margin-right:10px}");
        html.append(".file-info{flex:1;min-width:0}");
        html.append(".file-name{color:var(--heading);text-decoration:none;font-weight:500;display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}");
        html.append(".file-name:hover{color:#4da6ff}");
        html.append(".file-meta{color:var(--meta);font-size:0.8em;margin-top:2px}");
        html.append(".file-actions{display:flex;gap:8px}");
        html.append(".btn{padding:6px 12px;border-radius:6px;text-decoration:none;font-size:0.8em;border:none;cursor:pointer;display:inline-block}");
        html.append(".btn-primary{background:#4da6ff;color:#fff}");
        html.append(".btn-success{background:#28a745;color:#fff}");
        html.append(".btn-secondary{background:#555;color:#fff}");
        html.append(".btn-danger{background:#dc3545;color:#fff}");
        html.append(".btn:hover{opacity:0.85}");
        html.append("input[type=text],input[type=file]{width:100%;padding:10px;border:1px solid var(--input-border);border-radius:6px;background:var(--input-bg);color:var(--text);margin-bottom:10px}");
        html.append("input[type=text]::placeholder{color:var(--meta)}");
        html.append(".form-group{margin-bottom:15px}");
        html.append(".form-group:last-child{margin-bottom:0}");
        html.append(".empty{color:var(--empty);text-align:center;padding:40px}");
        html.append(".actions-bar{background:var(--panel);border:var(--panel-border);border-radius:12px;padding:15px;margin-top:15px;display:flex;gap:10px;flex-wrap:wrap}");
        html.append(".header{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;gap:10px;flex-wrap:wrap}");
        html.append(".header h1{margin:0}");
        html.append(".header-buttons{display:flex;gap:8px;align-items:center}");
        html.append(".header-btn{background:var(--panel);color:#4da6ff;border:1px solid #4da6ff;padding:8px 16px;border-radius:6px;cursor:pointer;font-size:0.9em;text-decoration:none}");
        html.append(".header-btn:hover{background:#4da6ff;color:#fff}");
        html.append(".theme-toggle{background:var(--panel);border:1px solid var(--input-border);padding:6px 12px;border-radius:6px;cursor:pointer;font-size:1.1em}");
        html.append(".modal{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:var(--modal-bg);z-index:1000;justify-content:center;align-items:center}");
        html.append(".modal.show{display:flex}");
        html.append(".modal-content{background:var(--panel);border-radius:12px;padding:30px;max-width:600px;width:90%;max-height:80vh;overflow-y:auto}");
        html.append(".modal-content h2{color:#4da6ff;margin-top:0}");
        html.append(".modal-content h3{color:var(--heading);margin-top:20px;margin-bottom:10px}");
        html.append(".modal-content p,.modal-content li{color:var(--text);line-height:1.6}");
        html.append(".modal-content ul{padding-left:20px}");
        html.append(".modal-content code{background:var(--code-bg);padding:2px 6px;border-radius:4px;color:#4da6ff}");
        html.append(".close-btn{float:right;background:none;border:none;color:var(--meta);font-size:24px;cursor:pointer}");
        html.append(".close-btn:hover{color:var(--heading)}");
        html.append(".context-menu{position:fixed;background:var(--panel);border:var(--panel-border);border-radius:8px;box-shadow:0 4px 20px rgba(0,0,0,0.3);z-index:2000;min-width:180px;padding:6px 0;display:none}");
        html.append(".context-menu.show{display:block}");
        html.append(".context-menu-item{padding:10px 16px;cursor:pointer;display:flex;align-items:center;gap:10px;color:var(--text);font-size:0.9em}");
        html.append(".context-menu-item:hover{background:var(--hover)}");
        html.append(".context-menu-item.danger{color:#dc3545}");
        html.append(".context-menu-divider{height:1px;background:var(--item-border);margin:6px 0}");
        html.append("</style></head><body>");
        
        html.append("<div class='header'>");
        html.append("<h1>AROMA File Manager</h1>");
        html.append("<div class='header-buttons'>");
        html.append("<button class='theme-toggle' onclick='toggleTheme()' title='Toggle theme'>&#9788;</button>");
        html.append("<a href='/terminal' class='header-btn' style='background:#28a745;border-color:#28a745;color:#fff'>Terminal</a>");
        html.append("<a href='#' class='header-btn' onclick='showModal(\"createFolderModal\");return false;'>+ New Folder</a>");
        html.append("<a href='#' class='header-btn' onclick='showModal(\"aboutModal\");return false;'>Help</a>");
        html.append("</div>");
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
                String escapedName = escapeHtml(name).replace("'", "\\'");
                html.append("<div class='file-item' draggable='true' ondragstart='handleDragStart(event,\"").append(escapedName).append("\")' ");
                if (f.isDirectory()) {
                    html.append("ondragover='handleDragOver(event)' ondragleave='handleDragLeave(event)' ondrop='handleDrop(event,\"").append(escapedName).append("\")' ");
                }
                html.append("oncontextmenu='showContextMenu(event,\"").append(escapedName).append("\",").append(f.isDirectory()).append(",\"").append(link).append("\")' data-name='").append(escapeHtml(name)).append("' data-size='").append(f.isDirectory() ? 0 : f.length()).append("' data-modified='").append(f.lastModified()).append("'>");
                html.append("<input type='checkbox' name='selected' value='").append(escapeHtml(name)).append("'>");
                if (f.isDirectory()) {
                    html.append("<span class='file-icon'>&#128193;</span>");
                    html.append("<div class='file-info'>");
                    html.append("<a class='file-name' href='").append(link).append("/'>").append(escapeHtml(name)).append("</a>");
                    html.append("<div class='file-meta'>Folder</div>");
                    html.append("</div>");
                } else {
                    String lowerName = name.toLowerCase();
                    boolean canPreview = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || 
                        lowerName.endsWith(".png") || lowerName.endsWith(".gif") || lowerName.endsWith(".webp") ||
                        lowerName.endsWith(".mp4") || lowerName.endsWith(".webm") || lowerName.endsWith(".mov") ||
                        lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") ||
                        lowerName.endsWith(".pdf") || lowerName.endsWith(".txt") || lowerName.endsWith(".json") ||
                        lowerName.endsWith(".xml") || lowerName.endsWith(".html") || lowerName.endsWith(".css") ||
                        lowerName.endsWith(".js") || lowerName.endsWith(".md") || lowerName.endsWith(".log");
                    html.append("<span class='file-icon'>&#128196;</span>");
                    html.append("<div class='file-info'>");
                    html.append("<a class='file-name' href='").append(link).append("'>").append(escapeHtml(name)).append("</a>");
                    html.append("<div class='file-meta'>").append(formatFileSize(f.length())).append("</div>");
                    html.append("</div>");
                    html.append("<div class='file-actions'>");
                    if (canPreview) {
                        html.append("<a class='btn btn-primary' href='").append(link).append("?preview' target='_blank'>Preview</a>");
                    }
                    html.append("<a class='btn btn-success' href='").append(link).append("?download'>Download</a>");
                    html.append("</div>");
                }
                html.append("</div>");
            }
        } else {
            if (files == null) {
                html.append("<div class='empty'>Cannot read directory: ").append(escapeHtml(dir.getAbsolutePath())).append("</div>");
            } else {
                html.append("<div class='empty'>This folder is empty. Right-click to create a folder.</div>");
            }
        }

        html.append("</div>");
        html.append("<div class='actions-bar'>");
        html.append("<button type='submit' class='btn btn-danger'>Delete Selected</button>");
        html.append("<button type='button' class='btn btn-success' onclick='downloadSelected()'>Download Selected</button>");
        html.append("<button type='button' class='btn btn-secondary' onclick='selectAll()'>Select All</button>");
        html.append("<button type='button' class='btn btn-secondary' onclick='selectNone()'>Select None</button>");
        html.append("</div>");
        html.append("</form>");
        html.append("</div>");
        
        html.append("<div class='sidebar'>");
        
        html.append("<div class='panel'>");
        html.append("<h2>Upload</h2>");
        html.append("<div class='form-group'>");
        html.append("<input type='file' name='uploadedFile' multiple id='fileInput' style='display:none'>");
        html.append("<input type='file' name='uploadedFile' webkitdirectory id='folderInput' style='display:none'>");
        html.append("<div style='display:flex;gap:8px;flex-wrap:wrap'>");
        html.append("<button type='button' class='btn btn-secondary' onclick='document.getElementById(\"fileInput\").click()'>Select Files</button>");
        html.append("<button type='button' class='btn btn-secondary' onclick='addFolder()'>Add Folder</button>");
        html.append("<button type='button' class='btn btn-secondary' id='clearSelBtn' onclick='clearSelection()' style='display:none'>Clear</button>");
        html.append("</div>");
        html.append("<div style='margin-top:6px;font-size:0.75em;color:var(--meta)'>Tip: click <b>Add Folder</b> multiple times to migrate several folders in one upload.</div>");
        html.append("<div id='selectedFiles' style='margin-top:10px;font-size:0.85em;color:var(--meta)'></div>");
        html.append("</div>");
        html.append("<div id='uploadProgress' style='display:none;margin-bottom:10px'>");
        html.append("<div style='background:var(--input-border);border-radius:4px;height:20px;overflow:hidden;position:relative'>");
        html.append("<div id='progressBar' style='background:linear-gradient(90deg,#4da6ff,#00d4ff);height:100%;width:0%;transition:width 0.15s ease-out'></div>");
        html.append("<div id='progressPercent' style='position:absolute;top:0;left:0;right:0;text-align:center;line-height:20px;font-size:11px;color:#fff;font-weight:600;text-shadow:0 1px 2px rgba(0,0,0,0.5)'>0%</div>");
        html.append("</div>");
        html.append("<div id='progressText' style='font-size:0.8em;color:var(--meta);margin-top:8px'>0 / 0 files</div>");
        html.append("<div id='progressSpeed' style='font-size:0.75em;color:var(--meta);margin-top:4px'></div>");
        html.append("</div>");
        html.append("<label style='display:flex;align-items:center;gap:8px;font-size:0.85em;color:var(--meta);margin-bottom:10px;cursor:pointer'>");
        html.append("<input type='checkbox' id='overwriteCheck' style='width:16px;height:16px;accent-color:#4da6ff'>");
        html.append("Overwrite existing files");
        html.append("</label>");
        html.append("<button type='button' class='btn btn-primary' id='uploadBtn' disabled onclick='startUpload()'>Upload</button>");
        html.append("<button type='button' class='btn btn-secondary' id='stopUploadBtn' onclick='stopUpload()' style='display:none;margin-left:8px'>Stop Upload</button>");
        html.append("<button type='button' class='btn btn-danger' id='retryFailedBtn' onclick='retryFailed()' style='display:none;margin-left:8px'>Retry Failed</button>");
        html.append("</div>");
        
        html.append("<div class='panel' id='fileInfoPanel' style='display:none'>");
        html.append("<h2>File Info</h2>");
        html.append("<div id='fileInfoContent' style='font-size:0.85em;color:var(--meta);line-height:1.6'></div>");
        html.append("</div>");
        
        html.append("<div class='panel'>");
        html.append("<h2>Tips</h2>");
        html.append("<p style='font-size:0.85em;color:var(--meta);line-height:1.5;margin:0'>");
        html.append("Right-click on files/folders for more options: rename, delete, download, copy path, and more.");
        html.append("</p>");
        html.append("</div>");
        
        html.append("</div>");
        html.append("</div>");
        
        // Context Menu
        html.append("<div id='contextMenu' class='context-menu'>");
        html.append("<div class='context-menu-item' onclick='openItem()'><span>&#128194;</span> Open</div>");
        html.append("<div class='context-menu-item' onclick='downloadItem()'><span>&#11015;</span> Download</div>");
        html.append("<div class='context-menu-item' onclick='previewItem()'><span>&#128065;</span> Preview</div>");
        html.append("<div class='context-menu-divider'></div>");
        html.append("<div class='context-menu-item' onclick='showRenameModal()'><span>&#9998;</span> Rename</div>");
        html.append("<div class='context-menu-item' onclick='copyPath()'><span>&#128203;</span> Copy Path</div>");
        html.append("<div class='context-menu-divider'></div>");
        html.append("<div class='context-menu-item' onclick='createFolderFromMenu()'><span>&#128193;</span> New Folder</div>");
        html.append("<div class='context-menu-item danger' onclick='deleteItem()'><span>&#128465;</span> Delete</div>");
        html.append("</div>");
        
        // Create Folder Modal
        html.append("<div id='createFolderModal' class='modal' onclick='if(event.target===this)hideModal(\"createFolderModal\")'>");
        html.append("<div class='modal-content' style='max-width:400px'>");
        html.append("<button class='close-btn' onclick='hideModal(\"createFolderModal\")'>&times;</button>");
        html.append("<h2>Create New Folder</h2>");
        html.append("<form method='post' onsubmit='return submitManagementForm(this)'>");
        html.append("<input type='hidden' name='action' value='create_folder'>");
        html.append("<div class='form-group'>");
        html.append("<input type='text' name='folder_name' id='newFolderName' placeholder='Folder name' autofocus>");
        html.append("</div>");
        html.append("<button type='submit' class='btn btn-primary'>Create Folder</button>");
        html.append("</form>");
        html.append("</div></div>");
        
        // Rename Modal
        html.append("<div id='renameModal' class='modal' onclick='if(event.target===this)hideModal(\"renameModal\")'>");
        html.append("<div class='modal-content' style='max-width:400px'>");
        html.append("<button class='close-btn' onclick='hideModal(\"renameModal\")'>&times;</button>");
        html.append("<h2>Rename</h2>");
        html.append("<form method='post' onsubmit='return submitManagementForm(this)'>");
        html.append("<input type='hidden' name='action' value='rename'>");
        html.append("<input type='hidden' name='selected' id='renameOldName'>");
        html.append("<div class='form-group'>");
        html.append("<label style='color:var(--meta);font-size:0.9em'>Current name:</label>");
        html.append("<div id='renameCurrentDisplay' style='padding:8px 0;font-weight:500'></div>");
        html.append("</div>");
        html.append("<div class='form-group'>");
        html.append("<input type='text' name='new_name' id='renameNewName' placeholder='New name'>");
        html.append("</div>");
        html.append("<button type='submit' class='btn btn-primary'>Rename</button>");
        html.append("</form>");
        html.append("</div></div>");
        
        // About Modal
        html.append("<div id='aboutModal' class='modal' onclick='if(event.target===this)hideModal(\"aboutModal\")'>");
        html.append("<div class='modal-content'>");
        html.append("<button class='close-btn' onclick='hideModal(\"aboutModal\")'>&times;</button>");
        html.append("<h2>AROMA File Manager</h2>");
        html.append("<p>Version " + BuildConfig.APP_VERSION + " - Android Remote Online Management App</p>");
        
        html.append("<h3>Quick Start</h3>");
        html.append("<ul>");
        html.append("<li><strong>Upload:</strong> Use the Upload panel on the right</li>");
        html.append("<li><strong>Download:</strong> Click Download button or right-click &gt; Download</li>");
        html.append("<li><strong>Delete:</strong> Check files and click Delete Selected, or right-click &gt; Delete</li>");
        html.append("<li><strong>Create Folder:</strong> Click '+ New Folder' or right-click in empty area</li>");
        html.append("<li><strong>Rename:</strong> Right-click on any file or folder &gt; Rename</li>");
        html.append("<li><strong>Navigate:</strong> Click folder names or use breadcrumbs</li>");
        html.append("</ul>");
        
        html.append("<h3>Keyboard Shortcuts</h3>");
        html.append("<ul>");
        html.append("<li><code>Ctrl+A</code> - Select all files</li>");
        html.append("<li><code>Escape</code> - Close dialogs</li>");
        html.append("</ul>");
        
        html.append("<h3>About</h3>");
        html.append("<p>AROMA is an Android app that turns your device into a file server.</p>");
        
        html.append("</div></div>");
        
        // JavaScript
        html.append("<script>");
        html.append("let currentItem={name:'',isDir:false,link:''};");
        html.append("let isEmptyAreaClick=false;");
        html.append("const fileData={};");
        html.append("function initTheme(){let t=localStorage.getItem('aroma-theme')||'light';document.body.setAttribute('data-theme',t)}");
        html.append("function toggleTheme(){let t=document.body.getAttribute('data-theme')==='dark'?'light':'dark';document.body.setAttribute('data-theme',t);localStorage.setItem('aroma-theme',t)}");
        html.append("function showModal(id){document.getElementById(id).classList.add('show')}");
        html.append("function hideModal(id){document.getElementById(id).classList.remove('show')}");
        html.append("function showContextMenu(e,name,isDir,link){e.preventDefault();e.stopPropagation();currentItem={name,isDir,link};isEmptyAreaClick=false;let m=document.getElementById('contextMenu');m.style.left=e.pageX+'px';m.style.top=e.pageY+'px';m.classList.add('show');updateContextMenuItems(false)}");
        html.append("function showEmptyContextMenu(e){e.preventDefault();isEmptyAreaClick=true;currentItem={name:'',isDir:false,link:''};let m=document.getElementById('contextMenu');m.style.left=e.pageX+'px';m.style.top=e.pageY+'px';m.classList.add('show');updateContextMenuItems(true)}");
        html.append("function updateContextMenuItems(emptyArea){let items=document.querySelectorAll('.context-menu-item');items.forEach(item=>{let t=item.textContent.trim();if(emptyArea){item.style.display=(t.includes('New Folder'))?'flex':'none'}else{item.style.display='flex'}})}");
        html.append("document.addEventListener('click',()=>document.getElementById('contextMenu').classList.remove('show'));");
        html.append("document.addEventListener('keydown',e=>{if(e.key==='Escape'){document.querySelectorAll('.modal.show').forEach(m=>m.classList.remove('show'));document.getElementById('contextMenu').classList.remove('show')}if(e.ctrlKey&&e.key==='a'){e.preventDefault();selectAll()}});");
        html.append("function openItem(){let href=currentItem.link+(currentItem.isDir?'/':'');if(isUploading){window.open(href,'_blank');return}window.location.href=href}");
        html.append("function downloadItem(){if(!currentItem.isDir){let href=currentItem.link+'?download';if(isUploading){window.open(href,'_blank');return}window.location.href=href}}");
        html.append("function previewItem(){if(!currentItem.isDir)window.open(currentItem.link+'?preview','_blank')}");
        html.append("function showRenameModal(){document.getElementById('renameOldName').value=currentItem.name;document.getElementById('renameCurrentDisplay').textContent=currentItem.name;document.getElementById('renameNewName').value=currentItem.name;showModal('renameModal');document.getElementById('renameNewName').select()}");
        html.append("function copyPath(){navigator.clipboard.writeText(window.location.origin+currentItem.link).then(()=>alert('Path copied!'))}");
        html.append("let draggedItemName=null;");
        html.append("function handleDragStart(e,name){draggedItemName=name;e.dataTransfer.setData('text/plain',name);e.dataTransfer.effectAllowed='move'}");
        html.append("function handleDragOver(e){e.preventDefault();e.currentTarget.classList.add('drag-over')}");
        html.append("function handleDragLeave(e){e.currentTarget.classList.remove('drag-over')}");
        html.append("function handleDrop(e,targetFolder){e.preventDefault();e.currentTarget.classList.remove('drag-over');let checked=document.querySelectorAll('input[name=selected]:checked');let names=checked.length>0?Array.from(checked).map(c=>c.value):(draggedItemName?[draggedItemName]:[]);if(names.length===0)return;if(names.includes(targetFolder)){alert('Cannot move a folder into itself');return}document.body.dataset.allowNavigate='true';let form=document.createElement('form');form.method='post';if(isUploading)form.target='_blank';form.innerHTML='<input name=\"action\" value=\"move\"><input name=\"target_folder\" value=\"'+targetFolder.replace(/\"/g,'&quot;')+'\">';names.forEach(n=>{let input=document.createElement('input');input.type='hidden';input.name='selected';input.value=n;form.appendChild(input)});document.body.appendChild(form);form.submit()}");
        html.append("function deleteItem(){if(confirm('Delete \"'+currentItem.name+'\"?')){document.body.dataset.allowNavigate='true';let f=document.createElement('form');f.method='post';if(isUploading)f.target='_blank';f.innerHTML='<input name=\"action\" value=\"delete\"><input name=\"selected\" value=\"'+currentItem.name+'\">';document.body.appendChild(f);f.submit()}}");
        html.append("function createFolderFromMenu(){showModal('createFolderModal');document.getElementById('newFolderName').focus()}");
        html.append("function selectAll(){document.querySelectorAll('input[name=selected]').forEach(c=>{c.checked=true});updateFileInfoPanel()}");
        html.append("function selectNone(){document.querySelectorAll('input[name=selected]').forEach(c=>{c.checked=false});updateFileInfoPanel()}");
        html.append("function downloadSelected(){let checked=document.querySelectorAll('input[name=selected]:checked');if(checked.length===0){alert('No files selected');return}document.body.dataset.allowNavigate='true';let files=Array.from(checked).map(c=>c.value);let form=document.createElement('form');form.method='POST';form.action=window.location.pathname+'?downloadzip';if(isUploading)form.target='_blank';files.forEach(f=>{let input=document.createElement('input');input.type='hidden';input.name='selected';input.value=f;form.appendChild(input)});document.body.appendChild(form);form.submit()}");
        html.append("function updateFileInfoPanel(){let checked=document.querySelectorAll('input[name=selected]:checked');let panel=document.getElementById('fileInfoPanel');let content=document.getElementById('fileInfoContent');if(checked.length===0){panel.style.display='none';return}panel.style.display='block';if(checked.length===1){let name=checked[0].value;let item=checked[0].closest('.file-item');let meta=item.querySelector('.file-meta');let size=meta?meta.textContent:'';content.innerHTML='<strong>'+name+'</strong><br>'+size}else{let totalSize=0;checked.forEach(c=>{let d=fileData[c.value];if(d&&d.size)totalSize+=d.size});content.innerHTML=checked.length+' items selected<br>Total: '+formatSize(totalSize)}}");
        html.append("function formatSize(b){if(b<=0)return'0 B';let u=['B','KB','MB','GB','TB'];let i=Math.floor(Math.log(b)/Math.log(1024));return(b/Math.pow(1024,i)).toFixed(1)+' '+u[i]}");
        html.append("function submitManagementForm(form){document.body.dataset.allowNavigate='true';if(isUploading){form.target='_blank'}return true}");
        html.append("document.querySelectorAll('input[name=selected]').forEach(c=>c.addEventListener('change',updateFileInfoPanel));");
        html.append("document.querySelectorAll('.file-item').forEach(item=>{let n=item.dataset.name;let s=parseInt(item.dataset.size)||0;let m=parseInt(item.dataset.modified)||0;fileData[n]={size:s,modified:m}});");
        html.append("let activeInput=null;let uploadFiles=[];let addedFolders=[];let pickedFiles=[];let sessionKey='aroma_upload_'+window.location.pathname;");
        html.append("function getUploadedFiles(){try{return JSON.parse(localStorage.getItem(sessionKey))||{}}catch(e){return{}}}");
        html.append("function saveUploadedFile(fname,size){let data=getUploadedFiles();data[fname]={size:size,time:Date.now()};localStorage.setItem(sessionKey,JSON.stringify(data))}");
        html.append("function addUploadedAlias(originalPath,savedAs,size){if(!savedAs||savedAs===originalPath)return;let data=getUploadedFiles();let entry=data[originalPath]||{size:size,time:Date.now()};entry.size=size||entry.size||0;entry.time=Date.now();data[savedAs]=entry;localStorage.setItem(sessionKey,JSON.stringify(data))}");
        html.append("function clearUploadSession(){localStorage.removeItem(sessionKey)}");
        html.append("function addFolder(){let fi=document.getElementById('folderInput');fi.value='';fi.click()}");
        html.append("function clearSelection(){addedFolders=[];pickedFiles=[];document.getElementById('fileInput').value='';document.getElementById('folderInput').value='';updateFileStatus()}");
        html.append("function rebuildUploadList(){let seen={};let out=[];pickedFiles.forEach(f=>{let k=f.webkitRelativePath||f.name;if(!seen[k]){seen[k]=1;out.push(f)}});addedFolders.forEach(folder=>{folder.forEach(f=>{let k=f.webkitRelativePath||f.name;if(!seen[k]){seen[k]=1;out.push(f)}})});return out.filter(f=>{let n=(f.webkitRelativePath||f.name).split('/').pop();return!n.startsWith('.')&&n!=='Thumbs.db'&&n!=='desktop.ini'})}");
        html.append("function updateFileStatus(){let sf=document.getElementById('selectedFiles');let btn=document.getElementById('uploadBtn');let clr=document.getElementById('clearSelBtn');uploadFiles=rebuildUploadList();let count=uploadFiles.length;if(count>0){let totalSize=uploadFiles.reduce((a,f)=>a+f.size,0);let folderNames=addedFolders.map(fs=>{let p=fs[0]&&fs[0].webkitRelativePath;return p?p.split('/')[0]:'(folder)'});let uploaded=getUploadedFiles();let alreadyUploaded=uploadFiles.filter(f=>{let fn=f.webkitRelativePath||f.name;return uploaded[fn]&&uploaded[fn].size===f.size}).length;let info=count+' file(s) ('+formatSize(totalSize)+')';if(folderNames.length>0)info+='<br>Folders ('+folderNames.length+'): '+folderNames.join(', ');if(pickedFiles.length>0)info+='<br>'+pickedFiles.length+' individual file(s)';if(alreadyUploaded>0){info+='<br><span style=\"color:#4da6ff\">'+alreadyUploaded+' already uploaded - will resume remaining '+(count-alreadyUploaded)+'</span>'}sf.innerHTML=info;btn.disabled=false;btn.textContent=alreadyUploaded>0?'Resume Upload':'Upload';clr.style.display='inline-block'}else{sf.textContent='';btn.disabled=true;btn.textContent='Upload';clr.style.display='none'}}");
        html.append("document.getElementById('fileInput').addEventListener('change',function(){pickedFiles=pickedFiles.concat(Array.from(this.files));this.value='';updateFileStatus()});");
        html.append("document.getElementById('folderInput').addEventListener('change',function(){let fs=Array.from(this.files);if(fs.length>0){let root=fs[0].webkitRelativePath?fs[0].webkitRelativePath.split('/')[0]:null;let dup=root&&addedFolders.some(f=>f[0]&&f[0].webkitRelativePath&&f[0].webkitRelativePath.split('/')[0]===root);if(!dup)addedFolders.push(fs)}this.value='';updateFileStatus()});");
        html.append("const DEFAULT_CONCURRENCY=4;const LARGE_UPLOAD_CONCURRENCY=2;const LARGE_UPLOAD_BYTES=2*1024*1024*1024;const MAX_RETRIES=4;const RETRY_DELAYS=[500,1500,3000,6000];let isUploading=false;let uploadCancelled=false;let activeXhrs=new Set();let pendingFailures=[];");
        html.append("window.addEventListener('beforeunload',function(e){let navigating=document.body.dataset.allowNavigate==='true';if(isUploading&&!navigating){e.preventDefault();e.returnValue='Upload in progress. Are you sure you want to leave?';return e.returnValue}});");
        html.append("function isRetryable(status){return status===0||status===408||status===409||status===425||status===429||(status>=500&&status<600)}");
        html.append("function sleep(ms){return new Promise(r=>setTimeout(r,ms))}");
        html.append("async function runUpload(filesIn,overwrite,isRetryPass){if(!filesIn||filesIn.length===0)return{success:0,skipped:0,failed:[]};let btn=document.getElementById('uploadBtn');let prog=document.getElementById('uploadProgress');let bar=document.getElementById('progressBar');let pct=document.getElementById('progressPercent');let txt=document.getElementById('progressText');let spd=document.getElementById('progressSpeed');btn.disabled=true;btn.textContent=isRetryPass?'Retrying...':'Uploading...';prog.style.display='block';let uploaded=getUploadedFiles();let filesToUpload=isRetryPass?filesIn.slice():filesIn.filter(f=>{let fn=f.webkitRelativePath||f.name;return!uploaded[fn]||uploaded[fn].size!==f.size});let skippedPrev=isRetryPass?0:(filesIn.length-filesToUpload.length);let totalFiles=filesToUpload.length;let totalBytes=filesToUpload.reduce((a,f)=>a+f.size,0);let concurrency=totalBytes>=LARGE_UPLOAD_BYTES?LARGE_UPLOAD_CONCURRENCY:DEFAULT_CONCURRENCY;let uploadedBytes=0;let uploadedFiles=0;let success=0;let skipped=0;let failed=[];let queue=filesToUpload.map(f=>({file:f,attempt:0}));let startTime=Date.now();function updateProgress(){let p=totalBytes>0?(uploadedBytes/totalBytes*100):0;bar.style.width=p+'%';pct.textContent=Math.round(p)+'%';txt.textContent=uploadedFiles+' / '+totalFiles+' files ('+formatSize(uploadedBytes)+' / '+formatSize(totalBytes)+')';let elapsed=(Date.now()-startTime)/1000;let speed=elapsed>0?uploadedBytes/elapsed:0;spd.textContent='Speed: '+formatSize(speed)+'/s'+(skippedPrev>0?' | Resumed: '+skippedPrev+' skipped':'')+(isRetryPass?' | Retrying failed items':'')+' | Parallel: '+concurrency}");
        html.append("function encodePathSegment(p){return p.split('/').map(s=>encodeURIComponent(s)).join('/')}");
        html.append("function b64UploadPath(s){return btoa(unescape(encodeURIComponent(s)))}");
        html.append("function uploadOnce(file,forceOverwrite,usePut){return new Promise((resolve)=>{let fname=file.webkitRelativePath||file.name;let xhr=new XMLHttpRequest();xhr.timeout=600000;let attemptBytes=0;xhr.upload.onprogress=function(e){if(e.lengthComputable){let delta=e.loaded-attemptBytes;attemptBytes=e.loaded;uploadedBytes+=delta;updateProgress()}};xhr.onload=function(){activeXhrs.delete(xhr);if(xhr.status>=200&&xhr.status<300){let alias=xhr.getResponseHeader('X-Aroma-Saved-As');resolve({ok:true,status:xhr.status,savedAs:alias})}else if(xhr.responseText&&xhr.responseText.includes('already exists')){uploadedBytes-=attemptBytes;resolve({ok:true,status:xhr.status,alreadyExists:true})}else{uploadedBytes-=attemptBytes;let reason='HTTP '+xhr.status;let raw=(xhr.responseText||'').replace(/<[^>]*>/g,' ').replace(/\\s+/g,' ').trim();if(usePut){if(raw)reason='HTTP '+xhr.status+': '+raw.substring(0,200)}else{let t=raw.toLowerCase();if(t.includes('permission denied'))reason='Permission denied';else if(t.includes('cannot create directory'))reason='Cannot create directory';else if(t.includes('temp file'))reason='Temp file error';else if(t.includes('no space')||t.includes('enospc'))reason='Storage full (no space)';else if(raw&&raw.length<200)reason='HTTP '+xhr.status+': '+raw}resolve({ok:false,status:xhr.status,reason:reason})}};xhr.onerror=function(){activeXhrs.delete(xhr);uploadedBytes-=attemptBytes;if(uploadedBytes<0)uploadedBytes=0;resolve({ok:false,status:0,reason:'Network error (check /_aroma_diag on server for details)'})};xhr.ontimeout=function(){activeXhrs.delete(xhr);uploadedBytes-=attemptBytes;if(uploadedBytes<0)uploadedBytes=0;resolve({ok:false,status:408,reason:'Request timed out after 10min'})};xhr.onabort=function(){activeXhrs.delete(xhr);uploadedBytes-=attemptBytes;if(uploadedBytes<0)uploadedBytes=0;resolve({ok:false,status:0,reason:'Aborted',aborted:true})};if(usePut){let base=window.location.pathname;if(!base.endsWith('/'))base+='/';xhr.open('PUT',base+'_aroma_put');xhr.setRequestHeader('Content-Type','application/octet-stream');xhr.setRequestHeader('X-Upload-Path',b64UploadPath(fname));activeXhrs.add(xhr);xhr.send(file)}else{let fd=new FormData();fd.append('uploadedFile',file,fname);fd.append('originalPath',fname);if(overwrite||forceOverwrite)fd.append('overwrite','true');xhr.open('POST',window.location.pathname);activeXhrs.add(xhr);xhr.send(fd)}})}");
        html.append("async function uploadWithRetry(file){let fname=file.webkitRelativePath||file.name;let lastReason='Unknown';for(let attempt=0;attempt<=MAX_RETRIES;attempt++){if(uploadCancelled)return{ok:false,reason:'Cancelled',fatal:true};let usePut=isRetryPass||attempt>=2;let r=await uploadOnce(file,isRetryPass||attempt>0,usePut);if(r.aborted||uploadCancelled)return{ok:false,reason:'Cancelled',fatal:true};if(r.ok){if(r.alreadyExists){skipped++}else{success++;saveUploadedFile(fname,file.size);if(r.savedAs&&r.savedAs!==fname){addUploadedAlias(fname,r.savedAs,file.size)}}return{ok:true}}lastReason=r.reason;if(!isRetryable(r.status)){return{ok:false,reason:lastReason,fatal:true}}if(attempt<MAX_RETRIES){let d=RETRY_DELAYS[Math.min(attempt,RETRY_DELAYS.length-1)];await sleep(d)}}return{ok:false,reason:lastReason+' (after '+MAX_RETRIES+' retries)',fatal:false}}");
        html.append("async function worker(){while(queue.length>0){if(uploadCancelled)break;let item=queue.shift();if(!item)continue;let r=await uploadWithRetry(item.file);if(!r.ok&&r.reason!=='Cancelled'){failed.push({file:item.file,name:item.file.webkitRelativePath||item.file.name,reason:r.reason})}uploadedFiles++;updateProgress()}}");
        html.append("if(totalFiles===0){btn.textContent='Upload';btn.disabled=false;prog.style.display='none';if(!isRetryPass)alert('All '+skippedPrev+' files already uploaded!');return{success:0,skipped:skippedPrev,failed:[]}}");
        html.append("let workers=[];for(let i=0;i<concurrency;i++)workers.push(worker());await Promise.all(workers);return{success:success,skipped:skipped+skippedPrev,failed:failed}}");
        html.append("function updateRetryButton(){let btn=document.getElementById('retryFailedBtn');if(!btn)return;if(pendingFailures.length>0){btn.style.display='inline-block';btn.textContent='Retry '+pendingFailures.length+' Failed'}else{btn.style.display='none'}}");
        html.append("function setStopButtonVisible(v){let b=document.getElementById('stopUploadBtn');if(b)b.style.display=v?'inline-block':'none';if(b){b.disabled=false;b.textContent='Stop Upload'}}");
        html.append("function stopUpload(){if(!isUploading)return;if(!confirm('Stop the current upload? In-flight files will be cancelled.'))return;uploadCancelled=true;let b=document.getElementById('stopUploadBtn');if(b){b.disabled=true;b.textContent='Stopping...'}activeXhrs.forEach(x=>{try{x.abort()}catch(e){}});activeXhrs.clear()}");
        html.append("async function retryFailed(){if(pendingFailures.length===0||isUploading)return;uploadCancelled=false;isUploading=true;setStopButtonVisible(true);let files=pendingFailures.map(f=>f.file);pendingFailures=[];updateRetryButton();let res=await runUpload(files,true,true);finalizeUpload(res,true)}");
        html.append("function finalizeUpload(res,wasRetry){isUploading=false;setStopButtonVisible(false);let btn=document.getElementById('uploadBtn');let prog=document.getElementById('uploadProgress');let bar=document.getElementById('progressBar');btn.textContent='Upload';btn.disabled=uploadFiles.length===0;prog.style.display='none';bar.style.width='0%';pendingFailures=res.failed||[];updateRetryButton();let msg=(uploadCancelled?'Upload stopped\\n':(wasRetry?'Retry pass complete\\n':''))+'Uploaded: '+res.success;if(res.skipped>0)msg+='\\nSkipped/resumed: '+res.skipped;if(pendingFailures.length>0){msg+='\\nStill failing: '+pendingFailures.length+'\\n';pendingFailures.forEach(f=>{msg+='\\n  '+f.name+'\\n    Reason: '+f.reason+'\\n'});msg+='\\nClick \"Retry '+pendingFailures.length+' Failed\" to try again.'}alert(msg);uploadCancelled=false;if(pendingFailures.length===0&&!wasRetry&&res.success+res.skipped>0){clearUploadSession();history.replaceState(null,'',window.location.pathname);let a=document.createElement('a');a.href=window.location.pathname;document.body.appendChild(a);a.click()}}");
        html.append("async function startUpload(){if(uploadFiles.length===0||isUploading)return;document.body.dataset.allowNavigate='false';uploadCancelled=false;isUploading=true;pendingFailures=[];updateRetryButton();setStopButtonVisible(true);let overwrite=document.getElementById('overwriteCheck').checked;let res=await runUpload(uploadFiles,overwrite,false);if(!uploadCancelled&&res.failed&&res.failed.length>0){let pass2=await runUpload(res.failed.map(f=>f.file),true,true);res.success+=pass2.success;res.skipped+=pass2.skipped;res.failed=pass2.failed}finalizeUpload(res,false)}");
        html.append("document.addEventListener('click',function(e){let anchor=e.target.closest('a');if(!anchor)return;let href=anchor.getAttribute('href');if(!href||href.startsWith('#')||anchor.target==='_blank')return;if(isUploading){e.preventDefault();window.open(anchor.href,'_blank')}},true);");
        html.append("document.querySelector('.file-list').addEventListener('contextmenu',function(e){if(e.target===this||e.target.classList.contains('empty')){showEmptyContextMenu(e)}});");
        html.append("initTheme();");
        html.append("</script>");
        
        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString());
    }

    private Response serveFile(File file, IHTTPSession session) {
        try {
            String uri = session.getUri();
            String queryString = session.getQueryParameterString();
            
            if ("preview".equals(queryString)) {
                String filename = file.getName().toLowerCase();
                String previewStyle = "<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0;padding:20px;background:#1a1a2e;color:#eee;font-family:system-ui,sans-serif}pre{background:#16213e;padding:20px;border-radius:8px;overflow-x:auto;white-space:pre-wrap;word-wrap:break-word;font-size:14px;line-height:1.5}.header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;flex-wrap:wrap;gap:10px}h2{margin:0;color:#4da6ff}.back-btn{background:#4da6ff;color:#fff;padding:8px 16px;border-radius:6px;text-decoration:none;font-size:14px}.media-container{text-align:center;background:#16213e;border-radius:8px;padding:20px}img,video,audio{max-width:100%;height:auto;border-radius:4px}iframe{border:none;border-radius:4px}</style></head><body>";
                String previewEnd = "</body></html>";
                String backLink = "<a class='back-btn' href='javascript:history.back()'>Back</a>";
                String downloadLink = "<a class='back-btn' href='" + uri + "?download' style='background:#28a745;margin-left:8px'>Download</a>";
                String headerHtml = "<div class='header'><h2>" + escapeHtml(file.getName()) + "</h2><div>" + backLink + downloadLink + "</div></div>";
                
                // Images - embed as base64 to avoid auth issues with nested requests
                if (filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".png") || 
                    filename.endsWith(".gif") || filename.endsWith(".webp") || filename.endsWith(".bmp") || 
                    filename.endsWith(".ico") || filename.endsWith(".svg")) {
                    try {
                        String mimeType = getMimeTypeForFile(filename);
                        String base64 = encodeFileToBase64(file);
                        return newFixedLengthResponse(Response.Status.OK, "text/html", 
                            previewStyle + headerHtml + "<div class='media-container'><img src='data:" + mimeType + ";base64," + base64 + "' alt='Preview'></div>" + previewEnd);
                    } catch (Exception e) {
                        return newFixedLengthResponse(Response.Status.OK, "text/html", 
                            previewStyle + headerHtml + "<p>Error loading image: " + escapeHtml(e.getMessage()) + "</p>" + previewEnd);
                    }
                }
                
                // Videos
                if (filename.endsWith(".mp4") || filename.endsWith(".webm") || filename.endsWith(".mov") || filename.endsWith(".m4v")) {
                    return newFixedLengthResponse(Response.Status.OK, "text/html", 
                        previewStyle + headerHtml + "<div class='media-container'><video controls autoplay style='max-height:80vh'><source src='" + uri + "'></video></div>" + previewEnd);
                }
                
                // Audio
                if (filename.endsWith(".mp3") || filename.endsWith(".wav") || filename.endsWith(".ogg") || 
                    filename.endsWith(".m4a") || filename.endsWith(".flac") || filename.endsWith(".aac")) {
                    return newFixedLengthResponse(Response.Status.OK, "text/html", 
                        previewStyle + headerHtml + "<div class='media-container'><audio controls autoplay style='width:100%;max-width:500px'><source src='" + uri + "'></audio></div>" + previewEnd);
                }
                
                // PDF
                if (filename.endsWith(".pdf")) {
                    return newFixedLengthResponse(Response.Status.OK, "text/html", 
                        previewStyle + headerHtml + "<iframe src='" + uri + "' style='width:100%;height:80vh'></iframe>" + previewEnd);
                }
                
                // Text-based files
                if (filename.endsWith(".txt") || filename.endsWith(".json") || filename.endsWith(".xml") || 
                    filename.endsWith(".html") || filename.endsWith(".htm") || filename.endsWith(".css") || 
                    filename.endsWith(".js") || filename.endsWith(".md") || filename.endsWith(".log") || 
                    filename.endsWith(".csv") || filename.endsWith(".py") || filename.endsWith(".java") || 
                    filename.endsWith(".kt") || filename.endsWith(".c") || filename.endsWith(".cpp") || 
                    filename.endsWith(".h") || filename.endsWith(".sh") || filename.endsWith(".yml") || 
                    filename.endsWith(".yaml") || filename.endsWith(".ini") || filename.endsWith(".conf") || 
                    filename.endsWith(".cfg") || filename.endsWith(".properties") || filename.endsWith(".sql") ||
                    filename.endsWith(".ts") || filename.endsWith(".tsx") || filename.endsWith(".jsx") ||
                    filename.endsWith(".rb") || filename.endsWith(".go") || filename.endsWith(".rs") ||
                    filename.endsWith(".swift") || filename.endsWith(".gradle") || filename.endsWith(".toml")) {
                    try {
                        String content = readTextFile(file);
                        if (content.length() > 500000) {
                            content = content.substring(0, 500000) + "\n\n... [File truncated - too large to preview] ...";
                        }
                        return newFixedLengthResponse(Response.Status.OK, "text/html", 
                            previewStyle + headerHtml + "<pre>" + escapeHtml(content) + "</pre>" + previewEnd);
                    } catch (Exception e) {
                        return newFixedLengthResponse(Response.Status.OK, "text/html", 
                            previewStyle + headerHtml + "<p>Error reading file: " + escapeHtml(e.getMessage()) + "</p>" + previewEnd);
                    }
                }
                
                // Unsupported type
                return newFixedLengthResponse(Response.Status.OK, "text/html", 
                    previewStyle + headerHtml + "<div class='media-container'><p style='color:#888'>Preview not available for this file type.</p><p><a href='" + uri + "?download' style='color:#4da6ff'>Download the file instead</a></p></div>" + previewEnd);
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

    private Response handleHead(File file, IHTTPSession session) {
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
        }
        Response response = newFixedLengthResponse(Response.Status.OK, file.isDirectory() ? "httpd/unix-directory" : getMimeTypeForFile(file.getName()), "");
        addCommonHeaders(response);
        if (!file.isDirectory()) {
            response.addHeader("Content-Length", String.valueOf(file.length()));
        }
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("Last-Modified", formatHttpDate(file.lastModified()));
        return response;
    }

    private Response handleWebDav(IHTTPSession session, File currentFile, String uri) {
        Method method = session.getMethod();
        if (method == Method.OPTIONS) {
            Response response = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
            addWebDavHeaders(response);
            response.addHeader("Allow", "OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY");
            return response;
        }
        if (method == Method.PROPFIND) {
            return handlePropfind(session, currentFile, uri);
        }
        if (method == Method.MKCOL) {
            return handleMkcol(currentFile);
        }
        if (method == Method.PUT) {
            return handlePut(session, currentFile);
        }
        if (method == Method.DELETE) {
            return handleDelete(currentFile, session);
        }
        if (method == Method.MOVE) {
            return handleMoveOrCopy(session, currentFile, false);
        }
        if (method == Method.COPY) {
            return handleMoveOrCopy(session, currentFile, true);
        }
        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed");
    }

    private Response handlePropfind(IHTTPSession session, File currentFile, String uri) {
        if (!currentFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        int depth = 1;
        String depthHeader = session.getHeaders().get("depth");
        if (depthHeader != null) {
            if ("0".equals(depthHeader)) {
                depth = 0;
            } else if ("1".equals(depthHeader)) {
                depth = 1;
            }
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        xml.append("<D:multistatus xmlns:D=\"DAV:\">");
        appendPropfindResponse(xml, currentFile, uri);
        if (depth > 0 && currentFile.isDirectory()) {
            File[] children = currentFile.listFiles();
            if (children != null) {
                java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File child : children) {
                    String childUri = uri.endsWith("/") ? uri + child.getName() : uri + "/" + child.getName();
                    appendPropfindResponse(xml, child, childUri);
                }
            }
        }
        xml.append("</D:multistatus>");
        Response response = newFixedLengthResponse(Response.Status.MULTI_STATUS, "application/xml; charset=utf-8", xml.toString());
        addWebDavHeaders(response);
        return response;
    }

    private void appendPropfindResponse(StringBuilder xml, File file, String uri) {
        String href = encodeUriForXml(file.isDirectory() && !uri.endsWith("/") ? uri + "/" : uri);
        xml.append("<D:response>");
        xml.append("<D:href>").append(href).append("</D:href>");
        xml.append("<D:propstat><D:prop>");
        xml.append("<D:displayname>").append(escapeXml(file.getName().isEmpty() ? "/" : file.getName())).append("</D:displayname>");
        xml.append("<D:resourcetype>");
        if (file.isDirectory()) {
            xml.append("<D:collection/>");
        }
        xml.append("</D:resourcetype>");
        xml.append("<D:getlastmodified>").append(formatHttpDate(file.lastModified())).append("</D:getlastmodified>");
        if (!file.isDirectory()) {
            xml.append("<D:getcontentlength>").append(file.length()).append("</D:getcontentlength>");
            xml.append("<D:getcontenttype>").append(escapeXml(getMimeTypeForFile(file.getName()))).append("</D:getcontenttype>");
        }
        xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>");
        xml.append("</D:response>");
    }

    private Response handleMkcol(File currentFile) {
        if (currentFile.exists()) {
            Response response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Already exists");
            addWebDavHeaders(response);
            return response;
        }
        if (currentFile.mkdirs()) {
            Response response = newFixedLengthResponse(Response.Status.CREATED, "text/plain", "");
            addWebDavHeaders(response);
            return response;
        }
        Response response = newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot create folder");
        addWebDavHeaders(response);
        return response;
    }

    private Response handlePut(IHTTPSession session, File currentFile) {
        long t0 = System.currentTimeMillis();
        String label = currentFile.getName();
        try {
            File parent = currentFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                diag("PUT mkdirs failed: " + parent.getAbsolutePath());
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot create parent directory: " + parent.getAbsolutePath());
            }
            long expectedLen = -1;
            String lenHeader = session.getHeaders().get("content-length");
            if (lenHeader != null) {
                try { expectedLen = Long.parseLong(lenHeader.trim()); } catch (NumberFormatException ignored) {}
            }
            boolean existed = currentFile.exists();
            InputStream body = session.getInputStream();
            long written = 0;
            byte[] buf = new byte[64 * 1024];
            try (FileOutputStream fos = new FileOutputStream(currentFile)) {
                while (true) {
                    int toRead = buf.length;
                    if (expectedLen > 0) {
                        long remaining = expectedLen - written;
                        if (remaining <= 0) break;
                        if (remaining < toRead) toRead = (int) remaining;
                    }
                    int n;
                    try {
                        n = body.read(buf, 0, toRead);
                    } catch (java.io.IOException ioe) {
                        diag("PUT read error at " + written + "/" + expectedLen + " for " + label + ": " + ioe.getMessage());
                        throw ioe;
                    }
                    if (n < 0) break;
                    if (n == 0) continue;
                    try {
                        fos.write(buf, 0, n);
                    } catch (java.io.IOException ioe) {
                        diag("PUT write error at " + written + " for " + currentFile.getAbsolutePath() + ": " + ioe.getMessage());
                        throw ioe;
                    }
                    written += n;
                }
                fos.getFD().sync();
            }
            if (expectedLen > 0 && written != expectedLen) {
                diag("PUT short body " + written + "/" + expectedLen + " for " + label);
                if (currentFile.exists()) currentFile.delete();
                Response response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain",
                        "Short body: " + written + "/" + expectedLen + " bytes");
                addWebDavHeaders(response);
                return response;
            }
            try {
                MediaScannerConnection.scanFile(context, new String[]{currentFile.getAbsolutePath()}, null, null);
            } catch (Throwable ignore) { }
            if (eventListener != null) {
                eventListener.onFileUploaded(currentFile.getName(), getClientIp(session));
            }
            long dt = System.currentTimeMillis() - t0;
            diag("PUT OK " + label + " " + written + "B in " + dt + "ms");
            Response response = newFixedLengthResponse(existed ? Response.Status.OK : Response.Status.CREATED, "text/plain", "");
            String relativeSavedPath = rootDir.toURI().relativize(currentFile.toURI()).getPath();
            if (relativeSavedPath != null && !relativeSavedPath.isEmpty()) {
                response.addHeader("X-Aroma-Saved-As", relativeSavedPath);
            }
            addWebDavHeaders(response);
            return response;
        } catch (Exception e) {
            String cls = e.getClass().getSimpleName();
            String msg = e.getMessage() == null ? cls : cls + ": " + e.getMessage();
            diag("PUT FAIL " + label + " -> " + msg);
            Log.e("AROMA", "WebDAV PUT failed for " + currentFile.getAbsolutePath() + ": " + msg, e);
            if (currentFile.exists()) {
                try { currentFile.delete(); } catch (Exception ignored) {}
            }
            Response response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", msg);
            addWebDavHeaders(response);
            return response;
        }
    }

    private Response handleDelete(File currentFile, IHTTPSession session) {
        if (!currentFile.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        boolean deleted = deleteRecursively(currentFile);
        if (deleted && eventListener != null) {
            eventListener.onFileDeleted(currentFile.getName(), getClientIp(session));
        }
        Response response = newFixedLengthResponse(deleted ? Response.Status.NO_CONTENT : Response.Status.FORBIDDEN, "text/plain", "");
        addWebDavHeaders(response);
        return response;
    }

    private Response handleMoveOrCopy(IHTTPSession session, File source, boolean copyOnly) {
        if (!source.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        String destinationHeader = session.getHeaders().get("destination");
        if (destinationHeader == null || destinationHeader.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing destination");
        }
        String destinationPath = normalizeDestinationPath(destinationHeader);
        if (destinationPath == null || destinationPath.contains("..")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid destination");
        }
        File destination = destinationPath.equals("/") ? rootDir : new File(rootDir, destinationPath.substring(1));
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot create destination directory");
        }
        boolean existed = destination.exists();
        try {
            if (source.isDirectory()) {
                copyDirectory(source, destination);
                if (!copyOnly) {
                    deleteRecursively(source);
                }
            } else {
                copyFile(source, destination);
                if (!copyOnly && !source.delete()) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot remove source");
                }
            }
            MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
            Response response = newFixedLengthResponse(existed ? Response.Status.NO_CONTENT : Response.Status.CREATED, "text/plain", "");
            addWebDavHeaders(response);
            return response;
        } catch (IOException e) {
            Log.e("AROMA", "WebDAV move/copy failed: " + e.getMessage());
            Response response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
            addWebDavHeaders(response);
            return response;
        }
    }

    private void copyDirectory(File source, File destination) throws IOException {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Cannot create directory");
        }
        File[] children = source.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File target = new File(destination, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, target);
            } else {
                copyFile(child, target);
            }
        }
    }

    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(destination);
             FileChannel inCh = fis.getChannel();
             FileChannel outCh = fos.getChannel()) {
            long size = inCh.size();
            long pos = 0;
            while (pos < size) {
                long written = inCh.transferTo(pos, size - pos, outCh);
                if (written <= 0) {
                    break;
                }
                pos += written;
            }
            outCh.force(true);
        }
    }

    private String normalizeDestinationPath(String destinationHeader) {
        try {
            java.net.URI destinationUri = java.net.URI.create(destinationHeader);
            String path = destinationUri.getPath();
            if (path == null || path.isEmpty()) {
                return "/";
            }
            return path;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void addCommonHeaders(Response response) {
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("MS-Author-Via", "DAV");
    }

    private void addWebDavHeaders(Response response) {
        addCommonHeaders(response);
        response.addHeader("DAV", "1");
        response.addHeader("Allow", "OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY");
    }

    private String formatHttpDate(long timeMillis) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(timeMillis));
    }

    private String encodeUriForXml(String uri) {
        return escapeXml(uri.replace(" ", "%20"));
    }

    private String escapeXml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
    
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String encodeFileToBase64(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] bytes = new byte[(int) file.length()];
        fis.read(bytes);
        fis.close();
        return Base64.getEncoder().encodeToString(bytes);
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
        
        String queryString = session.getQueryParameterString();
        if ("downloadzip".equals(queryString)) {
            return handleDownloadZip(session, currentDir);
        }
        
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
            if (failCount == 0) {
                return redirectResponse(uri);
            }
            String title = "Delete Completed with Errors";
            return buildResultResponse(title, "Deleted: " + successCount + ", Failed: " + failCount, results.toString(), uri, false);
        }

        if (params.containsKey("action") && "create_folder".equals(params.get("action").get(0))) {
            List<String> folderNames = params.get("folder_name");
            if (folderNames == null || folderNames.isEmpty() || folderNames.get(0).trim().isEmpty()) {
                return buildErrorResponse("Create Folder Failed", "Please enter a folder name.", uri);
            }
            String folderName = folderNames.get(0).trim();
            if (folderName.contains("/") || folderName.contains("\\") || folderName.equals(".") || folderName.equals("..")) {
                return buildErrorResponse("Create Folder Failed", "Invalid folder name. Cannot contain / \\ or be . or ..", uri);
            }
            File newFolder = new File(currentDir, folderName);
            if (newFolder.exists()) {
                return buildErrorResponse("Create Folder Failed", "A file or folder named '" + folderName + "' already exists.", uri);
            }
            if (newFolder.mkdir()) {
                if (eventListener != null) {
                    eventListener.onFolderCreated(folderName, getClientIp(session));
                }
                return redirectResponse(uri);
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
            if (newName.contains("/") || newName.contains("\\") || newName.equals(".") || newName.equals("..")) {
                return buildErrorResponse("Rename Failed", "Invalid new name. Cannot contain / \\ or be . or ..", uri);
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
                return redirectResponse(uri);
            } else {
                return buildErrorResponse("Rename Failed", "Could not rename '" + selected + "'. File may be in use or permission denied.", uri);
            }
        }

        if (params.containsKey("action") && "move".equals(params.get("action").get(0))) {
            List<String> selected = params.get("selected");
            List<String> targetFolders = params.get("target_folder");
            if (selected == null || selected.isEmpty() || targetFolders == null || targetFolders.isEmpty()) {
                return buildErrorResponse("Move Failed", "Missing source or destination.", uri);
            }
            String targetFolder = targetFolders.get(0);
            if (targetFolder.equals(".") || targetFolder.equals("..") || targetFolder.contains("\\") || targetFolder.startsWith("/")) {
                return buildErrorResponse("Move Failed", "Invalid target folder.", uri);
            }
            File destinationFolder = new File(currentDir, targetFolder);
            if (!destinationFolder.exists() || !destinationFolder.isDirectory()) {
                return buildErrorResponse("Move Failed", "Target folder not found.", uri);
            }
            StringBuilder results = new StringBuilder();
            int successCount = 0;
            int failCount = 0;
            for (String name : selected) {
                File source = new File(currentDir, name);
                File destination = new File(destinationFolder, source.getName());
                if (!source.exists()) {
                    results.append("Not found: ").append(name).append("\n");
                    failCount++;
                    continue;
                }
                if (destination.exists()) {
                    results.append("Already exists in target: ").append(name).append("\n");
                    failCount++;
                    continue;
                }
                if (source.renameTo(destination)) {
                    results.append("Moved: ").append(name).append("\n");
                    successCount++;
                } else {
                    results.append("Failed to move: ").append(name).append("\n");
                    failCount++;
                }
            }
            if (failCount == 0) {
                return redirectResponse(uri);
            }
            return buildResultResponse("Move Completed", "Moved: " + successCount + ", Failed: " + failCount, results.toString(), uri, false);
        }

        // Handle upload (supports multiple files and folder uploads)
        List<String> uploadedFileNames = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        long totalSize = 0;
        
        // Check if overwrite mode is enabled
        List<String> overwriteValues = params.get("overwrite");
        boolean overwriteMode = overwriteValues != null && !overwriteValues.isEmpty() && "true".equals(overwriteValues.get(0));
        
        for (String key : files.keySet()) {
            if (!key.startsWith("uploadedFile")) continue;
            
            String tempLocation = files.get(key);
            // Try to get the original path from the separate form field (better UTF-8 handling)
            List<String> originalPathValues = session.getParameters().get("originalPath");
            String originalName = (originalPathValues != null && !originalPathValues.isEmpty()) 
                ? originalPathValues.get(0) 
                : key;
            if (originalName == null || originalName.isEmpty()) continue;
            
            // Skip hidden/system files
            String fileName = originalName.contains("/") ? originalName.substring(originalName.lastIndexOf("/") + 1) : originalName;
            if (fileName.startsWith(".") || fileName.equals("Thumbs.db") || fileName.equals("desktop.ini")) {
                Log.d("AROMA", "Skipping hidden/system file: " + originalName);
                new File(tempLocation).delete();
                continue;
            }
            
            // Handle folder structure (webkitRelativePath includes folder/file.ext)
            String targetPath = originalName.replace("\\", "/");
            if (targetPath.startsWith("/") || targetPath.equals(".") || targetPath.equals("..")
                    || targetPath.contains("/../") || targetPath.startsWith("../") || targetPath.endsWith("/..")) {
                failedFiles.add(originalName + " (invalid path)");
                continue;
            }
            
            String safeTargetPath = sanitizeStoragePath(targetPath);
            if (!targetPath.equals(safeTargetPath)) {
                diag("POST sanitized path '" + targetPath + "' -> '" + safeTargetPath + "'");
            }

            File tempFile = new File(tempLocation);
            File targetFile = new File(currentDir, safeTargetPath);
            
            // Create parent directories for folder uploads
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.equals(currentDir)) {
                if (!parentDir.exists() && !parentDir.mkdirs()) {
                    Log.e("AROMA", "Cannot create directory for: " + originalName);
                    failedFiles.add(originalName + " (cannot create directory)");
                    tempFile.delete();
                    continue;
                }
            }
            
            if (targetFile.exists()) {
                if (overwriteMode) {
                    Log.d("AROMA", "Overwriting existing file: " + originalName);
                    if (!targetFile.delete()) {
                        Log.e("AROMA", "Cannot delete existing file for overwrite: " + originalName);
                        failedFiles.add(originalName + " (cannot overwrite)");
                        tempFile.delete();
                        continue;
                    }
                } else {
                    Log.d("AROMA", "File already exists: " + originalName);
                    failedFiles.add(originalName + " (already exists)");
                    tempFile.delete();
                    continue;
                }
            }
            
            // Verify temp file
            if (!tempFile.exists() || !tempFile.canRead()) {
                Log.e("AROMA", "Temp file issue for " + originalName + ": exists=" + tempFile.exists() + ", canRead=" + tempFile.canRead());
                failedFiles.add(originalName + " (temp file error)");
                continue;
            }
            
            Log.d("AROMA", "Uploading: " + originalName + " -> " + targetFile.getAbsolutePath());
            boolean success = false;
            try (FileInputStream fis = new FileInputStream(tempFile);
                 FileOutputStream fos = new FileOutputStream(targetFile);
                 FileChannel inCh = fis.getChannel();
                 FileChannel outCh = fos.getChannel()) {
                long size = inCh.size();
                long pos = 0;
                while (pos < size) {
                    long n = inCh.transferTo(pos, size - pos, outCh);
                    if (n <= 0) break;
                    pos += n;
                }
                if (pos != size) {
                    throw new IOException("Short write: " + pos + "/" + size);
                }
                outCh.force(true);
                success = true;
                totalSize += targetFile.length();
            } catch (Exception e) {
                Log.e("AROMA", "Copy failed for " + originalName + ": " + e.getMessage());
                String errorMessage = e.getMessage() == null ? "copy failed" : e.getMessage();
                if (errorMessage.toLowerCase().contains("space") || errorMessage.toLowerCase().contains("storage")) {
                    errorMessage = "storage full or unavailable";
                }
                failedFiles.add(originalName + " (" + errorMessage + ")");
                if (targetFile.exists()) targetFile.delete();
            } finally {
                if (tempFile.exists()) tempFile.delete();
            }
            
            if (success) {
                uploadedFileNames.add(targetPath.equals(safeTargetPath) ? originalName : originalName + " → " + safeTargetPath);
                if (eventListener != null) {
                    eventListener.onFileUploaded(originalName, getClientIp(session));
                }
                MediaScannerConnection.scanFile(context, new String[]{targetFile.getAbsolutePath()}, null, null);
            } else if (!failedFiles.contains(originalName)) {
                failedFiles.add(originalName + " (copy failed)");
            }
        }
        
        if (!uploadedFileNames.isEmpty() || !failedFiles.isEmpty()) {
            int successCount = uploadedFileNames.size();
            int failCount = failedFiles.size();
            
            if (failCount == 0) {
                String sizeStr = formatFileSize(totalSize);
                String fileList = successCount <= 5 
                    ? String.join(", ", uploadedFileNames) 
                    : uploadedFileNames.subList(0, 5).toString().replace("[", "").replace("]", "") + " ...";
                String successHtml = "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
                        "<meta http-equiv='refresh' content='3;url=" + uri + "'>" +
                        "<style>" +
                        "body{font-family:system-ui,sans-serif;background:#d4edda;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}" +
                        ".card{background:#fff;border-radius:12px;padding:40px;box-shadow:0 4px 20px rgba(0,0,0,0.1);text-align:center;max-width:500px}" +
                        ".icon{font-size:64px;margin-bottom:16px}" +
                        ".title{color:#155724;font-size:24px;margin-bottom:8px}" +
                        ".count{color:#333;font-size:18px;margin:8px 0}" +
                        ".filename{color:#333;font-size:14px;word-break:break-all;background:#f8f9fa;padding:12px;border-radius:8px;margin:16px 0;max-height:150px;overflow-y:auto}" +
                        ".size{color:#666;font-size:14px}" +
                        ".redirect{color:#666;font-size:12px;margin-top:20px}" +
                        "</style></head><body>" +
                        "<div class='card'>" +
                        "<div class='icon'>&#10004;</div>" +
                        "<div class='title'>Upload Successful!</div>" +
                        "<div class='count'>" + successCount + " file(s) uploaded</div>" +
                        "<div class='filename'>" + escapeHtml(fileList) + "</div>" +
                        "<div class='size'>Total size: " + sizeStr + "</div>" +
                        "<div class='redirect'>Redirecting in 3 seconds...</div>" +
                        "</div></body></html>";
                return newFixedLengthResponse(Response.Status.OK, "text/html", successHtml);
            } else {
                String details = "Uploaded: " + successCount + "\nFailed: " + failCount + "\n\n" + String.join("\n", failedFiles);
                return buildResultResponse("Upload Completed with Errors", 
                    successCount + " succeeded, " + failCount + " failed", details, uri, false);
            }
        }

        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid POST request");
    }

    public static String getMimeTypeForFile(String filename) {
        String name = filename.toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static class RootTempFileManagerFactory implements TempFileManagerFactory {
        private final File rootDir;

        RootTempFileManagerFactory(File rootDir) {
            this.rootDir = rootDir;
        }

        @Override
        public TempFileManager create() {
            return new RootTempFileManager(rootDir);
        }
    }

    private static class RootTempFileManager implements TempFileManager {
        private final List<TempFile> tempFiles = new ArrayList<>();
        private final File tempDir;

        RootTempFileManager(File rootDir) {
            this.tempDir = new File(rootDir, ".aroma-upload-temp");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
        }

        @Override
        public void clear() {
            for (TempFile tempFile : tempFiles) {
                try {
                    tempFile.delete();
                } catch (Exception ignored) {
                }
            }
            tempFiles.clear();
        }

        @Override
        public TempFile createTempFile(String filenameHint) throws Exception {
            File tempFile = File.createTempFile("upload_", ".tmp", tempDir);
            TempFile wrapper = new RootTempFile(tempFile);
            tempFiles.add(wrapper);
            return wrapper;
        }
    }

    private static class RootTempFile implements TempFile {
        private final File file;

        RootTempFile(File file) {
            this.file = file;
        }

        @Override
        public void delete() throws Exception {
            if (file.exists()) {
                file.delete();
            }
        }

        @Override
        public String getName() {
            return file.getAbsolutePath();
        }

        @Override
        public OutputStream open() throws Exception {
            return new FileOutputStream(file);
        }
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

    private Response redirectResponse(String uri) {
        Response response = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "");
        response.addHeader("Location", uri);
        return response;
    }

    private Response handleDownloadZip(IHTTPSession session, File currentDir) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, List<String>> params = session.getParameters();
            List<String> selected = params.get("selected");
            
            if (selected == null || selected.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No files selected");
            }
            
            File tempZip = File.createTempFile("aroma_download_", ".zip", context.getCacheDir());
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new FileOutputStream(tempZip));
            
            for (String name : selected) {
                File file = new File(currentDir, name);
                if (file.exists() && file.isFile()) {
                    java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
                    entry.setTime(file.lastModified());
                    zos.putNextEntry(entry);
                    FileInputStream fis = new FileInputStream(file);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                    fis.close();
                    zos.closeEntry();
                } else if (file.exists() && file.isDirectory()) {
                    addFolderToZip(zos, file, name);
                }
            }
            
            zos.close();
            
            String zipName = "download_" + System.currentTimeMillis() + ".zip";
            final File tempFile = tempZip;
            FileInputStream zipStream = new FileInputStream(tempZip) {
                @Override
                public void close() throws IOException {
                    super.close();
                    tempFile.delete();
                    Log.d("AROMA", "Temp ZIP deleted: " + tempFile.getName());
                }
            };
            long zipSize = tempZip.length();
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/zip", zipStream, zipSize);
            response.addHeader("Content-Disposition", "attachment; filename=\"" + zipName + "\"");
            
            return response;
            
        } catch (Exception e) {
            Log.e("AROMA", "ZIP creation failed: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to create ZIP: " + e.getMessage());
        }
    }
    
    private void addFolderToZip(java.util.zip.ZipOutputStream zos, File folder, String parentPath) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            String entryPath = parentPath + "/" + file.getName();
            if (file.isDirectory()) {
                addFolderToZip(zos, file, entryPath);
            } else {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(entryPath);
                entry.setTime(file.lastModified());
                zos.putNextEntry(entry);
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
                fis.close();
                zos.closeEntry();
            }
        }
    }

    private Response serveTerminal() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>");
        html.append("<title>AROMA Terminal</title>");
        html.append("<style>");
        html.append("*{box-sizing:border-box;margin:0;padding:0}");
        html.append("body{font-family:'Courier New',monospace;background:#0d0d0d;color:#00ff00;min-height:100vh;display:flex;flex-direction:column}");
        html.append(".header{background:#1a1a1a;padding:15px 20px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #333}");
        html.append(".header h1{font-size:1.2em;color:#00ff00}");
        html.append(".back-btn{background:#333;color:#00ff00;padding:8px 16px;border-radius:4px;text-decoration:none;font-size:0.9em;border:1px solid #00ff00}");
        html.append(".back-btn:hover{background:#00ff00;color:#000}");
        html.append(".terminal{flex:1;padding:20px;overflow-y:auto;font-size:14px;line-height:1.6}");
        html.append(".output{white-space:pre-wrap;word-break:break-all}");
        html.append(".output .cmd{color:#00ff00}");
        html.append(".output .result{color:#ccc}");
        html.append(".output .error{color:#ff6b6b}");
        html.append(".output .info{color:#4da6ff}");
        html.append(".input-area{background:#1a1a1a;padding:15px 20px;border-top:1px solid #333;display:flex;gap:10px}");
        html.append(".prompt{color:#00ff00;flex-shrink:0}");
        html.append("#cmdInput{flex:1;background:transparent;border:none;color:#00ff00;font-family:inherit;font-size:14px;outline:none}");
        html.append(".btn{background:#00ff00;color:#000;padding:8px 16px;border:none;border-radius:4px;cursor:pointer;font-family:inherit}");
        html.append(".btn:hover{background:#00cc00}");
        html.append(".btn:disabled{background:#333;color:#666;cursor:not-allowed}");
        html.append("</style></head><body>");
        html.append("<div class='header'><h1>AROMA Terminal</h1><a href='/' class='back-btn'>Back to Files</a></div>");
        html.append("<div class='terminal' id='terminal'><div class='output' id='output'><span class='info'>Welcome to AROMA Terminal</span>\n<span class='info'>Type commands to execute on the Android device.</span>\n<span class='info'>Type 'help' for available commands.</span>\n\n</div></div>");
        html.append("<div class='input-area'><span class='prompt'>$</span><input type='text' id='cmdInput' placeholder='Enter command...' autofocus><button class='btn' id='runBtn' onclick='runCommand()'>Run</button></div>");
        html.append("<script>");
        html.append("let history=[];let historyIndex=-1;");
        html.append("const input=document.getElementById('cmdInput');const output=document.getElementById('output');const terminal=document.getElementById('terminal');const runBtn=document.getElementById('runBtn');");
        html.append("input.addEventListener('keydown',function(e){if(e.key==='Enter'){runCommand()}else if(e.key==='ArrowUp'){e.preventDefault();if(historyIndex<history.length-1){historyIndex++;input.value=history[history.length-1-historyIndex]}}else if(e.key==='ArrowDown'){e.preventDefault();if(historyIndex>0){historyIndex--;input.value=history[history.length-1-historyIndex]}else{historyIndex=-1;input.value=''}}});");
        html.append("async function runCommand(){let cmd=input.value.trim();if(!cmd)return;history.push(cmd);historyIndex=-1;input.value='';runBtn.disabled=true;output.innerHTML+='<span class=\"cmd\">$ '+escapeHtml(cmd)+'</span>\\n';if(cmd==='clear'){output.innerHTML='';runBtn.disabled=false;return}if(cmd==='help'){output.innerHTML+='<span class=\"info\">Available commands:\\n  ls, pwd, cd, cat, echo, whoami, id, df, free, ps, top -n 1, uname -a\\n  getprop (Android properties)\\n  pm list packages (list apps)\\n  dumpsys battery (battery info)\\n  clear - clear screen\\n  Any other shell command supported by the device</span>\\n\\n';runBtn.disabled=false;scrollToBottom();return}try{let res=await fetch('/api/exec',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({cmd:cmd})});let data=await res.json();if(data.stdout){output.innerHTML+='<span class=\"result\">'+escapeHtml(data.stdout)+'</span>'}if(data.stderr){output.innerHTML+='<span class=\"error\">'+escapeHtml(data.stderr)+'</span>'}if(data.error){output.innerHTML+='<span class=\"error\">Error: '+escapeHtml(data.error)+'</span>\\n'}}catch(e){output.innerHTML+='<span class=\"error\">Request failed: '+escapeHtml(e.message)+'</span>\\n'}output.innerHTML+='\\n';runBtn.disabled=false;scrollToBottom()}");
        html.append("function scrollToBottom(){terminal.scrollTop=terminal.scrollHeight}");
        html.append("function escapeHtml(t){return t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}");
        html.append("</script>");
        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString());
    }

    private Response handleExec(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                return jsonResponse("{\"error\":\"No command provided\"}");
            }
            
            String cmd = "";
            if (body.contains("\"cmd\"")) {
                int start = body.indexOf("\"cmd\"");
                int colonPos = body.indexOf(":", start);
                int valueStart = body.indexOf("\"", colonPos + 1) + 1;
                int valueEnd = body.indexOf("\"", valueStart);
                if (valueStart > 0 && valueEnd > valueStart) {
                    cmd = body.substring(valueStart, valueEnd);
                }
            }
            
            if (cmd.isEmpty()) {
                return jsonResponse("{\"error\":\"Invalid command format\"}");
            }
            
            String[] dangerousCommands = {"rm -rf /", "mkfs", "dd if=", "> /dev/", "reboot", "shutdown", "halt", "poweroff"};
            for (String dangerous : dangerousCommands) {
                if (cmd.toLowerCase().contains(dangerous.toLowerCase())) {
                    return jsonResponse("{\"error\":\"Command blocked for safety\"}");
                }
            }
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            
            Thread outThread = new Thread(() -> {
                try {
                    int ch;
                    while ((ch = process.getInputStream().read()) != -1) {
                        stdout.append((char) ch);
                    }
                } catch (IOException ignored) {}
            });
            
            Thread errThread = new Thread(() -> {
                try {
                    int ch;
                    while ((ch = process.getErrorStream().read()) != -1) {
                        stderr.append((char) ch);
                    }
                } catch (IOException ignored) {}
            });
            
            outThread.start();
            errThread.start();
            
            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return jsonResponse("{\"error\":\"Command timed out (30s limit)\"}");
            }
            
            outThread.join(1000);
            errThread.join(1000);
            
            String result = "{\"stdout\":\"" + escapeJson(stdout.toString()) + "\",\"stderr\":\"" + escapeJson(stderr.toString()) + "\",\"exitCode\":" + process.exitValue() + "}";
            return jsonResponse(result);
            
        } catch (Exception e) {
            return jsonResponse("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }
    
    private Response jsonResponse(String json) {
        Response response = newFixedLengthResponse(Response.Status.OK, "application/json", json);
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }
    
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
