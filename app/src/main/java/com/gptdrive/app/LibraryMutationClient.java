package com.gptdrive.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native ChatGPT Library mutations and Recently deleted discovery. */
final class LibraryMutationClient {
    static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final String BASE = "https://chatgpt.com";

    static final class TrashItem {
        final DriveItem item;
        final String libraryFileId;
        final long deletedAt;

        TrashItem(DriveItem item, String libraryFileId, long deletedAt) {
            this.item = item;
            this.libraryFileId = libraryFileId == null ? "" : libraryFileId;
            this.deletedAt = deletedAt;
        }

        long expiresAt() { return deletedAt > 0 ? deletedAt + RETENTION_MS : 0; }
        int daysLeft() {
            if (expiresAt() <= 0) return 30;
            long left = Math.max(0, expiresAt() - System.currentTimeMillis());
            return (int) Math.ceil(left / 86400000d);
        }
    }

    private final Context app;
    private final long accountId;
    private final HttpTransport transport;

    LibraryMutationClient(Context context, long accountId) {
        this.app = context.getApplicationContext();
        this.accountId = accountId;
        this.transport = new AndroidHttpTransport(app);
    }

    List<TrashItem> loadTrash() throws Exception {
        LinkedHashMap<String, TrashItem> out = new LinkedHashMap<>();
        String[] candidates = {
                "/backend-api/files/library/trash?limit=200",
                "/backend-api/files/library/nodes?deleted=true&limit=200&include_saved_entities=true&include_folder_counts=true",
                "/backend-api/files/library/nodes?recently_deleted=true&limit=200&include_saved_entities=true&include_folder_counts=true",
                "/backend-api/files/library/nodes?view=deleted&limit=200&include_saved_entities=true&include_folder_counts=true"
        };
        Exception last = null;
        for (String first : candidates) {
            try {
                loadTrashCandidate(first, out);
                if (!out.isEmpty()) break;
            } catch (Exception e) {
                last = e;
            }
        }
        mergeShadow(out);
        if (out.isEmpty() && last != null && !hasShadow()) throw last;
        return new ArrayList<>(out.values());
    }

    private void loadTrashCandidate(String first, LinkedHashMap<String, TrashItem> out) throws Exception {
        String endpoint = first;
        String seenCursor = "";
        for (int page = 0; page < 30; page++) {
            HttpResponse r = request("GET", endpoint, null, true);
            if (r.code == 401 || r.code == 403) throw new java.io.IOException("Сессия ChatGPT недоступна (HTTP " + r.code + ")");
            if (r.code == 404 || r.code == 405) return;
            if (r.code != 200) throw new java.io.IOException("Удалённые: HTTP " + r.code);
            String raw = r.bodyUtf8();
            Object root = ChatGptResponseParser.parse(raw);
            if (root == null) return;
            boolean trustedTrashEndpoint = endpoint.contains("/library/trash");
            walkDeleted(root, out, trustedTrashEndpoint, 0);
            String cursor = findCursor(root);
            if (cursor.isEmpty() || cursor.equals(seenCursor)) return;
            seenCursor = cursor;
            endpoint = replaceOrAppend(endpoint, "cursor", cursor);
        }
    }

    boolean moveToTrash(DriveItem item) throws Exception {
        String lib = resolveLibraryFileId(item);
        if (lib.isEmpty()) throw new java.io.IOException("У файла нет library_file_id");
        HttpResponse r = request("DELETE", "/backend-api/files/library/files/" + Uri.encode(lib), null, false);
        if (r.code == 404 || r.code == 405) {
            String fid = item.fileId.isEmpty() ? RemoteFileSource.extractFileId(item.url) : item.fileId;
            String q = "?file_id=" + Uri.encode(fid == null ? "" : fid) +
                    "&file_name=" + Uri.encode(item.name == null ? "" : item.name) + "&soft_delete=true";
            r = request("POST", "/backend-api/files/library/files/" + Uri.encode(lib) + "/delete_stream" + q, null, false);
            if (r.code == 200 && !deletionCompleted(r.bodyUtf8()))
                throw new java.io.IOException("ChatGPT не подтвердил завершение удаления");
        }
        if (r.code != 200 && r.code != 204) throw new java.io.IOException("Удаление: HTTP " + r.code);
        rememberShadow(item, lib);
        return true;
    }

    boolean restore(TrashItem item) throws Exception {
        if (item == null || item.libraryFileId.isEmpty()) throw new java.io.IOException("Нет library_file_id");
        HttpResponse r = request("POST", "/backend-api/files/library/files/" + Uri.encode(item.libraryFileId) + "/restore", null, false);
        if (r.code != 200 && r.code != 204) throw new java.io.IOException("Восстановление: HTTP " + r.code);
        forgetShadow(item.libraryFileId);
        return true;
    }

    boolean emptyTrash() throws Exception {
        HttpResponse r = request("DELETE", "/backend-api/files/library/trash", null, false);
        if (r.code != 200 && r.code != 204) throw new java.io.IOException("Очистка удалённых: HTTP " + r.code);
        app.getSharedPreferences("trash-shadow", Context.MODE_PRIVATE).edit().remove(key()).apply();
        return true;
    }

    String resolveLibraryFileId(DriveItem item) {
        if (item == null) return "";
        if (!item.libraryFileId.trim().isEmpty()) return item.libraryFileId.trim();
        String fid = item.fileId.trim();
        if (fid.isEmpty()) fid = RemoteFileSource.extractFileId(item.url);
        if (fid == null || fid.trim().isEmpty()) return "";
        DriveDb db = new DriveDb(app);
        Cursor c = db.getReadableDatabase().query("files", new String[]{"library_file_id"},
                "account_id=? AND file_id=?", new String[]{Long.toString(accountId), fid.trim()}, null, null, null, "1");
        try { return c.moveToFirst() ? safe(c.getString(0)) : ""; }
        finally { c.close(); }
    }

    private HttpResponse request(String method, String endpoint, String payload, boolean retryable) throws Exception {
        ChatGptSessionStore.Session s = ChatGptSessionStore.load(app, accountId);
        if (s == null || s.accessToken == null || s.accessToken.trim().isEmpty())
            throw new java.io.IOException("Нет backend-сессии ChatGPT");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + s.accessToken);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
        h.put("Referer", BASE + "/library?deleted=true");
        h.put("Origin", BASE);
        h.put("User-Agent", AppIdentity.userAgent());
        h.put("Cache-Control", "no-cache");
        if (s.accountId != null && !s.accountId.isEmpty()) h.put("chatgpt-account-id", s.accountId);
        String cookie = AccountSession.cookieHeader(app, accountId, BASE);
        if (cookie != null && !cookie.trim().isEmpty()) h.put("Cookie", cookie);
        byte[] body = payload == null ? null : payload.getBytes(StandardCharsets.UTF_8);
        if (body != null) h.put("Content-Type", "application/json");
        return transport.execute(new HttpRequest(method, endpoint.startsWith("http") ? endpoint : BASE + endpoint,
                h, body, true, retryable, 15000, 30000, 16 * 1024 * 1024));
    }

    private void walkDeleted(Object node, LinkedHashMap<String, TrashItem> out, boolean trusted, int depth) {
        if (node == null || depth > 12) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walkDeleted(a.opt(i), out, trusted, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        String lib = ChatGptResponseParser.firstStringDeep(o, "library_file_id", "libraryFileId");
        boolean deleted = trusted || o.optBoolean("deleted", false) || o.optBoolean("is_deleted", false) ||
                o.has("deleted_at") || o.has("trashed_at") || o.has("soft_deleted_at") || o.has("deletedAt");
        if (deleted && !lib.isEmpty()) {
            ChatGptModels.RemoteFile f = ChatGptResponseParser.mapToRemote(o);
            if (f != null) {
                String fid = f.fileId.isEmpty() ? f.identity() : f.fileId;
                String ref = f.stableUrl();
                DriveItem di = new DriveItem(-Math.abs((lib + fid).hashCode()), accountId, "Deleted",
                        f.name, ref, f.mimeType, f.sizeBytes, f.updatedAt, fid, lib, f.createdAt);
                long deletedAt = readTime(o, "deleted_at", "trashed_at", "soft_deleted_at", "deletedAt", "trashedAt");
                if (deletedAt <= 0) deletedAt = f.updatedAt > 0 ? f.updatedAt : System.currentTimeMillis();
                out.put(lib, new TrashItem(di, lib, deletedAt));
            }
        }
        JSONArray names = o.names();
        if (names != null) for (int i = 0; i < names.length(); i++) {
            Object child = o.opt(names.optString(i));
            if (child instanceof JSONObject || child instanceof JSONArray) walkDeleted(child, out, trusted, depth + 1);
        }
    }

    private long readTime(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || JSONObject.NULL.equals(v)) continue;
            try {
                if (v instanceof Number) {
                    long n = ((Number) v).longValue();
                    return n < 100000000000L ? n * 1000L : n;
                }
                String s = String.valueOf(v);
                try { return java.time.Instant.parse(s).toEpochMilli(); } catch (Exception ignored) {}
                long n = Long.parseLong(s);
                return n < 100000000000L ? n * 1000L : n;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private String findCursor(Object root) {
        if (!(root instanceof JSONObject)) return "";
        JSONObject o = (JSONObject) root;
        String c = ChatGptModels.first(o.optString("next_cursor", ""), o.optString("cursor", ""), o.optString("nextCursor", ""));
        if (!c.isEmpty()) return c;
        for (String key : new String[]{"pagination", "page_info", "pageInfo", "meta"}) {
            JSONObject n = o.optJSONObject(key);
            if (n != null) {
                c = ChatGptModels.first(n.optString("next_cursor", ""), n.optString("cursor", ""), n.optString("nextCursor", ""));
                if (!c.isEmpty()) return c;
            }
        }
        return "";
    }

    private String replaceOrAppend(String endpoint, String key, String value) {
        Uri u = Uri.parse(endpoint);
        Uri.Builder b = u.buildUpon().clearQuery();
        for (String name : u.getQueryParameterNames()) if (!name.equals(key))
            for (String v : u.getQueryParameters(name)) b.appendQueryParameter(name, v);
        b.appendQueryParameter(key, value);
        return b.build().toString();
    }

    private boolean deletionCompleted(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        return raw.contains("file.deletion.completed") || raw.contains("\"completed\":true") || raw.contains("\"success\":true");
    }

    private void rememberShadow(DriveItem item, String lib) {
        try {
            JSONArray a = shadowArray();
            JSONObject o = new JSONObject();
            o.put("library_file_id", lib);
            o.put("file_id", item.fileId.isEmpty() ? RemoteFileSource.extractFileId(item.url) : item.fileId);
            o.put("name", item.name);
            o.put("url", item.url);
            o.put("mime", item.mime);
            o.put("size", item.sizeBytes);
            o.put("deleted_at", System.currentTimeMillis());
            a.put(o);
            app.getSharedPreferences("trash-shadow", Context.MODE_PRIVATE).edit().putString(key(), a.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void forgetShadow(String lib) {
        try {
            JSONArray src = shadowArray();
            JSONArray dst = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o != null && !lib.equals(o.optString("library_file_id", ""))) dst.put(o);
            }
            app.getSharedPreferences("trash-shadow", Context.MODE_PRIVATE).edit().putString(key(), dst.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void mergeShadow(LinkedHashMap<String, TrashItem> out) {
        JSONArray a = shadowArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String lib = o.optString("library_file_id", "");
            if (lib.isEmpty() || out.containsKey(lib)) continue;
            String fid = o.optString("file_id", "");
            DriveItem di = new DriveItem(-Math.abs((lib + fid).hashCode()), accountId, "Deleted",
                    o.optString("name", "ChatGPT file"), o.optString("url", ""), o.optString("mime", ""),
                    o.optLong("size", 0), o.optLong("deleted_at", 0), fid, lib, 0);
            out.put(lib, new TrashItem(di, lib, o.optLong("deleted_at", System.currentTimeMillis())));
        }
    }

    private JSONArray shadowArray() {
        try { return new JSONArray(app.getSharedPreferences("trash-shadow", Context.MODE_PRIVATE).getString(key(), "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private boolean hasShadow() { return shadowArray().length() > 0; }
    private String key() { return "account-" + accountId; }
    private String safe(String v) { return v == null ? "" : v.trim(); }
}
