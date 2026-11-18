package com.gptdrive.app;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** ChatGPT backend provider. No SQLite and no DNS policy lives here. */
final class ChatGptProvider {
    interface Progress { void onProgress(String phase, int part, int total, int objects); }
    interface PageConsumer { void onPage(int pages, List<ChatGptModels.RemoteFile> files, int objects) throws Exception; }

    static class ProviderException extends Exception { ProviderException(String m) { super(m); } }
    static final class AuthException extends ProviderException { AuthException(String m) { super(m); } }
    static final class RateLimitException extends ProviderException { RateLimitException(String m) { super(m); } }
    static final class PaginationException extends ProviderException { PaginationException(String m) { super(m); } }
    static final class SchemaException extends ProviderException { SchemaException(String m) { super(m); } }

    private static final String BASE = "https://chatgpt.com";

    private final Context context;
    private final long accountId;
    private final HttpTransport transport;
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> BOOTSTRAPPED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int lastLibraryStrategy = -1;

    ChatGptProvider(Context context, long accountId) throws AuthException {
        this.context = context.getApplicationContext();
        this.accountId = accountId;
        this.transport = new AndroidHttpTransport(this.context);
        requireSession();
    }

    int lastLibraryStrategy() { return lastLibraryStrategy; }

    /** Shared authenticated request path (token refresh, cookies, challenge bootstrap). */
    HttpResponse request(String method, String endpoint, String payload) throws Exception {
        return backend(method, endpoint, payload, true, true);
    }

    ChatGptModels.LibrarySnapshot loadLibrary(int maxPages, Progress progress) throws Exception {
        return loadLibrary(maxPages, progress, null);
    }

    ChatGptModels.LibrarySnapshot loadLibrary(int maxPages, Progress progress, PageConsumer consumer) throws Exception {
        try {
            LibraryPaginationEngine.Result result = LibraryPaginationEngine.load(
                    request -> validateLibraryResponse(backend("POST", request.endpoint, request.payload, true, true)),
                    ChatGptProvider::ensureLibraryStatus,
                    maxPages,
                    new LibraryPaginationEngine.Listener() {
                        @Override public void onPage(int pages, int objects) {
                            if (progress != null) progress.onProgress("Файлы", 1, 4, objects);
                        }

                        @Override public void onPageItems(int pages, int objects,
                                                          List<ChatGptModels.RemoteFile> pageFiles) throws Exception {
                            if (consumer != null && pageFiles != null && !pageFiles.isEmpty())
                                consumer.onPage(pages, pageFiles, objects);
                        }
                    });
            lastLibraryStrategy = result.strategy;
            return new ChatGptModels.LibrarySnapshot(result.files, result.pages, result.lastMeta, result.strategy);
        } catch (LibraryPaginationEngine.PaginationFailure e) {
            throw new PaginationException(e.getMessage());
        }
    }

    /**
     * HTTP 200 is not enough to call Library synchronized. ChatGPT can return an HTML shell,
     * a WARNING_BANNER-only object or another non-Library payload with status 200. Reject those
     * before the pagination engine can turn them into a fake empty successful snapshot.
     */
    static HttpResponse validateLibraryResponse(HttpResponse r) throws ProviderException {
        ensureLibraryStatus(r);
        String body = r.bodyUtf8();
        Object parsed = ChatGptResponseParser.parse(body);
        if (parsed == null) {
            throw new SchemaException("ChatGPT Library вернула не JSON; синхронизация не выполнена");
        }
        if (parsed instanceof JSONArray) return r;
        if (!(parsed instanceof JSONObject)) {
            throw new SchemaException("ChatGPT Library вернула неизвестный формат; синхронизация не выполнена");
        }

        JSONObject root = (JSONObject) parsed;
        if (root.has("WARNING_BANNER") && !hasLibraryEnvelope(root)) {
            throw new AuthException("ChatGPT backend-сессия не подтверждена (WARNING_BANNER). Обнови вход ChatGPT");
        }

        try { SchemaProbe.captureLibrary(root); } catch (Exception ignored) {}
        ChatGptModels.LibraryPage page = ChatGptResponseParser.decodeLibraryPage(body);
        if (page != null && page.files != null && !page.files.isEmpty()) return r;
        if (hasLibraryEnvelope(root)) return r;

        throw new SchemaException("ChatGPT Library вернула JSON без данных Library; ложный успех заблокирован");
    }

    private static boolean hasLibraryEnvelope(JSONObject root) {
        if (root == null) return false;
        String[] direct = {
                "items", "files", "library", "library_items", "assets", "results",
                "pagination", "page_info", "pageInfo", "meta", "next_cursor", "nextCursor",
                "has_more", "hasMore", "total", "total_count", "totalCount", "cursor"
        };
        for (String key : direct) if (root.has(key)) return true;

        Object data = root.opt("data");
        if (data instanceof JSONArray) return true;
        if (data instanceof JSONObject) {
            JSONObject o = (JSONObject) data;
            for (String key : direct) if (o.has(key)) return true;
        }
        return false;
    }

    List<ChatGptModels.RemoteFile> loadImages(Progress progress) throws Exception {
        LinkedHashMap<String, ChatGptModels.RemoteFile> out = new LinkedHashMap<>();
        String cursor = "";
        for (int pageNo = 0; pageNo < 100; pageNo++) {
            String ep = "/backend-api/my/recent/image_gen?limit=100" +
                    (cursor.isEmpty() ? "" : "&after=" + Uri.encode(cursor));
            HttpResponse r = backend("GET", ep, null, true, true);
            if (r.code == 401 || r.code == 403) throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
            if (r.code != 200) throw new java.io.IOException("ChatGPT: HTTP " + r.code + " на странице " + (pageNo + 1));
            JSONObject root = new JSONObject(r.bodyUtf8());
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) break;
            try { SchemaProbe.captureImage(items.optJSONObject(0)); } catch (Exception ignored) {}
            for (int i = 0; i < items.length(); i++) {
                ChatGptModels.RemoteFile f = ChatGptResponseParser.imageToRemote(items.optJSONObject(i));
                if (f != null) ChatGptModels.merge(out, f);
            }
            if (progress != null) progress.onProgress("Изображения", 2, 4, out.size());
            String next = root.optString("cursor", "").trim();
            if (next.isEmpty() || next.equals(cursor)) break;
            cursor = next;
            if (pageNo == 99) throw new java.io.IOException("Список изображений длиннее 100 страниц — загрузка неполная");
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
            if (r.code != 200) throw new java.io.IOException("ChatGPT: HTTP " + r.code + " на странице " + (pageNo + 1));
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
            if (pageNo == 99) throw new java.io.IOException("Список проектов длиннее 100 страниц — загрузка неполная");
        }
        return out;
    }

    /** Archived metadata used by the current Archive surface. */
    List<ChatGptModels.RemoteConversation> loadArchived() throws Exception {
        return ChatGptConversationsApi.list(
                ep -> backend("GET", ep, null, true, true),
                ChatGptProvider::ensureConversationStatus,
                true,
                100);
    }

    /** All conversation summaries for the future Chat Mirror/indexer. */
    List<ChatGptModels.RemoteConversation> loadConversations() throws Exception {
        return ChatGptConversationsApi.list(
                ep -> backend("GET", ep, null, true, true),
                ChatGptProvider::ensureConversationStatus,
                false,
                500);
    }

    /** Full raw conversation mapping. No mapping nodes are discarded. */
    ChatGptModels.RemoteConversation loadConversation(String conversationId) throws Exception {
        return ChatGptConversationsApi.load(
                ep -> backend("GET", ep, null, true, true),
                ChatGptProvider::ensureConversationStatus,
                conversationId);
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
        if (session.expired()) session = refreshOrFail();
        bootstrap();
        HttpResponse response = send(session, method, endpoint, payload, retryable, followRedirects);
        if (response.code == 401) {
            session = refreshOrFail();
            response = send(session, method, endpoint, payload, retryable, followRedirects);
            if (response.code == 401) {
                AccountAuth.markNeedsLogin(context, accountId, "HTTP 401");
            }
        }
        return response;
    }

    /** One refresh attempt; failure flips the account to "needs login" and notifies once. */
    private ChatGptSessionStore.Session refreshOrFail() throws AuthException {
        if (ChatGptSessionRefresher.refresh(context, accountId)) {
            ChatGptSessionStore.Session fresh = ChatGptSessionStore.load(context, accountId);
            if (fresh != null && !fresh.accessToken.isEmpty()) return fresh;
        }
        AccountAuth.markNeedsLogin(context, accountId, "token refresh failed");
        throw new AuthException("ChatGPT session истекла — нужен новый вход");
    }

    private HttpResponse send(ChatGptSessionStore.Session session, String method, String endpoint, String payload,
                              boolean retryable, boolean followRedirects) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + session.accessToken);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
        h.put("Referer", BASE + "/library");
        h.put("Origin", BASE);
        h.put("Cache-Control", "no-cache");
        h.put("Pragma", "no-cache");
        h.put("User-Agent", AppIdentity.userAgent());
        if (payload != null) h.put("Content-Type", "application/json");
        if (session.accountId != null && !session.accountId.isEmpty()) h.put("chatgpt-account-id", session.accountId);
        String cookie = AccountSession.cookieHeader(context, accountId, BASE);
        if (cookie != null && !cookie.trim().isEmpty()) h.put("Cookie", cookie);
        byte[] body = payload == null ? null : payload.getBytes(StandardCharsets.UTF_8);
        HttpRequest req = new HttpRequest(method, endpoint.startsWith("http") ? endpoint : BASE + endpoint, h, body,
                followRedirects, retryable, 15000, 30000, 32 * 1024 * 1024);
        HttpResponse response = transport.execute(req);
        if (response.code == 403 && "challenge".equalsIgnoreCase(response.header("cf-mitigated"))) {
            BOOTSTRAPPED.remove(accountId);
            bootstrap();
            response = transport.execute(req);
        }
        return response;
    }

    /** Warm-up GET of the home page, at most once per account per 20 minutes for the whole process. */
    private void bootstrap() {
        Long last = BOOTSTRAPPED.get(accountId);
        if (last != null && System.currentTimeMillis() - last < 20L * 60L * 1000L) return;
        BOOTSTRAPPED.put(accountId, System.currentTimeMillis());
        try {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", AppIdentity.userAgent());
            h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            h.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            String cookie = AccountSession.cookieHeader(context, accountId, BASE);
            if (cookie != null && !cookie.trim().isEmpty()) h.put("Cookie", cookie);
            transport.execute(new HttpRequest("GET", BASE + "/", h, null, true, false, 12000, 18000, 2 * 1024 * 1024));
        } catch (Exception ignored) {}
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
            if ("challenge".equalsIgnoreCase(r.header("cf-mitigated"))) throw new AuthException("ChatGPT Library: Cloudflare challenge. Обнови вход ChatGPT");
            throw new AuthException("ChatGPT session недоступна (HTTP 403)");
        }
        if (r.code == 429) throw new RateLimitException("ChatGPT временно ограничил Library (HTTP 429)");
        throw new ProviderException("ChatGPT Library HTTP " + r.code);
    }

    private static void ensureConversationStatus(HttpResponse r) throws ProviderException {
        if (r.code == 200) return;
        if (r.code == 401 || r.code == 403)
            throw new AuthException("ChatGPT conversations: сессия недоступна (HTTP " + r.code + ")");
        if (r.code == 429)
            throw new RateLimitException("ChatGPT временно ограничил Conversations (HTTP 429)");
        throw new ProviderException("ChatGPT Conversations HTTP " + r.code);
    }

    private static boolean isHttp(String value) {
        if (value == null) return false;
        String s = value.toLowerCase(Locale.ROOT);
        return s.startsWith("https://") || s.startsWith("http://");
    }
}
