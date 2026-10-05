package com.gptdrive.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Loads remote snapshots first, then atomically swaps normalized local state. */
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

        // Mirror is refreshed before the normalized file swap so newly discovered conversation
        // attachments become first-class files during the same sync pass.
        boolean mirrorFresh = false;
        String mirrorError = "";
        int conversationCount = 0;
        int mirroredChats = 0;
        int mirrorPending = 0;
        int mirrorMissing = 0;
        ChatMirrorRepository mirrorRepo = new ChatMirrorRepository(context, db, accountId);
        ChatAttachmentRepository attachmentRepo = new ChatAttachmentRepository(db, accountId);
        try {
            ChatMirrorSyncEngine.Result mr = new ChatMirrorSyncEngine(context, db, accountId, provider)
                    .sync(archivedFresh ? archived : null, ChatMirrorSyncEngine.DEFAULT_BODY_BUDGET, progress);
            mirrorFresh = true;
            conversationCount = mr.summaries;
            mirroredChats = mr.stats.mirrored;
            mirrorPending = mr.stats.pending;
            mirrorMissing = mr.stats.missingRemote;
        } catch (Exception e) {
            mirrorError = message(e);
            ChatMirrorRepository.Stats stats = mirrorRepo.stats();
            conversationCount = stats.remotePresent;
            mirroredChats = stats.mirrored;
            mirrorPending = stats.pending;
            mirrorMissing = stats.missingRemote;
        }

        List<ChatGptModels.RemoteFile> conversationAttachments = attachmentRepo.listAll();
        if (projectsFresh && projects != null)
            linkConversationAttachmentsToProjects(projects, conversationAttachments);
        for (ChatGptModels.RemoteFile f : conversationAttachments) ChatGptModels.merge(files, f);

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

        db.pruneFalsePositives(accountId);
        IndexMaintenance.pruneTransientSignedUrls(db, accountId);
        db.markSynced(accountId);

        db.recordSyncState(accountId, "Library", true, allFiles.size(), "",
                "pages=" + library.pages + ";strategy=" + library.strategy +
                        ";conversation_attachments=" + conversationAttachments.size());
        db.recordSyncState(accountId, "Images", imagesFresh, imageCount, imageError,
                imagesFresh ? "merged=file_id" : "previous_image_gen_rows_preserved=true");

        int projectCount = projectsFresh && projects != null
                ? projects.size() : (int) db.count(accountId, "Projects");
        db.recordSyncState(accountId, "Projects", projectsFresh, projectCount, projectError,
                projectsFresh ? "relations=project_files;chat_links=" + attachmentRepo.projectLinkCount()
                        : "previous_snapshot_preserved=true");

        int archivedCount = archivedFresh && archived != null
                ? archived.size() : (int) db.count(accountId, "Archived");
        db.recordSyncState(accountId, "Archived", archivedFresh, archivedCount, archiveError,
                archivedFresh ? "table=conversations" : "previous_snapshot_preserved=true");

        ChatMirrorRepository.Stats finalMirrorStats = mirrorRepo.stats();
        db.recordSyncState(accountId, "ChatMirror", mirrorFresh, mirroredChats, mirrorError,
                "remote=" + finalMirrorStats.remotePresent +
                        ";pending=" + finalMirrorStats.pending +
                        ";missing=" + finalMirrorStats.missingRemote +
                        ";bytes=" + finalMirrorStats.bytes +
                        ";attachments=" + attachmentRepo.count() +
                        ";project_links=" + attachmentRepo.projectLinkCount() +
                        (mirrorFresh ? "" : ";previous_snapshots_preserved=true"));

        return new Result(allFiles.size(), imageCount, projectCount, archivedCount,
                library.pages, library.strategy,
                conversationCount, mirroredChats, mirrorPending, mirrorMissing,
                imagesFresh, projectsFresh, archivedFresh, mirrorFresh);
    }

    private static void linkConversationAttachmentsToProjects(List<ChatGptModels.RemoteProject> projects,
                                                              List<ChatGptModels.RemoteFile> attachments) {
        if (projects == null || attachments == null) return;
        for (ChatGptModels.RemoteFile f : attachments) {
            if (f == null || f.identity().isEmpty()) continue;
            for (ChatGptModels.RemoteProject p : projects) {
                if (p == null || p.id == null || p.id.trim().isEmpty()) continue;
                boolean direct = p.id.equals(f.projectId);
                boolean byConversation = !f.conversationId.isEmpty() && p.conversationIds.contains(f.conversationId);
                if (!direct && !byConversation) continue;
                f.projectId = p.id;
                f.sourceFlags |= ChatGptModels.SRC_PROJECT;
                p.files.add(f);
            }
        }
    }

    private static String message(Throwable e) {
        if (e == null) return "";
        if (e.getMessage() == null || e.getMessage().trim().isEmpty()) return e.getClass().getSimpleName();
        return e.getMessage().trim();
    }
}
