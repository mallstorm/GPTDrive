package com.gptdrive.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps the app-scoped loopback HTTP CONNECT proxy alive while Smart DNS is enabled.
 * This is not Android VPNService: it only serves GPT Drive's own HTTP/WebView traffic.
 */
public final class RoutingForegroundService extends Service {
    private static final String CHANNEL = "gptdrive_smartdns_routing";
    private static final int NOTIFICATION_ID = 7403;
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updater = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
            } catch (Exception ignored) {}
            handler.postDelayed(this, 60_000L);
        }
    };

    static boolean isRunning() { return running; }

    static void ensureStarted(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SmartDnsManager.initialize(app);
        if (!SmartDnsManager.isEnabled()) return;
        try {
            Intent i = new Intent(app, RoutingForegroundService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Exception ignored) {
            // Android can reject a background FGS start. The next visible Activity
            // or boot/package event will retry; foreground routing still works while
            // the process is alive through SmartDnsProxy.
        }
    }

    static void stop(Context context) {
        if (context == null) return;
        try { context.getApplicationContext().stopService(new Intent(context, RoutingForegroundService.class)); }
        catch (Exception ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SmartDnsManager.initialize(this);
        if (!SmartDnsManager.isEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        running = true;

        // Starts the loopback CONNECT server if necessary and applies the process-wide
        // WebView proxy override. TLS remains end-to-end; the proxy never decrypts it.
        SmartDnsProxy.proxy(this);
        SmartDnsProxy.applyWebViewRouting(this, null);

        handler.removeCallbacks(updater);
        handler.postDelayed(updater, 2_000L);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(updater);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(
                CHANNEL, "Smart DNS routing", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("GPT Drive local Smart DNS routing gateway");
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, DnsSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 7403, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        String route;
        try { route = SmartDnsManager.selectedSummary(); }
        catch (Exception e) { route = "Smart DNS"; }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("GPT Drive · Smart DNS")
                .setContentText(route + " · локальный HTTP gateway активен")
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
