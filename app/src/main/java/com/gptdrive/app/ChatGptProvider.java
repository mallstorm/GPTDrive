package com.gptdrive.app;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** ChatGPT backend provider. No SQLite and no DNS policy lives here. */
final class ChatGptProvider {
    interface Progress { void onProgress(String phase, int part, int total, int objects); }

    static class ProviderException extends Exception { ProviderException(String m) { super(m); } }
    static final class AuthException extends ProviderException { AuthException(String m) { super(m); } }
    static final class RateLimitException extends ProviderException { RateLimitException(String m) { super(m); } }
    static final class PaginationException extends ProviderException { PaginationException(String m) { super(m); } }
    static final class SchemaException extends ProviderException { SchemaException(String m) { super(m); } }

    private static final String BASE = "https://chatgpt.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36 GPTDrive/0.8.0";

    private final Context context;
    private final long accountId;
    private final HttpTransport transport;
    private boolean bootstrapped;
    private volatile int lastLibraryStrategy = -1;

    ChatGptProvider(Context context, long accountId) throws AuthException {
        this.context = context.getApplicationContext();
        this.accountId = accountId;
        this.transport = new AndroidHttpTransport(this.context);
        requireSession();
    }

    int lastLibraryStrategy() { return lastLibraryStrategy; }

    ChatGptModels.LibrarySnapshot loadLibrary(int maxPages, Progress progress) throws Exception {
        if (maxPages <= 0) maxPages = 1;
        LinkedHashMap<String, ChatGptModels.RemoteFile> merged = new LinkedHashMap<>();
        Set<String> seenCursors = new LinkedHashSet<>();
        int strategy = -1;
        String cursor = "";
        int pages = 0;
        ChatGptModels.LibraryPageMeta lastMeta = new ChatGptModels.LibraryPageMeta();
        PageRequest request = new PageRequest("/backend-api/files/library", "{}");

        while (pages < maxPages) {
            HttpResponse response = backend("POST", request.endpoint, request.payload, true, true);
            ensureLibraryStatus(response);
            ChatGptModels.LibraryPage page = ChatGptResponseParser.decodeLibraryPage(response.bodyUtf8());
            pages++;
            int before = merged.size();
            for (ChatGptModels.RemoteFile f : page.files) ChatGptModels.merge(merged, f);
            int added = merged.size() - before;
            lastMeta = page.meta;
            if (progress != null) progress.onProgress("Файлы", 1, 4, merged.size());

            if (pages >= maxPages || (!page.meta.hasMore && page.meta.nextCursor.isEmpty())) break;
            String next = page.meta.nextCursor;
            if (next.isEmpty()) {
                strategy = 5;
                request = requestForStrategy(strategy, "", merged.size());
                continue;
            }
            if (seenCursors.contains(next)) throw new PaginationException("Library cursor зациклился");
            seenCursors.add(next);

            if (strategy >= 0) {
                cursor = next;
                request = requestForStrategy(strategy, cursor, merged.size());
                continue;
            }

            boolean accepted = false;
            for (int candidate = 0; candidate <= 4; candidate++) {
                PageRequest probe = requestForStrategy(candidate, next, merged.size());
                HttpResponse r2;
                try { r2 = backend("POST", probe.endpoint, probe.payload, true, true); }
                catch (Exception e) { continue; }
                if (r2.code != 200) continue;
                ChatGptModels.LibraryPage p2 = ChatGptResponseParser.decodeLibraryPage(r2.bodyUtf8());
                int newCount = 0;
                for (ChatGptModels.RemoteFile f : p2.files) {
                    String id = f.identity();
                    if (!id.isEmpty() && !merged.containsKey(id)) newCount++;
                }
                boolean progressed = newCount > 0 || !p2.meta.nextCursor.equals(next) ||
                        (!p2.meta.hasMore && p2.meta.nextCursor.isEmpty());
                if (!progressed) continue;

                strategy = candidate;
                lastLibraryStrategy = candidate;
                cursor = p2.meta.nextCursor;
                pages++;
                for (ChatGptModels.RemoteFile f : p2.files) ChatGptModels.merge(merged, f);
                lastMeta = p2.meta;
                if (progress != null) progress.onProgress("Файлы", 1, 4, merged.size());
                accepted = true;
                if (pages >= maxPages || (!p2.meta.hasMore && p2.meta.nextCursor.isEmpty())) {
                    request = null;
                } else {
                    if (cursor.isEmpty()) strategy = 5;
                    request = requestForStrategy(strategy, cursor, merged.size());
                }
                break;
            }
            if (!accepted) {
                strategy = 5;
                lastLibraryStrategy = 5;
                request = requestForStrategy(strategy, "", merged.size());
            }
            if (request == null) break;
            if (added == 0 && strategy == 5 && pages > 2)
                throw new PaginationException("Library backend повторяет одну и ту же страницу");
        }
        lastLibraryStrategy = strategy;
        return new ChatGptModels.LibrarySnapshot(new ArrayList<>(merged.values()), pages, lastMeta, strategy);
    }

    List<ChatGptModels.RemoteFile> loadImages(Progress progress) throws Exception {
        LinkedHashMap<String, ChatGptModels.RemoteFile> out = new LinkedHashMap<>();
        String cursor = "";
        for (int pageNo = 0; pageNo < 100; pageNo++) {
            String ep = "/backend-api/my/recent/image_gen?limit=100" +
                    (cursor.isEmpty() ? "" : "&after=" + Uri.encode(cursor));
            HttpResponse r = backend("GET", ep, null, true, true);
            if (r.code == 401 || r.code == 403) throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
            if (r.code != 200) break;
            JSONObject root = new JSONObject(r.bodyUtf8());
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                ChatGptModels.RemoteFile f = ChatGptResponseParser.imageToRemote(items.optJSONObject(i));
                if (f != null) ChatGptModels.merge(out, f);
            }
            if (progress != null) progress.onProgress("Изображения", 2, 4, out.size());
            String next = root.optString("cursor", "").trim();
            if (next.isEmpty() || next.equals(cursor)) break;
            cursor = next;
        }
        return new ArrayList<>(out.values());
    }

    List<ChatGptModels.RemoteProject> loadProjects() throws Exception {
        ArrayList<ChatGptModels.RemoteProject> out = new ArrayList<>();
        String cursor = "";
        for (int pageNo = 0; pageNo < 100; pageNo++) {
            String ep = "/backend-api/gizmos/snorlax/sidebar?owned_only=true&conversations_per_gizmo=100" +
                    (cursor.isEmpty() ? "" : "&cursor=" + Uri.encode(cursor));
            HttpResponse r = backend("GET", ep, null, true, true);
            if (r.code == 401 || r.code == 403) throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
            if (r.code != 200) break;
            JSONObject root = new JSONObject(r.bodyUtf8());
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                JSONObject outer = item.optJSONObject("gizmo");
                if (outer == null) continue;
                JSONObject g = outer.optJSONObject("gizmo");
                if (g == null) g = outer;
                String projectId = g.optString("id", "").trim();
                if (projectId.isEmpty()) continue;
                ChatGptModels.RemoteProject p = new ChatGptModels.RemoteProject();
                p.id = projectId;
                JSONObject display = g.optJSONObject("display");
                p.name = ChatGptResponseParser.cleanName(ChatGptModels.first(
                        display == null ? "" : display.optString("name", ""), g.optString("name", ""), "Project"));
                for (String key : new String[]{"conversations", "recent_conversations"}) {
                    JSONArray cvs = outer.optJSONArray(key);
                    if (cvs == null) continue;
                    for (int j = 0; j < cvs.length(); j++) {
                        JSONObject cv = cvs.optJSONObject(j);
                        if (cv == null) continue;
                        String id = ChatGptModels.first(cv.optString("id", ""), cv.optString("conversation_id", ""), cv.optString("conversationId", ""));
                        if (!id.isEmpty() && !p.conversationIds.contains(id)) p.conversationIds.add(id);
                    }
                }
                JSONArray files = outer.optJSONArray("files");
                if (files != null) for (int j = 0; j < files.length(); j++) {
                    JSONObject fm = files.optJSONObject(j);
                    if (fm == null) continue;
                    ChatGptModels.RemoteFile f = ChatGptResponseParser.mapToRemote(fm);
                    if (f == null) {
                        String fid = ChatGptModels.first(fm.optString("file_id", ""), fm.optString("fileId", ""), fm.optString("id", ""));
                        if (!fid.isEmpty()) {
                            f = new ChatGptModels.RemoteFile();
                            f.fileId = ChatGptModels.extractFileId(fid);
                            if (f.fileId.isEmpty()) f.fileId = fid;
                            f.name = ChatGptResponseParser.cleanName(ChatGptModels.first(fm.optString("name", ""), fm.optString("file_name", ""), "ChatGPT file"));
                            f.mimeType = ChatGptModels.first(fm.optString("type", ""), fm.optString("mime_type", ""), fm.optString("content_type", ""));
                            f.sizeBytes = fm.optLong("size", fm.optLong("file_size", fm.optLong("file_size_bytes", 0)));
                        }
                    }
                    if (f != null) {
                        f.sourceFlags |= ChatGptModels.SRC_PROJECT;
                        p.files.add(f);
                    }
                }
                out.add(p);
            }
            String next = root.optString("cursor", "").trim();
            if (next.isEmpty() || next.equals(cursor)) break;
            cursor = next;
        }
        return out;
    }

    List<ChatGptModels.RemoteConversation> loadArchived() throws Exception {
        ArrayList<ChatGptModels.RemoteConversation> out = new ArrayList<>();
        int offset = 0;
        int limit = 100;
        for (int page = 0; page < 100; page++) {
            HttpResponse r = backend("GET", "/backend-api/conversations?offset=" + offset + "&limit=" + limit + "&order=updated&is_archived=true", null, true, true);
            if (r.code == 401 || r.code == 403) throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
            if (r.code != 200) break;
            JSONObject root = new JSONObject(r.bodyUtf8());
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) break;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                String id = ChatGptModels.first(o.optString("id", ""), o.optString("conversation_id", ""));
                if (id.isEmpty()) continue;
                ChatGptModels.RemoteConversation c = new ChatGptModels.RemoteConversation();
                c.id = id;
                c.title = ChatGptResponseParser.cleanName(ChatGptModels.first(o.optString("title", ""), "ChatGPT chat"));
                c.archived = true;
                out.add(c);
            }
            int total = root.optInt("total", root.optInt("total_count", 0));
            offset += items.length();
            if (items.length() < limit || (total > 0 && offset >= total)) break;
        }
        return out;
    }

    String resolveDownload(String fileId, String conversationId) throws Exception {
        String fid = ChatGptModels.extractFileId(fileId);
        if (fid.isEmpty()) fid = fileId == null ? "" : fileId.trim();
        if (fid.isEmpty()) throw new java.io.IOException("У файла нет file_id");
        String q = "?inline=false";
        if (conversationId != null && !conversationId.trim().isEmpty()) q += "&conversation_id=" + Uri.encode(conversationId.trim());
        String[] candidates = {
                "/backend-api/files/download/" + Uri.encode(fid) + q,
                "/backend-api/files/" + Uri.encode(fid) + "/download" + q
        };
        for (String ep : candidates) {
            HttpResponse r;
            try { r = backend("GET", ep, null, true, false); }
            catch (Exception e) { continue; }
            if (r.code >= 300 && r.code < 400) {
                String loc = r.header("location");
                if (isHttp(loc)) return loc;
            }
            if (r.code != 200) continue;
            try {
                Object parsed = ChatGptResponseParser.parse(r.bodyUtf8());
                if (parsed instanceof JSONObject) {
                    JSONObject o = (JSONObject) parsed;
                    String u = ChatGptModels.first(o.optString("download_url", ""), o.optString("downloadUrl", ""), o.optString("url", ""));
                    if (isHttp(u)) return u;
                }
            } catch (Exception ignored) {}
        }
        throw new java.io.IOException("ChatGPT не выдал свежую подписанную ссылку на файл");
    }

    private HttpResponse backend(String method, String endpoint, String payload, boolean retryable, boolean followRedirects) throws Exception {
        ChatGptSessionStore.Session session = requireSession();
        bootstrap();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + session.accessToken);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
        h.put("Referer", BASE + "/library");
        h.put("Origin", BASE);
        h.put("Cache-Control", "no-cache");
        h.put("Pragma", "no-cache");
        h.put("User-Agent", UA);
        if (payload != null) h.put("Content-Type", "application/json");
        if (session.accountId != null && !session.accountId.isEmpty()) h.put("chatgpt-account-id", session.accountId);
        String cookie = AccountSession.cookieHeader(context, accountId, BASE);
        if (cookie != null && !cookie.trim().isEmpty()) h.put("Cookie", cookie);
        byte[] body = payload == null ? null : payload.getBytes(StandardCharsets.UTF_8);
        HttpRequest req = new HttpRequest(method, endpoint.startsWith("http") ? endpoint : BASE + endpoint, h, body,
                followRedirects, retryable, 15000, 30000, 32 * 1024 * 1024);
        HttpResponse response = transport.execute(req);
        if (response.code == 403 && "challenge".equalsIgnoreCase(response.header("cf-mitigated"))) {
            bootstrapped = false;
            bootstrap();
            response = transport.execute(req);
        }
        return response;
    }

    private void bootstrap() {
        if (bootstrapped) return;
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", UA);
            h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            h.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            String cookie = AccountSession.cookieHeader(context, accountId, BASE);
            if (cookie != null && !cookie.trim().isEmpty()) h.put("Cookie", cookie);
            transport.execute(new HttpRequest("GET", BASE + "/", h, null, true, false, 12000, 18000, 256 * 1024));
        } catch (Exception ignored) {}
        bootstrapped = true;
    }

    private ChatGptSessionStore.Session requireSession() throws AuthException {
        ChatGptSessionStore.Session s = ChatGptSessionStore.load(context, accountId);
        if (s == null || s.accessToken == null || s.accessToken.trim().isEmpty()) throw new AuthException("Нет backend-сессии ChatGPT");
        return s;
    }

    private static void ensureLibraryStatus(HttpResponse r) throws ProviderException {
        if (r.code == 200) return;
        if (r.code == 401) throw new AuthException("ChatGPT session истекла (HTTP 401)");
        if (r.code == 403) {
            if ("challenge".equalsIgnoreCase(r.header("cf-mitigated"))) throw new AuthException("ChatGPT Library: Cloudflare challenge. Нужен свежий Session JSON");
            throw new AuthException("ChatGPT session недоступна (HTTP 403)");
        }
        if (r.code == 429) throw new RateLimitException("ChatGPT временно ограничил Library (HTTP 429)");
        throw new ProviderException("ChatGPT Library HTTP " + r.code);
    }

    private static PageRequest requestForStrategy(int strategy, String cursor, int offset) {
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
        } catch (Exception e) {
            return new PageRequest("/backend-api/files/library", "{}");
        }
    }

    private static boolean isHttp(String value) {
        if (value == null) return false;
        String s = value.toLowerCase(Locale.ROOT);
        return s.startsWith("https://") || s.startsWith("http://");
    }

    private static final class PageRequest {
        final String endpoint;
        final String payload;
        PageRequest(String endpoint, String payload) { this.endpoint = endpoint; this.payload = payload; }
    }
}
