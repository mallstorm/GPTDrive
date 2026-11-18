package com.gptdrive.app;

import android.content.Context;

/**
 * Starts indexing without a button press: right after a session is connected and whenever the
 * app comes to the foreground with a stale index. Pages are merged and previews prefetched as
 * they arrive, so folders and thumbnails fill in while the first full pass is still running.
 */
final class AutoSync {
    private static final long STALE_MS = 10L * 60L * 1000L;

    private AutoSync() {}

    /** Immediately after login / Session JSON import. */
    static void startNow(Context context, long accountId) {
        if (context == null || accountId <= 0) return;
        try { SyncForegroundService.start(context.getApplicationContext(), accountId); }
        catch (Exception ignored) {}
    }

    /** On foreground transition: sync the active account when it never synced or is stale. */
    static void onForeground(Context context) {
        try {
            Context app = context.getApplicationContext();
            DriveDb db = DriveDb.get(app);
            AccountProfile a = db.activeAccount();
            if (a == null || !AccountAuth.connected(a)) return;
            SyncRuntimeState.Snapshot s = SyncRuntimeState.read(app, a.id);
            if (s.running()) return;
            boolean neverSynced = s.updatedAt == 0;
            boolean stale = System.currentTimeMillis() - s.updatedAt > STALE_MS;
            if (neverSynced || (s.success() && stale)) startNow(app, a.id);
        } catch (Exception ignored) {}
    }
}
