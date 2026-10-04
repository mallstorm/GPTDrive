package com.gptdrive.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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

public final class MainActivity extends Activity {
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF08A66D;
    private static final int GREEN_SOFT = 0xFFE4F8F0;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private DriveDb db;
    private long activeId;
    private TextView accountChip, storageValue, planLine, syncLine, sectionTitle, sectionCount, sortButton, listButton, gridButton;
    private StorageRingView storageRing;
    private LinearLayout contentContainer;
    private boolean gridMode = false;
    private int sortMode = 0; // 0 date, 1 name, 2 type
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

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(68)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(88));
        body.addView(buildStorageCard());
        body.addView(buildChips());
        body.addView(buildSectionHeader());
        body.addView(buildSortRow());
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(contentContainer, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, dp(76)));

        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(4), dp(10), 0);

        TextView menu = iconButton("☰", 28);
        menu.setOnClickListener(this::showMainMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView logo = text("☁", 29, true);
        logo.setTextColor(GREEN);
        logo.setGravity(Gravity.CENTER);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(48)));

        TextView title = text("GPT Drive", 26, true);
        title.setTextColor(TEXT);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));

        accountChip = text("Аккаунт  ▾", 14, true);
        accountChip.setGravity(Gravity.CENTER);
        accountChip.setMaxLines(1);
        accountChip.setEllipsize(TextUtils.TruncateAt.END);
        accountChip.setPadding(dp(14), 0, dp(14), 0);
        accountChip.setBackground(round(GREEN_SOFT, 24, 0x00000000, 0));
        accountChip.setOnClickListener(this::showAccountSwitcher);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(150), dp(46));
        ap.setMargins(dp(4), 0, dp(4), 0);
        bar.addView(accountChip, ap);

        TextView search = iconButton("⌕", 31);
        search.setOnClickListener(v -> showSearch());
        bar.addView(search, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return bar;
    }

    private View buildStorageCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        card.setBackground(round(0xFFFFFFFF, 22, 0xFFEFF1F3, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(156));
        cp.setMargins(0, dp(4), 0, dp(14));
        card.setLayoutParams(cp);

        storageRing = new StorageRingView(this);
        card.addView(storageRing, new LinearLayout.LayoutParams(dp(118), dp(118)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(16), 0, 0, 0);
        TextView title = text("Использовано места", 17, true);
        storageValue = text("Объём пока не определён", 21, true);
        storageValue.setTextColor(GREEN);
        planLine = text("План: Не определён", 13, true);
        planLine.setPadding(0, dp(7), 0, 0);
        syncLine = text("Не синхронизировано", 12, false);
        syncLine.setTextColor(MUTED);
        syncLine.setPadding(0, dp(5), 0, 0);
        info.addView(title);
        info.addView(storageValue);
        info.addView(planLine);
        info.addView(syncLine);
        card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));

        TextView arrow = text("›", 34, false);
        arrow.setTextColor(0xFF475467);
        arrow.setGravity(Gravity.CENTER);
        arrow.setOnClickListener(v -> openSync("Library"));
        card.addView(arrow, new LinearLayout.LayoutParams(dp(34), -1));
        return card;
    }

    private View buildChips() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(chip("▰  Все", true, v -> { searchQuery = ""; refreshContentOnly(); }));
        row.addView(chip("▤  Библиотека", false, v -> openCategory("Library")));
        row.addView(chip("▣  Картинки", false, v -> openCategory("Images")));
        row.addView(chip("□  Проекты", false, v -> openCategory("Projects")));
        row.addView(chip("▥  Архив", false, v -> openCategory("Archived")));
        hsv.addView(row, new HorizontalScrollView.LayoutParams(-2, dp(54)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(58));
        hp.setMargins(0, 0, 0, dp(8));
        hsv.setLayoutParams(hp);
        return hsv;
    }

    private View buildSectionHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        sectionTitle = text("Облако / Все файлы", 23, true);
        sectionCount = text("0 объектов", 13, false);
        sectionCount.setTextColor(MUTED);
        labels.addView(sectionTitle);
        labels.addView(sectionCount);
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(58), 1));
        return row;
    }

    private View buildSortRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(8));
        sortButton = text("⇅  По дате  ▾", 14, false);
        sortButton.setTextColor(MUTED);
        sortButton.setGravity(Gravity.CENTER_VERTICAL);
        sortButton.setOnClickListener(this::showSortMenu);
        row.addView(sortButton, new LinearLayout.LayoutParams(0, dp(46), 1));

        listButton = viewToggle("☷", true);
        listButton.setOnClickListener(v -> setGridMode(false));
        row.addView(listButton, new LinearLayout.LayoutParams(dp(50), dp(46)));
        gridButton = viewToggle("▦", false);
        gridButton.setOnClickListener(v -> setGridMode(true));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(50), dp(46));
        gp.setMargins(dp(6), 0, 0, 0);
        row.addView(gridButton, gp);
        return row;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(5), dp(8), dp(5));
        nav.setBackground(round(0xFFFFFFFF, 24, 0xFFE7EAEC, 1));
        nav.addView(navItem("▰", "Файлы", true, v -> {}), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("▣", "Картинки", false, v -> openCategory("Images")), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("□", "Проекты", false, v -> openCategory("Projects")), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem("♙", "Аккаунты", false, v -> startActivity(new Intent(this, AccountManagerActivity.class))), new LinearLayout.LayoutParams(0, -1, 1));
        return nav;
    }

    private View navItem(String icon, String label, boolean active, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        if (active) box.setBackground(round(GREEN_SOFT, 18, 0x00000000, 0));
        TextView i = text(icon, 24, true);
        i.setGravity(Gravity.CENTER);
        i.setTextColor(active ? GREEN : 0xFF475467);
        TextView t = text(label, 12, active);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(active ? GREEN : 0xFF475467);
        box.addView(i, new LinearLayout.LayoutParams(-1, dp(34)));
        box.addView(t, new LinearLayout.LayoutParams(-1, dp(24)));
        box.setOnClickListener(click);
        return box;
    }

    private TextView chip(String label, boolean active, View.OnClickListener click) {
        TextView v = text(label, 14, active);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(16), 0, dp(16), 0);
        v.setBackground(round(active ? GREEN_SOFT : 0xFFFFFFFF, 24, active ? 0xFF84DDBB : 0xFFE1E5E8, 1));
        v.setTextColor(active ? 0xFF06764E : TEXT);
        v.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(46));
        p.setMargins(0, 0, dp(8), 0);
        v.setLayoutParams(p);
        return v;
    }

    private TextView viewToggle(String symbol, boolean active) {
        TextView v = text(symbol, 25, false);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(active ? GREEN : 0xFF475467);
        v.setBackground(round(active ? GREEN_SOFT : 0xFFFFFFFF, 12, 0xFFE1E5E8, 1));
        return v;
    }

    private void refresh() {
        AccountProfile a = db.activeAccount();
        if (a == null) return;
        activeId = a.id;
        String name = shortAccount(a.displayName());
        accountChip.setText("●  " + name + "  ▾");
        updateStorageCard(a);
        refreshContentOnly();
    }

    private void updateStorageCard(AccountProfile a) {
        long known = db.knownStorageBytes(a.id);
        if (a.hasRemoteQuota()) {
            storageRing.setStorage(a.remoteUsedBytes, a.remoteQuotaBytes);
            storageValue.setText(formatBytes(a.remoteUsedBytes) + " / " + formatBytes(a.remoteQuotaBytes));
        } else {
            storageRing.setStorage(0, 0);
            storageValue.setText(known > 0 ? formatBytes(known) + " индексировано" : "Объём пока не определён");
        }
        planLine.setText("План: " + a.planLabel() + "  •  " + db.countAll(a.id) + " элементов");
        if (!a.hasSession()) {
            syncLine.setText("○ Нужен вход в ChatGPT");
            syncLine.setTextColor(0xFF8A5A00);
        } else if (a.lastSync > 0) {
            syncLine.setText("● Синхронизировано • " + formatSync(a.lastSync));
            syncLine.setTextColor(MUTED);
        } else {
            syncLine.setText("● Аккаунт подключён • синхронизация не завершена");
            syncLine.setTextColor(MUTED);
        }
    }

    private void refreshContentOnly() {
        if (contentContainer == null || activeId <= 0) return;
        List<DriveItem> items = new ArrayList<>(db.listDistinct(activeId, 120));
        if (!searchQuery.trim().isEmpty()) {
            String q = searchQuery.toLowerCase(Locale.ROOT);
            ArrayList<DriveItem> filtered = new ArrayList<>();
            for (DriveItem x : items) {
                if (x.name.toLowerCase(Locale.ROOT).contains(q) || x.category.toLowerCase(Locale.ROOT).contains(q) || DriveActions.shortType(x).toLowerCase(Locale.ROOT).contains(q)) filtered.add(x);
            }
            items = filtered;
            sectionTitle.setText("Поиск / " + searchQuery);
        } else {
            sectionTitle.setText("Облако / Все файлы");
        }
        sortItems(items);
        sectionCount.setText(items.size() + " объектов");
        contentContainer.removeAllViews();
        if (gridMode) renderGrid(items); else renderList(items);
        addFab();
    }

    private void renderList(List<DriveItem> items) {
        addFolderRow("📁", 0xFFFFC145, "Library", db.count(activeId, "Library"), "Library");
        addFolderRow("📁", 0xFF3B9DF5, "Images", db.count(activeId, "Images"), "Images");
        addFolderRow("📁", 0xFF42C894, "Projects", db.count(activeId, "Projects"), "Projects");
        addVirtualFolderRow("⬆", 0xFFFF5164, "Uploads", "Загруженные файлы");
        addVirtualFolderRow("✦", 0xFFFF8A1D, "Generated", "Сгенерированные файлы");
        addFolderRow("📁", 0xFF7957E8, "Archived", db.count(activeId, "Archived"), "Archived");
        for (DriveItem x : items) addFileRow(x);
        if (items.isEmpty()) addEmpty();
    }

    private void renderGrid(List<DriveItem> items) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(46)) / 2;
        addGridFolder(grid, w, "Library", "📁", 0xFFFFC145, db.count(activeId, "Library"), "Library");
        addGridFolder(grid, w, "Images", "📁", 0xFF3B9DF5, db.count(activeId, "Images"), "Images");
        addGridFolder(grid, w, "Projects", "📁", 0xFF42C894, db.count(activeId, "Projects"), "Projects");
        addGridFolder(grid, w, "Archived", "📁", 0xFF7957E8, db.count(activeId, "Archived"), "Archived");
        for (DriveItem x : items) addGridFile(grid, w, x);
        contentContainer.addView(grid);
        if (items.isEmpty()) addEmpty();
    }

    private void addFolderRow(String icon, int color, String name, long count, String category) {
        LinearLayout row = baseRow();
        row.addView(tile(icon, color), new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(name, 17, true);
        TextView meta = text(count + " объектов", 12, false);
        meta.setTextColor(MUTED);
        info.addView(n); info.addView(meta);
        row.addView(info, new LinearLayout.LayoutParams(0, dp(60), 1));
        TextView more = iconButton("⋮", 24);
        more.setOnClickListener(v -> folderMenu(v, category));
        row.addView(more, new LinearLayout.LayoutParams(dp(40), dp(50)));
        row.setOnClickListener(v -> openCategory(category));
        addRow(row);
    }

    private void addVirtualFolderRow(String icon, int color, String name, String subtitle) {
        LinearLayout row = baseRow();
        row.addView(tile(icon, color), new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(text(name, 17, true));
        TextView meta = text(subtitle + " • классификация при синхронизации", 12, false);
        meta.setTextColor(MUTED);
        info.addView(meta);
        row.addView(info, new LinearLayout.LayoutParams(0, dp(60), 1));
        TextView more = iconButton("⋮", 24);
        more.setOnClickListener(v -> openSync("Library"));
        row.addView(more, new LinearLayout.LayoutParams(dp(40), dp(50)));
        row.setOnClickListener(v -> openSync("Library"));
        addRow(row);
    }

    private void addFileRow(DriveItem x) {
        LinearLayout row = baseRow();
        if (DriveActions.isImage(x)) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(0xFFE9ECEF, 12, 0x00000000, 0));
            ImageLoader.load(image, x.url);
            row.addView(image, new LinearLayout.LayoutParams(dp(58), dp(58)));
        } else {
            row.addView(fileTile(x), new LinearLayout.LayoutParams(dp(58), dp(58)));
        }
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(x.name, 16, true);
        n.setMaxLines(1);
        n.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = text(fileMeta(x), 12, false);
        meta.setTextColor(MUTED);
        info.addView(n); info.addView(meta);
        row.addView(info, new LinearLayout.LayoutParams(0, dp(60), 1));
        if (!DriveActions.isLink(x)) {
            TextView cloud = text("☁", 20, false);
            cloud.setTextColor(0xFF667085);
            cloud.setGravity(Gravity.CENTER);
            row.addView(cloud, new LinearLayout.LayoutParams(dp(38), dp(48)));
        }
        TextView more = iconButton("⋮", 24);
        more.setOnClickListener(v -> itemMenu(v, x));
        row.addView(more, new LinearLayout.LayoutParams(dp(38), dp(48)));
        row.setOnClickListener(v -> DriveActions.open(this, x));
        addRow(row);
    }

    private void addGridFolder(GridLayout grid, int width, String name, String icon, int color, long count, String category) {
        LinearLayout cell = gridCell();
        cell.addView(tile(icon, color), new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView n = text(name, 15, true); n.setMaxLines(1); n.setEllipsize(TextUtils.TruncateAt.END); n.setPadding(0, dp(8), 0, 0); cell.addView(n);
        TextView meta = text(count + " объектов", 11, false); meta.setTextColor(MUTED); cell.addView(meta);
        cell.setOnClickListener(v -> openCategory(category));
        addGridCell(grid, cell, width);
    }

    private void addGridFile(GridLayout grid, int width, DriveItem x) {
        LinearLayout cell = gridCell();
        if (DriveActions.isImage(x)) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(0xFFE9ECEF, 12, 0x00000000, 0));
            ImageLoader.load(image, x.url);
            cell.addView(image, new LinearLayout.LayoutParams(-1, dp(92)));
        } else {
            cell.addView(fileTile(x), new LinearLayout.LayoutParams(dp(58), dp(58)));
        }
        TextView n = text(x.name, 13, true); n.setMaxLines(2); n.setEllipsize(TextUtils.TruncateAt.END); n.setPadding(0, dp(7), 0, 0); cell.addView(n);
        TextView meta = text(x.sizeBytes > 0 ? formatBytes(x.sizeBytes) : DriveActions.shortType(x), 11, false); meta.setTextColor(MUTED); cell.addView(meta);
        cell.setOnClickListener(v -> DriveActions.open(this, x));
        cell.setOnLongClickListener(v -> { itemMenu(v, x); return true; });
        addGridCell(grid, cell, width);
    }

    private LinearLayout gridCell() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(12), dp(12), dp(12), dp(12));
        cell.setBackground(round(0xFFFFFFFF, 18, 0xFFE8EBED, 1));
        return cell;
    }

    private void addGridCell(GridLayout grid, View cell, int width) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = width;
        p.height = dp(170);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(cell, p);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(8), dp(9));
        row.setBackground(round(0xFFFFFFFF, 17, 0xFFE9ECEF, 1));
        return row;
    }

    private void addRow(View row) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(78));
        p.setMargins(0, 0, 0, dp(6));
        contentContainer.addView(row, p);
    }

    private TextView tile(String symbol, int color) {
        TextView t = text(symbol, 25, true);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFFFFFFF);
        t.setBackground(round(color, 12, 0x00000000, 0));
        return t;
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
        TextView t = tile(symbol, color);
        t.setTextSize(symbol.length() > 3 ? 10 : 12);
        return t;
    }

    private void addEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(20), dp(34), dp(20), dp(34));
        box.setBackground(round(0xFFFFFFFF, 18, 0xFFE8EBED, 1));
        box.addView(text("Файлы ещё не проиндексированы", 17, true));
        TextView d = text("Нажми + и запусти синхронизацию Library.", 13, false);
        d.setTextColor(MUTED); d.setPadding(0, dp(6), 0, 0); box.addView(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(8), 0, dp(8)); contentContainer.addView(box, p);
    }

    private void addFab() {
        TextView fab = text("+", 34, false);
        fab.setGravity(Gravity.CENTER);
        fab.setTextColor(0xFFFFFFFF);
        fab.setBackground(round(GREEN, 34, 0x00000000, 0));
        fab.setElevation(dp(8));
        fab.setOnClickListener(this::showFabMenu);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(68), dp(68));
        p.gravity = Gravity.END;
        p.setMargins(0, dp(10), dp(6), 0);
        contentContainer.addView(fab, p);
    }

    private void showAccountSwitcher(View anchor) {
        List<AccountProfile> accounts = db.listAccounts();
        PopupMenu menu = new PopupMenu(this, anchor);
        int id = 100;
        for (AccountProfile a : accounts) {
            long known = db.knownStorageBytes(a.id);
            String size = a.hasRemoteQuota() ? formatBytes(a.remoteUsedBytes) : (known > 0 ? formatBytes(known) : "—");
            String label = (a.active ? "✓  " : "") + a.displayName() + "  ·  " + a.planLabel() + "  ·  " + size;
            menu.getMenu().add(0, id++, 0, label);
        }
        menu.getMenu().add(0, 9000, 1, "＋ Добавить аккаунт");
        menu.getMenu().add(0, 9001, 2, "Управление аккаунтами");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 9000) { addAccount(); return true; }
            if (item.getItemId() == 9001) { startActivity(new Intent(this, AccountManagerActivity.class)); return true; }
            int index = item.getItemId() - 100;
            if (index >= 0 && index < accounts.size()) {
                AccountProfile target = accounts.get(index);
                if (target.id == activeId) return true;
                AccountSession.switchTo(this, target.id, () -> {
                    notifyRoots();
                    refresh();
                    Toast.makeText(this, "Переключено: " + target.displayName(), Toast.LENGTH_SHORT).show();
                });
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showMainMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Синхронизировать Library");
        m.getMenu().add(0, 2, 1, "Android Files");
        m.getMenu().add(0, 3, 2, "Аккаунты");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) openSync("Library");
            else if (i.getItemId() == 2) openSystemFiles();
            else if (i.getItemId() == 3) startActivity(new Intent(this, AccountManagerActivity.class));
            return true;
        });
        m.show();
    }

    private void showFabMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Синхронизировать Library");
        m.getMenu().add(0, 2, 1, "Добавить ChatGPT аккаунт");
        m.getMenu().add(0, 3, 2, "Открыть Android Files");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) openSync("Library");
            else if (i.getItemId() == 2) addAccount();
            else if (i.getItemId() == 3) openSystemFiles();
            return true;
        });
        m.show();
    }

    private void showSortMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "По дате");
        m.getMenu().add(0, 2, 1, "По имени");
        m.getMenu().add(0, 3, 2, "По типу");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) { sortMode = 0; sortButton.setText("⇅  По дате  ▾"); }
            else if (i.getItemId() == 2) { sortMode = 1; sortButton.setText("⇅  По имени  ▾"); }
            else { sortMode = 2; sortButton.setText("⇅  По типу  ▾"); }
            refreshContentOnly();
            return true;
        });
        m.show();
    }

    private void showSearch() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Имя файла или тип");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(searchQuery);
        int pad = dp(18);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(-1, dp(54)));
        new AlertDialog.Builder(this)
                .setTitle("Поиск в GPT Drive")
                .setView(wrap)
                .setPositiveButton("Найти", (d, w) -> { searchQuery = input.getText().toString().trim(); refreshContentOnly(); })
                .setNeutralButton("Сбросить", (d, w) -> { searchQuery = ""; refreshContentOnly(); })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void folderMenu(View anchor, String category) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Открыть");
        m.getMenu().add(0, 2, 1, "Синхронизировать");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) openCategory(category); else openSync(category);
            return true;
        });
        m.show();
    }

    private void itemMenu(View anchor, DriveItem item) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Открыть");
        if (!DriveActions.isLink(item)) m.getMenu().add(0, 2, 1, "Скачать");
        m.getMenu().add(0, 3, 2, "Открыть источник в ChatGPT");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) DriveActions.open(this, item);
            else if (i.getItemId() == 2) DriveActions.download(this, item);
            else {
                Intent intent = new Intent(this, BrowserActivity.class);
                intent.putExtra(BrowserActivity.EXTRA_SECTION, item.category);
                intent.putExtra(BrowserActivity.EXTRA_URL, item.url);
                intent.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, item.accountId);
                startActivity(intent);
            }
            return true;
        });
        m.show();
    }

    private void setGridMode(boolean value) {
        gridMode = value;
        listButton.setBackground(round(!gridMode ? GREEN_SOFT : 0xFFFFFFFF, 12, 0xFFE1E5E8, 1));
        listButton.setTextColor(!gridMode ? GREEN : 0xFF475467);
        gridButton.setBackground(round(gridMode ? GREEN_SOFT : 0xFFFFFFFF, 12, 0xFFE1E5E8, 1));
        gridButton.setTextColor(gridMode ? GREEN : 0xFF475467);
        refreshContentOnly();
    }

    private void sortItems(List<DriveItem> items) {
        if (sortMode == 1) Collections.sort(items, Comparator.comparing(x -> x.name.toLowerCase(Locale.ROOT)));
        else if (sortMode == 2) Collections.sort(items, Comparator.comparing(x -> DriveActions.shortType(x).toLowerCase(Locale.ROOT)));
        else Collections.sort(items, (a, b) -> Long.compare(b.updated, a.updated));
    }

    private void addAccount() {
        long id = db.addAccount("Новый аккаунт");
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, "Library");
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, id);
        i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN, true);
        startActivity(i);
    }

    private void openCategory(String category) {
        Intent i = new Intent(this, NativeBrowserActivity.class);
        i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY, category);
        i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID, activeId);
        startActivity(i);
    }

    private void openSync(String section) {
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, section);
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, activeId);
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

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); }
        catch (Exception ignored) {}
    }

    private String fileMeta(DriveItem x) {
        String left = x.sizeBytes > 0 ? formatBytes(x.sizeBytes) : DriveActions.shortType(x);
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        return left + "  •  " + df.format(new Date(x.updated));
    }

    static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 Б";
        double b = bytes;
        if (b >= 1024d * 1024d * 1024d * 1024d) return String.format(Locale.US, "%.2f ТБ", b / (1024d * 1024d * 1024d * 1024d));
        if (b >= 1024d * 1024d * 1024d) return String.format(Locale.US, "%.2f ГБ", b / (1024d * 1024d * 1024d));
        if (b >= 1024d * 1024d) return String.format(Locale.US, "%.1f МБ", b / (1024d * 1024d));
        if (b >= 1024d) return String.format(Locale.US, "%.1f КБ", b / 1024d);
        return bytes + " Б";
    }

    private String formatSync(long time) {
        return new SimpleDateFormat("dd.MM, HH:mm", Locale.getDefault()).format(new Date(time));
    }

    private String shortAccount(String value) {
        if (value == null || value.trim().isEmpty()) return "Аккаунт";
        String v = value.trim();
        int at = v.indexOf('@');
        if (at > 0) v = v.substring(0, at);
        return v.length() > 18 ? v.substring(0, 18) + "…" : v;
    }

    private TextView iconButton(String symbol, int sp) {
        TextView t = text(symbol, sp, false);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(TEXT);
        return t;
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
