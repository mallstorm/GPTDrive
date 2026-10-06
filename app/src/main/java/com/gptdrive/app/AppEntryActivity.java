package com.gptdrive.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/** Launcher gate: app lock first, notification permission second, home last. */
public final class AppEntryActivity extends Activity {
    private static final int REQ_UNLOCK = 7101;
    private static final int REQ_NOTIFICATIONS = 7102;
    private boolean launchContinued;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLockStore store = new AppLockStore(this);
        if (store.isEnabled()) {
            AppLockStore.lockSession();
            startActivityForResult(new Intent(this, AppLockActivity.class), REQ_UNLOCK);
        } else {
            AppLockStore.markSessionUnlocked();
            continueLaunch();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_UNLOCK) return;
        if (resultCode == RESULT_OK) continueLaunch();
        else finish();
    }

    private void continueLaunch() {
        if (launchContinued) return;
        launchContinued = true;
        if (LibraryWatcherService.isEnabled(this) && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        openHome();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) openHome();
    }

    private void openHome() {
        LibraryWatcherService.ensureStarted(this);
        startActivity(new Intent(this, FolderHomeActivity.class));
        finish();
    }
}
