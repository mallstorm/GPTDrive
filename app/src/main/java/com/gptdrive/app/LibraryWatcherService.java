package com.gptdrive.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windows-style lightweight Library watcher. It is intentionally separate from full sync:
 * only recent first pages are polled, metadata is merged by stable remote identity and file
 * bodies are never downloaded.
 */
public final class LibraryWatcherService extends Service {
    private static final String PREFS = "gptdrive_library_watcher";
    private static final String K_ENABLED = "enabled";
    private static final String K_INTERVAL = "interval_ms";
    private static final String K_BASELINE_PREFIX = "baseline_";
    private static final long DEFAULT_INTERVAL = 15_000L;
    private static final long MIN_INTERVAL = 12_000L;
    private static final String WATCH_CHANNEL = "gptdrive_library_watch";
    private static final String EVENT_CHANNEL = "gptdrive_new_objects";
    private static final int FOREGROUND_ID = 7301;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loopRunning = new AtomicBoolean(false);

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(K_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(K_ENABLED, enabled).apply();
        if (enabled) ensureStarted(context);
        else stop(context);
    }

    static long intervalMs(Context context) {
        long value = prefs(context).getLong(K_INTERVAL, DEFAULT_INTERVAL);
        if (value != 15_000L && value != 30_000L && value != 60_000L) return DEFAULT_INTERVAL;
        return Math.max(MIN_INTERVAL, value);
    }

    static void setIntervalMs(Context context, long value) {
        long normalized = value == 30_000L || value == 60_000L ? value : 15_000L;
        prefs(context).edit().putLong(K_INTERVAL, normalized).apply();
        if (isEnabled(context)) ensureStarted(context);
    }

    static String intervalLabel(Context context) {
        long value = intervalMs(context);
        if (value == 60_000L) return "60 секунд";
        if (value == 30_000L) return "30 секунд";
        return "15 секунд";
    }

    static void ensureStarted(Context context) {
        if (context == null || !isEnabled(context)) return;
        Intent i = new Intent(context.getApplicationContext(), LibraryWatcherService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.getApplicationContext().startForegroundService(i);
            else context.getApplicationContext().startService(i);
        } catch (Exception ignored) {}
    }

    static void stop(Context context) {
        if (context == null) return;
        try { context.getApplicationContext().stopService(new Intent(context, LibraryWatcherService.class)); }
        catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public void onCreate() {
        super.onCreate();
        ensureChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(FOREGROUND_ID, watcherNotification("Наблюдение за новыми объектами активно"));
        if (loopRunning.compareAndSet(false, true)) worker.execute(this::watchLoop);
        return START_STICKY;
    }

    private void watchLoop() {
        try {
            while (isEnabled(this) && !Thread.currentThread().isInterrupted()) {
                long started = System.currentTimeMillis();
                runCycle();
                long wait = Math.max(MIN_INTERVAL, intervalMs(this)) - (System.currentTimeMillis() - started);
                if (wait < 1000L) wait = 1000L;
                try { Thread.sleep(wait); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } finally {
            loopRunning.set(false);
            if (!isEnabled(this)) {
                try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
                stopSelf();
            }
        }
    }

    private void runCycle() {
        DriveDb db = new DriveDb(this);
        int connected = 0;
        int discovered = 0;
        try {
            for (AccountProfile account : db.listAccounts()) {
                if (account == null || !ChatGptSessionStore.has(this, account.id)) continue;
                connected++;
                try {
                    List<ChatGptModels.RemoteFile> recent = new LibraryWatcherClient(this, account.id).pollRecent();
                    LibraryWatcherRepository.MergeResult merge =
                            new LibraryWatcherRepository(db, account.id).merge(recent);
                    boolean baseline = prefs(this).getBoolean(K_BASELINE_PREFIX + account.id, false);
                    if (!baseline || merge.existingBefore == 0) {
                        prefs(this).edit().putBoolean(K_BASELINE_PREFIX + account.id, true).apply();
                    } else if (!merge.newItems.isEmpty()) {
                        discovered += merge.newItems.size();
                        postNewItems(account, merge.newItems);
                    }
                } catch (ChatGptProvider.AuthException e) {
                    // Do not spam notifications for expired sessions; normal account UI handles re-login.
                } catch (Exception ignored) {
                    // Network/backend failures are transient. The next lightweight cycle retries.
                }
            }
            if (discovered > 0) notifyRoots();
        } finally {
            String text = connected == 0
                    ? "Ожидание подключённого ChatGPT-аккаунта"
                    : "Наблюдение активно · аккаунтов: " + connected + " · каждые " + intervalLabel(this);
            updateWatcherNotification(text);
            db.close();
        }
    }

    private void postNewItems(AccountProfile account, ArrayList<ChatGptModels.RemoteFile> items) {
        if (items == null || items.isEmpty()) return;
        int images = 0;
        for (ChatGptModels.RemoteFile f : items) if (f != null && f.isImage()) images++;
        boolean allImages = images == items.size();

        String title;
        String text;
        if (items.size() == 1 && allImages) {
            title = "Создано новое изображение";
            text = "Оно уже появилось в GPT Drive";
        } else if (allImages) {
            title = "Создано " + items.size() + " новых изображений";
            text = "Откройте галерею GPT Drive";
        } else if (items.size() == 1) {
            title = "В GPT Drive появился новый файл";
            text = "Метаданные обновлены без полной синхронизации";
        } else {
            title = "В GPT Drive появилось " + items.size() + " новых объектов";
            text = images > 0 ? "Изображений: " + images + " · остальные: " + (items.size() - images) : "Библиотека обновлена";
        }

        PendingIntent content = eventIntent(account.id, items, allImages);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, EVENT_CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_drive)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(eventNotificationId(account.id), builder.build());
    }

    private PendingIntent eventIntent(long accountId, ArrayList<ChatGptModels.RemoteFile> items, boolean allImages) {
        Intent open;
        if (items.size() == 1 && items.get(0) != null && items.get(0).isImage()) {
            ChatGptModels.RemoteFile f = items.get(0);
            open = new Intent(this, ImageViewerActivity.class);
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_URL, f.stableUrl());
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_NAME, f.name);
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_MIME, f.mimeType);
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_SIZE, f.sizeBytes);
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_ACCOUNT_ID, accountId);
        } else {
            open = new Intent(this, NativeBrowserActivity.class);
            open.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID, accountId);
            open.putExtra(NativeBrowserActivity.EXTRA_CATEGORY,
                    allImages ? DriveCollections.IMAGES : DriveCollections.FILES);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, eventNotificationId(accountId), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification watcherNotification(String text) {
        Intent settings = new Intent(this, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, FOREGROUND_ID, settings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, WATCH_CHANNEL)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_drive)
                .setContentTitle("GPT Drive · наблюдение")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateWatcherNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(FOREGROUND_ID, watcherNotification(text));
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel watch = new NotificationChannel(WATCH_CHANNEL,
                "Наблюдение GPT Drive", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("Фоновая проверка новых объектов без полной синхронизации");
        watch.setShowBadge(false);
        nm.createNotificationChannel(watch);

        NotificationChannel events = new NotificationChannel(EVENT_CHANNEL,
                "Новые объекты GPT Drive", NotificationManager.IMPORTANCE_DEFAULT);
        events.setDescription("Уведомления о новых изображениях и файлах ChatGPT");
        nm.createNotificationChannel(events);
    }

    private void notifyRoots() {
        try {
            getContentResolver().notifyChange(
                    DocumentsContract.buildRootsUri("com.gptdrive.app.documents"), null);
        } catch (Exception ignored) {}
    }

    private int eventNotificationId(long accountId) {
        return 7600 + (int) (Math.abs(accountId) % 300);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        loopRunning.set(false);
        worker.shutdownNow();
        super.onDestroy();
    }
}
