package com.gptdrive.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** GPT Drive settings: background watcher and application protection. */
public final class SettingsActivity extends Activity {
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private static final int REQ_PATTERN = 7201;
    private static final int REQ_NOTIFICATIONS = 7202;

    private AppLockStore lockStore;
    private Switch watcherSwitch;
    private TextView watcherInterval;
    private Switch lockSwitch;
    private TextView patternStatus;
    private Switch biometricSwitch;
    private TextView biometricHint;
    private TextView timeoutValue;
    private boolean refreshing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        lockStore = new AppLockStore(this);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(6), dp(12), dp(24));

        body.addView(sectionLabel("ФОНОВАЯ РАБОТА"));
        body.addView(buildWatcherCard());
        body.addView(sectionLabel("БЕЗОПАСНОСТЬ"));
        body.addView(buildSecurityCard());

        TextView note = text("Фоновое наблюдение проверяет только свежие метаданные Library и изображений. Полная синхронизация остаётся отдельной операцией.", 10, false);
        note.setTextColor(MUTED);
        note.setPadding(dp(6), dp(12), dp(6), 0);
        note.setLineSpacing(0, 1.15f);
        body.addView(note);

        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), 0);
        TextView back = text("‹", 32, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(52)));
        TextView title = text("Настройки", 20, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView version = text("v" + AppIdentity.VERSION, 10, true);
        version.setTextColor(MUTED);
        version.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        bar.addView(version, new LinearLayout.LayoutParams(dp(70), dp(52)));
        return bar;
    }

    private View buildWatcherCard() {
        LinearLayout card = card();

        LinearLayout row = row();
        LinearLayout labels = labels("Фоновое наблюдение", "Новые файлы и изображения без полной синхронизации");
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        watcherSwitch = new Switch(this);
        watcherSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (refreshing) return;
            LibraryWatcherService.setEnabled(this, checked);
            if (checked) requestNotificationPermissionIfNeeded();
            refreshState();
        });
        row.addView(watcherSwitch, new LinearLayout.LayoutParams(-2, dp(52)));
        card.addView(row);
        card.addView(divider());

        LinearLayout interval = clickableRow();
        interval.addView(labels("Интервал проверки", "Лёгкий запрос к свежей странице"), new LinearLayout.LayoutParams(0, -2, 1));
        watcherInterval = valueText("");
        interval.addView(watcherInterval, new LinearLayout.LayoutParams(dp(110), dp(52)));
        interval.setOnClickListener(v -> chooseWatcherInterval());
        card.addView(interval);

        TextView watcherInfo = text("Механизм работает отдельно от кнопки «Синхронизировать»: сравнивает стабильные remote ID и уведомляет только о действительно новых объектах.", 10, false);
        watcherInfo.setTextColor(MUTED);
        watcherInfo.setPadding(dp(12), dp(2), dp(12), dp(12));
        card.addView(watcherInfo);
        return card;
    }

    private View buildSecurityCard() {
        LinearLayout card = card();

        LinearLayout lockRow = row();
        lockRow.addView(labels("Блокировка приложения", "Закрывать GPT Drive графическим ключом"), new LinearLayout.LayoutParams(0, -2, 1));
        lockSwitch = new Switch(this);
        lockSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (refreshing) return;
            if (checked) {
                if (!lockStore.hasPattern()) openPatternSetup();
                else lockStore.setEnabled(true);
            } else {
                lockStore.setEnabled(false);
            }
            refreshState();
        });
        lockRow.addView(lockSwitch, new LinearLayout.LayoutParams(-2, dp(52)));
        card.addView(lockRow);
        card.addView(divider());

        LinearLayout pattern = clickableRow();
        pattern.addView(labels("Графический ключ", "Резервный способ входа и восстановление доступа"), new LinearLayout.LayoutParams(0, -2, 1));
        patternStatus = valueText("");
        pattern.addView(patternStatus, new LinearLayout.LayoutParams(dp(112), dp(52)));
        pattern.setOnClickListener(v -> openPatternSetup());
        card.addView(pattern);
        card.addView(divider());

        LinearLayout bioRow = row();
        LinearLayout bioLabels = new LinearLayout(this);
        bioLabels.setOrientation(LinearLayout.VERTICAL);
        bioLabels.setGravity(Gravity.CENTER_VERTICAL);
        TextView bioTitle = text("Отпечаток пальца", 13, true);
        biometricHint = text("", 10, false);
        biometricHint.setTextColor(MUTED);
        bioLabels.addView(bioTitle);
        bioLabels.addView(biometricHint);
        bioRow.addView(bioLabels, new LinearLayout.LayoutParams(0, -2, 1));
        biometricSwitch = new Switch(this);
        biometricSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (refreshing) return;
            if (checked) {
                if (!lockStore.hasPattern()) {
                    Toast.makeText(this, "Сначала задайте графический ключ", Toast.LENGTH_LONG).show();
                    openPatternSetup();
                    refreshState();
                    return;
                }
                if (!BiometricAuthHelper.isAvailable(this)) {
                    Toast.makeText(this, "На устройстве нет зарегистрированного отпечатка пальца", Toast.LENGTH_LONG).show();
                    refreshState();
                    return;
                }
                lockStore.setEnabled(true);
                lockStore.setBiometricEnabled(true);
            } else {
                lockStore.setBiometricEnabled(false);
            }
            refreshState();
        });
        bioRow.addView(biometricSwitch, new LinearLayout.LayoutParams(-2, dp(52)));
        card.addView(bioRow);
        card.addView(divider());

        LinearLayout timeout = clickableRow();
        timeout.addView(labels("Автоблокировка", "Когда снова запрашивать защиту после сворачивания"), new LinearLayout.LayoutParams(0, -2, 1));
        timeoutValue = valueText("");
        timeout.addView(timeoutValue, new LinearLayout.LayoutParams(dp(120), dp(52)));
        timeout.setOnClickListener(v -> chooseTimeout());
        card.addView(timeout);
        card.addView(divider());

        TextView reset = text("Сбросить защиту приложения", 12, true);
        reset.setTextColor(0xFFD92D20);
        reset.setGravity(Gravity.CENTER_VERTICAL);
        reset.setPadding(dp(12), 0, dp(12), 0);
        reset.setOnClickListener(v -> confirmReset());
        card.addView(reset, new LinearLayout.LayoutParams(-1, dp(48)));
        return card;
    }

    private void refreshState() {
        refreshing = true;
        watcherSwitch.setChecked(LibraryWatcherService.isEnabled(this));
        watcherInterval.setText(LibraryWatcherService.intervalLabel(this) + "  ›");
        lockSwitch.setChecked(lockStore.isEnabled());
        patternStatus.setText(lockStore.hasPattern() ? "Настроен  ›" : "Создать  ›");
        boolean bioAvailable = BiometricAuthHelper.isAvailable(this);
        biometricSwitch.setEnabled(lockStore.hasPattern() && bioAvailable);
        biometricSwitch.setChecked(lockStore.isBiometricEnabled() && bioAvailable);
        biometricHint.setText(bioAvailable
                ? (lockStore.hasPattern() ? "Быстрая разблокировка с ключом как резервом" : "Сначала создайте графический ключ")
                : "Сканер или зарегистрированный отпечаток не найден");
        timeoutValue.setText(lockStore.timeoutLabel() + "  ›");
        refreshing = false;
    }

    private void openPatternSetup() {
        Intent i = new Intent(this, AppLockActivity.class);
        i.putExtra(AppLockActivity.EXTRA_SETUP, true);
        startActivityForResult(i, REQ_PATTERN);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PATTERN) {
            if (resultCode == RESULT_OK) Toast.makeText(this, "Графический ключ сохранён", Toast.LENGTH_SHORT).show();
            refreshState();
        }
    }

    private void chooseWatcherInterval() {
        String[] values = {"15 секунд", "30 секунд", "60 секунд"};
        long current = LibraryWatcherService.intervalMs(this);
        int checked = current == 60_000L ? 2 : current == 30_000L ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Интервал наблюдения")
                .setSingleChoiceItems(values, checked, (dialog, which) -> {
                    LibraryWatcherService.setIntervalMs(this, which == 2 ? 60_000L : which == 1 ? 30_000L : 15_000L);
                    dialog.dismiss();
                    refreshState();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void chooseTimeout() {
        String[] values = {"Сразу", "Через 30 секунд", "Через 1 минуту", "Через 5 минут"};
        long current = lockStore.timeoutMs();
        int checked = current == AppLockStore.TIMEOUT_5_MINUTES ? 3 :
                current == AppLockStore.TIMEOUT_1_MINUTE ? 2 :
                current == AppLockStore.TIMEOUT_30_SECONDS ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Автоблокировка")
                .setSingleChoiceItems(values, checked, (dialog, which) -> {
                    long selected = which == 3 ? AppLockStore.TIMEOUT_5_MINUTES :
                            which == 2 ? AppLockStore.TIMEOUT_1_MINUTE :
                            which == 1 ? AppLockStore.TIMEOUT_30_SECONDS : AppLockStore.TIMEOUT_IMMEDIATE;
                    lockStore.setTimeoutMs(selected);
                    dialog.dismiss();
                    refreshState();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void confirmReset() {
        if (!lockStore.hasPattern() && !lockStore.isEnabled()) return;
        new AlertDialog.Builder(this)
                .setTitle("Сбросить защиту?")
                .setMessage("Графический ключ и использование отпечатка будут отключены.")
                .setPositiveButton("Сбросить", (dialog, which) -> {
                    lockStore.clearProtection();
                    refreshState();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(Color.WHITE, 16, 0xFFE4E7EC, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(p);
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(4), dp(8), dp(4));
        row.setMinimumHeight(dp(60));
        return row;
    }

    private LinearLayout clickableRow() {
        LinearLayout row = row();
        row.setClickable(true);
        return row;
    }

    private LinearLayout labels(String title, String subtitle) {
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView a = text(title, 13, true);
        TextView b = text(subtitle, 10, false);
        b.setTextColor(MUTED);
        b.setPadding(0, dp(2), 0, 0);
        labels.addView(a);
        labels.addView(b);
        return labels;
    }

    private TextView valueText(String value) {
        TextView t = text(value, 11, true);
        t.setTextColor(GREEN);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        return t;
    }

    private TextView sectionLabel(String value) {
        TextView t = text(value, 10, true);
        t.setTextColor(MUTED);
        t.setPadding(dp(5), dp(8), dp(5), dp(7));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFE9ECEF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.setMargins(dp(12), 0, dp(12), 0);
        v.setLayoutParams(p);
        return v;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
