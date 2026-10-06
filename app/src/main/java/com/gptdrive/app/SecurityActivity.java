package com.gptdrive.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.biometric.BiometricManager;

import java.util.Arrays;

/** Security settings. A graphical pattern is always the recovery path for biometric unlock. */
public final class SecurityActivity extends Activity {
    private LinearLayout root;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (AppLockManager.BIOMETRIC.equals(AppLockManager.mode(this)))
            AppLockManager.setMode(this, AppLockManager.BOTH);
        showSettings();
    }

    private void showSettings() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(0xFFF7F9FA);

        root.addView(text("Безопасность", 24, true), new LinearLayout.LayoutParams(-1, dp(58)));

        Switch enabled = new Switch(this);
        enabled.setText("Блокировка приложения");
        enabled.setChecked(AppLockManager.enabled(this));
        enabled.setOnCheckedChangeListener((v, on) -> {
            if (on && !AppLockManager.hasPattern(this)) {
                v.setChecked(false);
                setupPattern(true);
            } else {
                AppLockManager.setEnabled(this, on);
            }
        });
        root.addView(enabled, new LinearLayout.LayoutParams(-1, dp(54)));

        root.addView(text("Способ разблокировки", 13, true));
        String[] labels = {"Отпечаток + графический ключ", "Только графический ключ"};
        String[] values = {AppLockManager.BOTH, AppLockManager.PATTERN};
        RadioGroup modes = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(100 + i);
            r.setText(labels[i]);
            r.setChecked(values[i].equals(AppLockManager.mode(this)));
            modes.addView(r);
        }
        modes.setOnCheckedChangeListener((g, id) -> {
            int i = id - 100;
            if (i < 0 || i >= values.length) return;
            if (AppLockManager.BOTH.equals(values[i]) && !biometricAvailable()) {
                Toast.makeText(this, "На устройстве нет доступного зарегистрированного отпечатка", Toast.LENGTH_LONG).show();
                AppLockManager.setMode(this, AppLockManager.PATTERN);
                ((RadioButton) modes.findViewById(101)).setChecked(true);
                return;
            }
            AppLockManager.setMode(this, values[i]);
        });
        root.addView(modes);

        TextView fallback = text("Графический ключ обязателен и всегда остаётся резервным способом входа, даже когда включён отпечаток пальца.", 11, false);
        fallback.setTextColor(0xFF667085);
        fallback.setPadding(0, dp(8), 0, dp(10));
        root.addView(fallback);

        Button pattern = new Button(this);
        pattern.setText(AppLockManager.hasPattern(this) ? "Изменить графический ключ" : "Настроить графический ключ");
        pattern.setOnClickListener(v -> setupPattern(false));
        root.addView(pattern, new LinearLayout.LayoutParams(-1, dp(52)));

        root.addView(text("Автоблокировка", 13, true), new LinearLayout.LayoutParams(-1, dp(40)));
        long[] times = {0, 30_000, 60_000, 300_000, 900_000};
        String[] names = {"Сразу", "30 секунд", "1 минута", "5 минут", "15 минут"};
        RadioGroup timeout = new RadioGroup(this);
        for (int i = 0; i < names.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(200 + i);
            r.setText(names[i]);
            r.setChecked(AppLockManager.timeout(this) == times[i]);
            timeout.addView(r);
        }
        timeout.setOnCheckedChangeListener((g, id) -> {
            int i = id - 200;
            if (i >= 0 && i < times.length) AppLockManager.setTimeout(this, times[i]);
        });
        root.addView(timeout);

        TextView note = text("Экран разблокировки и настройки безопасности защищены от скриншотов и предпросмотра в недавних приложениях.", 11, false);
        note.setTextColor(0xFF667085);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private boolean biometricAvailable() {
        int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.BIOMETRIC_WEAK;
        return BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void setupPattern(boolean enable) {
        root.removeAllViews();
        TextView title = text("Нарисуйте новый ключ", 19, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(70)));
        PatternLockView pattern = new PatternLockView(this);
        root.addView(pattern, new LinearLayout.LayoutParams(-1, dp(360)));
        final int[][] first = {null};
        pattern.setListener(p -> {
            if (p.length < 4) {
                Toast.makeText(this, "Минимум 4 точки", Toast.LENGTH_SHORT).show();
                return;
            }
            if (first[0] == null) {
                first[0] = p.clone();
                title.setText("Повторите графический ключ");
            } else if (Arrays.equals(first[0], p)) {
                AppLockManager.setPattern(this, p);
                if (enable) AppLockManager.setEnabled(this, true);
                Toast.makeText(this, "Графический ключ сохранён", Toast.LENGTH_SHORT).show();
                showSettings();
            } else {
                first[0] = null;
                title.setText("Не совпало. Нарисуйте заново");
            }
        });
        setContentView(root);
    }

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
