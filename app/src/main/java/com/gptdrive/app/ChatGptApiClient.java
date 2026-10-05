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
        final int conversations, mirroredChats, mirrorPending, mirrorMissing;
        final int libraryStrategy;
        final boolean imagesFresh, projectsFresh, archivedFresh, mirrorFresh;

        SyncResult(int library, int images, int projects, int archived, int pages, int libraryStrategy,
                   int conversations, int mirroredChats, int mirrorPending, int mirrorMissing,
                   boolean imagesFresh, boolean projectsFresh, boolean archivedFresh, boolean mirrorFresh) {
            this.library = library;
            this.images = images;
            this.projects = projects;
            this.archived = archived;
            this.pages = pages;
            this.libraryStrategy = libraryStrategy;
            this.conversations = conversations;
            this.mirroredChats = mirroredChats;
            this.mirrorPending = mirrorPending;
            this.mirrorMissing = mirrorMissing;
            this.imagesFresh = imagesFresh;
            this.projectsFresh = projectsFresh;
            this.archivedFresh = archivedFresh;
            this.mirrorFresh = mirrorFresh;
        }

        boolean fullyFresh() { return imagesFresh && projectsFresh && archivedFresh; }
    }

    static final class AuthException extends Exception { AuthException(String message) { super(message); } }

    private static final Pattern FILE_ID = Pattern.compile("(file[-_][A-Za-z0-9_-]{6,})", Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final DriveDb db;
    private final long localAccountId;
    private final ChatGptProvider provider;

    ChatGptApiClient(Context context, DriveDb db, long localAccountId) throws AuthException {
        this.context = context.getApplicationContext();
        this.db = db;
        this.localAccountId = localAccountId;
        try {
            this.provider = new ChatGptProvider(this.context, localAccountId);
        } catch (ChatGptProvider.AuthException e) {
            throw new AuthException(e.getMessage());
        }
    }

    SyncResult sync(Progress progress) throws Exception {
        try {
            ChatGptSyncCoordinator.Result r = new ChatGptSyncCoordinator(context, db, localAccountId, provider).sync(
                    progress == null ? null : progress::onProgress);
            return new SyncResult(r.library, r.images, r.projects, r.archived, r.pages, r.libraryStrategy,
                    r.conversations, r.mirroredChats, r.mirrorPending, r.mirrorMissing,
                    r.imagesFresh, r.projectsFresh, r.archivedFresh, r.mirrorFresh);
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
        String best = "";
        while (m.find()) {
            String candidate = m.group(1);
            // file-service:// is a transport scheme, not a ChatGPT file identity.
            if ("file-service".equalsIgnoreCase(candidate)) continue;
            best = candidate;
        }
        return best;
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
