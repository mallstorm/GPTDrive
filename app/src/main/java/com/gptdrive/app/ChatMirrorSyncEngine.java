package com.gptdrive.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Incremental, non-destructive Chat Mirror synchronizer. */
final class ChatMirrorSyncEngine {
    static final int DEFAULT_BODY_BUDGET = 30;

    static final class Result {
        final int summaries;
        final int mirroredNow;
        final int failedBodies;
        final ChatMirrorRepository.Stats stats;

        Result(int summaries, int mirroredNow, int failedBodies, ChatMirrorRepository.Stats stats) {
            this.summaries = summaries;
            this.mirroredNow = mirroredNow;
            this.failedBodies = failedBodies;
            this.stats = stats;
        }
    }

    private final ChatGptProvider provider;
    private final ChatMirrorRepository mirror;
    private final ChatAttachmentRepository attachments;

    ChatMirrorSyncEngine(Context context, DriveDb db, long accountId, ChatGptProvider provider) {
        this.provider = provider;
        this.mirror = new ChatMirrorRepository(context, db, accountId);
        this.attachments = new ChatAttachmentRepository(db, accountId);
    }

    Result sync(List<ChatGptModels.RemoteConversation> archivedSeed,
                int bodyBudget, ChatGptProvider.Progress progress) throws Exception {
        List<ChatGptModels.RemoteConversation> active = provider.loadConversations();
        LinkedHashMap<String, ChatGptModels.RemoteConversation> merged = new LinkedHashMap<>();
        mergeSummaries(merged, active);
        mergeSummaries(merged, archivedSeed);
        ArrayList<ChatGptModels.RemoteConversation> summaries = new ArrayList<>(merged.values());
        mirror.applyRemoteSummaries(summaries);

        int limit = bodyBudget <= 0 ? DEFAULT_BODY_BUDGET : Math.min(bodyBudget, 500);
        List<String> pending = mirror.pendingIds(limit);
        int mirrored = 0;
        int failed = 0;
        for (String id : pending) {
            try {
                ChatGptModels.RemoteConversation full = provider.loadConversation(id);
                inheritSummary(full, merged.get(id));
                mirror.storeFull(full);
                attachments.replaceForConversation(full);
                mirrored++;
            } catch (ChatGptProvider.AuthException e) {
                mirror.markFetchError(id, e);
                throw e;
            } catch (Exception e) {
                failed++;
                mirror.markFetchError(id, e);
            }
            if (progress != null)
                progress.onProgress("Chat Mirror", 5, 5, mirrored);
        }

        return new Result(summaries.size(), mirrored, failed, mirror.stats());
    }

    private static void inheritSummary(ChatGptModels.RemoteConversation full,
                                       ChatGptModels.RemoteConversation summary) {
        if (full == null || summary == null) return;
        if (blank(full.title) || "ChatGPT chat".equals(full.title)) full.title = summary.title;
        if (full.createdAt <= 0) full.createdAt = summary.createdAt;
        if (full.updatedAt <= 0) full.updatedAt = summary.updatedAt;
        full.archived = full.archived || summary.archived;
        if (blank(full.projectId)) full.projectId = summary.projectId;
        for (ChatGptModels.RemoteFile f : full.attachments) {
            if (f == null) continue;
            if (blank(f.conversationId)) f.conversationId = full.id;
            if (blank(f.projectId)) f.projectId = full.projectId;
        }
    }

    private static void mergeSummaries(LinkedHashMap<String, ChatGptModels.RemoteConversation> out,
                                       List<ChatGptModels.RemoteConversation> list) {
        if (list == null) return;
        for (ChatGptModels.RemoteConversation c : list) {
            if (c == null || c.id == null || c.id.trim().isEmpty()) continue;
            String id = c.id.trim();
            ChatGptModels.RemoteConversation old = out.get(id);
            if (old == null) {
                out.put(id, c);
                continue;
            }
            if ((old.title == null || old.title.trim().isEmpty() || "ChatGPT chat".equals(old.title)) &&
                    c.title != null && !c.title.trim().isEmpty()) old.title = c.title;
            old.createdAt = old.createdAt > 0 ? old.createdAt : c.createdAt;
            old.updatedAt = Math.max(old.updatedAt, c.updatedAt);
            old.archived = old.archived || c.archived;
            if ((old.projectId == null || old.projectId.trim().isEmpty()) && c.projectId != null)
                old.projectId = c.projectId;
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    ChatMirrorRepository.Stats stats() { return mirror.stats(); }
}
