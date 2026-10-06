package com.gptdrive.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Immersive on-demand image viewer with swipe navigation and compact media controls. */
public final class ImageViewerActivity extends Activity {
    public static final String EXTRA_ID = "id";

    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF16B47B;
    private static final int WHITE = 0xFFF7F8FA;
    private static final int MUTED = 0xFFB7BDC6;

    private DriveDb db;
    private final List<DriveItem> gallery = new ArrayList<>();
    private DriveItem item;
    private int index;
    private ImageView image;
    private TextView title;
    private TextView counter;
    private TextView status;
    private LinearLayout thumbRow;
    private LinearLayout topBar;
    private LinearLayout bottomPanel;
    private boolean chromeVisible = true;
    private long loadingId = Long.MIN_VALUE;
    private Bitmap currentBitmap;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        db = new DriveDb(this);
        long id = getIntent().getLongExtra(EXTRA_ID, Long.MIN_VALUE);
        item = db.byId(id);
        if (item == null) { finish(); return; }

        buildGallery(item);
        buildUi();
        showIndex(index, false);
    }

    private void buildGallery(DriveItem current) {
        List<DriveItem> source = db.list(current.accountId, current.category, 5000);
        for (DriveItem x : source) if (DriveActions.isImage(x)) gallery.add(x);
        if (gallery.isEmpty()) gallery.add(current);
        index = 0;
        for (int i = 0; i < gallery.size(); i++) {
            if (gallery.get(i).id == current.id) { index = i; break; }
        }
        item = gallery.get(index);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleChrome();
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(70) || Math.abs(dx) < Math.abs(dy)) return false;
                if (dx < 0) next(); else previous();
                return true;
            }
        });
        image.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(6), dp(8), dp(6));
        topBar.setBackgroundColor(0xA8000000);

        TextView back = text("‹", 31, false, WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(52)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        title = text(item.name, 16, true, WHITE);
        title.setSingleLine(true);
        counter = text("", 11, false, MUTED);
        titles.addView(title);
        titles.addView(counter);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, dp(52), 1));

        TextView more = text("⋮", 25, false, WHITE);
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(this::showMenu);
        topBar.addView(more, new LinearLayout.LayoutParams(dp(44), dp(52)));

        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP);
        root.addView(topBar, tp);

        status = text("Получаю изображение…", 11, false, MUTED);
        status.setGravity(Gravity.CENTER);
        status.setBackground(round(0x88000000, 12, 0, 0));
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(-2, dp(36), Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        stp.setMargins(dp(18), 0, dp(18), 0);
        root.addView(status, stp);

        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(10), dp(8), dp(10), dp(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.addView(action("↓", "Скачать", v -> DriveActions.download(this, item)), new LinearLayout.LayoutParams(0, dp(58), 1));
        actions.addView(action("↗", "Поделиться", v -> share()), new LinearLayout.LayoutParams(0, dp(58), 1));
        actions.addView(action("ⓘ", "Инфо", v -> showInfo()), new LinearLayout.LayoutParams(0, dp(58), 1));
        bottomPanel.addView(actions, new LinearLayout.LayoutParams(-1, dp(60)));

        HorizontalScrollView thumbs = new HorizontalScrollView(this);
        thumbs.setHorizontalScrollBarEnabled(false);
        thumbs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        thumbRow = new LinearLayout(this);
        thumbRow.setGravity(Gravity.CENTER_VERTICAL);
        thumbs.addView(thumbRow, new HorizontalScrollView.LayoutParams(-2, dp(58)));
        bottomPanel.addView(thumbs, new LinearLayout.LayoutParams(-1, dp(60)));

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, dp(132), Gravity.BOTTOM);
        bp.setMargins(dp(8), 0, dp(8), dp(8));
        bottomPanel.setBackground(round(0xCC14171A, 22, 0x33FFFFFF, 1));
        root.addView(bottomPanel, bp);

        setContentView(root);
    }

    private View action(String glyph, String label, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView icon = text(glyph, 22, false, WHITE);
        icon.setGravity(Gravity.CENTER);
        TextView text = text(label, 10, true, WHITE);
        text.setGravity(Gravity.CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(-1, dp(30)));
        box.addView(text, new LinearLayout.LayoutParams(-1, dp(22)));
        box.setOnClickListener(listener);
        return box;
    }

    private void showIndex(int newIndex, boolean animate) {
        if (gallery.isEmpty()) return;
        if (newIndex < 0) newIndex = gallery.size() - 1;
        if (newIndex >= gallery.size()) newIndex = 0;
        index = newIndex;
        item = gallery.get(index);
        loadingId = item.id;
        currentBitmap = null;
        image.setImageDrawable(null);
        title.setText(item.name);
        counter.setText((index + 1) + " / " + gallery.size());
        status.setOnClickListener(null);
        status.setText("Получаю изображение…");
        status.setVisibility(View.VISIBLE);
        rebuildThumbnails();

        final long token = item.id;
        ImageLoader.load(this, item.accountId, item.url, bitmap -> runOnUiThread(() -> {
            if (token != loadingId || item.id != token) return;
            if (bitmap != null) {
                currentBitmap = bitmap;
                image.setImageBitmap(bitmap);
                status.setVisibility(View.GONE);
            } else {
                status.setText("Не удалось загрузить · нажми для повтора");
                status.setVisibility(View.VISIBLE);
                status.setOnClickListener(v -> showIndex(index, false));
            }
        }));
    }

    private void rebuildThumbnails() {
        thumbRow.removeAllViews();
        int from = Math.max(0, index - 3);
        int to = Math.min(gallery.size(), from + 7);
        if (to - from < 7) from = Math.max(0, to - 7);
        for (int i = from; i < to; i++) {
            final int target = i;
            DriveItem x = gallery.get(i);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(round(i == index ? 0x3308A66D : 0xFF2A2E32, 9,
                    i == index ? GREEN : 0x33FFFFFF, i == index ? 2 : 1));
            thumb.setPadding(dp(2), dp(2), dp(2), dp(2));
            ImageLoader.load(thumb, x.accountId, x.url);
            thumb.setOnClickListener(v -> showIndex(target, false));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(52));
            p.setMargins(dp(3), dp(3), dp(3), dp(3));
            thumbRow.addView(thumb, p);
        }
    }

    private void next() { showIndex(index + 1, true); }
    private void previous() { showIndex(index - 1, true); }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        topBar.animate().alpha(chromeVisible ? 1f : 0f).setDuration(140).start();
        bottomPanel.animate().alpha(chromeVisible ? 1f : 0f).setDuration(140).start();
        topBar.setClickable(chromeVisible);
        bottomPanel.setClickable(chromeVisible);
    }

    private void share() {
        try {
            Uri uri = DocumentsContract.buildDocumentUri(AUTHORITY, "file:" + item.id);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(item.mime == null || item.mime.isEmpty() ? "image/*" : item.mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Поделиться изображением"));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть меню отправки", Toast.LENGTH_SHORT).show();
        }
    }

    private void showInfo() {
        String dims = currentBitmap == null ? "—" : currentBitmap.getWidth() + " × " + currentBitmap.getHeight();
        String size = item.sizeBytes > 0 ? MainActivity.formatBytes(item.sizeBytes) : "—";
        String date = item.updated > 0 ? DateFormat.getDateTimeInstance().format(new Date(item.updated)) : "—";
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setMessage("Размер изображения: " + dims + "\nФайл: " + size + "\nДата: " + date + "\nПозиция: " + (index + 1) + " / " + gallery.size())
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Скачать");
        menu.getMenu().add("Поделиться");
        menu.getMenu().add("Информация");
        menu.setOnMenuItemClickListener(mi -> {
            String s = mi.getTitle().toString();
            if (s.startsWith("Скачать")) DriveActions.download(this, item);
            else if (s.startsWith("Поделиться")) share();
            else showInfo();
            return true;
        });
        menu.show();
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
