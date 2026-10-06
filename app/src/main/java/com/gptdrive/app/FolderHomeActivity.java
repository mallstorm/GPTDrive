package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Classic GPT Drive home restored for v0.10.6.
 * The legacy controls/settings remain, but the cloud root is folder-only.
 */
public final class FolderHomeActivity extends Activity {
    private static final int GREEN = 0xFF08A66D;
    private static final int GREEN_SOFT = 0xFFEAF9F3;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private static final int ICON_GRAY = 0xFF475467;

    private DriveDb db;
    private long activeId;
    private TextView accountLabel;
    private TextView storageValue;
    private TextView planLine;
    private TextView syncLine;
    private TextView sectionCount;
    private StorageRingView storageRing;
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
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(3), dp(12), dp(18));
        body.addView(buildStorageCard());
        body.addView(buildChips());
        body.addView(buildSectionHeader());

        folders = new LinearLayout(this);
        folders.setOrientation(LinearLayout.VERTICAL);
        body.addView(folders, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text("В корне находятся только папки. Файлы и изображения открываются внутри соответствующих разделов.", 10, false);
        note.setTextColor(MUTED);
        note.setLineSpacing(0, 1.15f);
        note.setPadding(dp(4), dp(8), dp(4), dp(12));
        body.addView(note);

        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, dp(68)));
        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), 0);

        ImageView menu = tintedIcon(R.drawable.ic_menu_24, TEXT, 23, 40);
        menu.setOnClickListener(this::showMainMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(46)));

        ImageView brand = rawIcon(R.drawable.ic_gpt_drive_logo);
        bar.addView(brand, new LinearLayout.LayoutParams(dp(46), dp(44)));

        TextView title = text("GPT Drive", 20, true);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));

        LinearLayout account = new LinearLayout(this);
        account.setGravity(Gravity.CENTER_VERTICAL);
        account.setPadding(dp(7), 0, dp(7), 0);
        account.setBackground(round(0xFFF3F5F6, 15, 0xFFE0E5E8, 1));
        ImageView avatar = tintedIcon(R.drawable.ic_nav_accounts, GREEN, 17, 30);
        avatar.setBackground(round(0xFFE1F8EF, 15, 0, 0));
        account.addView(avatar, new LinearLayout.LayoutParams(dp(30), dp(30)));
        accountLabel = text("Аккаунт", 11, true);
        accountLabel.setSingleLine(true);
        accountLabel.setGravity(Gravity.CENTER_VERTICAL);
        accountLabel.setEllipsize(TextUtils.TruncateAt.END);
        accountLabel.setPadding(dp(6), 0, dp(4), 0);
        account.addView(accountLabel, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView chevron = text("⌄", 15, true);
        chevron.setTextColor(ICON_GRAY);
        chevron.setGravity(Gravity.CENTER);
        account.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(34)));
        account.setOnClickListener(v -> startActivity(new Intent(this, AccountManagerActivity.class)));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(126), dp(38));
        ap.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(account, ap);

        ImageView search = tintedIcon(R.drawable.ic_search_24, TEXT, 23, 40);
        search.setOnClickListener(v -> openCategory("Library"));
        bar.addView(search, new LinearLayout.LayoutParams(dp(40), dp(46)));
        return bar;
    }

    private View buildStorageCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(13), dp(8), dp(10), dp(8));
        card.setBackground(round(Color.WHITE, 18, 0xFFE8EBED, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(124));
        cp.setMargins(0, dp(1), 0, dp(8));
        card.setLayoutParams(cp);

        storageRing = new StorageRingView(this);
        card.addView(storageRing, new LinearLayout.LayoutParams(dp(92), dp(92)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        info.addView(text("Облачное хранилище", 13, true));

        storageValue = text("Объём не определён", 18, true);
        storageValue.setTextColor(GREEN);
        storageValue.setSingleLine(true);
        storageValue.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(storageValue);

        planLine = text("План: Не определён", 10, true);
        planLine.setTextColor(0xFF344054);
        planLine.setPadding(0, dp(2), 0, 0);
        info.addView(planLine);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cloud = rawIcon(R.drawable.ic_gpt_drive_logo);
        syncRow.addView(cloud, new LinearLayout.LayoutParams(dp(22), dp(18)));
        syncLine = text("Не синхронизировано", 10, false);
        syncLine.setTextColor(MUTED);
        syncLine.setPadding(dp(4), 0, 0, 0);
        syncRow.addView(syncLine, new LinearLayout.LayoutParams(0, dp(20), 1));
        info.addView(syncRow);

        card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
        TextView arrow = text("›", 28, false);
        arrow.setTextColor(0xFF344054);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(26), -1));

        card.setOnClickListener(v -> {
            AccountProfile a = db.activeAccount();
            if (a != null && !a.hasSession()) login(a);
            else openSync("Library");
        });
        return card;
    }

    private View buildChips() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(chip(R.drawable.ic_chip_all, "Все", true, v -> {}));
        row.addView(chip(R.drawable.ic_chip_library, "Библиотека", false, v -> openCategory("Library")));
        row.addView(chip(R.drawable.ic_chip_images, "Картинки", false, v -> openCategory("Images")));
        row.addView(chip(R.drawable.ic_chip_projects, "Проекты", false, v -> openCategory("Projects")));
        row.addView(chip(R.drawable.ic_chip_archive, "Архив", false, v -> openCategory("Archived")));
        hsv.addView(row, new HorizontalScrollView.LayoutParams(-2, dp(38)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(43));
        hp.setMargins(0, 0, 0, dp(2));
        hsv.setLayoutParams(hp);
        return hsv;
    }

    private View chip(int iconRes, String label, boolean active, View.OnClickListener click) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), 0, dp(9), 0);
        chip.setBackground(round(active ? GREEN_SOFT : Color.WHITE, 10, active ? 0xFF7AD9B5 : 0xFFE1E5E8, 1));
        ImageView icon = tintedIcon(iconRes, active ? GREEN : 0xFF344054, 16, 24);
        chip.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(28)));
        TextView t = text(label, 10, active);
        t.setTextColor(active ? GREEN : TEXT);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(3), 0, 0, 0);
        chip.addView(t, new LinearLayout.LayoutParams(-2, dp(28)));
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(32));
        p.setMargins(0, 0, dp(5), 0);
        chip.setLayoutParams(p);
        return chip;
    }

    private View buildSectionHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView sectionTitle = text("Облако / Папки", 18, true);
        sectionCount = text("0 папок", 10, false);
        sectionCount.setTextColor(MUTED);
        labels.addView(sectionTitle);
        labels.addView(sectionCount);
        head.addView(labels, new LinearLayout.LayoutParams(0, dp(48), 1));

        TextView sync = text("Синхр.", 10, true);
        sync.setTextColor(GREEN);
        sync.setGravity(Gravity.CENTER);
        sync.setBackground(round(GREEN_SOFT, 10, 0xFFCDEEDF, 1));
        sync.setOnClickListener(v -> openSync("Library"));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(70), dp(34));
        sp.setMargins(dp(4), 0, 0, 0);
        head.addView(sync, sp);
        return head;
    }

    private View buildBottomNav() {
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
        icon.setImageBitmap(RasterNavIcons.make(this, type, active ? GREEN : ICON_GRAY, 30));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        TextView t = text(label, 11, active);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(active ? GREEN : ICON_GRAY);
        box.addView(icon, new LinearLayout.LayoutParams(-1, dp(36)));
        box.addView(t, new LinearLayout.LayoutParams(-1, dp(20)));
        box.setOnClickListener(click);
        return box;
    }

    private void refresh() {
        AccountProfile a = db.activeAccount();
        if (a == null) return;
        activeId = a.id;
        accountLabel.setText(shortAccount(a.displayName()));
        updateStorage(a);

        folders.removeAllViews();
        addFolder(R.drawable.folder_library, "Библиотека", "Library", db.count(activeId, "Library"));
        addFolder(R.drawable.folder_images, "Картинки", "Images", db.count(activeId, "Images"));
        addFolder(R.drawable.folder_projects, "Проекты", "Projects", db.count(activeId, "Projects"));
        addVirtualFolder(R.drawable.folder_uploads, "Загрузки", "Загруженные файлы");
        addVirtualFolder(R.drawable.folder_generated, "Сгенерированные", "Созданные изображения и файлы");
        addFolder(R.drawable.folder_archived, "Архив", "Archived", db.count(activeId, "Archived"));
        sectionCount.setText("6 папок");
    }

    private void updateStorage(AccountProfile a) {
        long known = db.knownStorageBytes(a.id);
        if (a.hasRemoteQuota()) {
            storageRing.setStorage(a.remoteUsedBytes, a.remoteQuotaBytes);
            storageValue.setText(MainActivity.formatBytes(a.remoteUsedBytes) + " / " + MainActivity.formatBytes(a.remoteQuotaBytes));
        } else {
            storageRing.setStorage(0, 0);
            storageValue.setText(known > 0 ? MainActivity.formatBytes(known) + " удалённо" : "Объём не определён");
        }
        planLine.setText("План: " + a.planLabel());
        if (!a.hasSession()) {
            syncLine.setText("Нужен вход в ChatGPT");
            syncLine.setTextColor(0xFF8A5A00);
        } else if (a.lastSync > 0) {
            syncLine.setText("Синхронизировано • " + friendlySync(a.lastSync));
            syncLine.setTextColor(MUTED);
        } else {
            syncLine.setText("Аккаунт подключён • нужна синхронизация");
            syncLine.setTextColor(MUTED);
        }
    }

    private void addFolder(int iconRes, String name, String category, long objects) {
        LinearLayout row = baseFolderRow();
        row.addView(rawIcon(iconRes), new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.addView(folderInfo(name, folderMeta(category, objects)), new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView more = text("⋮", 20, false);
        more.setGravity(Gravity.CENTER);
        more.setTextColor(ICON_GRAY);
        more.setOnClickListener(v -> showFolderMenu(v, category));
        row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(48)));
        row.setOnClickListener(v -> openCategory(category));
        addFolderRow(row);
    }

    private void addVirtualFolder(int iconRes, String name, String subtitle) {
        LinearLayout row = baseFolderRow();
        row.addView(rawIcon(iconRes), new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.addView(folderInfo(name, subtitle), new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView more = text("⋮", 20, false);
        more.setGravity(Gravity.CENTER);
        more.setTextColor(ICON_GRAY);
        more.setOnClickListener(v -> openSync("Library"));
        row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(48)));
        row.setOnClickListener(v -> openSync("Library"));
        addFolderRow(row);
    }

    private LinearLayout baseFolderRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(6), dp(4));
        row.setBackground(round(Color.WHITE, 13, 0xFFE8EBED, 1));
        return row;
    }

    private void addFolderRow(View row) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(64));
        p.setMargins(0, 0, 0, dp(5));
        folders.addView(row, p);
    }

    private LinearLayout folderInfo(String name, String meta) {
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(8), 0, 0, 0);
        TextView n = text(name, 14, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        TextView m = text(meta, 10, false);
        m.setTextColor(MUTED);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(n);
        info.addView(m);
        return info;
    }

    private String folderMeta(String category, long count) {
        List<DriveItem> latest = db.list(activeId, category, 1);
        if (latest.isEmpty()) return count + " объектов";
        return count + " объектов • " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(latest.get(0).updated));
    }

    private void showMainMenu(View anchor) {
        showActionPopup(anchor,
                new String[]{"Синхронизировать библиотеку", "Routing & DNS", "Android Files", "Аккаунты"},
                new Runnable[]{
                        () -> openSync("Library"),
                        () -> startActivity(new Intent(this, DnsSettingsActivity.class)),
                        this::openSystemFiles,
                        () -> startActivity(new Intent(this, AccountManagerActivity.class))
                }, dp(232));
    }

    private void showFolderMenu(View anchor, String category) {
        showActionPopup(anchor,
                new String[]{"Открыть", "Синхронизировать"},
                new Runnable[]{() -> openCategory(category), () -> openSync(category)},
                dp(180));
    }

    private void showActionPopup(View anchor, String[] labels, Runnable[] actions, int width) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackground(round(Color.WHITE, 14, 0xFFE5E8EB, 1));
        for (String label : labels) {
            TextView action = text(label, 11, false);
            action.setGravity(Gravity.CENTER_VERTICAL);
            action.setPadding(dp(11), 0, dp(11), 0);
            panel.addView(action, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        PopupWindow popup = new PopupWindow(panel, width, -2, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            panel.getChildAt(i).setOnClickListener(v -> {
                popup.dismiss();
                actions[index].run();
            });
        }
        popup.showAsDropDown(anchor, anchor.getWidth() - width, -dp(2));
    }

    private void openCategory(String category) {
        Intent i = new Intent(this, NativeBrowserActivity.class);
        i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY, category);
        i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID, activeId);
        startActivity(i);
    }

    private void openSync(String section) {
        AccountProfile a = db.activeAccount();
        if (a != null && !a.hasSession()) {
            login(a);
            return;
        }
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, section);
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, activeId);
        startActivity(i);
    }

    private void login(AccountProfile a) {
        Intent i = new Intent(this, ChatGptAuthActivity.class);
        i.putExtra(ChatGptAuthActivity.EXTRA_ACCOUNT_ID, a.id);
        i.putExtra(ChatGptAuthActivity.EXTRA_NEW_ACCOUNT, false);
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

    private String friendlySync(long time) {
        Date d = new Date(time);
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String prefix = day.format(d).equals(day.format(new Date()))
                ? "Сегодня, "
                : new SimpleDateFormat("dd.MM.yyyy, ", Locale.getDefault()).format(d);
        return prefix + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d);
    }

    private String shortAccount(String value) {
        if (value == null || value.trim().isEmpty()) return "Аккаунт";
        String v = value.trim();
        int at = v.indexOf('@');
        if (at > 0) v = v.substring(0, at);
        return v.length() > 11 ? v.substring(0, 11) + "…" : v;
    }

    private ImageView rawIcon(int res) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(dp(1), dp(1), dp(1), dp(1));
        return v;
    }

    private ImageView tintedIcon(int res, int tint, int innerDp, int boxDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = Math.max(0, (boxDp - innerDp) / 2);
        v.setPadding(dp(p), dp(p), dp(p), dp(p));
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

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
