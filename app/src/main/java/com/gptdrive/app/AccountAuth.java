package com.gptdrive.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Single place that answers "is this account usable right now?".
 *
 * An account is connected when it has a stored backend session (pasted Session JSON or WebView
 * login) or WebView cookies, and has not been marked "needs login" after a failed token refresh.
 * Marking raises exactly one notification; saving a fresh session clears the mark.
 */
final class AccountAuth {
    private static final String PREFS = "gptdrive_account_auth";
    private static final String CHANNEL = "gptdrive_auth";
    private static volatile Context app;

    private AccountAuth() {}

    static void init(Context c) { if (c != null) app = c.getApplicationContext(); }

    static boolean connected(AccountProfile a) {
        if (a == null) return false;
        Context c = app;
        if (c == null) return a.hasSession();
        if (needsLogin(c, a.id)) return false;
        return a.hasSession() || ChatGptSessionStore.has(c, a.id);
    }

    static boolean needsLogin(Context c, long accountId) {
        return prefs(c).getBoolean("needs_" + accountId, false);
    }

    static void clear(Context c, long accountId) {
        if (c == null || accountId <= 0) return;
        prefs(c).edit().remove("needs_" + accountId).apply();
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notificationId(accountId));
        } catch (Exception ignored) {}
    }

    /** Idempotent: the notification is shown only on the first transition. */
    static void markNeedsLogin(Context context, long accountId, String reason) {
        if (context == null || accountId <= 0) return;
        Context c = context.getApplicationContext();
        SharedPreferences p = prefs(c);
        if (p.getBoolean("needs_" + accountId, false)) return;
        p.edit().putBoolean("needs_" + accountId, true).apply();
        SyncLog.i(c, "auth", "account " + accountId + " needs login: " + reason);
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                    c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        "Требуется вход", NotificationManager.IMPORTANCE_DEFAULT));
            }
            AccountProfile a = DriveDb.get(c).accountById(accountId);
            String who = a == null ? "аккаунт" : a.displayName();
            Intent open = new Intent(c, FolderHomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, notificationId(accountId), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_drive)
                    .setContentTitle("GPT Drive: нужен вход")
                    .setContentText(who + " — сессия ChatGPT истекла. Вставьте новый Session JSON.")
                    .setAutoCancel(true).setContentIntent(pi);
            nm.notify(notificationId(accountId), b.build());
        } catch (Exception ignored) {}
    }

    private static int notificationId(long accountId) { return 7800 + (int) (Math.abs(accountId) % 100); }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
