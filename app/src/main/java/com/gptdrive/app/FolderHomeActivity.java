package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Folder-first home. One navigation model: semantic cloud folders, no duplicated chips or bottom tabs. */
public final class FolderHomeActivity extends Activity {
    private static final int GREEN = 0xFF08A66D;
    private static final int GREEN_SOFT = 0xFFEAF9F3;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private static final int ICON_GRAY = 0xFF475467;

    private DriveDb db;
    private DriveCollections collections;
    private long activeId;
    private AccountBadgeView accountBadge;
    private TextView accountLabel;
    private TextView storageValue;
    private TextView planLine;
    private TextView syncLine;
    private TextView sectionCount;
    private TextView footerStatus;
    private StorageRingView storageRing;
    private LinearLayout folders;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private ContentObserver indexObserver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        collections = new DriveCollections(db);
        SmartDnsManager.initialize(this);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onStart() {
        super.onStart();
        if (indexObserver == null) {
            indexObserver = new ContentObserver(refreshHandler) {
                @Override public void onChange(boolean selfChange) {
                    refreshHandler.removeCallbacks(FolderHomeActivity.this::refresh);
                    refreshHandler.postDelayed(FolderHomeActivity.this::refresh, 250);
                }
            };
            getContentResolver().registerContentObserver(IndexChangeNotifier.uri(db.activeAccountId()), false, indexObserver);
        }
    }

    @Override protected void onStop() {
        if (indexObserver != null) {
            getContentResolver().unregisterContentObserver(indexObserver);
            indexObserver = null;
        }
        super.onStop();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new SoftCloudDrawable());
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(3), dp(12), dp(16));
        body.addView(buildStorageCard());
        body.addView(buildSectionHeader());

        folders = new LinearLayout(this);
        folders.setOrientation(LinearLayout.VERTICAL);
        body.addView(folders, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text("Корень GPT Drive содержит только смысловые папки. Один и тот же файл не должен превращать корень в бесконечный список.", 10, false);
        note.setTextColor(MUTED);
        note.setLineSpacing(0, 1.15f);
        note.setPadding(dp(4), dp(8), dp(4), dp(8));
        body.addView(note);

        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildStatusFooter(), new LinearLayout.LayoutParams(-1, dp(48)));
        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), 0);

        TextView settings = text("⚙", 25, false);
        settings.setGravity(Gravity.CENTER);
        settings.setContentDescription("Настройки");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(settings, new LinearLayout.LayoutParams(dp(40), dp(46)));

        ImageView brand = rawIcon(R.drawable.ic_gpt_drive_logo);
        bar.addView(brand, new LinearLayout.LayoutParams(dp(46), dp(44)));

        TextView title = text("GPT Drive", 20, true);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));

        LinearLayout account = new LinearLayout(this);
        account.setGravity(Gravity.CENTER_VERTICAL);
        account.setPadding(dp(5), 0, dp(6), 0);
        account.setBackground(round(0xFFF3F5F6, 16, 0xFFE0E5E8, 1));
        accountBadge = new AccountBadgeView(this);
        account.addView(accountBadge, new LinearLayout.LayoutParams(dp(30), dp(30)));
        accountLabel = text("Аккаунт", 10, true);
        accountLabel.setSingleLine(true);
        accountLabel.setGravity(Gravity.CENTER_VERTICAL);
        accountLabel.setEllipsize(TextUtils.TruncateAt.END);
        accountLabel.setPadding(dp(6), 0, dp(2), 0);
        account.addView(accountLabel, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView chevron = text("⌄", 14, true);
        chevron.setTextColor(ICON_GRAY);
        chevron.setGravity(Gravity.CENTER);
        account.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(34)));
        account.setOnClickListener(v -> startActivity(new Intent(this, AccountManagerActivity.class)));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(132), dp(38));
        ap.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(account, ap);

        ImageView search = tintedIcon(R.drawable.ic_search_24, TEXT, 23, 40);
        search.setOnClickListener(v -> openCollection(DriveCollections.FILES));
        bar.addView(search, new LinearLayout.LayoutParams(dp(40), dp(46)));
        return bar;
    }

    private View buildStorageCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(13), dp(8), dp(10), dp(8));
        card.setBackground(gradientRound(new int[]{0xFAFFFFFF, 0xF2F1FFF9}, 18, 0xDDE3EAEE, 1));
        card.setElevation(dp(2));
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

        syncLine = text("Не синхронизировано", 10, false);
        syncLine.setTextColor(MUTED);
        syncLine.setPadding(0, dp(3), 0, 0);
        info.addView(syncLine);

        card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));
        TextView arrow = text("›", 28, false);
        arrow.setTextColor(0xFF344054);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(26), -1));

        card.setOnClickListener(v -> {
            AccountProfile a = db.activeAccount();
            if (a != null && !a.hasSession()) login(a); else openSync("Library");
        });
        return card;
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
        head.addView(labels, new LinearLayout.LayoutParams(0, dp(50), 1));

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

    private View buildStatusFooter() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(5), dp(14), dp(5));
        bar.setBackground(gradientRound(new int[]{0xFAFFFFFF, 0xF2ECFAF5}, 16, 0xDDE3E7EA, 1));
        bar.setElevation(dp(2));
        ImageView cloud = rawIcon(R.drawable.ic_gpt_drive_logo);
        bar.addView(cloud, new LinearLayout.LayoutParams(dp(34), dp(28)));
        footerStatus = text("В облаке • состояние не определено", 10, true);
        footerStatus.setTextColor(ICON_GRAY);
        footerStatus.setPadding(dp(7), 0, 0, 0);
        footerStatus.setSingleLine(true);
        footerStatus.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(footerStatus, new LinearLayout.LayoutParams(0, dp(32), 1));
        TextView accounts = text("Аккаунты", 10, true);
        accounts.setTextColor(GREEN);
        accounts.setGravity(Gravity.CENTER);
        accounts.setOnClickListener(v -> startActivity(new Intent(this, AccountManagerActivity.class)));
        bar.addView(accounts, new LinearLayout.LayoutParams(dp(70), dp(34)));
        bar.setOnClickListener(v -> openSync("Library"));
        return bar;
    }

    private void refresh() {
        AccountProfile a = db.activeAccount();
        if (a == null) return;
        activeId = a.id;
        accountBadge.bind(a);
        accountLabel.setText(shortAccount(a.displayName()));
        updateStorage(a);

        folders.removeAllViews();
        addFolder(R.drawable.folder_library, "Файлы", collections.count(activeId, DriveCollections.FILES) + " файлов", () -> openCollection(DriveCollections.FILES));
        addFolder(R.drawable.folder_images, "Картинки", collections.count(activeId, DriveCollections.IMAGES) + " изображений", () -> openCollection(DriveCollections.IMAGES));
        addFolder(R.drawable.folder_projects, "Проекты", collections.countProjects(activeId) + " проектов", this::openProjects);
        addFolder(R.drawable.folder_generated, "Сгенерированные", collections.count(activeId, DriveCollections.GENERATED) + " объектов нейросети", () -> openCollection(DriveCollections.GENERATED));
        long archivedChats = collections.countArchivedChats(activeId);
        long archivedFiles = collections.count(activeId, DriveCollections.ARCHIVED);
        addFolder(R.drawable.folder_archived, "Архив", archivedChats + " веток • " + archivedFiles + " файлов", () -> openCollection(DriveCollections.ARCHIVED));
        addFolder(R.drawable.folder_deleted, "Удалённые", "Корзина ChatGPT • хранение до 30 дней", this::openDeleted);
        sectionCount.setText("6 папок");
    }

    private void updateStorage(AccountProfile a) {
        long indexed = db.knownStorageBytes(a.id);
        QuotaPolicy.Value quota = QuotaPolicy.resolve(a, indexed);
        storageRing.setStorage(quota.used, quota.quota);
        if (quota.quota > 0) {
            storageValue.setText(MainActivity.formatBytes(quota.used) + " / " + MainActivity.formatBytes(quota.quota));
            planLine.setText("План: " + a.planLabel() + " • свободно " + MainActivity.formatBytes(quota.remaining()) + (quota.fallback ? " • лимит плана" : ""));
        } else {
            storageValue.setText(quota.used > 0 ? MainActivity.formatBytes(quota.used) + " в облаке" : "Объём не определён");
            planLine.setText("План: " + a.planLabel() + " • лимит не получен");
        }
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
        String cloud = quota.quota > 0
                ? "В облаке " + MainActivity.formatBytes(quota.used) + " из " + MainActivity.formatBytes(quota.quota) + " • " + quota.percent() + "%"
                : "В облаке " + MainActivity.formatBytes(quota.used) + " • лимит не получен";
        footerStatus.setText(a.planLabel() + " • " + cloud + (a.lastSync > 0 ? " • " + friendlySync(a.lastSync) : ""));
    }

    private void addFolder(int iconRes, String name, String meta, Runnable open) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(6), dp(4));
        row.setBackground(round(Color.WHITE, 13, 0xFFE8EBED, 1));
        row.addView(rawIcon(iconRes), new LinearLayout.LayoutParams(dp(54), dp(50)));

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
        row.addView(info, new LinearLayout.LayoutParams(0, dp(52), 1));

        TextView arrow = text("›", 26, false);
        arrow.setTextColor(ICON_GRAY);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(48)));
        row.setOnClickListener(v -> open.run());

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(66));
        p.setMargins(0, 0, 0, dp(6));
        folders.addView(row, p);
    }

    private void openCollection(String collection) {
        Intent i = new Intent(this, NativeBrowserActivity.class);
        i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY, collection);
        i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID, activeId);
        startActivity(i);
    }

    private void openProjects() {
        Intent i = new Intent(this, ProjectBrowserActivity.class);
        i.putExtra(ProjectBrowserActivity.EXTRA_ACCOUNT_ID, activeId);
        startActivity(i);
    }

    private void openDeleted() {
        Intent i = new Intent(this, DeletedLibraryActivity.class);
        i.putExtra(DeletedLibraryActivity.EXTRA_ACCOUNT_ID, activeId);
        startActivity(i);
    }

    private void openSync(String section) {
        AccountProfile a = db.activeAccount();
        if (a != null && !a.hasSession()) {
            login(a);
            return;
        }
        SyncForegroundService.start(this, activeId);
        android.widget.Toast.makeText(this,
                "Индексация запущена. Папки обновляются по мере загрузки.",
                android.widget.Toast.LENGTH_LONG).show();
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

    private GradientDrawable gradientRound(int[] colors, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
