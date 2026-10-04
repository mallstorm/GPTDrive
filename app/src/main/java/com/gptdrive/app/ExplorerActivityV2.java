package com.gptdrive.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ExplorerActivityV2 extends Activity {
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF0AAF76;
    private static final int GREEN_SOFT = 0xFFEAF9F3;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private DriveDb db;
    private long activeId;
    private TextView accountLabel, storageValue, planLine, syncLine, sectionTitle, sectionCount, sortLabel;
    private StorageRingView storageRing;
    private LinearLayout content;
    private ImageView listIcon, gridIcon;
    private boolean gridMode;
    private int sortMode;
    private String searchQuery = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
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
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(64)));

        FrameLayout stage = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(4), dp(12), dp(22));
        body.addView(buildStorageCard());
        body.addView(buildChips());
        body.addView(buildSectionBlock());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        body.addView(content, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        stage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView fab = text("+", 30, false);
        fab.setTextColor(Color.WHITE);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(round(GREEN, 29, 0, 0));
        fab.setElevation(dp(9));
        fab.setOnClickListener(this::showFabMenu);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END | Gravity.BOTTOM);
        fp.setMargins(0, 0, dp(18), dp(14));
        stage.addView(fab, fp);

        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, dp(72)));
        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), 0);

        ImageView menu = tintedIcon(R.drawable.ic_menu_24, TEXT, 24, 42);
        menu.setOnClickListener(this::showMainMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(42), dp(48)));

        ImageView brand = rawIcon(R.drawable.ic_gpt_drive_logo, 46, 32);
        bar.addView(brand, new LinearLayout.LayoutParams(dp(52), dp(48)));

        TextView title = text("GPT Drive", 21, true);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));

        LinearLayout account = new LinearLayout(this);
        account.setGravity(Gravity.CENTER_VERTICAL);
        account.setPadding(dp(7), 0, dp(7), 0);
        account.setBackground(round(0xFFF1F4F5, 22, 0xFFE0E5E8, 1));

        FrameLayout avatarWrap = new FrameLayout(this);
        avatarWrap.setBackground(round(0xFFE2F8F0, 17, 0, 0));
        ImageView avatar = tintedIcon(R.drawable.ic_nav_accounts, GREEN, 19, 34);
        avatarWrap.addView(avatar, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        account.addView(avatarWrap, new LinearLayout.LayoutParams(dp(34), dp(34)));

        accountLabel = text("Аккаунт 1", 12, true);
        accountLabel.setSingleLine(true);
        accountLabel.setEllipsize(TextUtils.TruncateAt.END);
        accountLabel.setGravity(Gravity.CENTER_VERTICAL);
        accountLabel.setPadding(dp(7), 0, dp(4), 0);
        account.addView(accountLabel, new LinearLayout.LayoutParams(0, dp(40), 1));

        TextView chevron = text("⌄", 18, true);
        chevron.setGravity(Gravity.CENTER);
        chevron.setTextColor(0xFF344054);
        account.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(40)));
        account.setOnClickListener(this::showAccountSwitcher);

        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(132), dp(44));
        ap.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(account, ap);

        ImageView search = tintedIcon(R.drawable.ic_search_24, TEXT, 24, 42);
        search.setOnClickListener(v -> showSearch());
        bar.addView(search, new LinearLayout.LayoutParams(dp(42), dp(48)));
        return bar;
    }

    private View buildStorageCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(9), dp(11), dp(9));
        card.setBackground(round(Color.WHITE, 20, 0xFFE8EBED, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(132));
        cp.setMargins(0, dp(2), 0, dp(10));
        card.setLayoutParams(cp);

        storageRing = new StorageRingView(this);
        card.addView(storageRing, new LinearLayout.LayoutParams(dp(100), dp(100)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(13), 0, 0, 0);

        TextView title = text("Использовано места", 14, true);
        storageValue = text("Объём не определён", 20, true);
        storageValue.setTextColor(GREEN);
        storageValue.setSingleLine(true);
        storageValue.setEllipsize(TextUtils.TruncateAt.END);

        planLine = text("План: Не определён", 11, true);
        planLine.setTextColor(0xFF344054);
        planLine.setPadding(0, dp(2), 0, 0);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cloud = rawIcon(R.drawable.ic_gpt_drive_logo, 22, 15);
        syncRow.addView(cloud, new LinearLayout.LayoutParams(dp(24), dp(20)));
        syncLine = text("Не синхронизировано", 11, false);
        syncLine.setTextColor(MUTED);
        syncLine.setPadding(dp(4), 0, 0, 0);
        syncRow.addView(syncLine, new LinearLayout.LayoutParams(0, dp(24), 1));

        info.addView(title);
        info.addView(storageValue);
        info.addView(planLine);
        info.addView(syncRow);
        card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));

        TextView arrow = text("›", 30, false);
        arrow.setTextColor(0xFF344054);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(28), -1));

        card.setOnClickListener(v -> {
            AccountProfile a = db.activeAccount();
            if (a != null && !a.hasSession()) login(a, false); else openSync("Library");
        });
        return card;
    }

    private View buildChips() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setFillViewport(true);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(chip(R.drawable.ic_chip_all, "Все", true, v -> { searchQuery = ""; refreshContent(); }));
        row.addView(chip(R.drawable.ic_chip_library, "Библиотека", false, v -> openCategory("Library")));
        row.addView(chip(R.drawable.ic_chip_images, "Картинки", false, v -> openCategory("Images")));
        row.addView(chip(R.drawable.ic_chip_projects, "Проекты", false, v -> openCategory("Projects")));
        row.addView(chip(R.drawable.ic_chip_archive, "Архив", false, v -> openCategory("Archived")));
        hsv.addView(row, new HorizontalScrollView.LayoutParams(-2, dp(42)));

        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(48));
        hp.setMargins(0, 0, 0, dp(4));
        hsv.setLayoutParams(hp);
        return hsv;
    }

    private View chip(int iconRes, String label, boolean active, View.OnClickListener click) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), 0, dp(9), 0);
        chip.setBackground(round(active ? GREEN_SOFT : Color.WHITE, 18, active ? 0xFF7AD9B5 : 0xFFE1E5E8, 1));

        ImageView icon = tintedIcon(iconRes, active ? GREEN : 0xFF344054, 18, 28);
        chip.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(30)));
        TextView t = text(label, 10, active);
        t.setTextColor(active ? GREEN : TEXT);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(2), 0, 0, 0);
        chip.addView(t, new LinearLayout.LayoutParams(-2, dp(32)));
        chip.setOnClickListener(click);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(38));
        p.setMargins(dp(2), 0, dp(2), 0);
        chip.setLayoutParams(p);
        return chip;
    }

    private View buildSectionBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        sectionTitle = text("Облако / Все файлы", 19, true);
        sectionCount = text("0 объектов", 11, false);
        sectionCount.setTextColor(MUTED);
        labels.addView(sectionTitle);
        labels.addView(sectionCount);
        head.addView(labels, new LinearLayout.LayoutParams(0, dp(52), 1));

        LinearLayout sort = new LinearLayout(this);
        sort.setGravity(Gravity.CENTER);
        ImageView sortIcon = tintedIcon(R.drawable.ic_sort_24, MUTED, 18, 28);
        sort.addView(sortIcon, new LinearLayout.LayoutParams(dp(28), dp(36)));
        sortLabel = text("По дате⌄", 11, false);
        sortLabel.setTextColor(MUTED);
        sort.addView(sortLabel, new LinearLayout.LayoutParams(-2, dp(36)));
        sort.setOnClickListener(this::showSortMenu);
        head.addView(sort, new LinearLayout.LayoutParams(dp(100), dp(40)));

        listIcon = viewToggle(R.drawable.ic_list_24, true);
        listIcon.setOnClickListener(v -> setGridMode(false));
        head.addView(listIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        gridIcon = viewToggle(R.drawable.ic_grid_24, false);
        gridIcon.setOnClickListener(v -> setGridMode(true));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(42), dp(42));
        gp.setMargins(dp(4), 0, 0, 0);
        head.addView(gridIcon, gp);
        block.addView(head);
        return block;
    }

    private ImageView viewToggle(int res, boolean active) {
        ImageView v = tintedIcon(res, active ? GREEN : 0xFF475467, 22, 42);
        v.setBackground(round(active ? GREEN_SOFT : Color.WHITE, 11, 0xFFE1E5E8, 1));
        return v;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(4), dp(8), dp(3));
        nav.setBackground(round(Color.WHITE, 18, 0xFFE3E7EA, 1));
        nav.addView(navItem(R.drawable.ic_nav_files, "Файлы", true, v -> {}), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_nav_images, "Картинки", false, v -> openCategory("Images")), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_nav_projects, "Проекты", false, v -> openCategory("Projects")), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_nav_accounts, "Аккаунты", false, v -> startActivity(new Intent(this, AccountManagerActivity.class))), new LinearLayout.LayoutParams(0, -1, 1));
        return nav;
    }

    private View navItem(int res, String label, boolean active, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(3), 0, 0);

        ImageView icon = tintedIcon(res, active ? GREEN : 0xFF475467, 29, 36);
        TextView t = text(label, 10, active);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(active ? GREEN : 0xFF475467);
        View dot = new View(this);
        dot.setBackground(active ? round(GREEN, 3, 0, 0) : new ColorDrawable(Color.TRANSPARENT));

        box.addView(icon, new LinearLayout.LayoutParams(-1, dp(34)));
        box.addView(t, new LinearLayout.LayoutParams(-1, dp(18)));
        LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(dp(6), dp(6));
        dpv.gravity = Gravity.CENTER_HORIZONTAL;
        dpv.setMargins(0, dp(2), 0, 0);
        box.addView(dot, dpv);
        box.setOnClickListener(click);
        return box;
    }

    private void refresh() {
        AccountProfile a = db.activeAccount();
        if (a == null) return;
        activeId = a.id;
        accountLabel.setText(shortAccount(a.displayName()));
        updateStorage(a);
        refreshContent();
    }

    private void updateStorage(AccountProfile a) {
        long known = db.knownStorageBytes(a.id);
        if (a.hasRemoteQuota()) {
            storageRing.setStorage(a.remoteUsedBytes, a.remoteQuotaBytes);
            storageValue.setText(MainActivity.formatBytes(a.remoteUsedBytes) + "  /  " + MainActivity.formatBytes(a.remoteQuotaBytes));
        } else {
            storageRing.setStorage(0, 0);
            storageValue.setText(known > 0 ? MainActivity.formatBytes(known) + " индексировано" : "Объём не определён");
        }
        planLine.setText("План: " + a.planLabel());
        if (!a.hasSession()) {
            syncLine.setText("Нужен вход в ChatGPT");
            syncLine.setTextColor(0xFF8A5A00);
        } else if (a.lastSync > 0) {
            syncLine.setText("Синхронизировано  •  " + friendlySync(a.lastSync));
            syncLine.setTextColor(MUTED);
        } else {
            syncLine.setText("Аккаунт подключён  •  нужна синхронизация");
            syncLine.setTextColor(MUTED);
        }
    }

    private void refreshContent() {
        if (content == null || activeId <= 0) return;
        List<DriveItem> items = new ArrayList<>(db.listDistinct(activeId, 180));
        if (!searchQuery.trim().isEmpty()) {
            String q = searchQuery.toLowerCase(Locale.ROOT);
            ArrayList<DriveItem> filtered = new ArrayList<>();
            for (DriveItem x : items) {
                if (x.name.toLowerCase(Locale.ROOT).contains(q)
                        || x.category.toLowerCase(Locale.ROOT).contains(q)
                        || DriveActions.shortType(x).toLowerCase(Locale.ROOT).contains(q)) filtered.add(x);
            }
            items = filtered;
            sectionTitle.setText("Поиск / " + searchQuery);
        } else sectionTitle.setText("Облако / Все файлы");

        sortItems(items);
        sectionCount.setText(items.size() + " объектов");
        content.removeAllViews();
        if (gridMode) renderGrid(items); else renderList(items);
    }

    private void renderList(List<DriveItem> items) {
        addFolderRow(R.drawable.folder_library, "Библиотека", db.count(activeId, "Library"), "Library");
        addFolderRow(R.drawable.folder_images, "Картинки", db.count(activeId, "Images"), "Images");
        addFolderRow(R.drawable.folder_projects, "Проекты", db.count(activeId, "Projects"), "Projects");
        addFolderRow(R.drawable.folder_uploads, "Загрузки", 0, "Library");
        addFolderRow(R.drawable.folder_generated, "Сгенерированные", 0, "Images");
        addFolderRow(R.drawable.folder_archived, "Архив", db.count(activeId, "Archived"), "Archived");
        for (DriveItem x : items) addFileRow(x);
        if (items.isEmpty()) addEmpty();
    }

    private void renderGrid(List<DriveItem> items) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2;
        addGridFolder(grid, w, R.drawable.folder_library, "Библиотека", db.count(activeId, "Library"), "Library");
        addGridFolder(grid, w, R.drawable.folder_images, "Картинки", db.count(activeId, "Images"), "Images");
        addGridFolder(grid, w, R.drawable.folder_projects, "Проекты", db.count(activeId, "Projects"), "Projects");
        addGridFolder(grid, w, R.drawable.folder_archived, "Архив", db.count(activeId, "Archived"), "Archived");
        for (DriveItem x : items) addGridFile(grid, w, x);
        content.addView(grid);
        if (items.isEmpty()) addEmpty();
    }

    private void addFolderRow(int res, String name, long count, String category) {
        LinearLayout row = baseRow();
        ImageView icon = rawIcon(res, 52, 48);
        row.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout info = rowInfo(name, count > 0 ? count + " объектов" : category.equals("Library") && name.equals("Загрузки") ? "Загруженные файлы" : category.equals("Images") && name.equals("Сгенерированные") ? "Созданные изображения и файлы" : count + " объектов");
        row.addView(info, new LinearLayout.LayoutParams(0, dp(54), 1));
        TextView more = moreButton();
        more.setOnClickListener(v -> folderMenu(v, category));
        row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(46)));
        row.setOnClickListener(v -> openCategory(category));
        addRow(row);
    }

    private void addFileRow(DriveItem x) {
        LinearLayout row = baseRow();
        if (DriveActions.isImage(x)) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(0xFFE9ECEF, 10, 0, 0));
            ImageLoader.load(image, x.url);
            row.addView(image, new LinearLayout.LayoutParams(dp(52), dp(52)));
        } else row.addView(fileTile(x), new LinearLayout.LayoutParams(dp(52), dp(52)));
        row.addView(rowInfo(x.name, fileMeta(x)), new LinearLayout.LayoutParams(0, dp(54), 1));
        TextView more = moreButton();
        more.setOnClickListener(v -> itemMenu(v, x));
        row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(46)));
        row.setOnClickListener(v -> DriveActions.open(this, x));
        addRow(row);
    }

    private LinearLayout rowInfo(String name, String metaText) {
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(10), 0, 0, 0);
        TextView n = text(name, 15, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = text(metaText, 11, false);
        meta.setTextColor(MUTED);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(n);
        info.addView(meta);
        return info;
    }

    private void addGridFolder(GridLayout grid, int width, int res, String name, long count, String category) {
        LinearLayout cell = gridCell();
        cell.addView(rawIcon(res, 54, 48), new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView n = text(name, 14, true); n.setSingleLine(true); n.setPadding(0, dp(6), 0, 0); cell.addView(n);
        TextView meta = text(count + " объектов", 10, false); meta.setTextColor(MUTED); cell.addView(meta);
        cell.setOnClickListener(v -> openCategory(category));
        addGridCell(grid, cell, width);
    }

    private void addGridFile(GridLayout grid, int width, DriveItem x) {
        LinearLayout cell = gridCell();
        if (DriveActions.isImage(x)) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(0xFFE9ECEF, 10, 0, 0));
            ImageLoader.load(image, x.url);
            cell.addView(image, new LinearLayout.LayoutParams(-1, dp(80)));
        } else cell.addView(fileTile(x), new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView n = text(x.name, 12, true); n.setMaxLines(2); n.setEllipsize(TextUtils.TruncateAt.END); n.setPadding(0, dp(6), 0, 0); cell.addView(n);
        TextView meta = text(x.sizeBytes > 0 ? MainActivity.formatBytes(x.sizeBytes) : DriveActions.shortType(x), 10, false); meta.setTextColor(MUTED); cell.addView(meta);
        cell.setOnClickListener(v -> DriveActions.open(this, x));
        cell.setOnLongClickListener(v -> { itemMenu(v, x); return true; });
        addGridCell(grid, cell, width);
    }

    private LinearLayout gridCell() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(10), dp(10), dp(10), dp(10));
        cell.setBackground(round(Color.WHITE, 16, 0xFFE8EBED, 1));
        return cell;
    }

    private void addGridCell(GridLayout grid, View cell, int width) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = width;
        p.height = dp(148);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(cell, p);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(6), dp(7));
        row.setBackground(round(Color.WHITE, 16, 0xFFE8EBED, 1));
        return row;
    }

    private void addRow(View row) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(72));
        p.setMargins(0, 0, 0, dp(5));
        content.addView(row, p);
    }

    private TextView fileTile(DriveItem x) {
        String symbol = "DOC";
        int color = 0xFF98A2B3;
        String mime = x.mime == null ? "" : x.mime;
        if (mime.equals("application/pdf")) { symbol = "PDF"; color = 0xFFF04438; }
        else if (mime.contains("zip")) { symbol = "ZIP"; color = 0xFF7A5AF8; }
        else if (mime.startsWith("text/")) { symbol = "TXT"; color = 0xFF98A2B3; }
        else if (mime.contains("wordprocessingml")) { symbol = "DOCX"; color = 0xFF2E90FA; }
        else if (mime.contains("spreadsheetml")) { symbol = "XLSX"; color = 0xFF12B76A; }
        else if (DriveActions.isLink(x)) { symbol = "↗"; color = 0xFF667085; }
        TextView t = text(symbol, symbol.length() > 3 ? 9 : 11, true);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.WHITE);
        t.setBackground(round(color, 10, 0, 0));
        return t;
    }

    private TextView moreButton() {
        TextView t = text("⋮", 22, false);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFF475467);
        return t;
    }

    private void addEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(18), dp(16), dp(18));
        box.setBackground(round(Color.WHITE, 16, 0xFFE8EBED, 1));
        box.addView(text("Файлы ещё не проиндексированы", 15, true));
        TextView d = text("Нажми + и запусти синхронизацию Библиотеки.", 11, false);
        d.setTextColor(MUTED); d.setPadding(0, dp(5), 0, 0); box.addView(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(90)); p.setMargins(0, dp(5), 0, dp(8)); content.addView(box, p);
    }

    private void showAccountSwitcher(View anchor) {
        List<AccountProfile> accounts = db.listAccounts();
        LinearLayout panel = popupPanel();
        for (AccountProfile a : accounts) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            ImageView icon = tintedIcon(R.drawable.ic_nav_accounts, a.active ? GREEN : 0xFF667085, 18, 34);
            row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView n = text((a.active ? "✓ " : "") + a.displayName(), 12, true);
            n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
            TextView m = text(a.planLabel() + "  •  " + (a.hasSession() ? "подключён" : "нужен вход"), 10, false); m.setTextColor(MUTED);
            labels.addView(n); labels.addView(m);
            row.addView(labels, new LinearLayout.LayoutParams(0, dp(42), 1));
            panel.addView(row, new LinearLayout.LayoutParams(-1, dp(54)));
        }
        panel.addView(divider());
        panel.addView(popupAction("＋ Добавить аккаунт"));
        panel.addView(popupAction("Управление аккаунтами"));

        PopupWindow w = popup(panel, dp(286));
        int index = 0;
        for (AccountProfile target : accounts) {
            View row = panel.getChildAt(index++);
            row.setOnClickListener(v -> {
                w.dismiss();
                if (target.id == activeId) return;
                AccountSession.switchTo(this, target.id, () -> {
                    notifyRoots();
                    refresh();
                    Toast.makeText(this, "Переключено: " + target.displayName(), Toast.LENGTH_SHORT).show();
                });
            });
        }
        index++;
        panel.getChildAt(index++).setOnClickListener(v -> { w.dismiss(); addAccount(); });
        panel.getChildAt(index).setOnClickListener(v -> { w.dismiss(); startActivity(new Intent(this, AccountManagerActivity.class)); });
        showPopup(anchor, w, dp(286));
    }

    private void showMainMenu(View anchor) {
        showActionPopup(anchor, new String[]{"Синхронизировать Библиотеку", "Android Files", "Аккаунты"},
                new Runnable[]{() -> openSync("Library"), this::openSystemFiles, () -> startActivity(new Intent(this, AccountManagerActivity.class))}, dp(220));
    }

    private void showFabMenu(View anchor) {
        showActionPopup(anchor, new String[]{"Синхронизировать Библиотеку", "Добавить аккаунт", "Android Files"},
                new Runnable[]{() -> openSync("Library"), this::addAccount, this::openSystemFiles}, dp(230));
    }

    private void showSortMenu(View anchor) {
        showActionPopup(anchor, new String[]{"По дате", "По имени", "По типу"}, new Runnable[]{
                () -> { sortMode=0; sortLabel.setText("По дате⌄"); refreshContent(); },
                () -> { sortMode=1; sortLabel.setText("По имени⌄"); refreshContent(); },
                () -> { sortMode=2; sortLabel.setText("По типу⌄"); refreshContent(); }
        }, dp(160));
    }

    private void folderMenu(View anchor, String category) {
        showActionPopup(anchor, new String[]{"Открыть", "Синхронизировать"}, new Runnable[]{() -> openCategory(category), () -> openSync(category)}, dp(190));
    }

    private void itemMenu(View anchor, DriveItem item) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        labels.add("Открыть"); actions.add(() -> DriveActions.open(this, item));
        if (!DriveActions.isLink(item)) { labels.add("Скачать"); actions.add(() -> DriveActions.download(this, item)); }
        labels.add("Источник в ChatGPT"); actions.add(() -> {
            Intent i = new Intent(this, BrowserActivity.class);
            i.putExtra(BrowserActivity.EXTRA_SECTION, item.category);
            i.putExtra(BrowserActivity.EXTRA_URL, item.url);
            i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, item.accountId);
            startActivity(i);
        });
        showActionPopup(anchor, labels.toArray(new String[0]), actions.toArray(new Runnable[0]), dp(210));
    }

    private void showSearch() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        panel.setBackground(round(Color.WHITE, 20, 0xFFE5E8EB, 1));
        panel.addView(text("Поиск по библиотеке", 17, true));
        TextView hint = text("Ищет по всем файлам, изображениям и проектам текущего аккаунта", 10, false);
        hint.setTextColor(MUTED);
        hint.setPadding(0, dp(3), 0, 0);
        panel.addView(hint);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Название файла, проект или тип");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(searchQuery);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(50)); ip.setMargins(0, dp(10), 0, dp(8)); panel.addView(input, ip);
        LinearLayout buttons = new LinearLayout(this); buttons.setGravity(Gravity.END);
        TextView reset = actionButton("Сбросить", false); TextView find = actionButton("Найти", true);
        buttons.addView(reset, new LinearLayout.LayoutParams(dp(92), dp(42))); buttons.addView(find, new LinearLayout.LayoutParams(dp(92), dp(42))); panel.addView(buttons);
        reset.setOnClickListener(v -> { searchQuery=""; d.dismiss(); refreshContent(); });
        find.setOnClickListener(v -> { searchQuery=input.getText().toString().trim(); d.dismiss(); refreshContent(); });
        d.setContentView(panel);
        d.setOnShowListener(x -> { Window ww=d.getWindow(); if(ww!=null) ww.setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.90f), WindowManager.LayoutParams.WRAP_CONTENT); });
        d.show();
        Window win=d.getWindow(); if(win!=null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private TextView actionButton(String label, boolean primary) {
        TextView v=text(label,12,true); v.setGravity(Gravity.CENTER); v.setTextColor(primary?Color.WHITE:TEXT); v.setBackground(round(primary?GREEN:0xFFF2F4F5,12,0,0)); return v;
    }

    private void showActionPopup(View anchor, String[] labels, Runnable[] actions, int width) {
        LinearLayout panel = popupPanel();
        for (String label : labels) panel.addView(popupAction(label), new LinearLayout.LayoutParams(-1, dp(46)));
        PopupWindow w = popup(panel, width);
        for (int i=0;i<labels.length;i++) { final int j=i; panel.getChildAt(i).setOnClickListener(v -> { w.dismiss(); actions[j].run(); }); }
        showPopup(anchor, w, width);
    }

    private LinearLayout popupPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(6), dp(5), dp(6));
        panel.setBackground(round(Color.WHITE, 16, 0xFFE5E8EB, 1));
        return panel;
    }

    private TextView popupAction(String label) {
        TextView v = text(label, 12, false);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(12), 0, dp(12), 0);
        return v;
    }

    private View divider() {
        View d = new View(this); d.setBackgroundColor(0xFFE8EAED);
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return d;
    }

    private PopupWindow popup(View content, int width) {
        PopupWindow w = new PopupWindow(content, width, -2, true);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setOutsideTouchable(true);
        w.setElevation(dp(12));
        return w;
    }

    private void showPopup(View anchor, PopupWindow w, int width) {
        int x = anchor.getWidth() - width;
        w.showAsDropDown(anchor, x, -dp(2));
    }

    private void setGridMode(boolean value) {
        gridMode=value;
        listIcon.setBackground(round(!gridMode?GREEN_SOFT:Color.WHITE,11,0xFFE1E5E8,1));
        listIcon.setColorFilter(!gridMode?GREEN:0xFF475467);
        gridIcon.setBackground(round(gridMode?GREEN_SOFT:Color.WHITE,11,0xFFE1E5E8,1));
        gridIcon.setColorFilter(gridMode?GREEN:0xFF475467);
        refreshContent();
    }

    private void sortItems(List<DriveItem> items) {
        if(sortMode==1) Collections.sort(items, Comparator.comparing(x->x.name.toLowerCase(Locale.ROOT)));
        else if(sortMode==2) Collections.sort(items, Comparator.comparing(x->DriveActions.shortType(x).toLowerCase(Locale.ROOT)));
        else Collections.sort(items,(a,b)->Long.compare(b.updated,a.updated));
    }

    private void addAccount() {
        long id=db.addAccount("Новый аккаунт");
        Intent i=new Intent(this,BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION,"Library");
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,id);
        i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN,true);
        startActivity(i);
    }

    private void login(AccountProfile a, boolean fresh) {
        Intent i=new Intent(this,BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION,"Library");
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,a.id);
        i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN,fresh);
        startActivity(i);
    }

    private void openCategory(String category) {
        Intent i=new Intent(this,NativeBrowserActivity.class);
        i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY,category);
        i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID,activeId);
        startActivity(i);
    }

    private void openSync(String section) {
        Intent i=new Intent(this,BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION,section);
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,activeId);
        startActivity(i);
    }

    private void openSystemFiles(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,100);}catch(Exception ignored){}}
    private void notifyRoots(){try{getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY),null);}catch(Exception ignored){}}

    private String fileMeta(DriveItem x) {
        String left=x.sizeBytes>0?MainActivity.formatBytes(x.sizeBytes):DriveActions.shortType(x);
        DateFormat df=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        return left+" • "+df.format(new Date(x.updated));
    }

    private String friendlySync(long time) {
        Date now = new Date();
        Date then = new Date(time);
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        if (day.format(now).equals(day.format(then))) return "Сегодня, " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(then);
        return new SimpleDateFormat("dd.MM, HH:mm", Locale.getDefault()).format(then);
    }

    private String shortAccount(String value) {
        if(value==null||value.trim().isEmpty())return"Аккаунт";
        String v=value.trim();
        int at=v.indexOf('@');
        if(at>0)v=v.substring(0,at);
        return v.length()>12?v.substring(0,12)+"…":v;
    }

    private ImageView tintedIcon(int res,int tint,int innerDp,int boxDp){
        ImageView v=new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p=Math.max(0,(boxDp-innerDp)/2);
        v.setPadding(dp(p),dp(p),dp(p),dp(p));
        return v;
    }

    private ImageView rawIcon(int res,int boxDp,int innerDp){
        ImageView v=new ImageView(this);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p=Math.max(0,(boxDp-innerDp)/2);
        v.setPadding(dp(p),dp(p),dp(p),dp(p));
        return v;
    }

    private TextView text(String value,int sp,boolean bold){
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color,int radiusDp,int strokeColor,int strokeDp){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if(strokeDp>0)g.setStroke(dp(strokeDp),strokeColor);
        return g;
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
