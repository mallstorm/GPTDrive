package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native folder browser. Images are paged so entering the folder never downloads the whole cloud. */
public final class NativeBrowserActivity extends Activity {
    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_ACCOUNT_ID = "account_id";

    private static final int GREEN = 0xFF08A66D;
    private static final int GREEN_SOFT = 0xFFE5F8F1;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private static final int IMAGE_PAGE = 12;
    private static final int SYNC_REQUEST = 3901;
    private static final long AUTO_PREVIEW_MAX = 1024L * 1024L;

    private String category;
    private long accountId;
    private DriveDb db;
    private LinearLayout content;
    private TextView summary;
    private EditText search;
    private List<DriveItem> all = new ArrayList<>();
    private boolean initialLoaded;

    private GridLayout imageGrid;
    private TextView imageMore;
    private List<DriveItem> filteredImages = new ArrayList<>();
    private int imageShown;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        category = getIntent().getStringExtra(EXTRA_CATEGORY);
        if (category == null) category = "Library";
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        // Returning from the full-screen viewer must not rebuild the grid and erase previews.
        if (!initialLoaded) {
            initialLoaded = true;
            reload();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SYNC_REQUEST) reload();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(10), 0);
        TextView back = text("‹", 28, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(52)));
        TextView title = text(titleFor(category), 20, true);
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
        TextView account = text(a == null ? "" : a.displayName(), 11, true);
        account.setTextColor(MUTED);
        meta.addView(account, new LinearLayout.LayoutParams(0, dp(28), 1));
        summary = text("0 объектов", 11, false);
        summary.setTextColor(MUTED);
        meta.addView(summary, new LinearLayout.LayoutParams(-2, dp(28)));
        root.addView(meta);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Поиск в " + titleFor(category));
        search.setTextSize(12);
        search.setTextColor(TEXT);
        search.setHintTextColor(0xFF98A2B3);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(Color.WHITE, 14, 0xFFE1E5E8, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(44));
        sp.setMargins(dp(14), 0, dp(14), dp(9));
        root.addView(search, sp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { render(); }
            public void afterTextChanged(Editable e) {}
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), 0, dp(14), dp(22));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void reload() {
        // Local metadata query only. Large folders are rendered page-by-page below.
        all = db.list(accountId, category, 5000);
        long files = 0;
        for (DriveItem x : all) if (!DriveActions.isLink(x)) files++;
        summary.setText(all.size() + " объектов" + (files != all.size() ? " • " + files + " файлов" : ""));
        render();
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        ArrayList<DriveItem> shown = new ArrayList<>();
        for (DriveItem x : all) {
            String name = x.name == null ? "" : x.name.toLowerCase(Locale.ROOT);
            String type = DriveActions.shortType(x).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || name.contains(q) || type.contains(q)) shown.add(x);
        }
        if (shown.isEmpty()) { empty(); return; }
        if ("Images".equals(category)) renderImages(shown); else renderList(shown);
    }

    private void renderImages(List<DriveItem> items) {
        filteredImages = new ArrayList<>();
        for (DriveItem x : items) if (DriveActions.isImage(x)) filteredImages.add(x);
        if (filteredImages.isEmpty()) { empty(); return; }

        imageShown = 0;
        imageGrid = new GridLayout(this);
        imageGrid.setColumnCount(2);
        content.addView(imageGrid);

        imageMore = text("", 11, true);
        imageMore.setGravity(Gravity.CENTER);
        imageMore.setTextColor(GREEN);
        imageMore.setBackground(round(GREEN_SOFT, 13, 0, 0));
        imageMore.setOnClickListener(v -> appendImagePage());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(46));
        mp.setMargins(dp(3), dp(8), dp(3), 0);
        content.addView(imageMore, mp);

        appendImagePage();
    }

    private void appendImagePage() {
        if (imageGrid == null || filteredImages == null) return;
        int end = Math.min(filteredImages.size(), imageShown + IMAGE_PAGE);
        int width = (getResources().getDisplayMetrics().widthPixels - dp(40)) / 2;
        for (int i = imageShown; i < end; i++) addImageCell(filteredImages.get(i), width);
        imageShown = end;
        if (imageShown >= filteredImages.size()) {
            imageMore.setVisibility(View.GONE);
        } else {
            int next = Math.min(IMAGE_PAGE, filteredImages.size() - imageShown);
            imageMore.setVisibility(View.VISIBLE);
            imageMore.setText("Показать ещё " + next + "  •  " + imageShown + "/" + filteredImages.size());
        }
    }

    private void addImageCell(DriveItem x, int width) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(5), dp(5), dp(5), dp(8));
        cell.setBackground(round(Color.WHITE, 15, 0xFFE4E8EB, 1));
        cell.setClickable(true);
        cell.setOnClickListener(v -> DriveActions.open(this, x));
        cell.setOnLongClickListener(v -> { menu(v, x); return true; });

        FrameLayoutCompat picture = new FrameLayoutCompat(this);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFFE9ECEF);
        image.setClickable(false);
        picture.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));

        boolean autoPreview = x.sizeBytes <= 0 || x.sizeBytes <= AUTO_PREVIEW_MAX;
        if (autoPreview) {
            ImageLoader.load(image, accountId, x.url);
        } else {
            TextView hint = text("Открыть", 10, true);
            hint.setTextColor(0xFF667085);
            hint.setGravity(Gravity.CENTER);
            picture.addView(hint, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        cell.addView(picture, new LinearLayout.LayoutParams(-1, dp(132)));

        TextView name = text(x.name, 11, true);
        name.setMaxLines(2);
        name.setPadding(dp(4), dp(5), dp(4), 0);
        cell.addView(name);

        GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
        gp.width = width;
        gp.height = dp(180);
        gp.setMargins(dp(3), dp(3), dp(3), dp(3));
        imageGrid.addView(cell, gp);
    }

    private void renderList(List<DriveItem> items) {
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (DriveItem x : items) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(9), dp(7), dp(6), dp(7));
            row.setBackground(round(Color.WHITE, 15, 0xFFE5E8EB, 1));
            ImageView icon = new ImageView(this);
            icon.setImageResource(DriveActions.isImage(x) ? R.drawable.ic_image_24 : R.drawable.ic_folder_24);
            icon.setColorFilter(DriveActions.isImage(x) ? 0xFF3B9DF5 : 0xFF667085);
            icon.setPadding(dp(9), dp(9), dp(9), dp(9));
            icon.setBackground(round(DriveActions.isImage(x) ? 0xFFEAF4FE : 0xFFF0F2F4, 10, 0, 0));
            row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(dp(9), 0, 0, 0);
            TextView n = text(x.name, 13, true);
            n.setMaxLines(1);
            TextView m = text(DriveActions.shortType(x) + " • " + df.format(x.updated), 10, false);
            m.setTextColor(MUTED);
            info.addView(n);
            info.addView(m);
            row.addView(info, new LinearLayout.LayoutParams(0, dp(48), 1));

            TextView more = text("⋮", 21, false);
            more.setGravity(Gravity.CENTER);
            more.setOnClickListener(v -> menu(v, x));
            row.addView(more, new LinearLayout.LayoutParams(dp(34), dp(44)));
            row.setOnClickListener(v -> DriveActions.open(this, x));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(66));
            rp.setMargins(0, 0, 0, dp(5));
            content.addView(row, rp);
        }
    }

    private void menu(View anchor, DriveItem item) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Открыть");
        if (!DriveActions.isLink(item)) m.getMenu().add(0, 2, 1, "Скачать");
        m.getMenu().add(0, 3, 2, "Источник в ChatGPT");
        m.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) DriveActions.open(this, item);
            else if (mi.getItemId() == 2) DriveActions.download(this, item);
            else {
                Intent i = new Intent(this, BrowserActivity.class);
                i.putExtra(BrowserActivity.EXTRA_SECTION, category);
                i.putExtra(BrowserActivity.EXTRA_URL, item.url);
                i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, item.accountId);
                startActivity(i);
            }
            return true;
        });
        m.show();
    }

    private void empty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(20), dp(54), dp(20), dp(30));
        ImageView icon = new ImageView(this);
        icon.setImageResource("Images".equals(category) ? R.drawable.ic_image_24 : R.drawable.ic_folder_24);
        icon.setColorFilter(0xFF98A2B3);
        box.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView h = text("Пока пусто", 18, true);
        h.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(0, dp(12), 0, 0);
        box.addView(h, hp);
        TextView d = text("Запусти синхронизацию выбранного аккаунта.", 11, false);
        d.setGravity(Gravity.CENTER);
        d.setTextColor(MUTED);
        d.setPadding(0, dp(5), 0, dp(14));
        box.addView(d);
        TextView b = text("Синхронизировать", 12, true);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(GREEN, 13, 0, 0));
        b.setOnClickListener(v -> sync());
        box.addView(b, new LinearLayout.LayoutParams(dp(210), dp(44)));
        content.addView(box);
    }

    private void sync() {
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, category);
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, accountId);
        startActivityForResult(i, SYNC_REQUEST);
    }

    private String titleFor(String c) {
        if ("Images".equals(c)) return "Картинки";
        if ("Projects".equals(c)) return "Проекты";
        if ("Archived".equals(c)) return "Архив";
        return "Библиотека";
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

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    /** Tiny alias keeps the image-card code readable without an XML layout dependency. */
    private static final class FrameLayoutCompat extends android.widget.FrameLayout {
        FrameLayoutCompat(android.content.Context context) { super(context); }
    }
}
