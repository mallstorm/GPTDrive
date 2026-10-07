package com.gptdrive.app;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure pagination state machine for POST /backend-api/files/library.
 * It has no Android, account, DNS or SQLite dependencies and can be tested with a fake backend.
 */
final class LibraryPaginationEngine {
    interface Fetcher { HttpResponse fetch(PageRequest request) throws Exception; }
    interface StatusHandler { void ensure(HttpResponse response) throws Exception; }
    interface Listener {
        void onPage(int pages, int objects) throws Exception;
        default void onPageItems(int pages, int objects, List<ChatGptModels.RemoteFile> pageFiles) throws Exception {}
    }

    static final class Result {
        final ArrayList<ChatGptModels.RemoteFile> files;
        final int pages;
        final ChatGptModels.LibraryPageMeta lastMeta;
        final int strategy;

        Result(ArrayList<ChatGptModels.RemoteFile> files, int pages,
               ChatGptModels.LibraryPageMeta lastMeta, int strategy) {
            this.files = files;
            this.pages = pages;
            this.lastMeta = lastMeta;
            this.strategy = strategy;
        }
    }

    static final class PageRequest {
        final String endpoint;
        final String payload;
        final int strategy;

        PageRequest(String endpoint, String payload, int strategy) {
            this.endpoint = endpoint;
            this.payload = payload;
            this.strategy = strategy;
        }
    }

    static final class PaginationFailure extends Exception {
        PaginationFailure(String message) { super(message); }
    }

    private LibraryPaginationEngine() {}

    static Result load(Fetcher fetcher, StatusHandler status, int maxPages, Listener listener) throws Exception {
        if (fetcher == null) throw new IllegalArgumentException("fetcher == null");
        if (status == null) throw new IllegalArgumentException("status == null");
        if (maxPages <= 0) maxPages = 1;

        LinkedHashMap<String, ChatGptModels.RemoteFile> merged = new LinkedHashMap<>();
        Set<String> seenContinuationCursors = new LinkedHashSet<>();
        Set<String> seenPageFingerprints = new LinkedHashSet<>();
        int strategy = -1;
        int pages = 0;
        ChatGptModels.LibraryPageMeta lastMeta = new ChatGptModels.LibraryPageMeta();
        PageRequest request = initialRequest();

        while (request != null && pages < maxPages) {
            HttpResponse response = fetcher.fetch(request);
            status.ensure(response);
            ChatGptModels.LibraryPage page = ChatGptResponseParser.decodeLibraryPage(response.bodyUtf8());
            pages++;

            String fingerprint = fingerprint(page);
            if (!fingerprint.isEmpty() && !seenPageFingerprints.add(fingerprint)) {
                throw new PaginationFailure("Library backend повторяет одну и ту же страницу");
            }

            mergePage(merged, page);
            lastMeta = page.meta;
            if (listener != null) {
                listener.onPage(pages, merged.size());
                listener.onPageItems(pages, merged.size(),
                        page == null || page.files == null ? new ArrayList<>() : new ArrayList<>(page.files));
            }

            if (pages >= maxPages || terminal(page)) break;

            String next = safe(page.meta.nextCursor);
            if (next.isEmpty()) {
                strategy = 5;
                request = requestForStrategy(strategy, "", merged.size());
                continue;
            }

            if (!seenContinuationCursors.add(next)) {
                throw new PaginationFailure("Library cursor зациклился");
            }

            if (strategy >= 0) {
                request = requestForStrategy(strategy, next, merged.size());
                continue;
            }

            Probe probe = negotiate(fetcher, status, merged, next, pages, maxPages, listener);
            if (probe.accepted) {
                strategy = probe.strategy;
                pages = probe.pages;
                lastMeta = probe.page.meta;
                mergePage(merged, probe.page);
                String probeFingerprint = fingerprint(probe.page);
                if (!probeFingerprint.isEmpty() && !seenPageFingerprints.add(probeFingerprint)) {
                    throw new PaginationFailure("Library backend повторяет одну и ту же страницу");
                }
                if (listener != null) {
                    listener.onPage(pages, merged.size());
                    listener.onPageItems(pages, merged.size(),
                            probe.page.files == null ? new ArrayList<>() : new ArrayList<>(probe.page.files));
                }
                if (pages >= maxPages || terminal(probe.page)) break;
                String cursor = safe(probe.page.meta.nextCursor);
                if (!cursor.isEmpty() && !seenContinuationCursors.add(cursor) && !cursor.equals(next))
                    throw new PaginationFailure("Library cursor зациклился");
                if (cursor.isEmpty()) strategy = 5;
                request = requestForStrategy(strategy, cursor, merged.size());
            } else {
                strategy = 5;
                request = requestForStrategy(strategy, "", merged.size());
            }
        }

        return new Result(new ArrayList<>(merged.values()), pages, lastMeta, strategy);
    }

    private static Probe negotiate(Fetcher fetcher, StatusHandler status,
                                   LinkedHashMap<String, ChatGptModels.RemoteFile> merged,
                                   String next, int pages, int maxPages, Listener listener) throws Exception {
        for (int candidate = 0; candidate <= 4 && pages < maxPages; candidate++) {
            PageRequest request = requestForStrategy(candidate, next, merged.size());
            HttpResponse response;
            try {
                response = fetcher.fetch(request);
            } catch (Exception e) {
                continue;
            }

            if (response.code == 401 || response.code == 403 || response.code == 429) {
                status.ensure(response);
            }
            if (response.code != 200) continue;

            ChatGptModels.LibraryPage page = ChatGptResponseParser.decodeLibraryPage(response.bodyUtf8());
            int newCount = countNewIdentities(merged, page);
            boolean cursorChanged = !safe(page.meta.nextCursor).equals(next);
            boolean completed = terminal(page);
            if (newCount <= 0 && !cursorChanged && !completed) continue;

            return new Probe(true, candidate, page, pages + 1);
        }
        return new Probe(false, -1, null, pages);
    }

    private static int countNewIdentities(LinkedHashMap<String, ChatGptModels.RemoteFile> merged,
                                          ChatGptModels.LibraryPage page) {
        int count = 0;
        Set<String> pageIds = new LinkedHashSet<>();
        if (page != null && page.files != null) {
            for (ChatGptModels.RemoteFile f : page.files) {
                if (f == null) continue;
                String id = safe(f.identity());
                if (!id.isEmpty() && !merged.containsKey(id) && pageIds.add(id)) count++;
            }
        }
        return count;
    }

    private static void mergePage(LinkedHashMap<String, ChatGptModels.RemoteFile> merged,
                                  ChatGptModels.LibraryPage page) {
        if (page == null || page.files == null) return;
        for (ChatGptModels.RemoteFile f : page.files) ChatGptModels.merge(merged, f);
    }

    private static boolean terminal(ChatGptModels.LibraryPage page) {
        return page == null || page.meta == null ||
                (!page.meta.hasMore && safe(page.meta.nextCursor).isEmpty());
    }

    static PageRequest initialRequest() {
        return new PageRequest("/backend-api/files/library", "{}", -1);
    }

    static PageRequest requestForStrategy(int strategy, String cursor, int offset) {
        String c = safe(cursor);
        try {
            switch (strategy) {
                case 0:
                    return new PageRequest("/backend-api/files/library",
                            new JSONObject().put("cursor", c).toString(), strategy);
                case 1:
                    return new PageRequest("/backend-api/files/library",
                            new JSONObject().put("cursor", c).put("limit", 100).toString(), strategy);
                case 2:
                    return new PageRequest("/backend-api/files/library",
                            new JSONObject().put("after", c).toString(), strategy);
                case 3:
                    return new PageRequest("/backend-api/files/library",
                            new JSONObject().put("next_cursor", c).toString(), strategy);
                case 4:
                    return new PageRequest("/backend-api/files/library?cursor=" + encode(c), "{}", strategy);
                case 5:
                    return new PageRequest("/backend-api/files/library",
                            new JSONObject().put("offset", Math.max(0, offset)).put("limit", 100).toString(), strategy);
                default:
                    return initialRequest();
            }
        } catch (Exception e) {
            return initialRequest();
        }
    }

    private static String fingerprint(ChatGptModels.LibraryPage page) {
        if (page == null) return "";
        StringBuilder s = new StringBuilder();
        if (page.files != null) {
            for (ChatGptModels.RemoteFile f : page.files) {
                if (f == null) continue;
                String id = safe(f.identity());
                if (!id.isEmpty()) s.append(id).append('|');
            }
        }
        s.append('#').append(safe(page.meta == null ? "" : page.meta.nextCursor));
        s.append('#').append(page.meta != null && page.meta.hasMore ? '1' : '0');
        return s.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(safe(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static final class Probe {
        final boolean accepted;
        final int strategy;
        final ChatGptModels.LibraryPage page;
        final int pages;

        Probe(boolean accepted, int strategy, ChatGptModels.LibraryPage page, int pages) {
            this.accepted = accepted;
            this.strategy = strategy;
            this.page = page;
            this.pages = pages;
        }
    }
}
