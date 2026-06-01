package com.example.aroma;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Protocol constants and JSON helpers for LocalSend v2.
 * Reference: https://github.com/localsend/localsend/blob/main/PROTOCOL.md
 */
public class LocalSendProtocol {

    // ---- Network constants ----
    public static final String MULTICAST_GROUP = "224.0.0.167";
    public static final int    PORT            = 53317;
    public static final String API_BASE        = "/api/localsend/v2";
    public static final String EP_INFO         = API_BASE + "/info";
    public static final String EP_REGISTER     = API_BASE + "/register";
    public static final String EP_SEND_REQUEST = API_BASE + "/send-request";
    public static final String EP_SEND         = API_BASE + "/send";
    public static final String EP_UPLOAD       = API_BASE + "/upload"; // v2.1 canonical name
    public static final String EP_CANCEL       = API_BASE + "/cancel";
    public static final String EP_PREPARE_UPLOAD = API_BASE + "/prepare-upload"; // legacy alias

    // ---- Device type ----
    public static final String TYPE_MOBILE  = "mobile";
    public static final String TYPE_DESKTOP = "desktop";
    public static final String TYPE_TABLET  = "tablet";

    // ---- Announcement type ----
    public static final String ANN_ANNOUNCE = "announce";
    public static final String ANN_GOODBYE  = "goodbye";

    /** File descriptor sent in a send-request. */
    public static class FileInfo {
        public final String id;
        public final String fileName;
        public final long   size;
        public final String mimeType;

        public FileInfo(String id, String fileName, long size, String mimeType) {
            this.id = id;
            this.fileName = fileName;
            this.size = size;
            this.mimeType = mimeType != null ? mimeType : "application/octet-stream";
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("fileName", fileName);
            o.put("size", size);
            o.put("fileType", mimeType);
            return o;
        }
    }

    // ---- Builders ----

    /** Build the JSON body for GET /info and UDP announcements. */
    public static JSONObject buildInfoJson(String alias, String fingerprint,
                                            String deviceModel, String deviceType,
                                            int port) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("alias", alias);
        o.put("version", "2.1");
        o.put("deviceModel", deviceModel);
        o.put("deviceType", deviceType);
        o.put("fingerprint", fingerprint);
        o.put("port", port);
        o.put("protocol", "https");
        o.put("download", false);
        o.put("announce", true);
        return o;
    }

    /** Build UDP announce packet (adds "announcement" type field). */
    public static JSONObject buildAnnounceJson(String alias, String fingerprint,
                                               String deviceModel, String deviceType,
                                               int port) throws JSONException {
        JSONObject o = buildInfoJson(alias, fingerprint, deviceModel, deviceType, port);
        o.put("announcement", true);
        return o;
    }

    /** Build UDP goodbye packet (port=0 signals departure). */
    public static JSONObject buildGoodbyeJson(String alias, String fingerprint,
                                               String deviceModel, String deviceType) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("alias", alias);
        o.put("version", "2.1");
        o.put("deviceModel", deviceModel);
        o.put("deviceType", deviceType);
        o.put("fingerprint", fingerprint);
        o.put("port", 0);
        o.put("protocol", "https");
        o.put("announcement", true);
        return o;
    }

    /** Parse a peer info/announce JSON into a LocalSendPeer. ip comes from the network layer. */
    public static LocalSendPeer parsePeer(JSONObject o, String fromIp) {
        try {
            String alias       = o.optString("alias", fromIp);
            String fingerprint = o.optString("fingerprint", "");
            int    port        = o.optInt("port", PORT);
            String model       = o.optString("deviceModel", "");
            String type        = o.optString("deviceType", TYPE_MOBILE);
            if (port <= 0) return null; // goodbye packet
            return new LocalSendPeer(alias, fingerprint, fromIp, port, model, type);
        } catch (Exception e) {
            return null;
        }
    }

    /** Build the send-request body (map of fileId → FileInfo). */
    public static JSONObject buildSendRequest(Map<String, FileInfo> files) throws JSONException {
        JSONObject filesObj = new JSONObject();
        for (Map.Entry<String, FileInfo> e : files.entrySet()) {
            filesObj.put(e.getKey(), e.getValue().toJson());
        }
        JSONObject req = new JSONObject();
        req.put("info", new JSONObject()); // sender info filled by caller
        req.put("files", filesObj);
        return req;
    }

    /** Build the send-request body with sender identity embedded. */
    public static JSONObject buildSendRequest(String senderAlias, String senderFingerprint,
                                               String deviceModel, String deviceType,
                                               Map<String, FileInfo> files) throws JSONException {
        JSONObject info = new JSONObject();
        info.put("alias", senderAlias);
        info.put("version", "2.1");
        info.put("deviceModel", deviceModel);
        info.put("deviceType", deviceType);
        info.put("fingerprint", senderFingerprint);

        JSONObject filesObj = new JSONObject();
        for (Map.Entry<String, FileInfo> e : files.entrySet()) {
            filesObj.put(e.getKey(), e.getValue().toJson());
        }

        JSONObject req = new JSONObject();
        req.put("info", info);
        req.put("files", filesObj);
        return req;
    }
}
