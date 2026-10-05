package com.gptdrive.app;

import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Loads remote snapshots first, then atomically swaps normalized local state. */
final class ChatGptSyncCoordinator {
    static final class Result {
        final int library, images, projects, archived, pages, libraryStrategy;
        final boolean imagesFresh, projectsFresh, archivedFresh;
        Result(int library, int images, int projects, int archived, int pages, int libraryStrategy,
               boolean imagesFresh, boolean projectsFresh, boolean archivedFresh) {
            this.library = library; this.images = images; this.projects = projects; this.archived = archived;
            this.pages = pages; this.libraryStrategy = libraryStrategy;
            this.imagesFresh = imagesFresh; this.projectsFresh = projectsFresh; this.archivedFresh = archivedFresh;
        }
    }

    private final DriveDb db;
    private final long accountId;
    private final ChatGptProvider provider;
    private final NormalizedIndexRepository index;

    ChatGptSyncCoordinator(DriveDb db, long accountId, ChatGptProvider provider) {
        this.db = db;
        this.accountId = accountId;
        this.provider = provider;
        this.index = new NormalizedIndexRepository(db, accountId);
    }

    Result sync(ChatGptProvider.Progress progress) throws Exception {
        ChatGptModels.LibrarySnapshot library;
        try {
            library = provider.loadLibrary(250, progress);
        } catch (Exception e) {
            db.recordSyncState(accountId, "Library", false, 0, message(e), "");
            throw e;
        }
        LinkedHashMap<String, ChatGptModels.RemoteFile> files = ChatGptModels.map(library.files);

        boolean imagesFresh = false;
        String imageError = "";
        try {
            for (ChatGptModels.RemoteFile f : provider.loadImages(progress)) ChatGptModels.merge(files, f);
            imagesFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            db.recordSyncState(accountId, "Images", false, 0, message(e), "");
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
            db.recordSyncState(accountId, "Projects", false, 0, message(e), "");
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
            db.recordSyncState(accountId, "Archived", false, 0, message(e), "");
            throw e;
        } catch (Exception e) {
            archiveError = message(e);
        }

        ArrayList<ChatGptModels.RemoteFile> allFiles = new ArrayList<>(files.values());
        int imageCount = 0;
        for (ChatGptModels.RemoteFile f : allFiles) if (f.isImage()) imageCount++;

        int preserveSources = 0;
        if (!imagesFresh) preserveSources |= ChatGptModels.SRC_IMAGE_GEN;
        if (!projectsFresh) preserveSources |= ChatGptModels.SRC_PROJECT;

        SQLiteDatabase sql = db.getWritableDatabase();
        sql.beginTransaction();
        try {
            // Library is authoritative and is replaced only after the complete remote snapshot exists.
            // Stable file row ids are preserved so open DocumentsProvider handles do not break.
            // Rows owned by a failed sub-surface survive until that endpoint succeeds again.
            index.replaceFiles(sql, allFiles, preserveSources);

            // Sub-surfaces commit only when their own fetch succeeded. A temporary endpoint failure
            // therefore cannot zero a previously valid Projects/Archive index.
            if (projectsFresh) index.replaceProjects(sql, projects);
            if (archivedFresh) index.replaceArchived(sql, archived);
            sql.setTransactionSuccessful();
        } finally {
            sql.endTransaction();
        }

        db.pruneFalsePositives(accountId);
        IndexMaintenance.pruneTransientSignedUrls(db, accountId);
        db.markSynced(accountId);

        db.recordSyncState(accountId, "Library", true, allFiles.size(), "",
                "pages=" + library.pages + ";strategy=" + library.strategy);
        db.recordSyncState(accountId, "Images", imagesFresh, imageCount, imageError,
                imagesFresh ? "merged=file_id" : "previous_image_gen_rows_preserved=true");

        int projectCount = projectsFresh && projects != null
                ? projects.size() : (int) db.count(accountId, "Projects");
        db.recordSyncState(accountId, "Projects", projectsFresh, projectCount, projectError,
                projectsFresh ? "relations=project_files" : "previous_snapshot_preserved=true");

        int archivedCount = archivedFresh && archived != null
                ? archived.size() : (int) db.count(accountId, "Archived");
        db.recordSyncState(accountId, "Archived", archivedFresh, archivedCount, archiveError,
                archivedFresh ? "table=conversations" : "previous_snapshot_preserved=true");

        return new Result(allFiles.size(), imageCount, projectCount, archivedCount,
                library.pages, library.strategy, imagesFresh, projectsFresh, archivedFresh);
    }

    private static String message(Throwable e) {
        if (e == null) return "";
        if (e.getMessage() == null || e.getMessage().trim().isEmpty()) return e.getClass().getSimpleName();
        return e.getMessage().trim();
    }
}
