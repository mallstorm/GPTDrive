package com.gptdrive.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory diagnostics only. Never records request headers, cookies, tokens, bodies or signed URLs.
 */
final class NetworkTraceStore {
    static final class Event {
        final long time;
        final String method;
        final String target;
        final int status;
        final long durationMs;
        final int attempt;
        final String route;
        final String error;

        Event(long time, String method, String target, int status, long durationMs,
              int attempt, String route, String error) {
            this.time = time;
            this.method = safe(method);
            this.target = safe(target);
            this.status = status;
            this.durationMs = Math.max(0, durationMs);
            this.attempt = Math.max(1, attempt);
            this.route = safe(route);
            this.error = safe(error);
        }
    }

    private static final int MAX = 80;
    private static final ArrayList<Event> EVENTS = new ArrayList<>();

    private NetworkTraceStore() {}

    static synchronized void add(String method, String url, int status, long durationMs,
                                 int attempt, String route, Throwable error) {
        String errorText = error == null ? "" : error.getClass().getSimpleName() +
                (safe(error.getMessage()).isEmpty() ? "" : ": " + safe(error.getMessage()));
        EVENTS.add(new Event(System.currentTimeMillis(), method, redact(url), status,
                durationMs, attempt, route, truncate(errorText, 140)));
        while (EVENTS.size() > MAX) EVENTS.remove(0);
    }

    static synchronized List<Event> recent(int limit) {
        int n = Math.max(0, Math.min(limit, EVENTS.size()));
        ArrayList<Event> out = new ArrayList<>(n);
        for (int i = EVENTS.size() - 1; i >= EVENTS.size() - n; i--) out.add(EVENTS.get(i));
        return Collections.unmodifiableList(out);
    }

    static synchronized void clear() { EVENTS.clear(); }

    static String redact(String raw) {
        String value = safe(raw);
        if (value.isEmpty()) return "—";
        try {
            URI u = URI.create(value);
            String host = safe(u.getHost());
            String path = safe(u.getPath());
            if (host.isEmpty()) return truncate(stripQuery(value), 120);
            if (host.equals("chatgpt.com") || host.equals("www.chatgpt.com")) {
                // ChatGPT backend path is useful for diagnostics. Query/cursor/file signatures are removed.
                return host + (path.isEmpty() ? "/" : truncate(path, 100));
            }
            // Never expose signed storage paths. Host is enough to diagnose redirect/storage failures.
            return host;
        } catch (Exception ignored) {
            return truncate(stripQuery(value), 120);
        }
    }

    private static String stripQuery(String s) {
        int q = s.indexOf('?');
        return q >= 0 ? s.substring(0, q) : s;
    }

    private static String truncate(String s, int max) {
        String v = safe(s);
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
