package com.gptdrive.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Metadata-only cloud index synchronizer. File bodies are never downloaded here. */
final class ChatGptSyncCoordinator {
    static final class Result {
        final int library, images, projects, archived, pages, libraryStrategy;
        final int conversations, mirroredChats, mirrorPending, mirrorMissing;
        final boolean imagesFresh, projectsFresh, archivedFresh, mirrorFresh;

        Result(int library, int images, int projects, int archived, int pages, int libraryStrategy,
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
    }

    private final Context context;
    private final DriveDb db;
    private final long accountId;
    private final ChatGptProvider provider;
    private final NormalizedIndexRepository index;

    ChatGptSyncCoordinator(Context context, DriveDb db, long accountId, ChatGptProvider provider) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.accountId = accountId;
        this.provider = provider;
        this.index = new NormalizedIndexRepository(db, accountId);
    }

    Result sync(ChatGptProvider.Progress progress) throws Exception {
        ChatGptModels.LibrarySnapshot library;
        final int[] originalPreviewBudget = {0};
        try {
            library = provider.loadLibrary(250, progress, (pages, pageFiles, objects) -> {
                SQLiteDatabase pageDb = db.getWritableDatabase();
                pageDb.beginTransaction();
                try {
                    index.mergeFiles(pageDb, pageFiles);
                    pageDb.setTransactionSuccessful();
                } finally {
                    pageDb.endTransaction();
                }
                IndexChangeNotifier.notify(context, accountId);
                for (ChatGptModels.RemoteFile f : pageFiles) {
                    if (f == null || !f.isImage()) continue;
                    String identity = f.stableUrl();
                    String source = f.thumbnailUrl;
                    if (source == null || source.trim().isEmpty()) {
                        if (originalPreviewBudget[0] <= 0) continue;
                        originalPreviewBudget[0]--;
                        source = identity;
                    }
                    ImageLoader.prefetchThumb(context, accountId, identity, source,
                            () -> IndexChangeNotifier.notify(context, accountId));
                }
            });
        } catch (Exception e) {
            db.recordSyncState(accountId, "Library", false, 0, message(e), "mode=metadata-only");
            throw e;
        }
        LinkedHashMap<String, ChatGptModels.RemoteFile> files = ChatGptModels.map(library.files);

        boolean imagesFresh = false;
        String imageError = "";
        try {
            for (ChatGptModels.RemoteFile f : provider.loadImages(progress)) ChatGptModels.merge(files, f);
            imagesFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            db.recordSyncState(accountId, "Images", false, 0, message(e), "mode=metadata-only");
            throw e;
        } catch (Exception e) {
            imageError = message(e);
        }

        List<ChatGptModels.RemoteProject> projects = null;
        boolean projectsFresh = false;
        String projectError = "";
        try {
            projects = provider.loadProjects();
            for (ChatGptModels.RemoteProject p : projects)
                for (ChatGptModels.RemoteFile f : p.files) ChatGptModels.merge(files, f);
            projectsFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            db.recordSyncState(accountId, "Projects", false, 0, message(e), "mode=metadata-only");
            throw e;
        } catch (Exception e) {
            projectError = message(e);
        }

        List<ChatGptModels.RemoteConversation> archived = null;
        boolean archivedFresh = false;
        String archiveError = "";
        try {
            archived = provider.loadArchived();
            archivedFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            db.recordSyncState(accountId, "Archived", false, 0, message(e), "mode=metadata-only");
            throw e;
        } catch (Exception e) {
            archiveError = message(e);
        }

        // Normal sync intentionally does NOT fetch /conversation/<id> bodies. Older builds
        // downloaded full chat snapshots in batches and then indexed their internal asset paths.
        // GPT Drive is a cloud index first: bodies are fetched only when a user explicitly opens
        // or downloads a concrete object.
        final boolean mirrorFresh = true;
        final int conversationCount = 0;
        final int mirroredChats = 0;
        final int mirrorPending = 0;
        final int mirrorMissing = 0;

        ArrayList<ChatGptModels.RemoteFile> allFiles = new ArrayList<>(files.values());
        int imageCount = 0;
        for (ChatGptModels.RemoteFile f : allFiles) if (f.isImage()) imageCount++;

        int preserveSources = 0;
        if (!imagesFresh) preserveSources |= ChatGptModels.SRC_IMAGE_GEN;
        if (!projectsFresh) preserveSources |= ChatGptModels.SRC_PROJECT;

        SQLiteDatabase sql = db.getWritableDatabase();
        sql.beginTransaction();
        try {
            index.replaceFiles(sql, allFiles, preserveSources);
            if (projectsFresh) index.replaceProjects(sql, projects);
            if (archivedFresh) index.replaceArchived(sql, archived);
            sql.setTransactionSuccessful();
        } finally {
            sql.endTransaction();
        }

        // v0.10.3 and older could leave full gzip conversation mirrors and derived attachment
        // relations on the phone. Metadata-only mode removes those leftovers after the new remote
        // index has been committed, so an in-place upgrade does not keep an unwanted local mirror.
        try { new ChatMirrorRepository(context, db, accountId).deleteAccountMirror(); }
        catch (Exception ignored) {}
        try {
            new ChatAttachmentRepository(db, accountId); // ensures legacy table exists before delete
            db.getWritableDatabase().delete("chat_attachments", "account_id=?",
                    new String[]{Long.toString(accountId)});
        } catch (Exception ignored) {}

        db.pruneFalsePositives(accountId);
        IndexMaintenance.pruneTransientSignedUrls(db, accountId);
        db.markSynced(accountId);
        IndexChangeNotifier.notify(context, accountId);

        db.recordSyncState(accountId, "Library", true, allFiles.size(), "",
                "pages=" + library.pages + ";strategy=" + library.strategy +
                        ";mode=metadata-only;body_downloads=0");
        db.recordSyncState(accountId, "Images", imagesFresh, imageCount, imageError,
                imagesFresh ? "mode=metadata-only;merged=file_id" : "previous_image_gen_rows_preserved=true");

        int projectCount = projectsFresh && projects != null
                ? projects.size() : (int) db.count(accountId, "Projects");
        db.recordSyncState(accountId, "Projects", projectsFresh, projectCount, projectError,
                projectsFresh ? "mode=metadata-only;relations=project_files" : "previous_snapshot_preserved=true");

        int archivedCount = archivedFresh && archived != null
                ? archived.size() : (int) db.count(accountId, "Archived");
        db.recordSyncState(accountId, "Archived", archivedFresh, archivedCount, archiveError,
                archivedFresh ? "mode=metadata-only;table=conversations" : "previous_snapshot_preserved=true");

        db.recordSyncState(accountId, "ChatMirror", true, 0, "",
                "mode=metadata-only;body_downloads=0;full_chat_mirror_disabled=true;legacy_mirror_removed=true");

        return new Result(allFiles.size(), imageCount, projectCount, archivedCount,
                library.pages, library.strategy,
                conversationCount, mirroredChats, mirrorPending, mirrorMissing,
                imagesFresh, projectsFresh, archivedFresh, mirrorFresh);
    }

    private static String message(Throwable e) {
        if (e == null) return "";
        if (e.getMessage() == null || e.getMessage().trim().isEmpty()) return e.getClass().getSimpleName();
        return e.getMessage().trim();
    }
}
