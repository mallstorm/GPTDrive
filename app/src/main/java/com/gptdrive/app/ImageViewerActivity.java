package com.gptdrive.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Immersive image viewer with persistent thumbnail carousel and collapsible action sheet. */
public final class ImageViewerActivity extends Activity {
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_REMOTE_URL = "remote_url";
    public static final String EXTRA_REMOTE_NAME = "remote_name";
    public static final String EXTRA_REMOTE_MIME = "remote_mime";
    public static final String EXTRA_REMOTE_SIZE = "remote_size";
    public static final String EXTRA_REMOTE_ACCOUNT_ID = "remote_account_id";

    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF16B47B;
    private static final int WHITE = 0xFFF7F8FA;
    private static final int MUTED = 0xFFB7BDC6;
    private static final int PANEL_BG = 0xF21A1D21;
    private static final int CAROUSEL_RADIUS = 36;

    private final List<DriveItem> gallery = new ArrayList<>();
    private final ExecutorService mutationWorker = Executors.newSingleThreadExecutor();
    private DriveDb db;
    private DriveItem item;
    private int index;
    private ZoomImageView image;
    private TextView title;
    private TextView counter;
    private TextView status;
    private LinearLayout topBar;
    private LinearLayout bottomPanel;
    private View handleTouch;
    private HorizontalScrollView thumbs;
    private LinearLayout thumbRow;
    private boolean topVisible = true;
    private boolean panelExpanded;
    private long loadingToken = Long.MIN_VALUE;
    private Bitmap currentBitmap;
    private float dragStartY;
    private float dragStartTranslation;
    private boolean draggingPanel;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        db = DriveDb.get(this);
        if (!resolveInitialItem()) { finish(); return; }
        buildGallery(item);
        buildUi();
        showIndex(index);
        bottomPanel.post(() -> applyOrientationState(false));
    }

    private boolean resolveInitialItem() {
        long id = getIntent().getLongExtra(EXTRA_ID, Long.MIN_VALUE);
        if (id != Long.MIN_VALUE) item = db.byId(id);
        if (item != null) return true;
        String url = getIntent().getStringExtra(EXTRA_REMOTE_URL);
        if (url == null || url.trim().isEmpty()) return false;
        long account = getIntent().getLongExtra(EXTRA_REMOTE_ACCOUNT_ID, db.activeAccountId());
        String name = getIntent().getStringExtra(EXTRA_REMOTE_NAME);
        String mime = getIntent().getStringExtra(EXTRA_REMOTE_MIME);
        long size = getIntent().getLongExtra(EXTRA_REMOTE_SIZE, 0);
        item = new DriveItem(Long.MIN_VALUE + 31, account, "Deleted",
                name == null || name.trim().isEmpty() ? "Изображение" : name,
                url, mime == null ? "image/*" : mime, size, 0);
        return true;
    }

    private void buildGallery(DriveItem current) {
        if (current.id == Long.MIN_VALUE + 31) {
            gallery.add(current);
            index = 0;
            return;
        }
        List<DriveItem> source = db.list(current.accountId, current.category, 5000);
        for (DriveItem x : source) if (DriveActions.isImage(x)) gallery.add(x);
        if (gallery.isEmpty()) gallery.add(current);
        index = 0;
        for (int i = 0; i < gallery.size(); i++) if (gallery.get(i).id == current.id) { index = i; break; }
        item = gallery.get(index);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        image = new ZoomImageView(this);
        image.setListener(new ZoomImageView.Listener() {
            @Override public void onSingleTap() { toggleTopBar(); }
            @Override public void onSwipeLeft() { showIndex(index + 1); }
            @Override public void onSwipeRight() { showIndex(index - 1); }
        });
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(5), dp(12), dp(5));
        topBar.setBackgroundColor(0xA6000000);
        TextView back = text("‹", 31, false, WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(54)));
        LinearLayout captions = new LinearLayout(this);
        captions.setOrientation(LinearLayout.VERTICAL);
        captions.setGravity(Gravity.CENTER_VERTICAL);
        title = text(item.name, 16, true, WHITE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        counter = text("", 11, false, MUTED);
        captions.addView(title);
        captions.addView(counter);
        topBar.addView(captions, new LinearLayout.LayoutParams(0, dp(54), 1));
        // Deliberately no overflow/three-dot menu: all actions live in the bottom sheet.
        root.addView(topBar, new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP));

        status = text("Получаю изображение…", 11, false, MUTED);
        status.setGravity(Gravity.CENTER);
        status.setBackground(round(0x88000000, 12, 0, 0));
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(-2, dp(36), Gravity.CENTER);
        stp.setMargins(dp(18), 0, dp(18), 0);
        root.addView(status, stp);

        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(12), 0, dp(12), dp(8));
        bottomPanel.setBackground(round(PANEL_BG, 24, 0x33FFFFFF, 1));

        LinearLayout handleBox = new LinearLayout(this);
        handleBox.setGravity(Gravity.CENTER);
        View handle = new View(this);
        handle.setBackground(round(0xFFF2F4F7, 3, 0, 0));
        handleBox.addView(handle, new LinearLayout.LayoutParams(dp(48), dp(5)));
        handleTouch = handleBox;
        installPanelDrag(handleBox);
        bottomPanel.addView(handleBox, new LinearLayout.LayoutParams(-1, dp(24)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.addView(action(ViewerActionIconView.DOWNLOAD, "Скачать", v -> DriveActions.download(this, item)), new LinearLayout.LayoutParams(0, dp(78), 1));
        actions.addView(action(ViewerActionIconView.SHARE, "Поделиться", v -> share()), new LinearLayout.LayoutParams(0, dp(78), 1));
        actions.addView(action(ViewerActionIconView.INFO, "Инфо", v -> showInfoSheet()), new LinearLayout.LayoutParams(0, dp(78), 1));
        actions.addView(action(ViewerActionIconView.TRASH, "В корзину", v -> confirmMoveToTrash()), new LinearLayout.LayoutParams(0, dp(78), 1));
        bottomPanel.addView(actions, new LinearLayout.LayoutParams(-1, dp(78)));

        View divider = new View(this);
        divider.setBackgroundColor(0x2EFFFFFF);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(dp(2), 0, dp(2), dp(4));
        bottomPanel.addView(divider, dividerParams);

        thumbs = new HorizontalScrollView(this);
        thumbs.setHorizontalScrollBarEnabled(false);
        thumbs.setFillViewport(false);
        thumbs.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        thumbRow = new LinearLayout(this);
        thumbRow.setGravity(Gravity.CENTER_VERTICAL);
        thumbs.addView(thumbRow, new HorizontalScrollView.LayoutParams(-2, dp(68)));
        bottomPanel.addView(thumbs, new LinearLayout.LayoutParams(-1, dp(70)));
        thumbs.setOnTouchListener((v, e) -> {
            return false; // preserve native fling/inertial scrolling
        });

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, dp(188), Gravity.BOTTOM);
        bp.setMargins(dp(4), 0, dp(4), 0);
        root.addView(bottomPanel, bp);
        setContentView(root);
    }

    private View action(int iconType, String label, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(2), dp(4), dp(2), dp(2));
        card.setBackgroundColor(Color.TRANSPARENT);
        ViewerActionIconView icon = new ViewerActionIconView(this, iconType);
        icon.setBackground(round(0xFF25292E, 24, 0x2EFFFFFF, 1));
        TextView caption = text(label, 10, true, WHITE);
        caption.setGravity(Gravity.CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
        card.addView(caption, new LinearLayout.LayoutParams(-1, dp(24)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(76), 1);
        p.setMargins(dp(2), 0, dp(2), 0);
        card.setOnClickListener(v -> {
            listener.onClick(v);
        });
        card.setLayoutParams(p);
        return card;
    }

    private void showIndex(int newIndex) {
        if (gallery.isEmpty()) return;
        if (newIndex < 0) newIndex = gallery.size() - 1;
        if (newIndex >= gallery.size()) newIndex = 0;
        index = newIndex;
        item = gallery.get(index);
        loadingToken = item.id == Long.MIN_VALUE + 31 ? System.nanoTime() : item.id;
        final long token = loadingToken;
        currentBitmap = null;
        image.setImageDrawable(null);
        title.setText(item.name);
        counter.setText((index + 1) + " / " + gallery.size());
        status.setText("Получаю изображение…");
        status.setVisibility(View.VISIBLE);
        rebuildCarousel();
        ImageLoader.load(this, item.accountId, item.url, bitmap -> runOnUiThread(() -> {
            if (token != loadingToken) return;
            if (bitmap != null) {
                currentBitmap = bitmap;
                image.setImageBitmap(bitmap);
                image.resetZoom();
                status.setVisibility(View.GONE);
            } else {
                status.setText("Не удалось загрузить · нажми для повтора");
                status.setVisibility(View.VISIBLE);
                status.setOnClickListener(v -> showIndex(index));
            }
        }));
    }

    private void rebuildCarousel() {
        thumbRow.removeAllViews();
        int from = Math.max(0, index - CAROUSEL_RADIUS);
        int to = Math.min(gallery.size() - 1, index + CAROUSEL_RADIUS);
        int targetChild = index - from;
        for (int i = from; i <= to; i++) {
            final int target = i;
            DriveItem x = gallery.get(i);
            LinearLayout frame = new LinearLayout(this);
            frame.setGravity(Gravity.CENTER);
            frame.setPadding(dp(2), dp(2), dp(2), dp(2));
            frame.setBackground(round(i == index ? 0x4416B47B : 0xFF25292E, 10,
                    i == index ? GREEN : 0x33FFFFFF, i == index ? 2 : 1));
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(0xFF30343A);
            ImageLoader.load(thumb, x.accountId, x.url);
            frame.addView(thumb, new LinearLayout.LayoutParams(dp(60), dp(52)));
            frame.setOnClickListener(v -> showIndex(target));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(68), dp(60));
            p.setMargins(dp(3), dp(4), dp(3), dp(4));
            thumbRow.addView(frame, p);
        }
        final int scrollTarget = Math.max(0, targetChild * dp(74) - getResources().getDisplayMetrics().widthPixels / 2 + dp(34));
        thumbs.post(() -> thumbs.smoothScrollTo(scrollTarget, 0));
    }

    private void installPanelDrag(View handle) {
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragStartY = e.getRawY();
                    dragStartTranslation = bottomPanel.getTranslationY();
                    draggingPanel = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - dragStartY;
                    if (Math.abs(dy) > dp(5)) draggingPanel = true;
                    float max = collapsedTranslation();
                    bottomPanel.setTranslationY(Math.max(0, Math.min(max, dragStartTranslation + dy)));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!draggingPanel) setPanelExpanded(!panelExpanded, true);
                    else setPanelExpanded(bottomPanel.getTranslationY() < collapsedTranslation() * .48f, true);
                    return true;
            }
            return false;
        });
    }

    private float collapsedTranslation() {
        return Math.max(0, bottomPanel.getHeight() - dp(18));
    }

    private void setPanelExpanded(boolean expanded, boolean animate) {
        panelExpanded = expanded;
        float y = expanded ? 0 : collapsedTranslation();
        bottomPanel.animate().cancel();
        if (animate) bottomPanel.animate().translationY(y).setDuration(180).start();
        else bottomPanel.setTranslationY(y);
    }

    private void toggleTopBar() {
        topVisible = !topVisible;
        topBar.animate().alpha(topVisible ? 1f : 0f).setDuration(150).start();
        topBar.setClickable(topVisible);
        if (!panelExpanded && getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE)
            handleTouch.setVisibility(View.VISIBLE);
    }

    private void share() {
        try {
            if (item.id == Long.MIN_VALUE + 31 || db.byId(item.id) == null) {
                Toast.makeText(this, "Для удалённого файла сначала скачай или восстанови материал", Toast.LENGTH_SHORT).show();
                return;
            }
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

    private void confirmMoveToTrash() {
        if (item.id == Long.MIN_VALUE + 31 || db.byId(item.id) == null) {
            Toast.makeText(this, "Этот материал уже находится вне активной библиотеки", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Переместить в корзину?")
                .setMessage(item.name)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("В корзину", (d, w) -> moveCurrentToTrash())
                .show();
    }

    private void moveCurrentToTrash() {
        final DriveItem deleting = item;
        Toast.makeText(this, "Перемещаю в корзину…", Toast.LENGTH_SHORT).show();
        mutationWorker.execute(() -> {
            try {
                new LibraryMutationClient(this, deleting.accountId).moveToTrash(deleting);
                runOnUiThread(() -> {
                    int removed = gallery.indexOf(deleting);
                    if (removed >= 0) gallery.remove(removed);
                    Toast.makeText(this, "Перемещено в корзину", Toast.LENGTH_SHORT).show();
                    if (gallery.isEmpty()) {
                        finish();
                    } else {
                        index = Math.min(Math.max(0, removed), gallery.size() - 1);
                        showIndex(index);
                    }
                });
            } catch (Exception e) {
                String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                        ? "Не удалось переместить в корзину" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showInfoSheet() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(10), dp(18), dp(20));
        sheet.setBackground(round(0xFF1B1F23, 24, 0x33FFFFFF, 1));

        View handle = new View(this);
        handle.setBackground(round(0xFF8E959D, 3, 0, 0));
        LinearLayout handleBox = new LinearLayout(this);
        handleBox.setGravity(Gravity.CENTER);
        handleBox.addView(handle, new LinearLayout.LayoutParams(dp(44), dp(4)));
        sheet.addView(handleBox, new LinearLayout.LayoutParams(-1, dp(22)));

        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(0xFF30343A);
        if (currentBitmap != null) preview.setImageBitmap(currentBitmap); else ImageLoader.load(preview, item.accountId, item.url);
        hero.addView(preview, new LinearLayout.LayoutParams(dp(76), dp(76)));
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(12), 0, 0, 0);
        TextView n = text(item.name, 15, true, WHITE);
        n.setMaxLines(2);
        heroText.addView(n);
        heroText.addView(text(DriveActions.shortType(item) + " • " + (index + 1) + " / " + gallery.size(), 10, false, MUTED));
        hero.addView(heroText, new LinearLayout.LayoutParams(0, dp(76), 1));
        sheet.addView(hero, new LinearLayout.LayoutParams(-1, dp(86)));

        String dims = currentBitmap == null ? "—" : currentBitmap.getWidth() + " × " + currentBitmap.getHeight();
        infoRow(sheet, "Размер файла", item.sizeBytes > 0 ? MainActivity.formatBytes(item.sizeBytes) : "—");
        infoRow(sheet, "Разрешение", dims);
        infoRow(sheet, "Категория", item.category == null || item.category.isEmpty() ? "—" : item.category);
        infoRow(sheet, "Дата", item.updated > 0 ? DateFormat.getDateTimeInstance().format(new Date(item.updated)) : "—");
        if (item.fileId != null && !item.fileId.isEmpty()) infoRow(sheet, "File ID", item.fileId);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        TextView download = text("Скачать", 11, true, WHITE);
        download.setGravity(Gravity.CENTER);
        download.setBackground(round(0xFF25292E, 12, 0x33FFFFFF, 1));
        download.setOnClickListener(v -> DriveActions.download(this, item));
        TextView close = text("Закрыть", 11, true, GREEN);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(0xFF1E352D, 12, 0, 0));
        close.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(download, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(46), 1);
        cp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(close, cp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(46));
        bp.setMargins(0, dp(14), 0, 0);
        sheet.addView(buttons, bp);

        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(.55f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(w.getAttributes());
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            w.setAttributes(lp);
        }
        dialog.show();
    }

    private void infoRow(LinearLayout sheet, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        TextView k = text(key, 10, false, MUTED);
        TextView v = text(value == null ? "—" : value, 10, true, WHITE);
        v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        v.setMaxLines(1);
        row.addView(k, new LinearLayout.LayoutParams(0, dp(26), 1));
        row.addView(v, new LinearLayout.LayoutParams(0, dp(26), 1));
        sheet.addView(row, new LinearLayout.LayoutParams(-1, dp(40)));
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        bottomPanel.post(() -> applyOrientationState(true));
    }

    private void applyOrientationState(boolean animate) {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            topVisible = true;
            topBar.setAlpha(1f);
            setPanelExpanded(false, animate);
        } else {
            setPanelExpanded(false, animate);
        }
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

    @Override protected void onDestroy() {
        mutationWorker.shutdownNow();
        super.onDestroy();
    }
}
