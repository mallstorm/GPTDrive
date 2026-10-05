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

public final class MainActivity extends Activity {
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF08A66D;
    private static final int GREEN_SOFT = 0xFFE5F8F1;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private DriveDb db;
    private long activeId;
    private TextView accountChip, storageValue, planLine, syncLine, sectionTitle, sectionCount, sortButton, listButton, gridButton;
    private StorageRingView storageRing;
    private LinearLayout contentContainer;
    private boolean gridMode = false;
    private int sortMode = 0;
    private String searchQuery = "";

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

        FrameLayout stage = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(22));
        body.addView(buildStorageCard());
        body.addView(buildChips());
        body.addView(buildSectionControls());
        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(contentContainer, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        stage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView fab = text("+", 31, false);
        fab.setGravity(Gravity.CENTER);
        fab.setTextColor(Color.WHITE);
        fab.setBackground(round(GREEN, 31, 0, 0));
        fab.setElevation(dp(8));
        fab.setOnClickListener(this::showFabMenu);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.END | Gravity.BOTTOM);
        fp.setMargins(0, 0, dp(18), dp(18));
        stage.addView(fab, fp);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, dp(66)));
        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(8), 0);

        ImageView menu = imageIcon(R.drawable.ic_menu_24, TEXT, 22);
        menu.setOnClickListener(this::showMainMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(44)));

        ImageView logo = imageIcon(R.drawable.ic_cloud_24, GREEN, 25);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(32), dp(44)));

        TextView title = text("GPT Drive", 21, true);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(5), 0, dp(4), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));

        accountChip = text("Аккаунт ▾", 12, true);
        accountChip.setGravity(Gravity.CENTER);
        accountChip.setSingleLine(true);
        accountChip.setEllipsize(TextUtils.TruncateAt.END);
        accountChip.setPadding(dp(10), 0, dp(10), 0);
        accountChip.setBackground(round(GREEN_SOFT, 20, 0, 0));
        accountChip.setOnClickListener(this::showAccountSwitcher);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(112), dp(40));
        ap.setMargins(dp(3), 0, dp(3), 0);
        bar.addView(accountChip, ap);

        ImageView search = imageIcon(R.drawable.ic_search_24, TEXT, 22);
        search.setOnClickListener(v -> showSearch());
        bar.addView(search, new LinearLayout.LayoutParams(dp(40), dp(44)));
        return bar;
    }

    private View buildStorageCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(11), dp(10));
        card.setBackground(round(Color.WHITE, 20, 0xFFE5E8EB, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(126));
        cp.setMargins(0, dp(2), 0, dp(10));
        card.setLayoutParams(cp);

        storageRing = new StorageRingView(this);
        card.addView(storageRing, new LinearLayout.LayoutParams(dp(92), dp(92)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        TextView title = text("Использовано места", 14, true);
        storageValue = text("Объём не определён", 18, true);
        storageValue.setTextColor(GREEN);
        storageValue.setSingleLine(true);
        storageValue.setEllipsize(TextUtils.TruncateAt.END);
        planLine = text("План: Не определён", 11, true);
        planLine.setPadding(0, dp(4), 0, 0);
        syncLine = text("Не синхронизировано", 11, false);
        syncLine.setTextColor(MUTED);
        syncLine.setPadding(0, dp(3), 0, 0);
        info.addView(title);
        info.addView(storageValue);
        info.addView(planLine);
        info.addView(syncLine);
        card.addView(info, new LinearLayout.LayoutParams(0, -1, 1));

        TextView arrow = text("›", 29, false);
        arrow.setTextColor(0xFF475467);
        arrow.setGravity(Gravity.CENTER);
        arrow.setOnClickListener(v -> openSync("Library"));
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
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(chip("Все", true, v -> { searchQuery = ""; refreshContentOnly(); }));
        row.addView(chip("Библиотека", false, v -> openCategory("Library")));
        row.addView(chip("Картинки", false, v -> openCategory("Images")));
        row.addView(chip("Проекты", false, v -> openCategory("Projects")));
        row.addView(chip("Архив", false, v -> openCategory("Archived")));
        hsv.addView(row, new HorizontalScrollView.LayoutParams(-2, dp(40)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(44));
        hp.setMargins(0, 0, 0, dp(4));
        hsv.setLayoutParams(hp);
        return hsv;
    }

    private View buildSectionControls() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(5));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        sectionTitle = text("Облако / Все файлы", 19, true);
        sectionTitle.setSingleLine(true);
        sectionCount = text("0 объектов", 11, false);
        sectionCount.setTextColor(MUTED);
        labels.addView(sectionTitle);
        labels.addView(sectionCount);
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(56), 1));

        sortButton = text("По дате ▾", 11, false);
        sortButton.setTextColor(MUTED);
        sortButton.setGravity(Gravity.CENTER);
        sortButton.setOnClickListener(this::showSortMenu);
        row.addView(sortButton, new LinearLayout.LayoutParams(dp(76), dp(40)));

        listButton = viewToggle("☷", true);
        listButton.setOnClickListener(v -> setGridMode(false));
        row.addView(listButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        gridButton = viewToggle("▦", false);
        gridButton.setOnClickListener(v -> setGridMode(true));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(40), dp(40));
        gp.setMargins(dp(4), 0, 0, 0);
        row.addView(gridButton, gp);
        return row;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(6), dp(4), dp(6), dp(4));
        nav.setBackground(round(Color.WHITE, 20, 0xFFE3E7EA, 1));
        nav.addView(navItem(R.drawable.ic_folder_24, "Файлы", true, v -> {}), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_image_24, "Картинки", false, v -> openCategory("Images")), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_folder_24, "Проекты", false, v -> openCategory("Projects")), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_account_24, "Аккаунты", false, v -> startActivity(new Intent(this, AccountManagerActivity.class))), new LinearLayout.LayoutParams(0, -1, 1));
        return nav;
    }

    private View navItem(int res, String label, boolean active, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        if (active) box.setBackground(round(GREEN_SOFT, 16, 0, 0));
        ImageView icon = imageIcon(res, active ? GREEN : 0xFF475467, 22);
        TextView t = text(label, 10, active);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(active ? GREEN : 0xFF475467);
        box.addView(icon, new LinearLayout.LayoutParams(-1, dp(30)));
        box.addView(t, new LinearLayout.LayoutParams(-1, dp(20)));
        box.setOnClickListener(click);
        return box;
    }

    private TextView chip(String label, boolean active, View.OnClickListener click) {
        TextView v = text(label, 11, active);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setPadding(dp(8), 0, dp(8), 0);
        v.setBackground(round(active ? GREEN_SOFT : Color.WHITE, 18, active ? 0xFF76D7B1 : 0xFFE1E5E8, 1));
        v.setTextColor(active ? 0xFF06764E : TEXT);
        v.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(36));
        p.setMargins(0, 0, dp(5), 0);
        v.setLayoutParams(p);
        return v;
    }

    private TextView viewToggle(String symbol, boolean active) {
        TextView v = text(symbol, 20, false);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(active ? GREEN : 0xFF475467);
        v.setBackground(round(active ? GREEN_SOFT : Color.WHITE, 11, 0xFFE1E5E8, 1));
        return v;
    }

    private void refresh() {
        AccountProfile a = db.activeAccount();
        if (a == null) return;
        activeId = a.id;
        accountChip.setText("● " + shortAccount(a.displayName()) + " ▾");
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
            storageValue.setText(known > 0 ? formatBytes(known) + " индексировано" : "Объём не определён");
        }
        planLine.setText("План: " + a.planLabel() + "  •  " + db.countAll(a.id) + " элементов");
        if (!a.hasSession()) {
            syncLine.setText("○ Нужен вход в ChatGPT — нажми здесь");
            syncLine.setTextColor(0xFF8A5A00);
        } else if (a.lastSync > 0) {
            syncLine.setText("● Синхронизировано • " + formatSync(a.lastSync));
            syncLine.setTextColor(MUTED);
        } else {
            syncLine.setText("● Аккаунт подключён • нужна синхронизация");
            syncLine.setTextColor(MUTED);
        }
    }

    private void refreshContentOnly() {
        if (contentContainer == null || activeId <= 0) return;
        List<DriveItem> items = new ArrayList<>(db.listDistinct(activeId, 120));
        if (!searchQuery.trim().isEmpty()) {
            String q = searchQuery.toLowerCase(Locale.ROOT);
            ArrayList<DriveItem> filtered = new ArrayList<>();
            for (DriveItem x : items) if (x.name.toLowerCase(Locale.ROOT).contains(q) || x.category.toLowerCase(Locale.ROOT).contains(q) || DriveActions.shortType(x).toLowerCase(Locale.ROOT).contains(q)) filtered.add(x);
            items = filtered;
            sectionTitle.setText("Поиск / " + searchQuery);
        } else sectionTitle.setText("Облако / Все файлы");
        sortItems(items);
        sectionCount.setText(items.size() + " объектов");
        contentContainer.removeAllViews();
        if (gridMode) renderGrid(items); else renderList(items);
    }

    private void renderList(List<DriveItem> items) {
        addFolderRow(0xFFFFC145, "Library", db.count(activeId, "Library"), "Library");
        addFolderRow(0xFF3B9DF5, "Images", db.count(activeId, "Images"), "Images");
        addFolderRow(0xFF42C894, "Projects", db.count(activeId, "Projects"), "Projects");
        addVirtualFolderRow("↑", 0xFFFF5164, "Uploads", "Загруженные файлы");
        addVirtualFolderRow("✦", 0xFFFF8A1D, "Generated", "Сгенерированные файлы");
        addFolderRow(0xFF7957E8, "Archived", db.count(activeId, "Archived"), "Archived");
        for (DriveItem x : items) addFileRow(x);
        if (items.isEmpty()) addEmpty();
    }

    private void renderGrid(List<DriveItem> items) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2;
        addGridFolder(grid, w, "Library", 0xFFFFC145, db.count(activeId, "Library"), "Library");
        addGridFolder(grid, w, "Images", 0xFF3B9DF5, db.count(activeId, "Images"), "Images");
        addGridFolder(grid, w, "Projects", 0xFF42C894, db.count(activeId, "Projects"), "Projects");
        addGridFolder(grid, w, "Archived", 0xFF7957E8, db.count(activeId, "Archived"), "Archived");
        for (DriveItem x : items) addGridFile(grid, w, x);
        contentContainer.addView(grid);
        if (items.isEmpty()) addEmpty();
    }

    private void addFolderRow(int color, String name, long count, String category) {
        LinearLayout row = baseRow();
        row.addView(folderTile(color), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout info = rowInfo(name, count + " объектов");
        row.addView(info, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView more = moreButton();
        more.setOnClickListener(v -> folderMenu(v, category));
        row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(44)));
        row.setOnClickListener(v -> openCategory(category));
        addRow(row);
    }

    private void addVirtualFolderRow(String symbol, int color, String name, String subtitle) {
        LinearLayout row = baseRow();
        row.addView(symbolTile(symbol, color), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout info = rowInfo(name, subtitle);
        row.addView(info, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView more = moreButton();
        more.setOnClickListener(v -> openSync("Library"));
        row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(44)));
        row.setOnClickListener(v -> openSync("Library"));
        addRow(row);
    }

    private LinearLayout rowInfo(String name, String metaText) {
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(10), 0, 0, 0);
        TextView n = text(name, 15, true);
        n.setSingleLine(true);
        TextView meta = text(metaText, 11, false);
        meta.setTextColor(MUTED);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(n);
        info.addView(meta);
        return info;
    }

    private void addFileRow(DriveItem x) {
        LinearLayout row = baseRow();
        if (DriveActions.isImage(x)) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(round(0xFFE9ECEF, 10, 0, 0));
            ImageLoader.load(image, x.url);
            row.addView(image, new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else row.addView(fileTile(x), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout info = rowInfo(x.name, fileMeta(x));
        row.addView(info, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView more = moreButton();
        more.setOnClickListener(v -> itemMenu(v, x));
        row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(44)));
        row.setOnClickListener(v -> DriveActions.open(this, x));
        addRow(row);
    }

    private void addGridFolder(GridLayout grid, int width, String name, int color, long count, String category) {
        LinearLayout cell = gridCell();
        cell.addView(folderTile(color), new LinearLayout.LayoutParams(dp(48), dp(48)));
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
        } else cell.addView(fileTile(x), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView n = text(x.name, 12, true); n.setMaxLines(2); n.setEllipsize(TextUtils.TruncateAt.END); n.setPadding(0, dp(6), 0, 0); cell.addView(n);
        TextView meta = text(x.sizeBytes > 0 ? formatBytes(x.sizeBytes) : DriveActions.shortType(x), 10, false); meta.setTextColor(MUTED); cell.addView(meta);
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
        row.setPadding(dp(9), dp(7), dp(6), dp(7));
        row.setBackground(round(Color.WHITE, 16, 0xFFE9ECEF, 1));
        return row;
    }

    private void addRow(View row) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(68));
        p.setMargins(0, 0, 0, dp(5));
        contentContainer.addView(row, p);
    }

    private ImageView folderTile(int color) {
        ImageView v = imageIcon(R.drawable.ic_folder_24, Color.WHITE, 25);
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        v.setBackground(round(color, 11, 0, 0));
        return v;
    }

    private TextView symbolTile(String symbol, int color) {
        TextView t = text(symbol, 22, true);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Color.WHITE);
        t.setBackground(round(color, 11, 0, 0));
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
        TextView t = symbolTile(symbol, color);
        t.setTextSize(symbol.length() > 3 ? 9 : 11);
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
        box.setPadding(dp(16), dp(20), dp(16), dp(20));
        box.setBackground(round(Color.WHITE, 16, 0xFFE8EBED, 1));
        box.addView(text("Файлы ещё не проиндексированы", 15, true));
        TextView d = text("Нажми + и запусти синхронизацию Library.", 11, false);
        d.setTextColor(MUTED); d.setPadding(0, dp(5), 0, 0); box.addView(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(94)); p.setMargins(0, dp(5), 0, dp(8)); contentContainer.addView(box, p);
    }

    private void showAccountSwitcher(View anchor) {
        List<AccountProfile> accounts = db.listAccounts();
        LinearLayout panel = popupPanel();
        for (AccountProfile a : accounts) {
            long known = db.knownStorageBytes(a.id);
            String size = a.hasRemoteQuota() ? formatBytes(a.remoteUsedBytes) : (known > 0 ? formatBytes(known) : "Объём —");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(9), dp(14), dp(9));
            TextView n = text((a.active ? "✓  " : "") + a.displayName(), 13, true);
            n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
            TextView m = text(a.planLabel() + "  •  " + size, 10, false); m.setTextColor(MUTED);
            row.addView(n); row.addView(m);
            panel.addView(row, new LinearLayout.LayoutParams(-1, dp(55)));
        }
        panel.addView(divider());
        panel.addView(popupAction("＋  Добавить аккаунт"));
        panel.addView(popupAction("Управление аккаунтами"));
        PopupWindow w = popup(panel, dp(286));
        int index = 0;
        for (int i = 0; i < accounts.size(); i++) {
            final AccountProfile target = accounts.get(i);
            View row = panel.getChildAt(index++);
            row.setOnClickListener(v -> {
                w.dismiss();
                if (target.id == activeId) return;
                AccountSession.switchTo(this, target.id, () -> { notifyRoots(); refresh(); Toast.makeText(this, "Переключено: " + target.displayName(), Toast.LENGTH_SHORT).show(); });
            });
        }
        index++; // divider
        panel.getChildAt(index++).setOnClickListener(v -> { w.dismiss(); addAccount(); });
        panel.getChildAt(index).setOnClickListener(v -> { w.dismiss(); startActivity(new Intent(this, AccountManagerActivity.class)); });
        showPopup(anchor, w, dp(286));
    }

    private void showMainMenu(View anchor) {
        showActionPopup(anchor, new String[]{"Синхронизировать Library", "Routing & DNS", "Android Files", "Аккаунты"}, new Runnable[]{() -> openSync("Library"), () -> startActivity(new Intent(this, DnsSettingsActivity.class)), this::openSystemFiles, () -> startActivity(new Intent(this, AccountManagerActivity.class))}, dp(238));
    }

    private void showFabMenu(View anchor) {
        showActionPopup(anchor, new String[]{"Синхронизировать Library", "Добавить аккаунт", "Android Files"}, new Runnable[]{() -> openSync("Library"), this::addAccount, this::openSystemFiles}, dp(230));
    }

    private void showSortMenu(View anchor) {
        showActionPopup(anchor, new String[]{"По дате", "По имени", "По типу"}, new Runnable[]{() -> { sortMode=0; sortButton.setText("По дате ▾"); refreshContentOnly(); }, () -> { sortMode=1; sortButton.setText("По имени ▾"); refreshContentOnly(); }, () -> { sortMode=2; sortButton.setText("По типу ▾"); refreshContentOnly(); }}, dp(160));
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

    private void showActionPopup(View anchor, String[] labels, Runnable[] actions, int width) {
        LinearLayout panel = popupPanel();
        for (String label : labels) panel.addView(popupAction(label), new LinearLayout.LayoutParams(-1, dp(46)));
        PopupWindow w = popup(panel, width);
        for (int i=0;i<labels.length;i++) {
            final int j=i;
            panel.getChildAt(i).setOnClickListener(v -> { w.dismiss(); actions[j].run(); });
        }
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

    private void showSearch() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        panel.setBackground(round(Color.WHITE, 20, 0xFFE5E8EB, 1));
        panel.addView(text("Поиск в GPT Drive", 17, true));
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint("Имя файла или тип"); input.setInputType(InputType.TYPE_CLASS_TEXT); input.setText(searchQuery);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(50)); ip.setMargins(0, dp(10), 0, dp(8)); panel.addView(input, ip);
        LinearLayout buttons = new LinearLayout(this); buttons.setGravity(Gravity.END);
        TextView reset = actionButton("Сбросить", false); TextView find = actionButton("Найти", true);
        buttons.addView(reset, new LinearLayout.LayoutParams(dp(92), dp(42))); buttons.addView(find, new LinearLayout.LayoutParams(dp(92), dp(42))); panel.addView(buttons);
        reset.setOnClickListener(v -> { searchQuery=""; d.dismiss(); refreshContentOnly(); });
        find.setOnClickListener(v -> { searchQuery=input.getText().toString().trim(); d.dismiss(); refreshContentOnly(); });
        d.setContentView(panel);
        Window win=d.getWindow(); if(win!=null){win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));win.setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.90f),-2);}
        d.setOnShowListener(x->{Window ww=d.getWindow();if(ww!=null)ww.setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.90f),WindowManager.LayoutParams.WRAP_CONTENT);});
        d.show();
    }

    private TextView actionButton(String label, boolean primary) {
        TextView v=text(label,12,true); v.setGravity(Gravity.CENTER); v.setTextColor(primary?Color.WHITE:TEXT); v.setBackground(round(primary?GREEN:0xFFF2F4F5,12,0,0)); return v;
    }

    private void setGridMode(boolean value) {
        gridMode=value;
        listButton.setBackground(round(!gridMode?GREEN_SOFT:Color.WHITE,11,0xFFE1E5E8,1));
        listButton.setTextColor(!gridMode?GREEN:0xFF475467);
        gridButton.setBackground(round(gridMode?GREEN_SOFT:Color.WHITE,11,0xFFE1E5E8,1));
        gridButton.setTextColor(gridMode?GREEN:0xFF475467);
        refreshContentOnly();
    }

    private void sortItems(List<DriveItem> items) {
        if(sortMode==1) Collections.sort(items, Comparator.comparing(x->x.name.toLowerCase(Locale.ROOT)));
        else if(sortMode==2) Collections.sort(items, Comparator.comparing(x->DriveActions.shortType(x).toLowerCase(Locale.ROOT)));
        else Collections.sort(items,(a,b)->Long.compare(b.updated,a.updated));
    }

    private void addAccount() {
        long id=db.addAccount("Новый аккаунт");
        Intent i=new Intent(this,BrowserActivity.class); i.putExtra(BrowserActivity.EXTRA_SECTION,"Library"); i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,id); i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN,true); startActivity(i);
    }

    private void login(AccountProfile a, boolean fresh) {
        Intent i=new Intent(this,BrowserActivity.class); i.putExtra(BrowserActivity.EXTRA_SECTION,"Library"); i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,a.id); i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN,fresh); startActivity(i);
    }

    private void openCategory(String category) { Intent i=new Intent(this,NativeBrowserActivity.class); i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY,category); i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID,activeId); startActivity(i); }
    private void openSync(String section) { Intent i=new Intent(this,BrowserActivity.class); i.putExtra(BrowserActivity.EXTRA_SECTION,section); i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,activeId); startActivity(i); }
    private void openSystemFiles(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,100);}catch(Exception ignored){}}
    private void notifyRoots(){try{getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY),null);}catch(Exception ignored){}}

    private String fileMeta(DriveItem x) { String left=x.sizeBytes>0?formatBytes(x.sizeBytes):DriveActions.shortType(x); DateFormat df=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT); return left+" • "+df.format(new Date(x.updated)); }
    static String formatBytes(long bytes){if(bytes<=0)return"0 Б";double b=bytes;if(b>=1099511627776d)return String.format(Locale.US,"%.2f ТБ",b/1099511627776d);if(b>=1073741824d)return String.format(Locale.US,"%.2f ГБ",b/1073741824d);if(b>=1048576d)return String.format(Locale.US,"%.1f МБ",b/1048576d);if(b>=1024d)return String.format(Locale.US,"%.1f КБ",b/1024d);return bytes+" Б";}
    private String formatSync(long time){return new SimpleDateFormat("dd.MM, HH:mm",Locale.getDefault()).format(new Date(time));}
    private String shortAccount(String value){if(value==null||value.trim().isEmpty())return"Аккаунт";String v=value.trim();int at=v.indexOf('@');if(at>0)v=v.substring(0,at);return v.length()>12?v.substring(0,12)+"…":v;}

    private ImageView imageIcon(int res,int tint,int innerDp){ImageView v=new ImageView(this);v.setImageResource(res);v.setColorFilter(tint);v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);v.setPadding(dp((44-innerDp)/2),dp((44-innerDp)/2),dp((44-innerDp)/2),dp((44-innerDp)/2));return v;}
    private TextView text(String value,int sp,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(TEXT);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radiusDp,int strokeColor,int strokeDp){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radiusDp));if(strokeDp>0)g.setStroke(dp(strokeDp),strokeColor);return g;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
