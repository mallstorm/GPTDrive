package com.gptdrive.app;

import android.content.Context;
import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compatibility facade. Backend logic now lives in Provider / Transport / Sync layers. */
final class ChatGptApiClient {
    interface Progress { void onProgress(String phase, int part, int total, int objects); }

    static final class SyncResult {
        final int library, images, projects, archived, pages;
        final int libraryStrategy;
        SyncResult(int library, int images, int projects, int archived, int pages, int libraryStrategy) {
            this.library = library;
            this.images = images;
            this.projects = projects;
            this.archived = archived;
            this.pages = pages;
            this.libraryStrategy = libraryStrategy;
        }
    }

    static final class AuthException extends Exception { AuthException(String message) { super(message); } }

    private static final Pattern FILE_ID = Pattern.compile("(file[-_][A-Za-z0-9_-]{6,})", Pattern.CASE_INSENSITIVE);

    private final DriveDb db;
    private final long localAccountId;
    private final ChatGptProvider provider;

    ChatGptApiClient(Context context, DriveDb db, long localAccountId) throws AuthException {
        this.db = db;
        this.localAccountId = localAccountId;
        try {
            this.provider = new ChatGptProvider(context, localAccountId);
        } catch (ChatGptProvider.AuthException e) {
            throw new AuthException(e.getMessage());
        }
    }

    SyncResult sync(Progress progress) throws Exception {
        try {
            ChatGptSyncCoordinator.Result r = new ChatGptSyncCoordinator(db, localAccountId, provider).sync(
                    progress == null ? null : progress::onProgress);
            return new SyncResult(r.library, r.images, r.projects, r.archived, r.pages, r.libraryStrategy);
        } catch (ChatGptProvider.AuthException e) {
            throw new AuthException(e.getMessage());
        }
    }

    static String resolveDownloadUrl(Context context, long localAccountId, String fileId, String conversationId) throws Exception {
        try {
            return new ChatGptProvider(context, localAccountId).resolveDownload(fileId, conversationId);
        } catch (ChatGptProvider.AuthException e) {
            throw new AuthException(e.getMessage());
        }
    }

    static String buildFileRef(String fileId, String conversationId) {
        String id = extractFileId(fileId);
        if (id.isEmpty()) return fileId == null ? "" : fileId;
        String ref = "gptdrive://file/" + Uri.encode(id);
        if (conversationId != null && !conversationId.trim().isEmpty())
            ref += "?conversation_id=" + Uri.encode(conversationId.trim());
        return ref;
    }

    static String extractFileId(String value) { return extractFileIdCompat(value); }

    static String extractFileIdCompat(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        Matcher m = FILE_ID.matcher(value);
        return m.find() ? m.group(1) : "";
    }

    static String extractConversationId(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            String c = Uri.parse(value).getQueryParameter("conversation_id");
            return c == null ? "" : c;
        } catch (Exception e) {
            return "";
        }
    }
}
