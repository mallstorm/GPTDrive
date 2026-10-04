package com.gptdrive.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Read-only sync for browser-imported ChatGPT sessions. */
final class NativeSessionSync {
    interface Listener {
        void onStatus(String message, long count);
        void onComplete(long count);
        void onError(String message);
    }

    private static final String BASE = "https://chatgpt.com";
    private static final int MAX_CONVERSATIONS = 160;

    private final Context context;
    private final long accountId;
    private final DriveDb db;
    private final String token;
    private final String remoteAccountId;
    private final Listener listener;
    private final Set<String> seenFiles = new HashSet<>();
    private int conversationCount;

    NativeSessionSync(Context context, long accountId, Listener listener) {
        this.context = context.getApplicationContext();
        this.accountId = accountId;
        this.db = new DriveDb(this.context);
        this.token = BrowserSessionStore.accessToken(this.context, accountId);
        this.remoteAccountId = BrowserSessionStore.chatGptAccountId(this.context, accountId);
        this.listener = listener;
    }

    void start() {
        new Thread(this::run, "gptdrive-native-sync").start();
    }

    private void run() {
        if (token == null || token.trim().isEmpty()) {
            error("В session JSON нет access token");
            return;
        }
        try {
            status("Проверяю доступ к аккаунту…");
            JSONObject models = getJson("/backend-api/models");
            if (models == null) throw new IllegalStateException("ChatGPT backend недоступен");

            // Remove artifacts produced by the old unauthenticated WebView scanner.
            db.getWritableDatabase().delete("items", "account_id=?", new String[]{Long.toString(accountId)});

            status("Получаю проекты…");
            syncProjects();

            status("Получаю историю и файлы…");
            syncConversations();

            db.markSynced(accountId);
            complete();
        } catch (HttpStatusException e) {
            if (e.code == 401 || e.code == 403) error("Сессия ChatGPT истекла или backend отклонил токен (HTTP " + e.code + ")");
            else error("Ошибка ChatGPT backend: HTTP " + e.code);
        } catch (Exception e) {
            String m = e.getMessage();
            error(m == null || m.trim().isEmpty() ? "Не удалось синхронизировать аккаунт" : m);
        }
    }

    private void syncProjects() throws Exception {
        JSONObject root = getJson("/backend-api/gizmos/snorlax/sidebar?owned_only=true&conversations_per_gizmo=0");
        if (root == null) return;
        collectProjects(root);
    }

    private void collectProjects(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String id = firstNonEmpty(o.optString("id", ""), o.optString("gizmo_id", ""));
            if (id.startsWith("g-p-")) {
                String name = firstNonEmpty(
                        o.optString("name", ""),
                        o.optString("title", ""),
                        nestedString(o, "display", "name"),
                        "ChatGPT Project");
                db.upsert(accountId, "Projects", name, BASE + "/g/" + id + "/project", DriveActions.LINK_MIME, 0);
            }
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) collectProjects(o.opt(names.optString(i)));
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectProjects(a.opt(i));
        }
    }

    private void syncConversations() throws Exception {
        int offset = 0;
        final int pageSize = 28;
        while (conversationCount < MAX_CONVERSATIONS) {
            JSONObject root = getJson("/backend-api/conversations?offset=" + offset + "&limit=" + pageSize + "&order=updated");
            JSONArray items = root == null ? null : root.optJSONArray("items");
            if (items == null || items.length() == 0) break;

            for (int i = 0; i < items.length() && conversationCount < MAX_CONVERSATIONS; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                if (id.isEmpty()) continue;
                conversationCount++;

                String title = firstNonEmpty(item.optString("title", ""), "ChatGPT conversation");
                boolean archived = item.optBoolean("is_archived", false);
                if (archived) {
                    db.upsert(accountId, "Archived", title, BASE + "/c/" + id, DriveActions.LINK_MIME, 0);
                }

                try {
                    JSONObject conversation = getJson("/backend-api/conversation/" + id);
                    if (conversation != null) collectFiles(conversation, id, title);
                } catch (HttpStatusException e) {
                    if (e.code == 404) continue;
                    throw e;
                }
                status("Сканирую материалы: " + conversationCount + " чатов");
            }

            if (items.length() < pageSize) break;
            offset += items.length();
        }
    }

    private void collectFiles(Object node, String conversationId, String conversationTitle) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;

            String pointer = o.optString("asset_pointer", "").trim();
            if (!pointer.isEmpty()) {
                String fileId = extractFileId(pointer);
                if (!fileId.isEmpty()) resolveFile(fileId, conversationId, conversationTitle, o);
            }

            String directFileId = o.optString("file_id", "").trim();
            if (directFileId.startsWith("file_") || directFileId.startsWith("file-")) {
                resolveFile(directFileId, conversationId, conversationTitle, o);
            }

            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    Object child = o.opt(names.optString(i));
                    if (child instanceof JSONObject || child instanceof JSONArray) collectFiles(child, conversationId, conversationTitle);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectFiles(a.opt(i), conversationId, conversationTitle);
        }
    }

    private void resolveFile(String fileId, String conversationId, String conversationTitle, JSONObject hint) throws Exception {
        if (fileId == null || fileId.trim().isEmpty()) return;
        fileId = fileId.trim();
        String dedupe = conversationId + ":" + fileId;
        if (!seenFiles.add(dedupe)) return;

        String path = "/backend-api/files/download/" + encodePath(fileId)
                + "?conversation_id=" + encodeQuery(conversationId) + "&inline=false";
        JSONObject download;
        try {
            download = getJson(path);
        } catch (HttpStatusException e) {
            if (e.code == 404 || e.code == 400) return;
            throw e;
        }
        if (download == null) return;

        String url = download.optString("download_url", "").trim();
        if (url.isEmpty()) return;
        String name = firstNonEmpty(
                download.optString("file_name", ""),
                hint == null ? "" : hint.optString("name", ""),
                conversationTitle + " - " + fileId);
        long size = download.optLong("file_size_bytes", 0);
        if (size <= 0 && hint != null) size = hint.optLong("size_bytes", 0);

        String mime = mimeFromHint(hint, name, url);
        db.upsert(accountId, "Library", name, url, mime, size);
        if (mime.startsWith("image/")) db.upsert(accountId, "Images", name, url, mime, size);
    }

    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(BASE + path).openConnection();
            h.setRequestMethod("GET");
            h.setInstanceFollowRedirects(true);
            h.setConnectTimeout(18000);
            h.setReadTimeout(30000);
            h.setRequestProperty("Accept", "application/json");
            h.setRequestProperty("Authorization", "Bearer " + token);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 Android GPTDrive/0.4.9");
            if (remoteAccountId != null && !remoteAccountId.isEmpty()) {
                h.setRequestProperty("ChatGPT-Account-ID", remoteAccountId);
            }
            int code = h.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? h.getInputStream() : h.getErrorStream();
            String body = readAll(stream);
            if (code < 200 || code >= 300) throw new HttpStatusException(code, body);
            if (body == null || body.trim().isEmpty()) return new JSONObject();
            return new JSONObject(body);
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private static String extractFileId(String pointer) {
        if (pointer == null) return "";
        String p = pointer.trim();
        String[] prefixes = {"sediment://", "file-service://", "sandbox:/mnt/data/"};
        for (String prefix : prefixes) if (p.startsWith(prefix)) p = p.substring(prefix.length());
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.startsWith("file_") || p.startsWith("file-")) return p;
        int pos = p.indexOf("file_");
        if (pos < 0) pos = p.indexOf("file-");
        if (pos >= 0) {
            p = p.substring(pos);
            int slash = p.indexOf('/');
            if (slash > 0) p = p.substring(0, slash);
            return p;
        }
        return "";
    }

    private static String mimeFromHint(JSONObject hint, String name, String url) {
        String type = hint == null ? "" : firstNonEmpty(
                hint.optString("mime_type", ""),
                hint.optString("mime", ""),
                hint.optString("content_type", ""));
        if (type.startsWith("image/")) return type;
        if ("image_asset_pointer".equals(type)) return "image/*";
        String guessed = DriveDb.guessMime(firstNonEmpty(name, url));
        if (!"application/octet-stream".equals(guessed)) return guessed;
        if (hint != null && (hint.optInt("width", 0) > 0 || hint.optInt("height", 0) > 0)) return "image/*";
        return guessed;
    }

    private static String nestedString(JSONObject root, String objectKey, String valueKey) {
        JSONObject o = root == null ? null : root.optJSONObject(objectKey);
        return o == null ? "" : o.optString(valueKey, "");
    }

    private static String encodePath(String value) {
        return value == null ? "" : value.replace("/", "%2F");
    }

    private static String encodeQuery(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception e) { return value == null ? "" : value; }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private void status(String message) {
        if (listener != null) listener.onStatus(message, db.countAll(accountId));
    }

    private void complete() {
        if (listener != null) listener.onComplete(db.countAll(accountId));
    }

    private void error(String message) {
        if (listener != null) listener.onError(message);
    }

    private static final class HttpStatusException extends Exception {
        final int code;
        HttpStatusException(int code, String body) {
            super("HTTP " + code + (body == null || body.isEmpty() ? "" : ": " + body.substring(0, Math.min(180, body.length()))));
            this.code = code;
        }
    }
}
