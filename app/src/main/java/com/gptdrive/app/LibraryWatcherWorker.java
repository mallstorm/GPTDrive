package com.gptdrive.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

/** One metadata-only watcher pass. WorkManager controls cadence and network availability. */
public final class LibraryWatcherWorker extends Worker {
    private static final String EVENT_CHANNEL = "gptdrive_new_objects";

    public LibraryWatcherWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context app = getApplicationContext();
        if (!LibraryWatcherService.isEnabled(app)) return Result.success();
        ensureChannel(app);
        DriveDb db = DriveDb.get(app);
        boolean transientFailure = false;
        try {
            for (AccountProfile account : db.listAccounts()) {
                if (account == null || !ChatGptSessionStore.has(app, account.id)
                        || AccountAuth.needsLogin(app, account.id)) continue;
                try {
                    List<ChatGptModels.RemoteFile> recent =
                            new LibraryWatcherClient(app, account.id).pollRecent();
                    LibraryWatcherRepository.MergeResult result =
                            new LibraryWatcherRepository(db, account.id).merge(recent);
                    boolean baseline = LibraryWatcherService.prefs(app).getBoolean(
                            LibraryWatcherService.K_BASELINE_PREFIX + account.id, false);
                    if (!baseline || result.existingBefore == 0) {
                        LibraryWatcherService.prefs(app).edit().putBoolean(
                                LibraryWatcherService.K_BASELINE_PREFIX + account.id, true).apply();
                    } else if (!result.newItems.isEmpty()) {
                        notifyNew(app, account.id, result.newItems);
                        IndexChangeNotifier.notify(app, account.id);
                        for (ChatGptModels.RemoteFile f : result.newItems) {
                            if (f == null || !f.isImage()) continue;
                            String identity = f.stableUrl();
                            String source = f.thumbnailUrl == null || f.thumbnailUrl.trim().isEmpty()
                                    ? identity : f.thumbnailUrl;
                            ImageLoader.prefetchThumb(app, account.id, identity, source,
                                    () -> IndexChangeNotifier.notify(app, account.id));
                        }
                    }
                } catch (ChatGptProvider.AuthException e) {
                    // Retrying cannot repair it: flag the account and notify once.
                    AccountAuth.markNeedsLogin(app, account.id, e.getMessage());
                } catch (Exception e) {
                    transientFailure = true;
                }
            }
        } finally {
            // DriveDb is the process-wide singleton: never close it here.
        }
        return transientFailure ? Result.retry() : Result.success();
    }

    private static void notifyNew(Context app, long accountId,
                                  ArrayList<ChatGptModels.RemoteFile> items) {
        int images = 0;
        for (ChatGptModels.RemoteFile f : items) if (f != null && f.isImage()) images++;
        boolean allImages = images == items.size();
        String title = items.size() == 1 && allImages ? "Создано новое изображение"
                : allImages ? "Создано " + items.size() + " новых изображений"
                : items.size() == 1 ? "В GPT Drive появился новый файл"
                : "В GPT Drive появилось " + items.size() + " новых объектов";
        String text = items.size() == 1 && items.get(0) != null
                ? items.get(0).name : "Откройте GPT Drive для просмотра";

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, EVENT_CHANNEL) : new Notification.Builder(app);
        b.setSmallIcon(R.drawable.ic_drive).setContentTitle(title).setContentText(text)
                .setAutoCancel(true).setContentIntent(eventIntent(app, accountId, items, allImages));
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(7600 + (int) (Math.abs(accountId) % 300), b.build());
    }

    private static PendingIntent eventIntent(Context app, long accountId,
                                             ArrayList<ChatGptModels.RemoteFile> items,
                                             boolean allImages) {
        Intent open;
        ChatGptModels.RemoteFile first = items.size() == 1 ? items.get(0) : null;
        if (first != null && first.isImage()) {
            open = new Intent(app, ImageViewerActivity.class);
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_URL, first.stableUrl());
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_NAME, first.name);
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_MIME, first.mimeType);
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_SIZE, first.sizeBytes);
            open.putExtra(ImageViewerActivity.EXTRA_REMOTE_ACCOUNT_ID, accountId);
        } else {
            open = new Intent(app, NativeBrowserActivity.class);
            open.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID, accountId);
            open.putExtra(NativeBrowserActivity.EXTRA_CATEGORY,
                    allImages ? DriveCollections.IMAGES : DriveCollections.FILES);
            if (first != null) open.putExtra(NativeBrowserActivity.EXTRA_OPEN_FILE_ID, first.identity());
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(app, 7600 + (int) (Math.abs(accountId) % 300), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureChannel(Context app) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel events = new NotificationChannel(EVENT_CHANNEL,
                "Новые объекты GPT Drive", NotificationManager.IMPORTANCE_DEFAULT);
        events.setDescription("Новые изображения и файлы ChatGPT");
        nm.createNotificationChannel(events);
    }
}
