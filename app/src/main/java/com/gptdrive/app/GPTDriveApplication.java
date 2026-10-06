package com.gptdrive.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

/** Process lifecycle bridge for relocking the app after it leaves the foreground. */
public final class GPTDriveApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private int startedActivities;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityStarted(Activity activity) {
        boolean enteringForeground = startedActivities == 0;
        startedActivities++;
        if (!enteringForeground) return;
        if (activity instanceof AppLockActivity || activity instanceof AppEntryActivity) return;

        AppLockStore store = new AppLockStore(this);
        if (store.shouldRequireUnlock()) {
            AppLockStore.lockSession();
            Intent lock = new Intent(this, AppLockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(lock);
        } else {
            AppLockStore.clearBackgroundMarker();
        }
    }

    @Override public void onActivityStopped(Activity activity) {
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0 && !activity.isChangingConfigurations()) AppLockStore.markBackgrounded();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
