package com.gptdrive.app;

import android.content.Context;

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

    ChatMirrorSyncEngine(Context context, DriveDb db, long accountId, ChatGptProvider provider) {
        this.provider = provider;
        this.mirror = new ChatMirrorRepository(context, db, accountId);
    }

    Result sync(int bodyBudget, ChatGptProvider.Progress progress) throws Exception {
        List<ChatGptModels.RemoteConversation> summaries = provider.loadConversations();
        mirror.applyRemoteSummaries(summaries);

        int limit = bodyBudget <= 0 ? DEFAULT_BODY_BUDGET : Math.min(bodyBudget, 500);
        List<String> pending = mirror.pendingIds(limit);
        int mirrored = 0;
        int failed = 0;
        int i = 0;
        for (String id : pending) {
            i++;
            try {
                ChatGptModels.RemoteConversation full = provider.loadConversation(id);
                mirror.storeFull(full);
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

        return new Result(summaries == null ? 0 : summaries.size(), mirrored, failed, mirror.stats());
    }

    ChatMirrorRepository.Stats stats() { return mirror.stats(); }
}
