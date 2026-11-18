package com.gptdrive.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

final class ChatGptResponseParser {
    private ChatGptResponseParser() {}

    static ChatGptModels.LibraryPage decodeLibraryPage(String raw) {
        LinkedHashMap<String, ChatGptModels.RemoteFile> dedupe = new LinkedHashMap<>();
        Object root = parse(raw);
        walk(root, dedupe, 0);
        ChatGptModels.LibraryPageMeta meta = new ChatGptModels.LibraryPageMeta();
        if (root instanceof JSONObject) {
            inspectMeta((JSONObject) root, meta, 0);
            if (meta.nextCursor.isEmpty()) meta.nextCursor = clean(((JSONObject) root).optString("cursor", ""));
            if (!meta.nextCursor.isEmpty()) meta.hasMore = true;
        }
        return new ChatGptModels.LibraryPage(new ArrayList<>(dedupe.values()), meta);
    }

    static ChatGptModels.RemoteFile imageToRemote(JSONObject o) {
        if (o == null) return null;
        String raw = ChatGptModels.first(o.optString("download_url", ""), o.optString("url", ""), o.optString("asset_pointer", ""));
        String fileId = ChatGptModels.first(o.optString("file_id", ""), ChatGptModels.extractFileId(raw), ChatGptModels.extractFileId(o.optString("id", "")));
        String id = ChatGptModels.first(fileId, o.optString("id", ""));
        if (id.isEmpty() && raw.isEmpty()) return null;
        ChatGptModels.RemoteFile f = new ChatGptModels.RemoteFile();
        f.fileId = ChatGptModels.first(fileId, ChatGptModels.extractFileId(id));
        f.name = cleanName(ChatGptModels.first(o.optString("title", ""), o.optString("prompt", ""), "ChatGPT image"));
        if (!ChatGptModels.isImageName(f.name)) f.name += ".png";
        f.mimeType = ChatGptModels.first(o.optString("mime_type", ""), o.optString("content_type", ""), "image/png");
        f.conversationId = clean(o.optString("conversation_id", ""));
        f.messageId = clean(o.optString("message_id", ""));
        f.assetPointer = clean(o.optString("asset_pointer", ""));
        f.rawUrl = raw;
        f.thumbnailUrl = firstStringDeep(o, "thumbnail_url", "thumbnailUrl", "preview_url",
                "previewUrl", "small_url", "smallUrl", "thumb_url", "thumbUrl", "thumbnail_link",
                "preview_image_url", "previewImageUrl", "thumbnail");
        f.width = o.optInt("width", 0);
        f.height = o.optInt("height", 0);
        f.createdAt = parseTime(o.opt("created_at"));
        f.updatedAt = f.createdAt;
        f.sourceFlags = ChatGptModels.SRC_IMAGE_GEN;
        return f;
    }

    static ChatGptModels.RemoteFile mapToRemote(JSONObject o) {
        if (o == null) return null;
        String rawUrl = firstStringDeep(o, "download_url", "downloadUrl", "url", "asset_pointer", "assetPointer");
        String fileId = ChatGptModels.first(firstStringDeep(o, "file_id", "fileId"), ChatGptModels.extractFileId(rawUrl));
        String objectId = firstStringDeep(o, "id");
        String libraryFileId = firstStringDeep(o, "library_file_id", "libraryFileId", "node_id", "nodeId");
        // Current Library node payloads may expose the mutable library identifier as the
        // wrapper/node id while the underlying content keeps its own file_id. Preserve both.
        if (libraryFileId.isEmpty() && !fileId.isEmpty() && !objectId.isEmpty() && !objectId.equals(fileId))
            libraryFileId = objectId;
        String id = ChatGptModels.first(fileId, libraryFileId, objectId);
        String name = firstStringDeep(o, "file_name", "filename", "fileName", "name", "title");
        if (looksInternalStorageName(name)) return null;
        if (fileId.isEmpty() && (id.isEmpty() || name.isEmpty())) return null;
        String lower = id.toLowerCase(Locale.ROOT);
        if (fileId.isEmpty() && libraryFileId.isEmpty() && !lower.startsWith("file_") && !lower.startsWith("file-")) return null;
        if (fileId.isEmpty()) fileId = ChatGptModels.extractFileId(id);
        if (fileId.isEmpty() && libraryFileId.isEmpty()) return null;

        ChatGptModels.RemoteFile f = new ChatGptModels.RemoteFile();
        f.fileId = fileId;
        f.libraryFileId = libraryFileId;
        f.name = cleanName(name.isEmpty() ? "ChatGPT file" : name);
        f.mimeType = firstStringDeep(o, "mime_type", "mimeType", "content_type", "contentType", "type");
        f.sizeBytes = firstLongDeep(o, "file_size_bytes", "size_bytes", "size", "file_size", "fileSizeBytes", "fileSize");
        f.conversationId = firstStringDeep(o, "conversation_id", "conversationId");
        f.messageId = firstStringDeep(o, "message_id", "messageId");
        f.assetPointer = firstStringDeep(o, "asset_pointer", "assetPointer");
        f.rawUrl = rawUrl;
        f.thumbnailUrl = firstStringDeep(o, "thumbnail_url", "thumbnailUrl", "preview_url",
                "previewUrl", "small_url", "smallUrl", "thumb_url", "thumbUrl", "thumbnail_link",
                "preview_image_url", "previewImageUrl", "thumbnail");
        f.width = (int) Math.min(Integer.MAX_VALUE, firstLongDeep(o, "width", "image_width", "imageWidth"));
        f.height = (int) Math.min(Integer.MAX_VALUE, firstLongDeep(o, "height", "image_height", "imageHeight"));
        f.createdAt = firstTimeDeep(o, "created_at", "create_time", "createdAt", "uploaded_at", "uploadedAt");
        f.updatedAt = firstTimeDeep(o, "updated_at", "modified_at", "update_time", "updatedAt", "modifiedAt");
        if (f.updatedAt <= 0) f.updatedAt = f.createdAt;
        f.mimeType = f.mimeType.isEmpty() ? guessMime(f.name, rawUrl) : f.mimeType;
        f.sourceFlags = ChatGptModels.SRC_LIBRARY;
        return f;
    }

    private static boolean looksInternalStorageName(String value) {
        String s = clean(value).replace('\\', '/');
        if (s.isEmpty() || !s.contains("/")) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.startsWith("user-") || lower.startsWith("file-service://") ||
                lower.startsWith("sediment://") || lower.startsWith("asset-pointer://");
    }

    private static void walk(Object node, LinkedHashMap<String, ChatGptModels.RemoteFile> out, int depth) {
        if (node == null || depth > 12) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), out, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        ChatGptModels.RemoteFile f = mapToRemote(o);
        if (f != null) ChatGptModels.merge(out, f);
        JSONArray names = o.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            Object child = o.opt(names.optString(i));
            if (child instanceof JSONObject || child instanceof JSONArray) walk(child, out, depth + 1);
        }
    }

    private static void inspectMeta(JSONObject o, ChatGptModels.LibraryPageMeta meta, int depth) {
        if (o == null || depth > 4) return;
        if (meta.nextCursor.isEmpty())
            meta.nextCursor = firstString(o, "next_cursor", "nextCursor", "next_page_cursor", "nextPageCursor");
        for (String k : new String[]{"has_more", "hasMore", "more"})
            if (o.has(k) && o.optBoolean(k, false)) meta.hasMore = true;
        if (meta.total <= 0) meta.total = firstLong(o, "total", "total_count", "totalCount", "count_total");
        for (String nested : new String[]{"pagination", "page_info", "pageInfo", "meta"})
            inspectMeta(o.optJSONObject(nested), meta, depth + 1);
    }

    static Object parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        try {
            if (s.startsWith("{")) return new JSONObject(s);
            if (s.startsWith("[")) return new JSONArray(s);
        } catch (Exception ignored) {}
        return null;
    }

    static String firstStringDeep(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String key : keys) {
            String v = clean(o.optString(key, ""));
            if (!v.isEmpty()) return v;
        }
        for (String nested : new String[]{"metadata", "file", "asset", "item", "data"}) {
            JSONObject n = o.optJSONObject(nested);
            if (n == null) continue;
            for (String key : keys) {
                String v = clean(n.optString(key, ""));
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    private static long firstLongDeep(JSONObject o, String... keys) {
        if (o == null) return 0;
        for (String key : keys) { long v = o.optLong(key, 0); if (v > 0) return v; }
        for (String nested : new String[]{"metadata", "file", "asset", "item", "data"}) {
            JSONObject n = o.optJSONObject(nested);
            if (n == null) continue;
            for (String key : keys) { long v = n.optLong(key, 0); if (v > 0) return v; }
        }
        return 0;
    }

    private static long firstTimeDeep(JSONObject o, String... keys) {
        if (o == null) return 0;
        for (String key : keys) { long v = parseTime(o.opt(key)); if (v > 0) return v; }
        for (String nested : new String[]{"metadata", "file", "asset", "item", "data"}) {
            JSONObject n = o.optJSONObject(nested);
            if (n == null) continue;
            for (String key : keys) { long v = parseTime(n.opt(key)); if (v > 0) return v; }
        }
        return 0;
    }

    private static long parseTime(Object v) {
        if (v == null || JSONObject.NULL.equals(v)) return 0;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            long n = (long) d;
            return n < 100000000000L ? n * 1000L : n;
        }
        String s = clean(String.valueOf(v));
        if (s.isEmpty()) return 0;
        try {
            double d = Double.parseDouble(s);
            long n = (long) d;
            return n < 100000000000L ? n * 1000L : n;
        } catch (Exception ignored) {}
        try { return java.time.Instant.parse(s).toEpochMilli(); }
        catch (Exception ignored) {}
        return 0;
    }

    private static String guessMime(String name, String url) {
        String s = ((name == null ? "" : name) + " " + (url == null ? "" : url)).toLowerCase(Locale.ROOT);
        if (s.contains(".png")) return "image/png";
        if (s.contains(".jpg") || s.contains(".jpeg")) return "image/jpeg";
        if (s.contains(".webp")) return "image/webp";
        if (s.contains(".gif")) return "image/gif";
        if (s.contains(".pdf")) return "application/pdf";
        if (s.contains(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (s.contains(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (s.contains(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (s.contains(".zip")) return "application/zip";
        if (s.contains(".apk")) return "application/vnd.android.package-archive";
        if (s.contains(".json")) return "application/json";
        if (s.contains(".txt") || s.contains(".md")) return "text/plain";
        return ChatGptModels.isImageName(s) ? "image/png" : "application/octet-stream";
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) { String v = clean(o.optString(k, "")); if (!v.isEmpty()) return v; }
        return "";
    }

    private static long firstLong(JSONObject o, String... keys) {
        for (String k : keys) { long v = o.optLong(k, 0); if (v > 0) return v; }
        return 0;
    }

    static String cleanName(String value) {
        if (value == null) return "";
        String s = value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String s = value.trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }
}
