package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Folder-first cloud home. Root never renders individual cloud objects. */
public final class FolderHomeActivity extends Activity {
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private DriveDb db;
    private long activeId;
    private TextView accountChip;
    private TextView title;
    private TextView count;
    private TextView syncState;
    private LinearLayout folders;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        SmartDnsManager.initialize(this);
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
        root.addView(topBar(), new LinearLayout.LayoutParams(-1, dp(58)));

        FrameLayout stage = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(10), dp(14), dp(24));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        title = text("Облако / Папки", 20, true, TEXT);
        count = text("0 папок", 11, false, MUTED);
        heading.addView(title);
        heading.addView(count);
        body.addView(heading, new LinearLayout.LayoutParams(-1, dp(52)));

        syncState = text("", 11, false, MUTED);
        syncState.setGravity(Gravity.CENTER_VERTICAL);
        syncState.setPadding(dp(12), 0, dp(12), 0);
        syncState.setBackground(round(Color.WHITE, 13, 0xFFE6E9EC, 1));
        syncState.setOnClickListener(v -> openSync());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(48));
        sp.setMargins(0, 0, 0, dp(10));
        body.addView(syncState, sp);

        folders = new LinearLayout(this);
        folders.setOrientation(LinearLayout.VERTICAL);
        body.addView(folders, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text("В корне показываются только папки. Файлы и изображения находятся внутри соответствующих разделов.", 10, false, MUTED);
        note.setLineSpacing(0, 1.15f);
        note.setPadding(dp(4), dp(10), dp(4), dp(8));
        body.addView(note);

        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        stage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView fab = text("+", 30, false, Color.WHITE);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(round(GREEN, 30, 0, 0));
        fab.setElevation(dp(8));
        fab.setOnClickListener(v -> openSync());
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END | Gravity.BOTTOM);
        fp.setMargins(0, 0, dp(18), dp(18));
        stage.addView(fab, fp);

        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(bottomNav(), new LinearLayout.LayoutParams(-1, dp(68)));
        setContentView(root);
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), 0, dp(8), 0);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_gpt_drive_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(54), dp(46)));

        TextView brand = text("GPT Drive", 20, true, TEXT);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(brand, new LinearLayout.LayoutParams(0, dp(46), 1));

        accountChip = text("Аккаунт", 11, true, TEXT);
        accountChip.setGravity(Gravity.CENTER);
        accountChip.setSingleLine(true);
        accountChip.setBackground(round(0xFFF1F4F5, 16, 0xFFE0E5E8, 1));
        accountChip.setOnClickListener(v -> startActivity(new Intent(this, AccountManagerActivity.class)));
        bar.addView(accountChip, new LinearLayout.LayoutParams(dp(126), dp(38)));

        ImageView search = new ImageView(this);
        search.setImageResource(R.drawable.ic_search_24);
        search.setColorFilter(TEXT);
        search.setPadding(dp(9), dp(9), dp(9), dp(9));
        search.setOnClickListener(v -> openCategory("Library"));
        bar.addView(search, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return bar;
    }

    private View bottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(7), dp(4), dp(7), dp(3));
        nav.setBackground(round(Color.WHITE, 16, 0xFFE3E7EA, 1));
        nav.addView(navItem(RasterNavIcons.FILES, "Файлы", true, v -> {}), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(RasterNavIcons.IMAGES, "Картинки", false, v -> openCategory("Images")), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(RasterNavIcons.PROJECTS, "Проекты", false, v -> openCategory("Projects")), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(RasterNavIcons.ACCOUNTS, "Аккаунты", false, v -> startActivity(new Intent(this, AccountManagerActivity.class))), new LinearLayout.LayoutParams(0, -1, 1));
        return nav;
    }

    private View navItem(int type, String label, boolean active, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageBitmap(RasterNavIcons.make(this, type, active ? GREEN : 0xFF475467, 30));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        TextView t = text(label, 11, active, active ? GREEN : 0xFF475467);
        t.setGravity(Gravity.CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(-1, dp(36)));
        box.addView(t, new LinearLayout.LayoutParams(-1, dp(20)));
        box.setOnClickListener(click);
        return box;
    }

    private void refresh() {
        AccountProfile a = db.activeAccount();
        if (a == null) return;
        activeId = a.id;
        accountChip.setText(shortAccount(a.displayName()) + "  ⌄");
        folders.removeAllViews();
        addFolder(R.drawable.folder_library, "Библиотека", "Library", db.count(activeId, "Library"));
        addFolder(R.drawable.folder_images, "Картинки", "Images", db.count(activeId, "Images"));
        addFolder(R.drawable.folder_projects, "Проекты", "Projects", db.count(activeId, "Projects"));
        addFolder(R.drawable.folder_archived, "Архив", "Archived", db.count(activeId, "Archived"));
        count.setText("4 папки");
        if (!a.hasSession()) {
            syncState.setText("Нужен вход в ChatGPT — нажми для подключения");
            syncState.setTextColor(0xFF8A5A00);
        } else if (a.lastSync > 0) {
            syncState.setText("Синхронизировано: " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(a.lastSync)) + "  •  метаданные");
            syncState.setTextColor(MUTED);
        } else {
            syncState.setText("Аккаунт подключён — запусти синхронизацию метаданных");
            syncState.setTextColor(MUTED);
        }
    }

    private void addFolder(int iconRes, String name, String category, long objects) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(6), dp(8), dp(6));
        row.setBackground(round(Color.WHITE, 15, 0xFFE5E8EB, 1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(54)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(8), 0, 0, 0);
        info.addView(text(name, 14, true, TEXT));
        List<DriveItem> latest = db.list(activeId, category, 1);
        String meta = objects + " объектов";
        if (!latest.isEmpty()) meta += "  •  " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(latest.get(0).updated));
        info.addView(text(meta, 10, false, MUTED));
        row.addView(info, new LinearLayout.LayoutParams(0, dp(54), 1));

        TextView arrow = text("›", 27, false, 0xFF475467);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(52)));
        row.setOnClickListener(v -> openCategory(category));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(72));
        p.setMargins(0, 0, 0, dp(7));
        folders.addView(row, p);
    }

    private void openCategory(String category) {
        Intent i = new Intent(this, NativeBrowserActivity.class);
        i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY, category);
        i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID, activeId);
        startActivity(i);
    }

    private void openSync() {
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, "Library");
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, activeId);
        startActivity(i);
    }

    private String shortAccount(String value) {
        if (value == null || value.trim().isEmpty()) return "Аккаунт";
        String v = value.trim();
        int at = v.indexOf('@');
        if (at > 0) v = v.substring(0, at);
        return v.length() > 12 ? v.substring(0, 12) + "…" : v;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
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
