package com.gptdrive.app;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

public final class GptDriveApp extends Application implements Application.ActivityLifecycleCallbacks {
    private static final String PREFS = "gptdrive_app_runtime";
    private static final String K_NOTIFICATION_PROMPTED = "watch_notification_prompted";
    private static final int REQ_NOTIFICATIONS = 7402;

    private int started;
    private boolean changing;

    @Override public void onCreate() {
        super.onCreate();
        AccountAuth.init(this);
        registerActivityLifecycleCallbacks(this);
        SmartDnsManager.initialize(this);
        SmartDnsProxy.applyWebViewRouting(this, null);
        if (SmartDnsManager.isEnabled()) RoutingForegroundService.ensureStarted(this);
        LibraryWatcherService.ensureStarted(this);
    }

    @Override public void onActivityStarted(Activity activity) {
        if (started++ == 0 && !changing) {
            AppLockManager.onForeground(this);
            if (SmartDnsManager.isEnabled()) RoutingForegroundService.ensureStarted(this);
            if (AppLockManager.needsUnlock(this) && !(activity instanceof UnlockActivity)) {
                startActivity(new Intent(this, UnlockActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } else {
                AutoSync.onForeground(this);
            }
        }
    }

    @Override public void onActivityResumed(Activity activity) {
        if (SmartDnsManager.isEnabled() && !RoutingForegroundService.isRunning()) {
            RoutingForegroundService.ensureStarted(this);
        }
        if (Build.VERSION.SDK_INT < 33) return;
        if (!LibraryWatcherService.isEnabled(this) && !SmartDnsManager.isEnabled()) return;
        if (activity instanceof UnlockActivity || AppLockManager.needsUnlock(this)) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(K_NOTIFICATION_PROMPTED, false)) return;
        p.edit().putBoolean(K_NOTIFICATION_PROMPTED, true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    @Override public void onActivityStopped(Activity activity) {
        changing = activity.isChangingConfigurations();
        started = Math.max(0, started - 1);
        if (started == 0 && !changing) AppLockManager.onBackground();
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
