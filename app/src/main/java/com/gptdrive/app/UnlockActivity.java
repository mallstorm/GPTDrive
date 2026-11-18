package com.gptdrive.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

/** Secure unlock screen. A configured graphical pattern is always available as fallback. */
public final class UnlockActivity extends FragmentActivity {
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        build();
        if (AppLockManager.allowBiometric(this)) biometric();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(0xFFF7F9FA);

        TextView title = text("GPT Drive", 26, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(64)));

        boolean hasPattern = AppLockManager.hasPattern(this);
        status = text(AppLockManager.allowBiometric(this)
                ? "Разблокируйте отпечатком или графическим ключом"
                : "Нарисуйте графический ключ", 14, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(62)));

        if (hasPattern) {
            PatternLockView v = new PatternLockView(this);
            v.setListener(this::check);
            root.addView(v, new LinearLayout.LayoutParams(-1, dp(330)));
        }
        setContentView(root);
    }

    private void biometric() {
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.BIOMETRIC_WEAK;
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            status.setText("Биометрия недоступна — используйте графический ключ");
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        AppLockManager.resetFailures(UnlockActivity.this);
                        success();
                    }
                    @Override public void onAuthenticationFailed() { status.setText("Отпечаток не распознан — попробуйте ещё раз или используйте ключ"); }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        if (!isFinishing()) status.setText("Используйте графический ключ");
                    }
                });
        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle("GPT Drive")
                .setSubtitle("Разблокировка приложения")
                .setNegativeButtonText("Графический ключ")
                .build());
    }

    private void check(int[] p) {
        long wait = AppLockManager.blockedUntil(this) - System.currentTimeMillis();
        if (wait > 0) {
            status.setText("Слишком много попыток · повторите через " + Math.max(1, wait / 1000) + " с");
            return;
        }
        if (p.length >= 4 && AppLockManager.verify(this, p)) {
            AppLockManager.resetFailures(this);
            success();
            return;
        }
        AppLockManager.registerFailure(this);
        long next = AppLockManager.blockedUntil(this) - System.currentTimeMillis();
        status.setText(next > 0
                ? "Слишком много попыток · пауза " + Math.max(1, next / 1000) + " с"
                : "Неверный графический ключ");
    }

    private void success() {
        AppLockManager.unlock();
        finish();
    }

    @Override public void onBackPressed() { moveTaskToBack(true); }

    private TextView text(String s, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(0xFF101828);
        if (bold) v.setTypeface(null, 1);
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
