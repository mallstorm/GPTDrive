package com.gptdrive.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Lightweight first-page poller used by the persistent watcher; never performs a full sync. */
final class LibraryWatcherClient {
    private final android.content.Context context;
    private final long accountId;
    private ChatGptProvider provider;

    LibraryWatcherClient(android.content.Context context, long accountId) {
        this.context = context.getApplicationContext();
        this.accountId = accountId;
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
        ArrayList<ChatGptModels.RemoteFile> out = new ArrayList<>();
        if (page != null && page.files != null) out.addAll(page.files);
        return out;
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
        if (provider == null) provider = new ChatGptProvider(context, accountId);
        return provider.request(method, endpoint, payload);
    }
}
