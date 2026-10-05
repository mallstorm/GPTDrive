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

    ChatGptSyncCoordinator(DriveDb db, long accountId, ChatGptProvider provider) {
        this.db = db; this.accountId = accountId; this.provider = provider;
    }

    Result sync(ChatGptProvider.Progress progress) throws Exception {
        ChatGptModels.LibrarySnapshot library;
        try {
            library = provider.loadLibrary(250, progress);
        } catch (Exception e) {
            SyncStateRepository.record(db, accountId, "Library", false, 0, message(e), "");
            DiagnosticsState.recordError("Library", message(e));
            throw e;
        }
        LinkedHashMap<String, ChatGptModels.RemoteFile> files = ChatGptModels.map(library.files);

        boolean imagesFresh = false;
        String imageError = "";
        try {
            for (ChatGptModels.RemoteFile f : provider.loadImages(progress)) ChatGptModels.merge(files, f);
            imagesFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            SyncStateRepository.record(db, accountId, "Images", false, 0, message(e), "");
            DiagnosticsState.recordError("Images", message(e));
            throw e;
        } catch (Exception e) { imageError = message(e); DiagnosticsState.recordError("Images", imageError); }

        List<ChatGptModels.RemoteProject> projects = null;
        boolean projectsFresh = false;
        String projectError = "";
        try {
            projects = provider.loadProjects();
            for (ChatGptModels.RemoteProject p : projects)
                for (ChatGptModels.RemoteFile f : p.files) ChatGptModels.merge(files, f);
            projectsFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            SyncStateRepository.record(db, accountId, "Projects", false, 0, message(e), "");
            DiagnosticsState.recordError("Projects", message(e));
            throw e;
        } catch (Exception e) { projectError = message(e); DiagnosticsState.recordError("Projects", projectError); }

        List<ChatGptModels.RemoteConversation> archived = null;
        boolean archivedFresh = false;
        String archiveError = "";
        try { archived = provider.loadArchived(); archivedFresh = true; }
        catch (ChatGptProvider.AuthException e) {
            SyncStateRepository.record(db, accountId, "Archived", false, 0, message(e), "");
            DiagnosticsState.recordError("Archived", message(e));
            throw e;
        } catch (Exception e) { archiveError = message(e); DiagnosticsState.recordError("Archived", archiveError); }

        ArrayList<ChatGptModels.RemoteFile> allFiles = new ArrayList<>(files.values());
        int imageCount = 0;
        for (ChatGptModels.RemoteFile f : allFiles) if (f.isImage()) imageCount++;

        SQLiteDatabase sql = db.getWritableDatabase();
        sql.beginTransaction();
        try {
            db.replaceRemoteFiles(sql, accountId, allFiles);
            if (projects != null) {
                db.replaceProjectsNormalized(sql, accountId, projects);
                replaceProjectLinks(sql, projects);
            } else {
                // File rows are replaced as a snapshot; stale project/file row ids must not survive.
                sql.delete("project_files", null, null);
            }
            if (archived != null) {
                db.replaceConversationsNormalized(sql, accountId, archived);
                replaceArchivedLinks(sql, archived);
            }
            sql.setTransactionSuccessful();
        } finally { sql.endTransaction(); }

        db.pruneFalsePositives(accountId);
        IndexMaintenance.pruneTransientSignedUrls(db, accountId);
        db.markSynced(accountId);
        SyncStateRepository.record(db, accountId, "Library", true, allFiles.size(), "",
                "pages=" + library.pages + ";strategy=" + library.strategy);
        SyncStateRepository.record(db, accountId, "Images", imagesFresh, imageCount, imageError, "");
        int projectCount = projects == null ? (int) db.count(accountId, "Projects") : projects.size();
        SyncStateRepository.record(db, accountId, "Projects", projectsFresh, projectCount, projectError, "");
        int archivedCount = archived == null ? (int) db.count(accountId, "Archived") : archived.size();
        SyncStateRepository.record(db, accountId, "Archived", archivedFresh, archivedCount, archiveError, "");
        DiagnosticsState.recordPagination(library.strategy, library.pages, allFiles.size());

        return new Result(allFiles.size(), imageCount, projectCount, archivedCount,
                library.pages, library.strategy, imagesFresh, projectsFresh, archivedFresh);
    }

    private void replaceProjectLinks(SQLiteDatabase sql, List<ChatGptModels.RemoteProject> projects) {
        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), "Projects"});
        for (ChatGptModels.RemoteProject p : projects) {
            if (p == null || p.id == null || p.id.trim().isEmpty()) continue;
            db.upsert(accountId, "Projects", ChatGptResponseParser.cleanName(p.name),
                    "https://chatgpt.com/g/" + p.id.trim(), DriveActions.LINK_MIME, 0);
        }
    }

    private void replaceArchivedLinks(SQLiteDatabase sql, List<ChatGptModels.RemoteConversation> archived) {
        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), "Archived"});
        for (ChatGptModels.RemoteConversation c : archived) {
            if (c == null || c.id == null || c.id.trim().isEmpty()) continue;
            db.upsert(accountId, "Archived", ChatGptResponseParser.cleanName(c.title),
                    "https://chatgpt.com/c/" + c.id.trim(), DriveActions.LINK_MIME, 0);
        }
    }

    private static String message(Throwable e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) return e == null ? "" : e.getClass().getSimpleName();
        return e.getMessage().trim();
    }
}
