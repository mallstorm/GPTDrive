package com.gptdrive.app;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

public final class GptDriveApp extends Application implements Application.ActivityLifecycleCallbacks {
    private int started;
    private boolean changing;
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        NewFileWatchScheduler.ensure(this);
    }
    @Override public void onActivityStarted(Activity activity) {
        if (started++ == 0 && !changing) {
            AppLockManager.onForeground(this);
            if (AppLockManager.needsUnlock(this) && !(activity instanceof UnlockActivity)) {
                startActivity(new Intent(this, UnlockActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
        }
    }
    @Override public void onActivityStopped(Activity activity) {
        changing = activity.isChangingConfigurations();
        started = Math.max(0, started - 1);
        if (started == 0 && !changing) AppLockManager.onBackground();
    }
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityResumed(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}