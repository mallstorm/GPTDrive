package com.gptdrive.app;

import android.net.Uri;

import java.util.Locale;

/** In-memory, credential-free runtime diagnostics. */
final class DiagnosticsState {
    private static volatile String lastMethod = "";
    private static volatile String lastEndpoint = "";
    private static volatile int lastStatus;
    private static volatile long lastDurationMs;
    private static volatile long lastHttpAt;
    private static volatile int libraryStrategy = -1;
    private static volatile int libraryPages;
    private static volatile int libraryObjects;
    private static volatile String lastErrorSurface = "";
    private static volatile String lastError = "";
    private static volatile long lastErrorAt;

    private DiagnosticsState() {}

    static void recordHttp(String method, String url, int status, long durationMs) {
        lastMethod = method == null ? "" : method;
        lastEndpoint = sanitizeEndpoint(url);
        lastStatus = status;
        lastDurationMs = Math.max(0, durationMs);
        lastHttpAt = System.currentTimeMillis();
    }

    static void recordPagination(int strategy, int pages, int objects) {
        libraryStrategy = strategy;
        libraryPages = Math.max(0, pages);
        libraryObjects = Math.max(0, objects);
    }

    static void recordError(String surface, String message) {
        lastErrorSurface = surface == null ? "" : surface;
        lastError = redact(message);
        lastErrorAt = System.currentTimeMillis();
    }

    static Snapshot snapshot() {
        return new Snapshot(lastMethod, lastEndpoint, lastStatus, lastDurationMs, lastHttpAt,
                libraryStrategy, libraryPages, libraryObjects,
                lastErrorSurface, lastError, lastErrorAt);
    }

    static final class Snapshot {
        final String method, endpoint;
        final int status;
        final long durationMs, httpAt;
        final int strategy, pages, objects;
        final String errorSurface, error;
        final long errorAt;
        Snapshot(String method, String endpoint, int status, long durationMs, long httpAt,
                 int strategy, int pages, int objects, String errorSurface, String error, long errorAt) {
            this.method=method; this.endpoint=endpoint; this.status=status; this.durationMs=durationMs; this.httpAt=httpAt;
            this.strategy=strategy; this.pages=pages; this.objects=objects;
            this.errorSurface=errorSurface; this.error=error; this.errorAt=errorAt;
        }
    }

    private static String sanitizeEndpoint(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            Uri u = Uri.parse(value);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String path = u.getPath() == null ? "" : u.getPath();
            // Never keep signed query strings, OAuth codes, cursors or tokens in diagnostics.
            return host + path;
        } catch (Exception e) {
            int q = value.indexOf('?');
            String s = q >= 0 ? value.substring(0, q) : value;
            return s.length() > 220 ? s.substring(0, 220) : s;
        }
    }

    private static String redact(String message) {
        if (message == null) return "";
        String s = message
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~-]+", "Bearer <redacted>")
                .replaceAll("(?i)(access[_-]?token[=:\\s]+)[^\\s,}]+", "$1<redacted>")
                .replaceAll("(?i)(authorization[=:\\s]+)[^\\s,}]+", "$1<redacted>");
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
