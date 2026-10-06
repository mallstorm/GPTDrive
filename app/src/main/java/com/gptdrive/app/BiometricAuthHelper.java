package com.gptdrive.app;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

/** Platform-only fingerprint/biometric helper; no AndroidX dependency required. */
final class BiometricAuthHelper {
    interface Callback {
        void onSuccess();
        void onFailure(String message);
    }

    private BiometricAuthHelper() {}

    @SuppressWarnings("deprecation")
    static boolean isAvailable(Context context) {
        try {
            FingerprintManager fm = (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    static CancellationSignal authenticate(Activity activity, Callback callback) {
        if (activity == null || callback == null || !isAvailable(activity)) {
            if (callback != null) callback.onFailure("Отпечаток пальца недоступен");
            return null;
        }
        CancellationSignal signal = new CancellationSignal();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(activity)
                            .setTitle("Разблокировка GPT Drive")
                            .setSubtitle("Подтвердите отпечаток пальца")
                            .setDescription("Или используйте графический ключ")
                            .setNegativeButton("Графический ключ", activity.getMainExecutor(),
                                    (DialogInterface dialog, int which) -> callback.onFailure("Используйте графический ключ"))
                            .build();
            prompt.authenticate(signal, activity.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            callback.onSuccess();
                        }

                        @Override public void onAuthenticationFailed() {
                            callback.onFailure("Отпечаток не распознан");
                        }

                        @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                            callback.onFailure(errString == null ? "Биометрическая проверка отменена" : errString.toString());
                        }
                    });
            return signal;
        }

        FingerprintManager fm = (FingerprintManager) activity.getSystemService(Context.FINGERPRINT_SERVICE);
        if (fm == null) {
            callback.onFailure("Сканер отпечатка недоступен");
            return null;
        }
        Handler main = new Handler(Looper.getMainLooper());
        fm.authenticate(null, signal, 0, new FingerprintManager.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                main.post(callback::onSuccess);
            }

            @Override public void onAuthenticationFailed() {
                main.post(() -> callback.onFailure("Отпечаток не распознан"));
            }

            @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                main.post(() -> callback.onFailure(errString == null ? "Биометрическая проверка отменена" : errString.toString()));
            }
        }, null);
        return signal;
    }
}
