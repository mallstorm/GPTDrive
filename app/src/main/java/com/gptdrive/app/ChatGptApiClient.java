package com.gptdrive.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native provider ported from Windows ChatGPTDrive v0.17.3. */
final class ChatGptApiClient {
    interface Progress { void onProgress(String phase, int part, int total, int objects); }

    static final class SyncResult {
        final int library, images, projects, archived, pages;
        SyncResult(int library, int images, int projects, int archived, int pages) {
            this.library = library; this.images = images; this.projects = projects; this.archived = archived; this.pages = pages;
        }
    }

    static final class AuthException extends Exception { AuthException(String message) { super(message); } }

    private static final String BASE = "https://chatgpt.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36 GPTDrive/0.6.2";
    private static final Pattern FILE_ID = Pattern.compile("(file[-_][A-Za-z0-9_-]{6,})", Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final DriveDb db;
    private final long localAccountId;
    private ChatGptSessionStore.Session session;

    ChatGptApiClient(Context context, DriveDb db, long localAccountId) throws AuthException {
        this.context = context.getApplicationContext();
        this.db = db;
        this.localAccountId = localAccountId;
        session = ChatGptSessionStore.load(context, localAccountId);
        if (session == null || session.accessToken == null || session.accessToken.trim().isEmpty()) throw new AuthException("Нет backend-сессии ChatGPT");
    }

    SyncResult sync(Progress progress) throws Exception {
        if (progress != null) progress.onProgress("Файлы", 1, 4, 0);
        LibraryResult library = fetchLibraryPages(250, progress);
        replaceCategory("Library", library.files);

        if (progress != null) progress.onProgress("Изображения", 2, 4, library.files.size());
        LinkedHashMap<String, RemoteEntry> images = new LinkedHashMap<>();
        for (RemoteEntry f : library.files) if (f.isImage()) merge(images, f);
        try { for (RemoteEntry f : fetchImages(progress)) merge(images, f); }
        catch (AuthException e) { throw e; }
        catch (Exception ignored) {}
        replaceCategory("Images", new ArrayList<>(images.values()));

        if (progress != null) progress.onProgress("Проекты", 3, 4, library.files.size() + images.size());
        List<RemoteEntry> projects;
        try { projects = fetchProjects(); } catch (AuthException e) { throw e; } catch (Exception e) { projects = null; }
        if (projects != null) replaceCategory("Projects", projects);

        if (progress != null) progress.onProgress("Архив", 4, 4, library.files.size() + images.size() + (projects == null ? 0 : projects.size()));
        List<RemoteEntry> archived;
        try { archived = fetchArchived(); } catch (AuthException e) { throw e; } catch (Exception e) { archived = null; }
        if (archived != null) replaceCategory("Archived", archived);

        db.pruneFalsePositives(localAccountId);
        IndexMaintenance.pruneTransientSignedUrls(db, localAccountId);
        db.markSynced(localAccountId);
        return new SyncResult(library.files.size(), images.size(),
                projects == null ? (int) db.count(localAccountId, "Projects") : projects.size(),
                archived == null ? (int) db.count(localAccountId, "Archived") : archived.size(), library.pages);
    }

    static String resolveDownloadUrl(Context context, long localAccountId, String fileId, String conversationId) throws Exception {
        // Validate that we still have a backend session, then return the authenticated
        // backend download route. The route itself redirects to a fresh signed asset URL.
        new ChatGptApiClient(context, new DriveDb(context.getApplicationContext()), localAccountId);
        String fid = extractFileId(fileId);
        if (fid.isEmpty()) throw new java.io.IOException("У файла нет file_id");
        StringBuilder u = new StringBuilder(BASE)
                .append("/backend-api/files/download/")
                .append(Uri.encode(fid));
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            u.append("?conversation_id=").append(Uri.encode(conversationId.trim()));
        }
        return u.toString();
    }

    private LibraryResult fetchLibraryPages(int maxPages, Progress progress) throws Exception {
        // Current ChatGPT Library uses GET /files/library/nodes with cursor=<cursor>.
        // POST /files/library is useful for other surfaces but its cursor is not the
        // continuation token for enumerating the complete Library and caused HTTP 400.
        LinkedHashMap<String, RemoteEntry> merged = new LinkedHashMap<>();
        ArrayList<String> seenCursors = new ArrayList<>();
        String cursor = "";
        int pages = 0;

        while (pages < maxPages) {
            String endpoint = "/backend-api/files/library/nodes" +
                    (cursor.isEmpty() ? "" : "?cursor=" + Uri.encode(cursor));

            Response r = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                r = request("GET", endpoint, null);
                if (r.code == 200 || r.code == 401 || r.code == 403) break;
                if (r.code != 429 && r.code < 500) break;
                try { Thread.sleep(Math.min(8000L, 700L * (1L << attempt))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            if (r == null) throw new java.io.IOException("ChatGPT Library: пустой ответ");
            if (r.code == 401 || r.code == 403)
                throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
            if (r.code != 200)
                throw new java.io.IOException("ChatGPT Library nodes HTTP " + r.code);

            JSONObject root = new JSONObject(r.body);
            JSONArray items = root.optJSONArray("items");
            if (items == null)
                throw new java.io.IOException("ChatGPT Library вернула неизвестный формат");

            int before = merged.size();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                if (item.optBoolean("is_root", false)) continue;
                if ("directory".equalsIgnoreCase(item.optString("kind", ""))) continue;
                RemoteEntry e = mapToRemote(item);
                if (e != null) merge(merged, e);
            }
            pages++;
            if (progress != null) progress.onProgress("Файлы", 1, 4, merged.size());

            String next = root.optString("cursor", "").trim();
            if (next.isEmpty()) break;
            if (next.equals(cursor) || seenCursors.contains(next)) break;
            seenCursors.add(next);
            cursor = next;

            // If the backend returned only duplicates but still repeated a cursor chain,
            // continue one page; the cursor guards above prevent an infinite loop.
            if (merged.size() == before && items.length() == 0) break;
        }
        return new LibraryResult(new ArrayList<>(merged.values()), pages);
    }

    private List<RemoteEntry> fetchImages(Progress progress) throws Exception {
        LinkedHashMap<String, RemoteEntry> out = new LinkedHashMap<>();
        String cursor = "";
        for (int pageNo = 0; pageNo < 100; pageNo++) {
            String endpoint = "/backend-api/my/recent/image_gen?limit=100" + (cursor.isEmpty() ? "" : "&after=" + Uri.encode(cursor));
            Response r = request("GET", endpoint, null);
            if (r.code == 401 || r.code == 403) throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
            if (r.code != 200) break;
            JSONObject root = new JSONObject(r.body);
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) { RemoteEntry e = imageToRemote(items.optJSONObject(i)); if (e != null) merge(out, e); }
            if (progress != null) progress.onProgress("Изображения", 2, 4, out.size());
            String next = root.optString("cursor", "").trim();
            if (next.isEmpty() || next.equals(cursor)) break;
            cursor = next;
        }
        return new ArrayList<>(out.values());
    }

    private List<RemoteEntry> fetchProjects() throws Exception {
        LinkedHashMap<String, RemoteEntry> out = new LinkedHashMap<>();
        String cursor = "";
        for (int pageNo = 0; pageNo < 100; pageNo++) {
            String endpoint = "/backend-api/gizmos/snorlax/sidebar?owned_only=true&conversations_per_gizmo=100" + (cursor.isEmpty() ? "" : "&cursor=" + Uri.encode(cursor));
            Response r = request("GET", endpoint, null);
            if (r.code == 401 || r.code == 403) throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
            if (r.code != 200) break;
            JSONObject root = new JSONObject(r.body);
            JSONArray items = root.optJSONArray("items");
            if (items == null) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i); if (item == null) continue;
                JSONObject outer = item.optJSONObject("gizmo"); JSONObject g = outer == null ? null : outer.optJSONObject("gizmo"); if (g == null) g = outer; if (g == null) continue;
                String id = g.optString("id", "").trim(); JSONObject display = g.optJSONObject("display");
                String name = first(display == null ? "" : display.optString("name", ""), g.optString("name", ""));
                if (id.isEmpty()) continue; if (name.isEmpty()) name = "Project";
                merge(out, new RemoteEntry(id, cleanName(name), "https://chatgpt.com/g/" + id, DriveActions.LINK_MIME, 0, "", false));
            }
            String next = root.optString("cursor", "").trim(); if (next.isEmpty() || next.equals(cursor) || items.length() == 0) break; cursor = next;
        }
        return new ArrayList<>(out.values());
    }

    private List<RemoteEntry> fetchArchived() throws Exception {
        LinkedHashMap<String, RemoteEntry> out = new LinkedHashMap<>(); int offset = 0, limit = 100;
        for (int page = 0; page < 100; page++) {
            Response r = request("GET", "/backend-api/conversations?offset=" + offset + "&limit=" + limit + "&order=updated&is_archived=true", null);
            if (r.code == 401 || r.code == 403) throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")"); if (r.code != 200) break;
            JSONObject root = new JSONObject(r.body); JSONArray items = root.optJSONArray("items"); if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i); if (o == null) continue;
                String id = first(o.optString("id", ""), o.optString("conversation_id", "")); if (id.isEmpty()) continue;
                String title = cleanName(first(o.optString("title", ""), "ChatGPT chat"));
                merge(out, new RemoteEntry(id, title, "https://chatgpt.com/c/" + id, DriveActions.LINK_MIME, 0, "", false));
            }
            int total = root.optInt("total", root.optInt("total_count", 0)); offset += items.length();
            if (items.length() < limit || (total > 0 && offset >= total)) break;
        }
        return new ArrayList<>(out.values());
    }

    private void replaceCategory(String category, List<RemoteEntry> entries) {
        if (entries == null) return;
        SQLiteDatabase sql = db.getWritableDatabase(); sql.beginTransaction();
        try {
            sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(localAccountId), category});
            for (RemoteEntry e : entries) db.upsert(localAccountId, category, e.name, e.url, e.mime, e.size);
            sql.setTransactionSuccessful();
        } finally { sql.endTransaction(); }
    }

    private Response request(String method, String endpoint, String payload) throws Exception {
        session = ChatGptSessionStore.load(context, localAccountId);
        if (session == null || session.accessToken == null || session.accessToken.isEmpty()) throw new AuthException("Нет backend-сессии ChatGPT");
        HttpURLConnection h = (HttpURLConnection) new URL(endpoint.startsWith("http") ? endpoint : BASE + endpoint).openConnection();
        h.setRequestMethod(method); h.setConnectTimeout(15000); h.setReadTimeout(30000); h.setInstanceFollowRedirects(true);
        h.setRequestProperty("Authorization", "Bearer " + session.accessToken); h.setRequestProperty("Accept", "application/json, text/plain, */*");
        h.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8"); h.setRequestProperty("Referer", BASE + "/library"); h.setRequestProperty("Origin", BASE);
        h.setRequestProperty("Cache-Control", "no-cache"); h.setRequestProperty("Pragma", "no-cache"); h.setRequestProperty("User-Agent", UA);
        if (session.accountId != null && !session.accountId.isEmpty()) h.setRequestProperty("chatgpt-account-id", session.accountId);
        String cookie = AccountSession.cookieHeader(context, localAccountId, BASE); if (cookie != null && !cookie.trim().isEmpty()) h.setRequestProperty("Cookie", cookie);
        if (payload != null) {
            h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json"); byte[] bytes = payload.getBytes(StandardCharsets.UTF_8); h.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = h.getOutputStream()) { out.write(bytes); }
        }
        int code = h.getResponseCode(); InputStream stream = code >= 400 ? h.getErrorStream() : h.getInputStream(); String body = readText(stream, 32 * 1024 * 1024); h.disconnect();
        return new Response(code, body);
    }

    private static String readText(InputStream in, int maxBytes) throws Exception {
        if (in == null) return ""; StringBuilder sb = new StringBuilder(); int total = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192]; int n; while ((n = br.read(buf)) != -1) { total += n * 2; if (total > maxBytes) break; sb.append(buf, 0, n); }
        }
        return sb.toString();
    }

    private static boolean isPaginationRejected(int code) {
        return code == 400 || code == 404 || code == 405 || code == 422;
    }

    private static void ensureLibraryStatus(Response r) throws Exception {
        if (r.code == 200) return;
        if (r.code == 401 || r.code == 403) throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
        if (r.code == 429) throw new java.io.IOException("ChatGPT ограничил частоту запросов Library (HTTP 429)");
        throw new java.io.IOException("ChatGPT Library HTTP " + r.code);
    }

    private static Page decodeLibraryPage(String body) {
        LinkedHashMap<String, RemoteEntry> files = new LinkedHashMap<>(); String nextCursor = ""; boolean hasMore = false;
        try {
            Object root = parseJson(body); if (root != null) walkFiles(root, files, 0);
            if (root instanceof JSONObject) {
                JSONObject o = (JSONObject) root;
                boolean explicitMore = findBoolean(o, 0, "has_more", "hasMore", "more");
                nextCursor = findString(o, 0, "next_cursor", "nextCursor", "next_page_cursor", "nextPageCursor");
                if (nextCursor.isEmpty() && explicitMore) nextCursor = o.optString("cursor", "").trim();
                hasMore = explicitMore || !nextCursor.isEmpty();
            }
        } catch (Exception ignored) {}
        return new Page(new ArrayList<>(files.values()), nextCursor, hasMore);
    }

    private static Object parseJson(String body) {
        if (body == null) return null; String s = body.trim();
        try { if (s.startsWith("{")) return new JSONObject(s); if (s.startsWith("[")) return new JSONArray(s); } catch (Exception ignored) {}
        return null;
    }

    private static void walkFiles(Object node, LinkedHashMap<String, RemoteEntry> out, int depth) {
        if (node == null || depth > 10) return;
        if (node instanceof JSONArray) { JSONArray a = (JSONArray) node; for (int i = 0; i < a.length(); i++) walkFiles(a.opt(i), out, depth + 1); return; }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node; RemoteEntry e = mapToRemote(o); if (e != null) merge(out, e); JSONArray names = o.names(); if (names == null) return;
        for (int i = 0; i < names.length(); i++) { Object child = o.opt(names.optString(i)); if (child instanceof JSONObject || child instanceof JSONArray) walkFiles(child, out, depth + 1); }
    }

    private static RemoteEntry mapToRemote(JSONObject o) {
        String rawUrl = firstStringDeep(o, "download_url", "downloadUrl", "url", "asset_pointer", "assetPointer");
        String fileId = first(firstStringDeep(o, "file_id", "fileId"), extractFileId(rawUrl)); String libraryFileId = firstStringDeep(o, "library_file_id", "libraryFileId");
        String id = first(fileId, libraryFileId, firstStringDeep(o, "id")); String name = firstStringDeep(o, "file_name", "filename", "fileName", "name", "title");
        if (fileId.isEmpty() && (id.isEmpty() || name.isEmpty())) return null;
        String lower = id.toLowerCase(Locale.ROOT); if (fileId.isEmpty() && libraryFileId.isEmpty() && !lower.startsWith("file_") && !lower.startsWith("file-")) return null;
        if (fileId.isEmpty()) fileId = extractFileId(id); if (fileId.isEmpty()) return null; if (name.isEmpty()) name = "ChatGPT file";
        String mime = firstStringDeep(o, "mime_type", "mimeType", "content_type", "contentType", "type"); long size = firstLongDeep(o, "file_size_bytes", "size_bytes", "size", "file_size", "fileSizeBytes", "fileSize");
        String conversationId = firstStringDeep(o, "conversation_id", "conversationId"); String stable = buildFileRef(fileId, conversationId);
        boolean image = mime.toLowerCase(Locale.ROOT).startsWith("image/") || isImageName(name) || isImageName(rawUrl); if (mime.isEmpty()) mime = guessMime(name, rawUrl, image);
        return new RemoteEntry(fileId, cleanName(name), stable, mime, size, conversationId, image);
    }

    private static RemoteEntry imageToRemote(JSONObject o) {
        if (o == null) return null; String raw = first(o.optString("download_url", ""), o.optString("url", ""), o.optString("asset_pointer", ""));
        String fileId = first(o.optString("file_id", ""), extractFileId(raw), extractFileId(o.optString("id", ""))); String id = first(fileId, o.optString("id", "")); if (id.isEmpty() && raw.isEmpty()) return null;
        String name = cleanName(first(o.optString("title", ""), o.optString("prompt", ""), "ChatGPT image")); if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg|webp|gif|heic|avif)$")) name += ".png";
        String conversationId = o.optString("conversation_id", "").trim(); String url = !fileId.isEmpty() ? buildFileRef(fileId, conversationId) : raw;
        return new RemoteEntry(id, name, url, "image/png", 0, conversationId, true);
    }

    static String buildFileRef(String fileId, String conversationId) {
        String id = extractFileId(fileId); if (id.isEmpty()) return fileId == null ? "" : fileId; String ref = "gptdrive://file/" + Uri.encode(id);
        if (conversationId != null && !conversationId.trim().isEmpty()) ref += "?conversation_id=" + Uri.encode(conversationId.trim()); return ref;
    }

    static String extractFileId(String value) { if (value == null || value.trim().isEmpty()) return ""; Matcher m = FILE_ID.matcher(value); return m.find() ? m.group(1) : ""; }
    static String extractConversationId(String value) { if (value == null || value.trim().isEmpty()) return ""; try { String c = Uri.parse(value).getQueryParameter("conversation_id"); return c == null ? "" : c; } catch (Exception e) { return ""; } }

    private static PageRequest libraryRequestForStrategy(int strategy, String cursor, int offset) {
        String c = cursor == null ? "" : cursor;
        try {
            switch (strategy) {
                case 0: return new PageRequest("/backend-api/files/library", new JSONObject().put("cursor", c).toString());
                case 1: return new PageRequest("/backend-api/files/library", new JSONObject().put("cursor", c).put("limit", 100).toString());
                case 2: return new PageRequest("/backend-api/files/library", new JSONObject().put("after", c).toString());
                case 3: return new PageRequest("/backend-api/files/library", new JSONObject().put("next_cursor", c).toString());
                case 4: return new PageRequest("/backend-api/files/library?cursor=" + Uri.encode(c), "{}");
                case 5: return new PageRequest("/backend-api/files/library", new JSONObject().put("offset", offset).put("limit", 100).toString());
                default: return new PageRequest("/backend-api/files/library", "{}");
            }
        } catch (Exception e) { return new PageRequest("/backend-api/files/library", "{}"); }
    }

    private static String findString(JSONObject o, int depth, String... keys) {
        if (o == null || depth > 4) return ""; for (String k : keys) { String v = o.optString(k, "").trim(); if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v; }
        for (String nested : new String[]{"pagination", "page_info", "pageInfo", "meta"}) { String v = findString(o.optJSONObject(nested), depth + 1, keys); if (!v.isEmpty()) return v; } return "";
    }

    private static boolean findBoolean(JSONObject o, int depth, String... keys) {
        if (o == null || depth > 4) return false; for (String k : keys) if (o.has(k) && o.optBoolean(k, false)) return true;
        for (String nested : new String[]{"pagination", "page_info", "pageInfo", "meta"}) if (findBoolean(o.optJSONObject(nested), depth + 1, keys)) return true; return false;
    }

    private static String firstStringDeep(JSONObject o, String... keys) {
        if (o == null) return ""; for (String key : keys) { String v = o.optString(key, "").trim(); if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v; }
        for (String nested : new String[]{"metadata", "file", "asset", "item", "data"}) { JSONObject n = o.optJSONObject(nested); if (n == null) continue; for (String key : keys) { String v = n.optString(key, "").trim(); if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v; } } return "";
    }

    private static long firstLongDeep(JSONObject o, String... keys) {
        if (o == null) return 0; for (String key : keys) { long v = o.optLong(key, 0); if (v > 0) return v; }
        for (String nested : new String[]{"metadata", "file", "asset", "item", "data"}) { JSONObject n = o.optJSONObject(nested); if (n == null) continue; for (String key : keys) { long v = n.optLong(key, 0); if (v > 0) return v; } } return 0;
    }

    private static String guessMime(String name, String url, boolean image) {
        String s = ((name == null ? "" : name) + " " + (url == null ? "" : url)).toLowerCase(Locale.ROOT);
        if (s.contains(".png")) return "image/png"; if (s.contains(".jpg") || s.contains(".jpeg")) return "image/jpeg"; if (s.contains(".webp")) return "image/webp"; if (s.contains(".gif")) return "image/gif";
        if (s.contains(".pdf")) return "application/pdf"; if (s.contains(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; if (s.contains(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (s.contains(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation"; if (s.contains(".zip")) return "application/zip"; if (s.contains(".apk")) return "application/vnd.android.package-archive";
        if (s.contains(".json")) return "application/json"; if (s.contains(".txt") || s.contains(".md")) return "text/plain"; return image ? "image/png" : "application/octet-stream";
    }

    private static boolean isImageName(String value) { if (value == null) return false; String s = value.toLowerCase(Locale.ROOT); return s.contains(".png") || s.contains(".jpg") || s.contains(".jpeg") || s.contains(".webp") || s.contains(".gif") || s.contains(".heic") || s.contains(".avif"); }
    private static String cleanName(String value) { if (value == null) return ""; String s = value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " "); return s.length() > 160 ? s.substring(0, 160) : s; }
    private static String first(String... values) { if (values != null) for (String v : values) if (v != null && !v.trim().isEmpty() && !"null".equalsIgnoreCase(v.trim())) return v.trim(); return ""; }

    private static void merge(Map<String, RemoteEntry> dst, RemoteEntry e) {
        if (e == null) return; String key = e.identity(); if (key.isEmpty()) return; RemoteEntry cur = dst.get(key); if (cur == null) { dst.put(key, e); return; }
        String name = cur.name; if ((name == null || name.isEmpty() || name.startsWith("ChatGPT")) && e.name != null && !e.name.isEmpty()) name = e.name;
        dst.put(key, new RemoteEntry(first(cur.id, e.id), name, first(cur.url, e.url), first(cur.mime, e.mime), cur.size > 0 ? cur.size : e.size, first(cur.conversationId, e.conversationId), cur.image || e.image));
    }

    private static final class RemoteEntry {
        final String id, name, url, mime, conversationId; final long size; final boolean image;
        RemoteEntry(String id, String name, String url, String mime, long size, String conversationId, boolean image) {
            this.id = id == null ? "" : id; this.name = name == null || name.trim().isEmpty() ? "ChatGPT item" : name.trim(); this.url = url == null ? "" : url; this.mime = mime == null ? "" : mime; this.size = Math.max(0, size); this.conversationId = conversationId == null ? "" : conversationId; this.image = image;
        }
        String identity() { return first(extractFileId(id), id, url); }
        boolean isImage() { return image || (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/")); }
    }
    private static final class Response { final int code; final String body; Response(int code, String body) { this.code = code; this.body = body == null ? "" : body; } }
    private static final class Page { final List<RemoteEntry> files; final String nextCursor; final boolean hasMore; Page(List<RemoteEntry> files, String nextCursor, boolean hasMore) { this.files = files; this.nextCursor = nextCursor == null ? "" : nextCursor; this.hasMore = hasMore; } }
    private static final class PageRequest { final String endpoint, payload; PageRequest(String endpoint, String payload) { this.endpoint = endpoint; this.payload = payload; } }
    private static final class LibraryResult { final List<RemoteEntry> files; final int pages; LibraryResult(List<RemoteEntry> files, int pages) { this.files = files; this.pages = pages; } }
}
