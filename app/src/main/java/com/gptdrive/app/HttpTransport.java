package com.gptdrive.app;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

interface HttpTransport {
    HttpResponse execute(HttpRequest request) throws Exception;
}

final class HttpRequest {
    final String method;
    final String url;
    final Map<String, String> headers;
    final byte[] body;
    final boolean followRedirects;
    final boolean retryable;
    final int connectTimeoutMs;
    final int readTimeoutMs;
    final int maxBodyBytes;

    HttpRequest(String method, String url, Map<String, String> headers, byte[] body,
                boolean followRedirects, boolean retryable,
                int connectTimeoutMs, int readTimeoutMs, int maxBodyBytes) {
        this.method = method;
        this.url = url;
        this.headers = headers == null ? Collections.emptyMap() : new LinkedHashMap<>(headers);
        this.body = body;
        this.followRedirects = followRedirects;
        this.retryable = retryable;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxBodyBytes = maxBodyBytes;
    }
}

final class HttpResponse {
    final int code;
    final byte[] body;
    final Map<String, String> headers;
    final String finalUrl;

    HttpResponse(int code, byte[] body, Map<String, String> headers, String finalUrl) {
        this.code = code;
        this.body = body == null ? new byte[0] : body;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.finalUrl = finalUrl == null ? "" : finalUrl;
    }

    String bodyUtf8() { return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
    String header(String name) {
        if (name == null) return "";
        String v = headers.get(name.toLowerCase(java.util.Locale.ROOT));
        return v == null ? "" : v;
    }
}
