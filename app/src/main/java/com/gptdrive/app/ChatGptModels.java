package com.gptdrive.app;

import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ChatGptModels {
    static final int SRC_LIBRARY = 1;
    static final int SRC_IMAGE_GEN = 1 << 1;
    static final int SRC_PROJECT = 1 << 2;
    static final int SRC_CONVERSATION = 1 << 3;

    private ChatGptModels() {}

    static final class RemoteFile {
        String fileId = "";
        String libraryFileId = "";
        String name = "ChatGPT file";
        String mimeType = "";
        long sizeBytes;
        long createdAt;
        long updatedAt;
        String conversationId = "";
        String messageId = "";
        String assetPointer = "";
        String rawUrl = "";
        int width;
        int height;
        int sourceFlags;

        String identity() {
            return first(fileId, libraryFileId, extractFileId(rawUrl));
        }

        boolean isImage() {
            String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
            return mime.startsWith("image/") || isImageName(name) || isImageName(rawUrl) || (sourceFlags & SRC_IMAGE_GEN) != 0;
        }

        String stableUrl() {
            String id = identity();
            if (id.isEmpty()) return rawUrl == null ? "" : rawUrl;
            String out = "gptdrive://file/" + Uri.encode(id);
            if (conversationId != null && !conversationId.trim().isEmpty())
                out += "?conversation_id=" + Uri.encode(conversationId.trim());
            return out;
        }
    }

    static final class RemoteProject {
        String id = "";
        String name = "Project";
        final List<RemoteFile> files = new ArrayList<>();
        final List<String> conversationIds = new ArrayList<>();
    }

    /** Summary or full raw conversation. rawJson is filled only for /conversation/<id>. */
    static final class RemoteConversation {
        String id = "";
        String title = "ChatGPT chat";
        long createdAt;
        long updatedAt;
        boolean archived;
        String projectId = "";
        String currentNode = "";
        boolean hasMapping;
        String rawJson = "";

        boolean hasFullSnapshot() { return rawJson != null && !rawJson.trim().isEmpty(); }
    }

    static final class LibraryPageMeta {
        String nextCursor = "";
        boolean hasMore;
        long total;
    }

    static final class LibraryPage {
        final List<RemoteFile> files;
        final LibraryPageMeta meta;
        LibraryPage(List<RemoteFile> files, LibraryPageMeta meta) {
            this.files = files;
            this.meta = meta;
        }
    }

    static final class LibrarySnapshot {
        final List<RemoteFile> files;
        final int pages;
        final LibraryPageMeta lastMeta;
        final int strategy;
        LibrarySnapshot(List<RemoteFile> files, int pages, LibraryPageMeta lastMeta, int strategy) {
            this.files = files;
            this.pages = pages;
            this.lastMeta = lastMeta;
            this.strategy = strategy;
        }
    }

    static void merge(Map<String, RemoteFile> dst, RemoteFile incoming) {
        if (incoming == null) return;
        String key = incoming.identity();
        if (key.isEmpty()) return;
        RemoteFile cur = dst.get(key);
        if (cur == null) {
            dst.put(key, incoming);
            return;
        }
        cur.fileId = first(cur.fileId, incoming.fileId);
        cur.libraryFileId = first(cur.libraryFileId, incoming.libraryFileId);
        if (weakName(cur.name) && !weakName(incoming.name)) cur.name = incoming.name;
        cur.mimeType = first(cur.mimeType, incoming.mimeType);
        if (cur.sizeBytes <= 0) cur.sizeBytes = incoming.sizeBytes;
        if (cur.createdAt <= 0) cur.createdAt = incoming.createdAt;
        if (incoming.updatedAt > cur.updatedAt) cur.updatedAt = incoming.updatedAt;
        cur.conversationId = first(cur.conversationId, incoming.conversationId);
        cur.messageId = first(cur.messageId, incoming.messageId);
        cur.assetPointer = first(cur.assetPointer, incoming.assetPointer);
        cur.rawUrl = first(cur.rawUrl, incoming.rawUrl);
        if (cur.width <= 0) cur.width = incoming.width;
        if (cur.height <= 0) cur.height = incoming.height;
        cur.sourceFlags |= incoming.sourceFlags;
    }

    static LinkedHashMap<String, RemoteFile> map(List<RemoteFile> files) {
        LinkedHashMap<String, RemoteFile> out = new LinkedHashMap<>();
        if (files != null) for (RemoteFile f : files) merge(out, f);
        return out;
    }

    static String extractFileId(String value) {
        return ChatGptApiClient.extractFileIdCompat(value);
    }

    private static boolean weakName(String s) {
        if (s == null || s.trim().isEmpty()) return true;
        String v = s.trim().toLowerCase(Locale.ROOT);
        return v.equals("chatgpt file") || v.equals("chatgpt image") || v.equals("chatgpt item");
    }

    static boolean isImageName(String value) {
        if (value == null) return false;
        String s = value.toLowerCase(Locale.ROOT);
        return s.contains(".png") || s.contains(".jpg") || s.contains(".jpeg") || s.contains(".webp") ||
                s.contains(".gif") || s.contains(".heic") || s.contains(".avif");
    }

    static String first(String... values) {
        if (values != null) for (String v : values)
            if (v != null && !v.trim().isEmpty() && !"null".equalsIgnoreCase(v.trim())) return v.trim();
        return "";
    }
}
