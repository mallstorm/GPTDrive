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

public final class ExplorerActivityCompact extends Activity {
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF08A66D;
    private static final int GREEN_SOFT = 0xFFEAF9F3;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private static final int ICON_GRAY = 0xFF475467;

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
        db = DriveDb.get(this);
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

        FrameLayout stage = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setSaveEnabled(false);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(3), dp(12), dp(18));
        body.addView(buildStorageCard());
        body.addView(buildChips());
        body.addView(buildSectionBlock());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        body.addView(content, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        stage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        scroll.post(() -> scroll.scrollTo(0, 0));

        TextView fab = text("+", 29, false);
        fab.setTextColor(Color.WHITE);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(round(GREEN, 28, 0, 0));
        fab.setElevation(dp(8));
        fab.setOnClickListener(this::showFabMenu);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END | Gravity.BOTTOM);
        fp.setMargins(0, 0, dp(17), dp(14));
        stage.addView(fab, fp);

        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
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
        accountLabel = text("Аккаунт 1", 11, true);
        accountLabel.setSingleLine(true);
        accountLabel.setGravity(Gravity.CENTER_VERTICAL);
        accountLabel.setEllipsize(TextUtils.TruncateAt.END);
        accountLabel.setPadding(dp(6), 0, dp(5), 0);
        account.addView(accountLabel, new LinearLayout.LayoutParams(0, dp(34), 1));
        TextView chevron = text("⌄", 15, true);
        chevron.setTextColor(ICON_GRAY);
        chevron.setGravity(Gravity.CENTER);
        account.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(34)));
        account.setOnClickListener(this::showAccountSwitcher);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(126), dp(38));
        ap.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(account, ap);

        ImageView search = tintedIcon(R.drawable.ic_search_24, TEXT, 23, 40);
        search.setOnClickListener(v -> showSearch());
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
        TextView title = text("Использовано места", 13, true);
        storageValue = text("Объём не определён", 18, true);
        storageValue.setTextColor(GREEN);
        storageValue.setSingleLine(true);
        storageValue.setEllipsize(TextUtils.TruncateAt.END);
        planLine = text("План: Не определён", 10, true);
        planLine.setTextColor(0xFF344054);
        planLine.setPadding(0, dp(2), 0, 0);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cloud = rawIcon(R.drawable.ic_gpt_drive_logo);
        syncRow.addView(cloud, new LinearLayout.LayoutParams(dp(22), dp(18)));
        syncLine = text("Не синхронизировано", 10, false);
        syncLine.setTextColor(MUTED);
        syncLine.setPadding(dp(4), 0, 0, 0);
        syncRow.addView(syncLine, new LinearLayout.LayoutParams(0, dp(20), 1));

        info.addView(title);
        info.addView(storageValue);
        info.addView(planLine);
        info.addView(syncRow);
        card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));

        TextView arrow = text("›", 28, false);
        arrow.setTextColor(0xFF344054);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(26), -1));
        card.setOnClickListener(v -> {
            AccountProfile a = db.activeAccount();
            if (a != null && !AccountAuth.connected(a)) login(a, false); else openSync("Library");
        });
        return card;
    }

    private View buildChips() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(chip(R.drawable.ic_chip_all, "Все", true, v -> { searchQuery = ""; refreshContent(); }));
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

    private View buildSectionBlock() {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        sectionTitle = text("Облако / Все файлы", 18, true);
        sectionCount = text("0 объектов", 10, false);
        sectionCount.setTextColor(MUTED);
        labels.addView(sectionTitle);
        labels.addView(sectionCount);
        head.addView(labels, new LinearLayout.LayoutParams(0, dp(48), 1));

        LinearLayout sort = new LinearLayout(this);
        sort.setGravity(Gravity.CENTER);
        ImageView sortIcon = tintedIcon(R.drawable.ic_sort_24, MUTED, 16, 24);
        sort.addView(sortIcon, new LinearLayout.LayoutParams(dp(24), dp(32)));
        sortLabel = text("По дате⌄", 10, false);
        sortLabel.setTextColor(MUTED);
        sort.addView(sortLabel, new LinearLayout.LayoutParams(-2, dp(32)));
        sort.setOnClickListener(this::showSortMenu);
        head.addView(sort, new LinearLayout.LayoutParams(dp(84), dp(34)));

        listIcon = viewToggle(R.drawable.ic_list_24, true);
        listIcon.setOnClickListener(v -> setGridMode(false));
        head.addView(listIcon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        gridIcon = viewToggle(R.drawable.ic_grid_24, false);
        gridIcon.setOnClickListener(v -> setGridMode(true));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(34), dp(34));
        gp.setMargins(dp(3), 0, 0, 0);
        head.addView(gridIcon, gp);
        return head;
    }

    private ImageView viewToggle(int res, boolean active) {
        ImageView v = tintedIcon(res, active ? GREEN : ICON_GRAY, 18, 34);
        v.setBackground(round(active ? GREEN_SOFT : Color.WHITE, 8, 0xFFE1E5E8, 1));
        return v;
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
        refreshContent();
    }

    private void updateStorage(AccountProfile a) {
        long known = db.knownStorageBytes(a.id);
        if (a.hasRemoteQuota()) {
            storageRing.setStorage(a.remoteUsedBytes, a.remoteQuotaBytes);
            storageValue.setText(MainActivity.formatBytes(a.remoteUsedBytes) + " / " + MainActivity.formatBytes(a.remoteQuotaBytes));
        } else {
            storageRing.setStorage(0, 0);
            storageValue.setText(known > 0 ? MainActivity.formatBytes(known) + " индексировано" : "Объём не определён");
        }
        planLine.setText("План: " + a.planLabel());
        if (!AccountAuth.connected(a)) {
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

    private void refreshContent() {
        if (content == null || activeId <= 0) return;
        List<DriveItem> items;
        if (searchQuery.trim().isEmpty()) {
            items = new ArrayList<>(db.listDistinct(activeId, 160));
            sectionTitle.setText("Облако / Все файлы");
        } else {
            String q = searchQuery.toLowerCase(Locale.ROOT);
            items = new ArrayList<>();
            for (DriveItem x : db.listDistinct(activeId, 5000)) {
                String name = x.name == null ? "" : x.name.toLowerCase(Locale.ROOT);
                String cat = x.category == null ? "" : x.category.toLowerCase(Locale.ROOT);
                String type = DriveActions.shortType(x).toLowerCase(Locale.ROOT);
                if (name.contains(q) || cat.contains(q) || type.contains(q)) items.add(x);
            }
            sectionTitle.setText("Поиск / " + searchQuery);
        }
        sortItems(items);
        sectionCount.setText(items.size() + " объектов");
        content.removeAllViews();
        if (gridMode) renderGrid(items); else renderList(items);
    }

    private void renderList(List<DriveItem> items) {
        if (searchQuery.trim().isEmpty()) {
            addFolderRow(R.drawable.folder_library, "Библиотека", db.count(activeId, "Library"), "Library");
            addFolderRow(R.drawable.folder_images, "Картинки", db.count(activeId, "Images"), "Images");
            addFolderRow(R.drawable.folder_projects, "Проекты", db.count(activeId, "Projects"), "Projects");
            addVirtualFolderRow(R.drawable.folder_uploads, "Загрузки", "Загруженные файлы");
            addVirtualFolderRow(R.drawable.folder_generated, "Сгенерированные", "Созданные изображения и файлы");
            addFolderRow(R.drawable.folder_archived, "Архив", db.count(activeId, "Archived"), "Archived");
        }
        for (DriveItem x : items) addFileRow(x);
        if (items.isEmpty()) addEmpty(searchQuery.trim().isEmpty() ? "Файлы ещё не проиндексированы" : "Ничего не найдено", searchQuery.trim().isEmpty() ? "Запусти синхронизацию библиотеки." : "Измени запрос поиска по библиотеке.");
    }

    private void renderGrid(List<DriveItem> items) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(38)) / 2;
        if (searchQuery.trim().isEmpty()) {
            addGridFolder(grid, w, R.drawable.folder_library, "Библиотека", db.count(activeId, "Library"), "Library");
            addGridFolder(grid, w, R.drawable.folder_images, "Картинки", db.count(activeId, "Images"), "Images");
            addGridFolder(grid, w, R.drawable.folder_projects, "Проекты", db.count(activeId, "Projects"), "Projects");
            addGridFolder(grid, w, R.drawable.folder_archived, "Архив", db.count(activeId, "Archived"), "Archived");
        }
        for (DriveItem x : items) addGridFile(grid, w, x);
        content.addView(grid);
        if (items.isEmpty()) addEmpty("Файлы ещё не проиндексированы", "Запусти синхронизацию библиотеки.");
    }

    private void addFolderRow(int drawable, String name, long count, String category) {
        LinearLayout row = baseRow();
        row.addView(rawIcon(drawable), new LinearLayout.LayoutParams(dp(46), dp(42)));
        row.addView(rowInfo(name, folderMeta(category, count)), new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView more = moreButton();
        more.setOnClickListener(v -> folderMenu(v, category));
        row.addView(more, new LinearLayout.LayoutParams(dp(30), dp(40)));
        row.setOnClickListener(v -> openCategory(category));
        addRow(row);
    }

    private void addVirtualFolderRow(int drawable, String name, String subtitle) {
        LinearLayout row = baseRow();
        row.addView(rawIcon(drawable), new LinearLayout.LayoutParams(dp(46), dp(42)));
        row.addView(rowInfo(name, subtitle), new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView more = moreButton();
        more.setOnClickListener(v -> openSync("Library"));
        row.addView(more, new LinearLayout.LayoutParams(dp(30), dp(40)));
        row.setOnClickListener(v -> openSync("Library"));
        addRow(row);
    }

    private LinearLayout rowInfo(String name, String meta) {
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

    private void addFileRow(DriveItem x) {
        LinearLayout row = baseRow();
        if (DriveActions.isImage(x)) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(0xFFE9ECEF, 7, 0, 0));
            ImageLoader.load(image, x.url);
            row.addView(image, new LinearLayout.LayoutParams(dp(42), dp(42)));
        } else row.addView(fileTile(x), new LinearLayout.LayoutParams(dp(42), dp(42)));
        row.addView(rowInfo(x.name, fileMeta(x)), new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView more = moreButton();
        more.setOnClickListener(v -> itemMenu(v, x));
        row.addView(more, new LinearLayout.LayoutParams(dp(30), dp(40)));
        row.setOnClickListener(v -> DriveActions.open(this, x));
        addRow(row);
    }

    private void addGridFolder(GridLayout grid, int width, int drawable, String name, long count, String category) {
        LinearLayout cell = gridCell();
        cell.addView(rawIcon(drawable), new LinearLayout.LayoutParams(dp(48), dp(44)));
        TextView n = text(name, 13, true); n.setSingleLine(true); n.setPadding(0, dp(4), 0, 0); cell.addView(n);
        TextView m = text(folderMeta(category, count), 9, false); m.setTextColor(MUTED); cell.addView(m);
        cell.setOnClickListener(v -> openCategory(category));
        addGridCell(grid, cell, width);
    }

    private void addGridFile(GridLayout grid, int width, DriveItem x) {
        LinearLayout cell = gridCell();
        if (DriveActions.isImage(x)) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(0xFFE9ECEF, 7, 0, 0));
            ImageLoader.load(image, x.url);
            cell.addView(image, new LinearLayout.LayoutParams(-1, dp(78)));
        } else cell.addView(fileTile(x), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView n = text(x.name, 11, true); n.setMaxLines(2); n.setEllipsize(TextUtils.TruncateAt.END); n.setPadding(0, dp(5), 0, 0); cell.addView(n);
        TextView m = text(x.sizeBytes > 0 ? MainActivity.formatBytes(x.sizeBytes) : DriveActions.shortType(x), 9, false); m.setTextColor(MUTED); cell.addView(m);
        cell.setOnClickListener(v -> DriveActions.open(this, x));
        cell.setOnLongClickListener(v -> { itemMenu(v, x); return true; });
        addGridCell(grid, cell, width);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(6), dp(4));
        row.setBackground(round(Color.WHITE, 13, 0xFFE8EBED, 1));
        return row;
    }

    private void addRow(View row) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
        p.setMargins(0, 0, 0, dp(4));
        content.addView(row, p);
    }

    private LinearLayout gridCell() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(9), dp(9), dp(9), dp(9));
        cell.setBackground(round(Color.WHITE, 13, 0xFFE8EBED, 1));
        return cell;
    }

    private void addGridCell(GridLayout grid, View cell, int width) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = width;
        p.height = dp(136);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(cell, p);
    }

    private TextView fileTile(DriveItem x) {
        String symbol = "DOC";
        int color = 0xFF98A2B3;
        String mime = x.mime == null ? "" : x.mime;
        if (mime.equals("application/pdf")) { symbol = "PDF"; color = 0xFFE52D3D; }
        else if (mime.contains("zip")) { symbol = "ZIP"; color = 0xFF7357EA; }
        else if (mime.startsWith("text/")) { symbol = "TXT"; color = 0xFF7C8794; }
        else if (mime.contains("wordprocessingml")) { symbol = "DOCX"; color = 0xFF2E90FA; }
        else if (mime.contains("spreadsheetml")) { symbol = "XLSX"; color = 0xFF12B76A; }
        TextView t = text(symbol, symbol.length() > 3 ? 8 : 10, true);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(color, 8, 0, 0));
        return t;
    }

    private TextView moreButton() {
        TextView t = text("⋮", 20, false);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ICON_GRAY);
        return t;
    }

    private void addEmpty(String title, String message) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(round(Color.WHITE, 13, 0xFFE8EBED, 1));
        box.addView(text(title, 14, true));
        TextView d = text(message, 10, false);
        d.setTextColor(MUTED); d.setPadding(0, dp(3), 0, 0); box.addView(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(76)); p.setMargins(0, dp(4), 0, dp(7)); content.addView(box, p);
    }

    private String folderMeta(String category, long count) {
        List<DriveItem> latest = db.list(activeId, category, 1);
        if (latest.isEmpty()) return count + " объектов";
        return count + " объектов • " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(latest.get(0).updated));
    }

    private String fileMeta(DriveItem x) {
        String left = x.sizeBytes > 0 ? MainActivity.formatBytes(x.sizeBytes) : DriveActions.shortType(x);
        return left + " • " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(x.updated));
    }

    private void showSearch() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        panel.setBackground(round(Color.WHITE, 18, 0xFFE5E8EB, 1));
        panel.addView(text("Поиск по библиотеке", 16, true));
        TextView hint = text("Ищет по проиндексированной библиотеке активного аккаунта.", 10, false);
        hint.setTextColor(MUTED); hint.setPadding(0, dp(3), 0, 0); panel.addView(hint);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Файл, изображение, проект, архив…");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(searchQuery);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(48)); ip.setMargins(0, dp(8), 0, dp(8)); panel.addView(input, ip);
        LinearLayout buttons = new LinearLayout(this); buttons.setGravity(Gravity.END);
        TextView reset = actionButton("Сбросить", false); TextView find = actionButton("Найти", true);
        buttons.addView(reset, new LinearLayout.LayoutParams(dp(88), dp(40))); buttons.addView(find, new LinearLayout.LayoutParams(dp(88), dp(40))); panel.addView(buttons);
        reset.setOnClickListener(v -> { searchQuery = ""; d.dismiss(); refreshContent(); });
        find.setOnClickListener(v -> { searchQuery = input.getText().toString().trim(); d.dismiss(); refreshContent(); });
        d.setContentView(panel);
        Window win = d.getWindow(); if (win != null) win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        d.setOnShowListener(x -> { Window w = d.getWindow(); if (w != null) w.setLayout((int)(getResources().getDisplayMetrics().widthPixels * .90f), WindowManager.LayoutParams.WRAP_CONTENT); });
        d.show();
    }

    private TextView actionButton(String label, boolean primary) {
        TextView v = text(label, 12, true); v.setGravity(Gravity.CENTER); v.setTextColor(primary ? Color.WHITE : TEXT); v.setBackground(round(primary ? GREEN : 0xFFF2F4F5, 10, 0, 0)); return v;
    }

    private void showAccountSwitcher(View anchor) {
        List<AccountProfile> accounts = db.listAccounts();
        LinearLayout panel = popupPanel();
        ArrayList<View> accountRows = new ArrayList<>();
        for (AccountProfile a : accounts) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(7), dp(10), dp(7));

            TextView check = text(a.active ? "✓" : "", 14, false);
            check.setTextColor(0xFF98A2B3);
            check.setGravity(Gravity.CENTER);
            row.addView(check, new LinearLayout.LayoutParams(dp(24), dp(40)));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(a.displayName(), 12, true);
            name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
            long known = db.knownStorageBytes(a.id);
            String size = a.hasRemoteQuota() ? MainActivity.formatBytes(a.remoteUsedBytes) : (known > 0 ? MainActivity.formatBytes(known) : "Объём —");
            TextView meta = text(a.planLabel() + " • " + size, 9, false); meta.setTextColor(MUTED);
            info.addView(name); info.addView(meta);
            row.addView(info, new LinearLayout.LayoutParams(0, dp(40), 1));
            panel.addView(row, new LinearLayout.LayoutParams(-1, dp(50)));
            accountRows.add(row);
        }
        panel.addView(divider());
        TextView add = popupAction("＋  Добавить аккаунт"); panel.addView(add, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView manage = popupAction("Управление аккаунтами"); panel.addView(manage, new LinearLayout.LayoutParams(-1, dp(44)));
        PopupWindow w = popup(panel, dp(266));
        for (int i = 0; i < accounts.size(); i++) {
            AccountProfile target = accounts.get(i);
            accountRows.get(i).setOnClickListener(v -> {
                w.dismiss();
                if (target.id == activeId) return;
                AccountSession.switchTo(this, target.id, () -> { notifyRoots(); refresh(); Toast.makeText(this, "Переключено: " + target.displayName(), Toast.LENGTH_SHORT).show(); });
            });
        }
        add.setOnClickListener(v -> { w.dismiss(); addAccount(); });
        manage.setOnClickListener(v -> { w.dismiss(); startActivity(new Intent(this, AccountManagerActivity.class)); });
        showPopup(anchor, w, dp(266));
    }

    private void showMainMenu(View anchor) {
        showActionPopup(anchor, new String[]{"Синхронизировать библиотеку", "Routing & DNS", "Android Files", "Аккаунты"}, new Runnable[]{() -> openSync("Library"), () -> startActivity(new Intent(this, DnsSettingsActivity.class)), this::openSystemFiles, () -> startActivity(new Intent(this, AccountManagerActivity.class))}, dp(232));
    }

    private void showFabMenu(View anchor) {
        showActionPopup(anchor, new String[]{"Синхронизировать библиотеку", "Добавить аккаунт", "Android Files"}, new Runnable[]{() -> openSync("Library"), this::addAccount, this::openSystemFiles}, dp(224));
    }

    private void showSortMenu(View anchor) {
        showActionPopup(anchor, new String[]{"По дате", "По имени", "По типу"}, new Runnable[]{() -> { sortMode = 0; sortLabel.setText("По дате⌄"); refreshContent(); }, () -> { sortMode = 1; sortLabel.setText("По имени⌄"); refreshContent(); }, () -> { sortMode = 2; sortLabel.setText("По типу⌄"); refreshContent(); }}, dp(150));
    }

    private void folderMenu(View anchor, String category) {
        showActionPopup(anchor, new String[]{"Открыть", "Синхронизировать"}, new Runnable[]{() -> openCategory(category), () -> openSync(category)}, dp(180));
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
        showActionPopup(anchor, labels.toArray(new String[0]), actions.toArray(new Runnable[0]), dp(204));
    }

    private void showActionPopup(View anchor, String[] labels, Runnable[] actions, int width) {
        LinearLayout panel = popupPanel();
        for (String label : labels) panel.addView(popupAction(label), new LinearLayout.LayoutParams(-1, dp(44)));
        PopupWindow w = popup(panel, width);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            panel.getChildAt(i).setOnClickListener(v -> { w.dismiss(); actions[index].run(); });
        }
        showPopup(anchor, w, width);
    }

    private LinearLayout popupPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackground(round(Color.WHITE, 14, 0xFFE5E8EB, 1));
        return panel;
    }

    private TextView popupAction(String label) {
        TextView v = text(label, 11, false); v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(dp(11), 0, dp(11), 0); return v;
    }

    private View divider() { View v = new View(this); v.setBackgroundColor(0xFFE8EAED); v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return v; }
    private PopupWindow popup(View content, int width) { PopupWindow w = new PopupWindow(content, width, -2, true); w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setOutsideTouchable(true); w.setElevation(dp(10)); return w; }
    private void showPopup(View anchor, PopupWindow w, int width) { w.showAsDropDown(anchor, anchor.getWidth() - width, -dp(2)); }

    private void setGridMode(boolean value) {
        gridMode = value;
        listIcon.setBackground(round(!gridMode ? GREEN_SOFT : Color.WHITE, 8, 0xFFE1E5E8, 1));
        listIcon.setColorFilter(!gridMode ? GREEN : ICON_GRAY);
        gridIcon.setBackground(round(gridMode ? GREEN_SOFT : Color.WHITE, 8, 0xFFE1E5E8, 1));
        gridIcon.setColorFilter(gridMode ? GREEN : ICON_GRAY);
        refreshContent();
    }

    private void sortItems(List<DriveItem> items) {
        if (sortMode == 1) Collections.sort(items, Comparator.comparing(x -> x.name.toLowerCase(Locale.ROOT)));
        else if (sortMode == 2) Collections.sort(items, Comparator.comparing(x -> DriveActions.shortType(x).toLowerCase(Locale.ROOT)));
        else Collections.sort(items, (a, b) -> Long.compare(b.updated, a.updated));
    }

    private void addAccount() {
        long id = db.addAccount("Новый аккаунт");
        Intent i = new Intent(this, BrowserActivity.class); i.putExtra(BrowserActivity.EXTRA_SECTION, "Library"); i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, id); i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN, true); startActivity(i);
    }
    private void login(AccountProfile a, boolean fresh) { Intent i = new Intent(this, BrowserActivity.class); i.putExtra(BrowserActivity.EXTRA_SECTION, "Library"); i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, a.id); i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN, fresh); startActivity(i); }
    private void openCategory(String category) { Intent i = new Intent(this, NativeBrowserActivity.class); i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY, category); i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID, activeId); startActivity(i); }
    private void openSync(String section) { Intent i = new Intent(this, BrowserActivity.class); i.putExtra(BrowserActivity.EXTRA_SECTION, section); i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, activeId); startActivity(i); }
    private void openSystemFiles() { try { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, 100); } catch (Exception ignored) {} }
    private void notifyRoots() { try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); } catch (Exception ignored) {} }

    private String friendlySync(long time) {
        Date d = new Date(time);
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String prefix = day.format(d).equals(day.format(new Date())) ? "Сегодня, " : new SimpleDateFormat("dd.MM.yyyy, ", Locale.getDefault()).format(d);
        return prefix + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d);
    }

    private String shortAccount(String value) {
        if (value == null || value.trim().isEmpty()) return "Аккаунт";
        String v = value.trim(); int at = v.indexOf('@'); if (at > 0) v = v.substring(0, at); return v.length() > 11 ? v.substring(0, 11) + "…" : v;
    }

    private ImageView rawIcon(int res) {
        ImageView v = new ImageView(this); v.setImageResource(res); v.setScaleType(ImageView.ScaleType.CENTER_INSIDE); v.setPadding(dp(1), dp(1), dp(1), dp(1)); return v;
    }

    private ImageView tintedIcon(int res, int tint, int innerDp, int boxDp) {
        ImageView v = new ImageView(this); v.setImageResource(res); v.setColorFilter(tint); v.setScaleType(ImageView.ScaleType.CENTER_INSIDE); int p = Math.max(0, (boxDp - innerDp) / 2); v.setPadding(dp(p), dp(p), dp(p), dp(p)); return v;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(TEXT); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp)); if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor); return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
