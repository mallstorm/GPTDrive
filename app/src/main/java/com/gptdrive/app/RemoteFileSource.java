package com.gptdrive.app;

import android.content.Context;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves ChatGPT file references to a stable authenticated content endpoint.
 * Signed oaiusercontent URLs are deliberately treated as a fallback only.
 */
final class RemoteFileSource {
    private static final Pattern FILE_ID = Pattern.compile("(file[-_][A-Za-z0-9_-]{6,})", Pattern.CASE_INSENSITIVE);
    private static final String CHATGPT = "https://chatgpt.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 GPTDrive/0.5.1";

    private RemoteFileSource() {}

    static String extractFileId(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        Matcher m = FILE_ID.matcher(value);
        return m.find() ? m.group(1) : "";
    }

    static String stableUrl(String value) {
        String id = extractFileId(value);
        if (id.isEmpty()) return value == null ? "" : value;
        return CHATGPT + "/backend-api/files/" + id + "/content";
    }

    static boolean isTransientSignedUrl(String value) {
        if (value == null) return false;
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains("oaiusercontent.com") || s.contains("oaidalle") ||
                s.contains("x-goog-signature=") || s.contains("x-amz-signature=") ||
                s.contains("sig=") && (s.contains("files.") || s.contains("blob"));
    }

    static Connection open(Context context, long accountId, String sourceUrl) throws Exception {
        String stable = stableUrl(sourceUrl);
        Connection first = openOne(context, accountId, stable);
        if (first.isSuccess()) return first;
        int firstCode = first.code;
        first.close();

        if (sourceUrl != null && !sourceUrl.isEmpty() && !sourceUrl.equals(stable)) {
            Connection fallback = openOne(context, accountId, sourceUrl);
            if (fallback.isSuccess()) return fallback;
            fallback.close();
        }
        throw new java.io.IOException("HTTP " + firstCode);
    }

    private static Connection openOne(Context context, long accountId, String url) throws Exception {
        if (url == null || url.trim().isEmpty()) throw new java.io.IOException("Empty URL");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(12000);
        h.setReadTimeout(45000);
        h.setRequestProperty("User-Agent", UA);
        h.setRequestProperty("Accept", "*/*");
        h.setRequestProperty("Referer", CHATGPT + "/");
        h.setRequestProperty("Origin", CHATGPT);
        String cookie = AccountSession.cookieHeader(context, accountId, url);
        if (cookie != null && !cookie.trim().isEmpty()) h.setRequestProperty("Cookie", cookie);
        h.connect();
        int code = h.getResponseCode();
        return new Connection(h, code);
    }

    static final class Connection implements AutoCloseable {
        final HttpURLConnection connection;
        final int code;

        Connection(HttpURLConnection connection, int code) {
            this.connection = connection;
            this.code = code;
        }

        boolean isSuccess() { return code >= 200 && code < 400; }
        InputStream inputStream() throws Exception { return connection.getInputStream(); }
        String finalUrl() { return connection.getURL().toString(); }
        String contentType() { return connection.getContentType(); }
        long contentLength() { return connection.getContentLengthLong(); }

        @Override public void close() { connection.disconnect(); }
    }
}
