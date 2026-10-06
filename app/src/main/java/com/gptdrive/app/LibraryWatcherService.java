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

/** Persistent Windows-style recent-object watcher, deliberately independent from full sync. */
public final class LibraryWatcherService extends Service {
    private static final String PREFS = "gptdrive_library_watcher";
    private static final String K_ENABLED = "enabled";
    private static final String K_INTERVAL = "interval_ms";
    private static final String K_BASELINE_PREFIX = "baseline_";
    private static final long DEFAULT_INTERVAL = 15_000L;
    private static final String WATCH_CHANNEL = "gptdrive_library_watch";
    private static final String EVENT_CHANNEL = "gptdrive_new_objects";
    private static final int FOREGROUND_ID = 7301;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    static boolean isEnabled(Context c) { return prefs(c).getBoolean(K_ENABLED, true); }
    static void setEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(K_ENABLED, enabled).apply();
        if (enabled) ensureStarted(c); else stop(c);
    }
    static long intervalMs(Context c) {
        long v = prefs(c).getLong(K_INTERVAL, DEFAULT_INTERVAL);
        return v == 30_000L || v == 60_000L ? v : DEFAULT_INTERVAL;
    }
    static void setIntervalMs(Context c, long value) {
        long v = value == 30_000L || value == 60_000L ? value : DEFAULT_INTERVAL;
        prefs(c).edit().putLong(K_INTERVAL, v).apply();
        if (isEnabled(c)) ensureStarted(c);
    }
    static String intervalLabel(Context c) {
        long v = intervalMs(c);
        return v == 60_000L ? "60 секунд" : v == 30_000L ? "30 секунд" : "15 секунд";
    }
    static void ensureStarted(Context c) {
        if (c == null || !isEnabled(c)) return;
        Intent i = new Intent(c.getApplicationContext(), LibraryWatcherService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.getApplicationContext().startForegroundService(i);
            else c.getApplicationContext().startService(i);
        } catch (Exception ignored) {}
    }
    static void stop(Context c) {
        if (c == null) return;
        try { c.getApplicationContext().stopService(new Intent(c, LibraryWatcherService.class)); }
        catch (Exception ignored) {}
    }
    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public void onCreate() { super.onCreate(); ensureChannels(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) { stopSelf(); return START_NOT_STICKY; }
        startForeground(FOREGROUND_ID, watchNotification("Наблюдение за новыми объектами активно"));
        if (running.compareAndSet(false, true)) worker.execute(this::loop);
        return START_STICKY;
    }

    private void loop() {
        try {
            while (isEnabled(this) && !Thread.currentThread().isInterrupted()) {
                long started = System.currentTimeMillis();
                cycle();
                long wait = intervalMs(this) - (System.currentTimeMillis() - started);
                try { Thread.sleep(Math.max(1000L, wait)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } finally {
            running.set(false);
            if (!isEnabled(this)) { try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {} stopSelf(); }
        }
    }

    private void cycle() {
        DriveDb db = new DriveDb(this);
        int connected = 0;
        int found = 0;
        try {
            for (AccountProfile account : db.listAccounts()) {
                if (account == null || !ChatGptSessionStore.has(this, account.id)) continue;
                connected++;
                try {
                    List<ChatGptModels.RemoteFile> recent = new LibraryWatcherClient(this, account.id).pollRecent();
                    LibraryWatcherRepository.MergeResult result = new LibraryWatcherRepository(db, account.id).merge(recent);
                    boolean baseline = prefs(this).getBoolean(K_BASELINE_PREFIX + account.id, false);
                    if (!baseline || result.existingBefore == 0) {
                        prefs(this).edit().putBoolean(K_BASELINE_PREFIX + account.id, true).apply();
                    } else if (!result.newItems.isEmpty()) {
                        found += result.newItems.size();
                        notifyNew(account.id, result.newItems);
                    }
                } catch (Exception ignored) {}
            }
            if (found > 0) notifyRoots();
        } finally {
            updateWatchNotification(connected == 0
                    ? "Ожидание подключённого ChatGPT-аккаунта"
                    : "Аккаунтов: " + connected + " · проверка каждые " + intervalLabel(this));
            db.close();
        }
    }

    private void notifyNew(long accountId, ArrayList<ChatGptModels.RemoteFile> items) {
        int images = 0;
        for (ChatGptModels.RemoteFile f : items) if (f != null && f.isImage()) images++;
        boolean allImages = images == items.size();
        String title = items.size() == 1 && allImages ? "Создано новое изображение"
                : allImages ? "Создано " + items.size() + " новых изображений"
                : items.size() == 1 ? "В GPT Drive появился новый файл"
                : "В GPT Drive появилось " + items.size() + " новых объектов";
        String text = allImages ? "Откройте галерею GPT Drive" : "Метаданные обновлены без полной синхронизации";

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, EVENT_CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_drive).setContentTitle(title).setContentText(text)
                .setAutoCancel(true).setContentIntent(eventIntent(accountId, items, allImages));
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(7600 + (int)(Math.abs(accountId) % 300), b.build());
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
            open.putExtra(NativeBrowserActivity.EXTRA_CATEGORY, allImages ? DriveCollections.IMAGES : DriveCollections.FILES);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 7600 + (int)(Math.abs(accountId) % 300), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification watchNotification(String text) {
        Intent i = new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, FOREGROUND_ID, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, WATCH_CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_drive).setContentTitle("GPT Drive · наблюдение")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build();
    }

    private void updateWatchNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(FOREGROUND_ID, watchNotification(text));
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel watch = new NotificationChannel(WATCH_CHANNEL, "Наблюдение GPT Drive", NotificationManager.IMPORTANCE_LOW);
        watch.setDescription("Проверка новых объектов без полной синхронизации");
        watch.setShowBadge(false);
        nm.createNotificationChannel(watch);
        NotificationChannel events = new NotificationChannel(EVENT_CHANNEL, "Новые объекты GPT Drive", NotificationManager.IMPORTANCE_DEFAULT);
        events.setDescription("Новые изображения и файлы ChatGPT");
        nm.createNotificationChannel(events);
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri("com.gptdrive.app.documents"), null); }
        catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { running.set(false); worker.shutdownNow(); super.onDestroy(); }
}
