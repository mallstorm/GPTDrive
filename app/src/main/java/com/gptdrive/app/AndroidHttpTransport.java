package com.gptdrive.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Android transport. Smart DNS is deliberately below the ChatGPT provider layer. */
final class AndroidHttpTransport implements HttpTransport {
    private final Context context;

    AndroidHttpTransport(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public HttpResponse execute(HttpRequest request) throws Exception {
        Exception last = null;
        int attempts = request.retryable ? 4 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                HttpResponse r = executeOnce(request);
                if (!request.retryable || !shouldRetry(r.code) || attempt + 1 >= attempts) return r;
                sleepBackoff(attempt, r.header("retry-after"));
            } catch (Exception e) {
                last = e;
                if (!request.retryable || attempt + 1 >= attempts) throw e;
                sleepBackoff(attempt, "");
            }
        }
        if (last != null) throw last;
        throw new java.io.IOException("HTTP transport failed");
    }

    private HttpResponse executeOnce(HttpRequest r) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(r.url).openConnection(SmartDnsProxy.proxy(context));
        h.setRequestMethod(r.method);
        h.setConnectTimeout(r.connectTimeoutMs > 0 ? r.connectTimeoutMs : 15000);
        h.setReadTimeout(r.readTimeoutMs > 0 ? r.readTimeoutMs : 30000);
        h.setInstanceFollowRedirects(r.followRedirects);
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) h.setRequestProperty(e.getKey(), e.getValue());
        }
        if (r.body != null) {
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(r.body.length);
            try (OutputStream out = h.getOutputStream()) { out.write(r.body); }
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        byte[] body = read(in, r.maxBodyBytes > 0 ? r.maxBodyBytes : 32 * 1024 * 1024);
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : h.getHeaderFields().entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
        }
        String finalUrl = h.getURL() == null ? r.url : h.getURL().toString();
        h.disconnect();
        return new HttpResponse(code, body, headers, finalUrl);
    }

    private static boolean shouldRetry(int code) {
        return code == 429 || code >= 500;
    }

    private static void sleepBackoff(int attempt, String retryAfter) {
        long ms = Math.min(8000L, 700L * (1L << Math.max(0, attempt)));
        if (retryAfter != null && !retryAfter.trim().isEmpty()) {
            try { ms = Math.max(ms, Long.parseLong(retryAfter.trim()) * 1000L); }
            catch (Exception ignored) {}
        }
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static byte[] read(InputStream in, int limit) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = input.read(buf)) != -1) {
                if (total + n > limit) {
                    int allowed = Math.max(0, limit - total);
                    if (allowed > 0) out.write(buf, 0, allowed);
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        }
    }
}
