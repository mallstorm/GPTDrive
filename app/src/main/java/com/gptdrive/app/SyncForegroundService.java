package com.gptdrive.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.DocumentsContract;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground data-sync service so metadata synchronization survives Activity/background changes. */
public final class SyncForegroundService extends Service {
    static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final String CHANNEL = "gptdrive_sync";
    private static final Set<Long> RUNNING = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static void start(Context context, long accountId) {
        Intent i = new Intent(context, SyncForegroundService.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        long accountId = intent == null ? -1 : intent.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        if (accountId <= 0) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(notificationId(accountId), buildNotification(accountId, "Подготовка синхронизации…", 0));
        if (!RUNNING.add(accountId)) return START_REDELIVER_INTENT;

        SyncRuntimeState.begin(this, accountId);
        worker.execute(() -> runSync(accountId));
        return START_REDELIVER_INTENT;
    }

    private void runSync(long accountId) {
        DriveDb db = DriveDb.get(this);
        try {
            ChatGptApiClient client = new ChatGptApiClient(this, db, accountId);
            ChatGptApiClient.SyncResult result = client.sync((phase, part, total, objects) -> {
                SyncRuntimeState.progress(this, accountId, phase, part, total, objects);
                notifyProgress(accountId, phase, objects);
            });
            SyncRuntimeState.success(this, accountId, result);
            notifyRoots();
        } catch (ChatGptApiClient.AuthException e) {
            AccountAuth.markNeedsLogin(this, accountId, e.getMessage());
            SyncRuntimeState.auth(this, accountId,
                    e.getMessage() == null ? "Сессия ChatGPT недействительна" : e.getMessage());
        } catch (Exception e) {
            String m = e.getMessage();
            SyncRuntimeState.error(this, accountId,
                    m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m.trim());
        } finally {
            RUNNING.remove(accountId);
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
            stopSelf();
        }
    }

    private void notifyProgress(long accountId, String phase, int objects) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notificationId(accountId), buildNotification(accountId,
                (phase == null || phase.trim().isEmpty() ? "Синхронизация" : phase) + " · " + objects, objects));
    }

    private Notification buildNotification(long accountId, String text, int objects) {
        Intent open = new Intent(this, BrowserActivity.class);
        open.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, accountId);
        open.putExtra(BrowserActivity.EXTRA_SECTION, "Library");
        open.putExtra(BrowserActivity.EXTRA_ATTACH_ONLY, true);
        PendingIntent pi = PendingIntent.getActivity(this, notificationId(accountId), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_drive)
                .setContentTitle("GPT Drive · синхронизация")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(0, 0, true);
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL, "Синхронизация GPT Drive", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("Фоновая синхронизация метаданных GPT Drive");
        nm.createNotificationChannel(c);
    }

    private void notifyRoots() {
        try {
            getContentResolver().notifyChange(
                    DocumentsContract.buildRootsUri("com.gptdrive.app.documents"), null);
        } catch (Exception ignored) {}
    }

    private int notificationId(long accountId) {
        return 6100 + (int) (Math.abs(accountId) % 700);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
