package com.gptdrive.app;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight first-page poller used by the persistent watcher; never performs a full sync. */
final class LibraryWatcherClient {
    private static final String BASE = "https://chatgpt.com";
    private final Context context;
    private final long accountId;
    private final HttpTransport transport;
    private boolean bootstrapped;

    LibraryWatcherClient(Context context, long accountId) {
        this.context = context.getApplicationContext();
        this.accountId = accountId;
        this.transport = new AndroidHttpTransport(this.context);
    }

    ArrayList<ChatGptModels.RemoteFile> pollRecent() throws Exception {
        LinkedHashMap<String, ChatGptModels.RemoteFile> out = new LinkedHashMap<>();
        for (ChatGptModels.RemoteFile f : loadLibraryFirstPage()) ChatGptModels.merge(out, f);
        for (ChatGptModels.RemoteFile f : loadRecentImages(40)) ChatGptModels.merge(out, f);
        return new ArrayList<>(out.values());
    }

    private ArrayList<ChatGptModels.RemoteFile> loadLibraryFirstPage() throws Exception {
        HttpResponse response = backend("POST", "/backend-api/files/library", "{}");
        ChatGptProvider.validateLibraryResponse(response);
        ChatGptModels.LibraryPage page = ChatGptResponseParser.decodeLibraryPage(response.bodyUtf8());
        return new ArrayList<>(page == null || page.files == null
                ? java.util.Collections.emptyList() : page.files);
    }

    private ArrayList<ChatGptModels.RemoteFile> loadRecentImages(int limit) throws Exception {
        int safeLimit = Math.max(1, Math.min(100, limit));
        HttpResponse response = backend("GET", "/backend-api/my/recent/image_gen?limit=" + safeLimit, null);
        if (response.code == 401 || response.code == 403)
            throw new ChatGptProvider.AuthException("ChatGPT session недоступна (HTTP " + response.code + ")");
        if (response.code == 429)
            throw new ChatGptProvider.RateLimitException("ChatGPT временно ограничил проверку новых изображений");
        if (response.code != 200) return new ArrayList<>();

        JSONObject root = new JSONObject(response.bodyUtf8());
        JSONArray items = root.optJSONArray("items");
        ArrayList<ChatGptModels.RemoteFile> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            ChatGptModels.RemoteFile f = ChatGptResponseParser.imageToRemote(items.optJSONObject(i));
            if (f != null) out.add(f);
        }
        return out;
    }

    private HttpResponse backend(String method, String endpoint, String payload) throws Exception {
        ChatGptSessionStore.Session session = ChatGptSessionStore.load(context, accountId);
        if (session == null || session.accessToken == null || session.accessToken.trim().isEmpty())
            throw new ChatGptProvider.AuthException("Нет backend-сессии ChatGPT");
        bootstrap();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + session.accessToken);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
        headers.put("Referer", BASE + "/library");
        headers.put("Origin", BASE);
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");
        headers.put("User-Agent", AppIdentity.userAgent());
        if (payload != null) headers.put("Content-Type", "application/json");
        if (session.accountId != null && !session.accountId.isEmpty())
            headers.put("chatgpt-account-id", session.accountId);
        String cookie = AccountSession.cookieHeader(context, accountId, BASE);
        if (cookie != null && !cookie.trim().isEmpty()) headers.put("Cookie", cookie);

        byte[] body = payload == null ? null : payload.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = new HttpRequest(method, BASE + endpoint, headers, body,
                true, true, 12_000, 22_000, 4 * 1024 * 1024);
        HttpResponse response = transport.execute(request);
        if (response.code == 403 && "challenge".equalsIgnoreCase(response.header("cf-mitigated"))) {
            bootstrapped = false;
            bootstrap();
            response = transport.execute(request);
        }
        return response;
    }

    private void bootstrap() {
        if (bootstrapped) return;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", AppIdentity.userAgent());
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            String cookie = AccountSession.cookieHeader(context, accountId, BASE);
            if (cookie != null && !cookie.trim().isEmpty()) headers.put("Cookie", cookie);
            transport.execute(new HttpRequest("GET", BASE + "/", headers, null,
                    true, false, 10_000, 15_000, 256 * 1024));
        } catch (Exception ignored) {}
        bootstrapped = true;
    }
}
