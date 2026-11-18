package com.gptdrive.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native semantic collection browser with stable infinite paging and batch selection. */
public final class NativeBrowserActivity extends Activity {
    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_PROJECT_ROW_ID = "project_row_id";
    public static final String EXTRA_OPEN_FILE_ID = "open_file_id";

    private static final int GREEN = 0xFF08A66D;
    private static final int GREEN_SOFT = 0xFFE5F8F1;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private static final int PAGE = 24;
    private static final int INITIAL_IMAGE_WINDOW = 68;
    private static final int SYNC_REQUEST = 3901;

    private String category;
    private long accountId;
    private long projectRowId;
    private DriveDb db;
    private DriveCollections collections;
    private LinearLayout content;
    private TextView title;
    private TextView summary;
    private EditText search;
    private ScrollView scroll;
    private LinearLayout actionBar;
    private TextView selectionCount;
    private final ArrayList<DriveItem> loaded = new ArrayList<>();
    private final Set<Long> selected = new HashSet<>();
    private final ExecutorService mutationWorker = Executors.newSingleThreadExecutor();
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshIndex = this::resetAndLoad;
    private ContentObserver indexObserver;
    private String openFileId = "";
    private int offset;
    private long total;
    private boolean loading;
    private boolean exhausted;
    private boolean initialLoaded;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = DriveDb.get(this);
        collections = new DriveCollections(db);
        category = normalizeCollection(getIntent().getStringExtra(EXTRA_CATEGORY));
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        projectRowId = getIntent().getLongExtra(EXTRA_PROJECT_ROW_ID, -1);
        openFileId = getIntent().getStringExtra(EXTRA_OPEN_FILE_ID);
        if (openFileId == null) openFileId = "";
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!openFileId.trim().isEmpty()) {
            DriveItem direct = db.byFileId(accountId, openFileId.trim());
            openFileId = "";
            if (direct != null) {
                DriveActions.open(this, direct);
                return;
            }
        }
        if (!initialLoaded) {
            initialLoaded = true;
            resetAndLoad();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (indexObserver == null) {
            indexObserver = new ContentObserver(refreshHandler) {
                @Override public void onChange(boolean selfChange) {
                    if (selected.isEmpty()) {
                        refreshHandler.removeCallbacks(refreshIndex);
                        refreshHandler.postDelayed(refreshIndex, 250);
                    }
                }
            };
            getContentResolver().registerContentObserver(IndexChangeNotifier.uri(accountId), false, indexObserver);
        }
    }

    @Override protected void onStop() {
        if (indexObserver != null) {
            getContentResolver().unregisterContentObserver(indexObserver);
            indexObserver = null;
        }
        refreshHandler.removeCallbacks(refreshIndex);
        super.onStop();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SYNC_REQUEST) resetAndLoad();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new SoftCloudDrawable());

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(10), 0);
        TextView back = text("‹", 28, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> {
            if (!selected.isEmpty()) clearSelection(); else finish();
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(52)));
        title = text(titleFor(), 20, true);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView sync = text("Синхр.", 11, true);
        sync.setGravity(Gravity.CENTER);
        sync.setTextColor(GREEN);
        sync.setBackground(round(GREEN_SOFT, 12, 0, 0));
        sync.setOnClickListener(v -> sync());
        bar.addView(sync, new LinearLayout.LayoutParams(dp(72), dp(36)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        AccountProfile a = db.accountById(accountId);
        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(dp(14), 0, dp(14), dp(6));
        AccountBadgeView badge = new AccountBadgeView(this);
        badge.bind(a);
        meta.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView account = text(a == null ? "" : a.displayName(), 11, true);
        account.setTextColor(MUTED);
        account.setPadding(dp(7), 0, 0, 0);
        meta.addView(account, new LinearLayout.LayoutParams(0, dp(28), 1));
        summary = text("0 объектов", 11, false);
        summary.setTextColor(MUTED);
        meta.addView(summary, new LinearLayout.LayoutParams(-2, dp(28)));
        root.addView(meta);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Поиск в " + titleFor());
        search.setTextSize(12);
        search.setTextColor(TEXT);
        search.setHintTextColor(0xFF98A2B3);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(gradientRound(new int[]{0xFAFFFFFF, 0xF2F5FAFF}, 14, 0xDDE1E5E8, 1));
        search.setElevation(dp(1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(44));
        sp.setMargins(dp(14), 0, dp(14), dp(9));
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { renderFromLoaded(); }
            public void afterTextChanged(Editable e) {}
        });

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            View child = scroll.getChildAt(0);
            if (child == null || exhausted || loading) return;
            int remain = child.getHeight() - (scroll.getHeight() + sy);
            if (remain < dp(520)) loadNextPage(false);
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), 0, dp(14), dp(26));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        actionBar = buildActionBar();
        actionBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(62));
        actionParams.setMargins(dp(10), dp(2), dp(10), dp(7));
        root.addView(actionBar, actionParams);
        setContentView(root);
    }

    private LinearLayout buildActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(6), dp(5), dp(6), dp(5));
        bar.setBackground(gradientRound(new int[]{0xFFFFFFFF, 0xF5F1FAF7}, 22, 0xDDE1E5E8, 1));
        bar.setElevation(dp(5));

        selectionCount = text("0", 13, true);
        selectionCount.setGravity(Gravity.CENTER);
        bar.addView(selectionCount, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(divider(), new LinearLayout.LayoutParams(dp(1), dp(30)));

        View download = action(ViewerActionIconView.DOWNLOAD, "Скачать", TEXT, false);
        download.setOnClickListener(v -> batchDownload());
        bar.addView(download, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(divider(), new LinearLayout.LayoutParams(dp(1), dp(30)));

        View delete = action(ViewerActionIconView.TRASH, "В корзину", 0xFFE02D3C, false);
        delete.setOnClickListener(v -> deleteSelectedSafely());
        bar.addView(delete, new LinearLayout.LayoutParams(0, dp(48), 1));
        return bar;
    }

    private View action(int iconType, String label, int color, boolean circle) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(circle ? 7 : 5), 0, dp(circle ? 7 : 5), 0);
        box.setBackground(circle ? round(0xFFF1F4F6, 22, 0, 0) : null);
        ViewerActionIconView icon = new ViewerActionIconView(this, iconType);
        icon.setIconColor(color);
        box.addView(icon, new LinearLayout.LayoutParams(dp(25), dp(25)));
        if (!label.isEmpty()) {
            TextView t = text(label, 11, true);
            t.setTextColor(color);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(5), 0, 0, 0);
            box.addView(t, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        return box;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x1F667085);
        return v;
    }

    private void resetAndLoad() {
        loaded.clear();
        selected.clear();
        offset = 0;
        exhausted = false;
        loading = false;
        total = totalCount();
        updateSummary();
        content.removeAllViews();
        actionBar.setVisibility(View.GONE);
        title.setText(titleFor());
        loadNextPage(true);
    }

    private void loadNextPage(boolean initial) {
        if (loading || exhausted) return;
        loading = true;
        int requestLimit = initial && isImageOnlyCollection() ? INITIAL_IMAGE_WINDOW : PAGE;
        List<DriveItem> page = fetchPage(offset, requestLimit);
        if (page.isEmpty()) {
            exhausted = true;
            loading = false;
            if (loaded.isEmpty()) empty();
            return;
        }
        int oldSize = loaded.size();
        loaded.addAll(page);
        offset += page.size();
        if (page.size() < requestLimit || loaded.size() >= total) exhausted = true;
        loading = false;
        updateSummary();

        if (searchText().isEmpty() && !initial) appendRendered(page);
        else renderFromLoaded();
        if (!isImageOnlyCollection() && !exhausted && loaded.size() == oldSize + page.size() && loaded.size() < PAGE * 2 && scroll.getHeight() > 0)
            scroll.post(() -> {
                View child = scroll.getChildAt(0);
                if (child != null && child.getHeight() <= scroll.getHeight() + dp(300)) loadNextPage(false);
            });
    }

    private List<DriveItem> fetchPage(int start, int limit) {
        if (projectRowId > 0) return collections.projectFiles(accountId, projectRowId, limit, start);
        if (DriveCollections.FILES.equals(category) || DriveCollections.IMAGES.equals(category) ||
                DriveCollections.GENERATED.equals(category) || DriveCollections.ARCHIVED.equals(category))
            return collections.list(accountId, category, limit, start);
        List<DriveItem> all = db.list(accountId, category, Math.min(5000, start + limit));
        int from = Math.min(start, all.size());
        return new ArrayList<>(all.subList(from, all.size()));
    }

    private long totalCount() {
        if (projectRowId > 0) return collections.projectFileCount(accountId, projectRowId);
        if (DriveCollections.FILES.equals(category) || DriveCollections.IMAGES.equals(category) ||
                DriveCollections.GENERATED.equals(category) || DriveCollections.ARCHIVED.equals(category))
            return collections.count(accountId, category);
        return db.count(accountId, category);
    }

    private void renderFromLoaded() {
        if (content == null) return;
        content.removeAllViews();
        ArrayList<DriveItem> shown = filtered(loaded);
        if (shown.isEmpty()) {
            if (loaded.isEmpty() || !searchText().isEmpty()) empty();
            return;
        }
        appendRendered(shown);
    }

    private void appendRendered(List<DriveItem> items) {
        if (isImageOnlyCollection()) appendImages(items); else appendList(items);
    }

    private void appendImages(List<DriveItem> items) {
        GridLayout grid;
        if (content.getChildCount() > 0 && content.getChildAt(content.getChildCount() - 1) instanceof GridLayout) grid = (GridLayout) content.getChildAt(content.getChildCount() - 1);
        else {
            grid = new GridLayout(this);
            grid.setColumnCount(2);
            content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        }
        int width = (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2;
        for (DriveItem x : items) if (DriveActions.isImage(x)) addImageCell(grid, x, width);
    }

    private void addImageCell(GridLayout grid, DriveItem x, int width) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(5), dp(5), dp(5), dp(8));
        applySelectionBackground(cell, x);
        cell.setClickable(true);
        cell.setOnClickListener(v -> onItemClick(x));
        cell.setOnLongClickListener(v -> { toggleSelection(x); return true; });

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFFF3F5F6);
        image.setImageResource(R.drawable.ic_image_24);
        image.setColorFilter(0xFFB0B8C1);
        ImageLoader.load(image, accountId, x.url);
        cell.addView(image, new LinearLayout.LayoutParams(-1, dp(138)));

        TextView name = text(x.name, 11, true);
        name.setMaxLines(2);
        name.setPadding(dp(4), dp(5), dp(4), 0);
        cell.addView(name);

        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = width;
        gp.height = dp(184);
        gp.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(cell, gp);
    }

    private void appendList(List<DriveItem> items) {
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (DriveItem x : items) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(7), dp(6), dp(7));
            applySelectionBackground(row, x);

            ImageView icon = new ImageView(this);
            if (DriveActions.isImage(x)) {
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.setBackgroundColor(0xFFF3F5F6);
                icon.setImageResource(R.drawable.ic_image_24);
                icon.setColorFilter(0xFFB0B8C1);
                ImageLoader.load(icon, accountId, x.url);
            } else {
                row.removeView(icon);
                row.addView(new FileTypeIconView(this, x), new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            if (DriveActions.isImage(x)) row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(dp(9), 0, 0, 0);
            TextView n = text(x.name, 13, true);
            n.setMaxLines(1);
            String meta = DriveActions.shortType(x);
            if (x.sizeBytes > 0) meta += " • " + MainActivity.formatBytes(x.sizeBytes);
            if (x.updated > 0) meta += " • " + df.format(x.updated);
            TextView m = text(meta, 10, false);
            m.setTextColor(MUTED);
            info.addView(n);
            info.addView(m);
            row.addView(info, new LinearLayout.LayoutParams(0, dp(50), 1));

            TextView more = text("⋮", 21, false);
            more.setGravity(Gravity.CENTER);
            more.setOnClickListener(v -> menu(v, x));
            row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(44)));
            row.setOnClickListener(v -> onItemClick(x));
            row.setOnLongClickListener(v -> { toggleSelection(x); return true; });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(68));
            rp.setMargins(0, 0, 0, dp(5));
            content.addView(row, rp);
        }
    }

    private void onItemClick(DriveItem item) {
        if (!selected.isEmpty()) toggleSelection(item); else DriveActions.open(this, item);
    }

    private void toggleSelection(DriveItem item) {
        if (selected.contains(item.id)) selected.remove(item.id); else selected.add(item.id);
        updateSelectionUi();
        renderFromLoaded();
    }

    private void clearSelection() {
        selected.clear();
        updateSelectionUi();
        renderFromLoaded();
    }

    private void updateSelectionUi() {
        int n = selected.size();
        actionBar.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        selectionCount.setText(String.valueOf(n));
        title.setText(n == 0 ? titleFor() : "Выбрано: " + n);
    }

    private void applySelectionBackground(View v, DriveItem x) {
        boolean active = selected.contains(x.id);
        v.setBackground(active
                ? gradientRound(new int[]{0xFFE5FAF2, 0xFFF3FFFB}, 16, GREEN, 2)
                : gradientRound(new int[]{0xFAFFFFFF, 0xF3F7FAFF}, 16, 0xDDE4E8EB, 1));
        v.setElevation(dp(active ? 2 : 1));
    }

    private void batchDownload() {
        List<DriveItem> items = selectedItems();
        if (items.isEmpty()) return;
        for (DriveItem x : items) DriveActions.download(this, x);
        Toast.makeText(this, "Скачивание: " + items.size() + " файлов", Toast.LENGTH_SHORT).show();
        clearSelection();
    }

    private void deleteSelectedSafely() {
        List<DriveItem> items = selectedItems();
        if (items.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Переместить в «Удалённые»?")
                .setMessage("Выбрано: " + items.size() + ". Материалы будут перемещены в нативный раздел «Удалённые» и останутся доступными для восстановления до окончательной очистки.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("В корзину", (d, w) -> moveSelectedToTrash(items))
                .show();
    }

    private void moveSelectedToTrash(List<DriveItem> items) {
        if (items == null || items.isEmpty()) return;
        actionBar.setEnabled(false);
        Toast.makeText(this, "Перемещаю в «Удалённые»…", Toast.LENGTH_SHORT).show();
        mutationWorker.execute(() -> {
            LibraryMutationClient client = new LibraryMutationClient(this, accountId);
            int ok = 0;
            String firstError = "";
            for (DriveItem x : items) {
                try {
                    client.moveToTrash(x);
                    ok++;
                } catch (Exception e) {
                    if (firstError.isEmpty()) firstError = e.getMessage() == null ? "ошибка удаления" : e.getMessage();
                }
            }
            int success = ok;
            String error = firstError;
            runOnUiThread(() -> {
                actionBar.setEnabled(true);
                resetAndLoad();
                if (success == items.size()) Toast.makeText(this, "Перемещено в «Удалённые»: " + success, Toast.LENGTH_SHORT).show();
                else Toast.makeText(this, "Удалено: " + success + "/" + items.size() + (error.isEmpty() ? "" : " · " + error), Toast.LENGTH_LONG).show();
            });
        });
    }

    private List<DriveItem> selectedItems() {
        ArrayList<DriveItem> out = new ArrayList<>();
        for (DriveItem x : loaded) if (selected.contains(x.id)) out.add(x);
        return out;
    }

    private void menu(View anchor, DriveItem item) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Открыть");
        m.getMenu().add(0, 2, 1, "Скачать");
        m.getMenu().add(0, 3, 2, "Выделить");
        m.getMenu().add(0, 4, 3, "В корзину");
        m.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) DriveActions.open(this, item);
            else if (mi.getItemId() == 2) DriveActions.download(this, item);
            else if (mi.getItemId() == 3) toggleSelection(item);
            else {
                ArrayList<DriveItem> one = new ArrayList<>();
                one.add(item);
                new AlertDialog.Builder(this)
                        .setTitle("Переместить в «Удалённые»?")
                        .setMessage(item.name)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("В корзину", (d, w) -> moveSelectedToTrash(one))
                        .show();
            }
            return true;
        });
        m.show();
    }

    private ArrayList<DriveItem> filtered(List<DriveItem> items) {
        String q = searchText();
        ArrayList<DriveItem> shown = new ArrayList<>();
        for (DriveItem x : items) {
            String name = x.name == null ? "" : x.name.toLowerCase(Locale.ROOT);
            String type = DriveActions.shortType(x).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || name.contains(q) || type.contains(q)) shown.add(x);
        }
        return shown;
    }

    private String searchText() {
        return search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
    }

    private boolean isImageOnlyCollection() { return DriveCollections.IMAGES.equals(category); }

    private void empty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(20), dp(54), dp(20), dp(30));
        ImageView icon = new ImageView(this);
        icon.setImageResource(isImageOnlyCollection() ? R.drawable.ic_image_24 : R.drawable.ic_folder_24);
        icon.setColorFilter(0xFF98A2B3);
        box.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView h = text(searchText().isEmpty() ? "Пока пусто" : "Ничего не найдено", 18, true);
        h.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(0, dp(12), 0, 0);
        box.addView(h, hp);
        if (searchText().isEmpty()) {
            TextView d = text("Запусти синхронизацию выбранного аккаунта.", 11, false);
            d.setGravity(Gravity.CENTER);
            d.setTextColor(MUTED);
            d.setPadding(0, dp(5), 0, dp(14));
            box.addView(d);
        }
        content.addView(box);
    }

    private void sync() {
        AccountProfile account = db.accountById(accountId);
        if (account == null || !AccountAuth.connected(account)) {
            Intent login = new Intent(this, ChatGptAuthActivity.class);
            login.putExtra(ChatGptAuthActivity.EXTRA_ACCOUNT_ID, accountId);
            login.putExtra(ChatGptAuthActivity.EXTRA_NEW_ACCOUNT, false);
            startActivity(login);
            return;
        }
        SyncForegroundService.start(this, accountId);
        Toast.makeText(this, "Индексация запущена. Файлы появятся по мере загрузки.", Toast.LENGTH_LONG).show();
    }

    private void updateSummary() { summary.setText(loaded.size() + " / " + total + " объектов"); }

    private String titleFor() {
        if (projectRowId > 0) return collections.projectName(accountId, projectRowId);
        if (DriveCollections.FILES.equals(category)) return "Файлы";
        if (DriveCollections.IMAGES.equals(category)) return "Картинки";
        if (DriveCollections.GENERATED.equals(category)) return "Сгенерированные";
        if (DriveCollections.ARCHIVED.equals(category)) return "Архив";
        return category;
    }

    private String normalizeCollection(String c) {
        if (c == null || c.trim().isEmpty() || "Library".equals(c)) return DriveCollections.FILES;
        if ("Images".equals(c)) return DriveCollections.IMAGES;
        if ("Generated".equals(c)) return DriveCollections.GENERATED;
        if ("Archived".equals(c)) return DriveCollections.ARCHIVED;
        if ("Projects".equals(c)) return "Projects";
        return c;
    }

    private TextView text(String v, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(v);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private GradientDrawable gradientRound(int[] colors, int radius, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(dp(radius));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        refreshHandler.removeCallbacks(refreshIndex);
        mutationWorker.shutdownNow();
        super.onDestroy();
    }
}
