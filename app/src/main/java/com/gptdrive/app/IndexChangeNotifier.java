package com.gptdrive.app;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

final class IndexChangeNotifier {
    private static final String AUTHORITY = "com.gptdrive.app.documents";

    private IndexChangeNotifier() {}

    static Uri uri(long accountId) {
        return Uri.parse("content://" + AUTHORITY + "/index/" + accountId);
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Set<Long> SCHEDULED = new java.util.HashSet<>();

    /** Coalesces bursts (a notification per page and per finished preview) into one per ~1.2 s. */
    static void notify(Context context, long accountId) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        synchronized (SCHEDULED) {
            if (!SCHEDULED.add(accountId)) return;
        }
        MAIN.postDelayed(() -> {
            synchronized (SCHEDULED) { SCHEDULED.remove(accountId); }
            deliver(app, accountId);
        }, 1200L);
    }

    private static void deliver(Context context, long accountId) {
        try { context.getContentResolver().notifyChange(uri(accountId), null); }
        catch (Exception ignored) {}
        try {
            context.getContentResolver().notifyChange(
                    DocumentsContract.buildRootsUri(AUTHORITY), null);
        } catch (Exception ignored) {}
    }
}
