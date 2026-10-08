package com.gptdrive.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the user-enabled Smart DNS gateway after reboot or package replacement. */
public final class RoutingBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        SmartDnsManager.initialize(context);
        if (SmartDnsManager.isEnabled()) RoutingForegroundService.ensureStarted(context);
    }
}
