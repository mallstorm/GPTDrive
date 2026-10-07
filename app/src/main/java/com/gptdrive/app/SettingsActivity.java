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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** General settings: background watcher plus entry point to application security. */
public final class SettingsActivity extends Activity {
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private static final int REQ_NOTIFICATIONS = 7401;

    private Switch watcherSwitch;
    private TextView intervalValue;
    private TextView securityState;
    private boolean refreshing;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), 0, dp(12), 0);
        TextView back = text("‹", 32, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(54)));
        top.addView(text("Настройки", 20, true), new LinearLayout.LayoutParams(0, dp(54), 1));
        TextView version = text("v" + AppIdentity.VERSION, 10, true);
        version.setTextColor(MUTED);
        version.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        top.addView(version, new LinearLayout.LayoutParams(dp(70), dp(54)));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(5), dp(12), dp(24));

        body.addView(section("АККАУНТ"));
        body.addView(linkCard("Аккаунт", "Подключение, профиль и смена аккаунта", () -> startActivity(new Intent(this, AccountManagerActivity.class))));
        body.addView(section("СИНХРОНИЗАЦИЯ И ХРАНИЛИЩЕ"));
        body.addView(watcherCard());
        body.addView(linkCard("Синхронизация библиотеки", "Обновить облачный индекс GPT Drive", () -> openSync("Library")));
        body.addView(linkCard("Файлы устройства", "Открыть документ из памяти телефона", this::openSystemFiles));
        body.addView(linkCard("Корзина", "Удалённые файлы и восстановление", this::openDeleted));
        body.addView(section("БЕЗОПАСНОСТЬ"));
        body.addView(securityCard());
        body.addView(section("СЕТЬ"));
        body.addView(linkCard("Сеть и DNS", "Подключение, маршрутизация и DNS", () -> startActivity(new Intent(this, DnsSettingsActivity.class))));

        TextView note = text("Наблюдение работает отдельно от полной синхронизации: загружаются только свежие метаданные, а содержимое файлов остаётся в облаке до явного открытия.", 10, false);
        note.setTextColor(MUTED);
        note.setPadding(dp(5), dp(10), dp(5), 0);
        body.addView(note);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private LinearLayout watcherCard() {
        LinearLayout card = card();
        LinearLayout first = row();
        first.addView(labels("Фоновое наблюдение", "Новые изображения и файлы без полной синхронизации"), new LinearLayout.LayoutParams(0, -2, 1));
        watcherSwitch = new Switch(this);
        watcherSwitch.setOnCheckedChangeListener((v, on) -> {
            if (refreshing) return;
            LibraryWatcherService.setEnabled(this, on);
            if (on) requestNotificationPermission();
            refresh();
        });
        first.addView(watcherSwitch, new LinearLayout.LayoutParams(-2, dp(52)));
        card.addView(first);
        card.addView(divider());

        LinearLayout interval = row();
        interval.setClickable(true);
        interval.addView(labels("Интервал проверки", "Лёгкий запрос к свежим Library/image_gen"), new LinearLayout.LayoutParams(0, -2, 1));
        intervalValue = text("", 11, true);
        intervalValue.setTextColor(GREEN);
        intervalValue.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        interval.addView(intervalValue, new LinearLayout.LayoutParams(dp(115), dp(52)));
        interval.setOnClickListener(v -> chooseInterval());
        card.addView(interval);
        return card;
    }

    private LinearLayout linkCard(String title, String subtitle, Runnable action) {
        LinearLayout card = card();
        LinearLayout row = row();
        row.setClickable(true);
        row.addView(labels(title, subtitle), new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 24, false);
        arrow.setTextColor(0xFF98A2B3);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(52)));
        row.setOnClickListener(v -> action.run());
        card.addView(row);
        return card;
    }

    private void openSync(String section) {
        DriveDb db = new DriveDb(this);
        AccountProfile a = db.activeAccount();
        if (a != null && !a.hasSession()) {
            Intent login = new Intent(this, ChatGptAuthActivity.class);
            login.putExtra(ChatGptAuthActivity.EXTRA_ACCOUNT_ID, a.id);
            login.putExtra(ChatGptAuthActivity.EXTRA_NEW_ACCOUNT, false);
            startActivity(login);
            return;
        }
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, section);
        if (a != null) i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, a.id);
        startActivity(i);
    }

    private void openDeleted() {
        AccountProfile a = new DriveDb(this).activeAccount();
        Intent i = new Intent(this, DeletedLibraryActivity.class);
        if (a != null) i.putExtra(DeletedLibraryActivity.EXTRA_ACCOUNT_ID, a.id);
        startActivity(i);
    }

    private void openSystemFiles() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 100);
        } catch (Exception ignored) {}
    }

    private LinearLayout securityCard() {
        LinearLayout card = card();
        LinearLayout row = row();
        row.setClickable(true);
        row.addView(labels("Блокировка приложения", "Отпечаток пальца, графический ключ и автоблокировка"), new LinearLayout.LayoutParams(0, -2, 1));
        securityState = text("", 11, true);
        securityState.setTextColor(GREEN);
        securityState.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.addView(securityState, new LinearLayout.LayoutParams(dp(110), dp(52)));
        row.setOnClickListener(v -> startActivity(new Intent(this, SecurityActivity.class)));
        card.addView(row);
        return card;
    }

    private void refresh() {
        refreshing = true;
        watcherSwitch.setChecked(LibraryWatcherService.isEnabled(this));
        intervalValue.setText(LibraryWatcherService.intervalLabel(this) + "  ›");
        securityState.setText(AppLockManager.enabled(this) ? "Включена  ›" : "Выключена  ›");
        refreshing = false;
    }

    private void chooseInterval() {
        String[] names = {"15 минут", "30 минут", "60 минут"};
        long current = LibraryWatcherService.intervalMs(this);
        int checked = current == 60 * 60_000L ? 2 : current == 30 * 60_000L ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("Интервал наблюдения")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    LibraryWatcherService.setIntervalMs(this, which == 2 ? 60 * 60_000L : which == 1 ? 30 * 60_000L : 15 * 60_000L);
                    dialog.dismiss();
                    refresh();
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(round(Color.WHITE, 16, 0xFFE4E7EC, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        v.setLayoutParams(p);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(12), dp(4), dp(8), dp(4));
        v.setMinimumHeight(dp(60));
        return v;
    }

    private LinearLayout labels(String a, String b) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(a, 13, true);
        TextView sub = text(b, 10, false);
        sub.setTextColor(MUTED);
        sub.setPadding(0, dp(2), 0, 0);
        v.addView(title);
        v.addView(sub);
        return v;
    }

    private TextView section(String s) {
        TextView v = text(s, 10, true);
        v.setTextColor(MUTED);
        v.setPadding(dp(5), dp(8), dp(5), dp(7));
        return v;
    }

    private android.view.View divider() {
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(0xFFE9ECEF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.setMargins(dp(12), 0, dp(12), 0);
        v.setLayoutParams(p);
        return v;
    }

    private TextView text(String s, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(TEXT);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
