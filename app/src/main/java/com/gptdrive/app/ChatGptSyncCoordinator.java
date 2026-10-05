package com.gptdrive.app;

import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Loads remote snapshots first, then commits a consistent local index transaction. */
final class ChatGptSyncCoordinator {
    static final class Result {
        final int library, images, projects, archived, pages, libraryStrategy;
        final boolean imagesFresh, projectsFresh, archivedFresh;
        Result(int library, int images, int projects, int archived, int pages, int libraryStrategy,
               boolean imagesFresh, boolean projectsFresh, boolean archivedFresh) {
            this.library = library;
            this.images = images;
            this.projects = projects;
            this.archived = archived;
            this.pages = pages;
            this.libraryStrategy = libraryStrategy;
            this.imagesFresh = imagesFresh;
            this.projectsFresh = projectsFresh;
            this.archivedFresh = archivedFresh;
        }
    }

    private final DriveDb db;
    private final long accountId;
    private final ChatGptProvider provider;

    ChatGptSyncCoordinator(DriveDb db, long accountId, ChatGptProvider provider) {
        this.db = db;
        this.accountId = accountId;
        this.provider = provider;
    }

    Result sync(ChatGptProvider.Progress progress) throws Exception {
        // Never mutate the local index until the authoritative Library snapshot is complete.
        ChatGptModels.LibrarySnapshot library = provider.loadLibrary(250, progress);
        LinkedHashMap<String, ChatGptModels.RemoteFile> files = ChatGptModels.map(library.files);

        boolean imagesFresh = false;
        try {
            for (ChatGptModels.RemoteFile f : provider.loadImages(progress)) ChatGptModels.merge(files, f);
            imagesFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            throw e;
        } catch (Exception ignored) {
            // Library images remain available; image_gen metadata can recover next sync.
        }

        List<ChatGptModels.RemoteProject> projects = null;
        boolean projectsFresh = false;
        try {
            projects = provider.loadProjects();
            for (ChatGptModels.RemoteProject p : projects)
                for (ChatGptModels.RemoteFile f : p.files) ChatGptModels.merge(files, f);
            projectsFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            throw e;
        } catch (Exception ignored) {
            // Preserve the previous Projects surface rather than replacing it with zero.
        }

        List<ChatGptModels.RemoteConversation> archived = null;
        boolean archivedFresh = false;
        try {
            archived = provider.loadArchived();
            archivedFresh = true;
        } catch (ChatGptProvider.AuthException e) {
            throw e;
        } catch (Exception ignored) {
            // Preserve previous Archive on a subsystem-only failure.
        }

        ArrayList<ChatGptModels.RemoteFile> allFiles = new ArrayList<>(files.values());
        ArrayList<ChatGptModels.RemoteFile> imageFiles = new ArrayList<>();
        for (ChatGptModels.RemoteFile f : allFiles) if (f.isImage()) imageFiles.add(f);

        SQLiteDatabase sql = db.getWritableDatabase();
        sql.beginTransaction();
        try {
            replaceFileCategory(sql, "Library", allFiles);
            // Images is a view of the unified file identities. If image_gen failed,
            // image files discovered by Library still remain visible.
            replaceFileCategory(sql, "Images", imageFiles);
            if (projects != null) replaceProjects(sql, projects);
            if (archived != null) replaceArchived(sql, archived);
            sql.setTransactionSuccessful();
        } finally {
            sql.endTransaction();
        }

        db.pruneFalsePositives(accountId);
        IndexMaintenance.pruneTransientSignedUrls(db, accountId);
        db.markSynced(accountId);
        int projectCount = projects == null ? (int) db.count(accountId, "Projects") : projects.size();
        int archivedCount = archived == null ? (int) db.count(accountId, "Archived") : archived.size();
        return new Result(allFiles.size(), imageFiles.size(), projectCount, archivedCount,
                library.pages, library.strategy, imagesFresh, projectsFresh, archivedFresh);
    }

    private void replaceFileCategory(SQLiteDatabase sql, String category, List<ChatGptModels.RemoteFile> entries) {
        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), category});
        for (ChatGptModels.RemoteFile f : entries) {
            String url = f.stableUrl();
            if (url == null || url.trim().isEmpty()) continue;
            db.upsert(accountId, category,
                    ChatGptResponseParser.cleanName(f.name),
                    url,
                    f.mimeType,
                    f.sizeBytes);
        }
    }

    private void replaceProjects(SQLiteDatabase sql, List<ChatGptModels.RemoteProject> projects) {
        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), "Projects"});
        for (ChatGptModels.RemoteProject p : projects) {
            if (p == null || p.id == null || p.id.trim().isEmpty()) continue;
            db.upsert(accountId, "Projects",
                    ChatGptResponseParser.cleanName(p.name),
                    "https://chatgpt.com/g/" + p.id.trim(),
                    DriveActions.LINK_MIME,
                    0);
        }
    }

    private void replaceArchived(SQLiteDatabase sql, List<ChatGptModels.RemoteConversation> archived) {
        sql.delete("items", "account_id=? AND category=?", new String[]{Long.toString(accountId), "Archived"});
        for (ChatGptModels.RemoteConversation c : archived) {
            if (c == null || c.id == null || c.id.trim().isEmpty()) continue;
            db.upsert(accountId, "Archived",
                    ChatGptResponseParser.cleanName(c.title),
                    "https://chatgpt.com/c/" + c.id.trim(),
                    DriveActions.LINK_MIME,
                    0);
        }
    }
}
