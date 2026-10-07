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

    static void notify(Context context, long accountId) {
        if (context == null) return;
        try { context.getContentResolver().notifyChange(uri(accountId), null); }
        catch (Exception ignored) {}
        try {
            context.getContentResolver().notifyChange(
                    DocumentsContract.buildRootsUri(AUTHORITY), null);
        } catch (Exception ignored) {}
    }
}
