package com.gptdrive.app;

import android.content.Context;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Resolves a stable ChatGPT file_id to a fresh signed download URL on every open. */
final class RemoteFileSource {
    private static final String CHATGPT = "https://chatgpt.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36 GPTDrive/0.6.3";

    private RemoteFileSource() {}

    static String extractFileId(String value) { return ChatGptApiClient.extractFileId(value); }

    static String stableUrl(String value) {
        String id = extractFileId(value);
        if (id.isEmpty()) return value == null ? "" : value;
        return ChatGptApiClient.buildFileRef(id, ChatGptApiClient.extractConversationId(value));
    }

    static boolean isTransientSignedUrl(String value) {
        if (value == null) return false;
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("oaiusercontent.com") || s.contains("oaidalle") || s.contains("x-goog-signature=") ||
                s.contains("x-amz-signature=") || (s.contains("sig=") && (s.contains("files.") || s.contains("blob")));
    }

    static String resolve(Context context, long accountId, String sourceUrl) throws Exception {
        String fileId = extractFileId(sourceUrl);
        if (!fileId.isEmpty()) {
            return ChatGptApiClient.resolveDownloadUrl(context, accountId, fileId,
                    ChatGptApiClient.extractConversationId(sourceUrl));
        }
        if (sourceUrl != null && (sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://"))) return sourceUrl;
        throw new java.io.IOException("Не удалось определить источник файла");
    }

    static Connection open(Context context, long accountId, String sourceUrl) throws Exception {
        String resolved = resolve(context, accountId, sourceUrl);
        Connection c = openOne(context, accountId, resolved, false);
        if (c.isSuccess()) return c;
        int code = c.code;
        c.close();
        if (sourceUrl != null && !sourceUrl.equals(resolved) && sourceUrl.startsWith("https://")) {
            Connection fallback = openOne(context, accountId, sourceUrl, true);
            if (fallback.isSuccess()) return fallback;
            fallback.close();
        }
        throw new java.io.IOException("HTTP " + code);
    }

    private static Connection openOne(Context context, long accountId, String url, boolean addSessionCookie) throws Exception {
        if (url == null || url.trim().isEmpty()) throw new java.io.IOException("Empty URL");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection(SmartDnsProxy.proxy(context));
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(12000);
        h.setReadTimeout(45000);
        h.setRequestProperty("User-Agent", UA);
        h.setRequestProperty("Accept", "*/*");
        h.setRequestProperty("Referer", CHATGPT + "/");

        boolean chatGptBackend = url.startsWith(CHATGPT + "/") || url.startsWith("https://www.chatgpt.com/");
        if (chatGptBackend) {
            ChatGptSessionStore.Session session = ChatGptSessionStore.load(context, accountId);
            if (session != null && session.accessToken != null && !session.accessToken.isEmpty()) {
                h.setRequestProperty("Authorization", "Bearer " + session.accessToken);
                if (session.accountId != null && !session.accountId.isEmpty())
                    h.setRequestProperty("chatgpt-account-id", session.accountId);
            }
            h.setRequestProperty("Origin", CHATGPT);
            h.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
        }
        if (addSessionCookie || chatGptBackend) {
            String cookie = AccountSession.cookieHeader(context, accountId, url);
            if (cookie != null && !cookie.trim().isEmpty()) h.setRequestProperty("Cookie", cookie);
        }
        h.connect();
        return new Connection(h, h.getResponseCode());
    }

    static final class Connection implements AutoCloseable {
        final HttpURLConnection connection;
        final int code;
        Connection(HttpURLConnection connection, int code) { this.connection = connection; this.code = code; }
        boolean isSuccess() { return code >= 200 && code < 400; }
        InputStream inputStream() throws Exception { return connection.getInputStream(); }
        String finalUrl() { return connection.getURL().toString(); }
        String contentType() { return connection.getContentType(); }
        long contentLength() { return connection.getContentLengthLong(); }
        @Override public void close() { connection.disconnect(); }
    }
}
