package com.gptdrive.app;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fully native Recently deleted surface. No visible ChatGPT web page is used. */
public final class DeletedLibraryActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_URL = "url";       // legacy, intentionally ignored
    public static final String EXTRA_TITLE = "title";   // legacy, intentionally ignored
    public static final String EXTRA_NOTE = "note";     // legacy, intentionally ignored

    private static final int GREEN = 0xFF08A66D;
    private static final int RED = 0xFFD92D20;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private long accountId;
    private LibraryMutationClient client;
    private final ArrayList<LibraryMutationClient.TrashItem> all = new ArrayList<>();
    private LinearLayout content;
    private TextView count;
    private TextView status;
    private EditText search;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DriveDb db = new DriveDb(this);
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        client = new LibraryMutationClient(this, accountId);
        buildUi();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(10), 0);
        TextView back = text("‹", 29, false, TEXT);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(54)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Удалённые", 20, true, TEXT);
        count = text("Обновление…", 10, false, MUTED);
        titles.addView(title);
        titles.addView(count);
        bar.addView(titles, new LinearLayout.LayoutParams(0, dp(54), 1));

        TextView refresh = pill("Обновить", GREEN, 0xFFE5F8F1);
        refresh.setOnClickListener(v -> refresh());
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(82), dp(36)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout warning = new LinearLayout(this);
        warning.setGravity(Gravity.CENTER_VERTICAL);
        warning.setPadding(dp(12), dp(8), dp(12), dp(8));
        warning.setBackground(round(Color.WHITE, 14, 0xFFE4E8EB, 1));
        TextView note = text("Удалённые материалы хранятся до 30 дней. Восстановление выполняется напрямую через ChatGPT Library API.", 10, false, MUTED);
        warning.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = pill("Очистить", RED, 0xFFFFECEB);
        clear.setOnClickListener(v -> confirmEmptyTrash());
        warning.addView(clear, new LinearLayout.LayoutParams(dp(76), dp(34)));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.setMargins(dp(14), 0, dp(14), dp(8));
        root.addView(warning, wp);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Поиск в удалённых");
        search.setTextSize(12);
        search.setTextColor(TEXT);
        search.setHintTextColor(0xFF98A2B3);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(Color.WHITE, 14, 0xFFE1E5E8, 1));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { render(); }
            public void afterTextChanged(Editable e) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(44));
        sp.setMargins(dp(14), 0, dp(14), dp(8));
        root.addView(search, sp);

        status = text("Подключаюсь к удалённым…", 11, false, MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(2), dp(14), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void refresh() {
        List<LibraryMutationClient.TrashItem> local = client.loadShadow();
        all.clear();
        all.addAll(local);
        count.setText(all.size() + " объектов");
        render();
        status.setVisibility(View.VISIBLE);
        status.setText(local.isEmpty() ? "Проверяю удалённые в ChatGPT…" : "Показаны локальные удаления • сверяю с ChatGPT…");
        worker.execute(() -> {
            try {
                List<LibraryMutationClient.TrashItem> list = client.loadTrash();
                runOnUiThread(() -> {
                    all.clear();
                    all.addAll(list);
                    count.setText(all.size() + " объектов");
                    status.setVisibility(View.GONE);
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    count.setText("—");
                    status.setVisibility(View.VISIBLE);
                    status.setText("Не удалось прочитать удалённые: " + shortError(e));
                    render();
                });
            }
        });
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (LibraryMutationClient.TrashItem t : all) {
            if (t == null || t.item == null) continue;
            String name = t.item.name == null ? "" : t.item.name;
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
            addRow(t);
            shown++;
        }
        if (shown == 0 && status.getVisibility() != View.VISIBLE) {
            TextView empty = text(q.isEmpty() ? "Удалённых материалов нет" : "Ничего не найдено", 15, true, MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(70), dp(16), dp(20));
            content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void addRow(LibraryMutationClient.TrashItem t) {
        DriveItem item = t.item;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(7), dp(7));
        row.setBackground(round(Color.WHITE, 15, 0xFFE4E8EB, 1));

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(0xFFE9ECEF);
        if (DriveActions.isImage(item)) ImageLoader.load(preview, accountId, item.url);
        else {
            preview.setImageResource(R.drawable.ic_folder_24);
            preview.setColorFilter(0xFF667085);
            preview.setPadding(dp(13), dp(13), dp(13), dp(13));
        }
        preview.setOnClickListener(v -> open(item));
        row.addView(preview, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(10), 0, dp(8), 0);
        TextView name = text(item.name, 13, true, TEXT);
        name.setSingleLine(true);
        String meta = DriveActions.shortType(item);
        if (item.sizeBytes > 0) meta += " • " + MainActivity.formatBytes(item.sizeBytes);
        TextView m = text(meta, 10, false, MUTED);
        String when = t.deletedAt > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(t.deletedAt) : "—";
        TextView expiry = text("Удалено: " + when + " • осталось ~" + t.daysLeft() + " дн.", 9, false, 0xFF8A5A00);
        info.addView(name);
        info.addView(m);
        info.addView(expiry);
        row.addView(info, new LinearLayout.LayoutParams(0, dp(64), 1));

        TextView restore = pill("Вернуть", GREEN, 0xFFE5F8F1);
        restore.setOnClickListener(v -> restore(t));
        row.addView(restore, new LinearLayout.LayoutParams(dp(72), dp(36)));
        row.setOnClickListener(v -> open(item));

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(82));
        rp.setMargins(0, 0, 0, dp(6));
        content.addView(row, rp);
    }

    private void open(DriveItem item) {
        if (DriveActions.isImage(item)) {
            Intent i = new Intent(this, ImageViewerActivity.class);
            i.putExtra(ImageViewerActivity.EXTRA_REMOTE_URL, item.url);
            i.putExtra(ImageViewerActivity.EXTRA_REMOTE_NAME, item.name);
            i.putExtra(ImageViewerActivity.EXTRA_REMOTE_MIME, item.mime);
            i.putExtra(ImageViewerActivity.EXTRA_REMOTE_SIZE, item.sizeBytes);
            i.putExtra(ImageViewerActivity.EXTRA_REMOTE_ACCOUNT_ID, accountId);
            startActivity(i);
        } else {
            DriveActions.open(this, item);
        }
    }

    private void restore(LibraryMutationClient.TrashItem item) {
        Toast.makeText(this, "Восстанавливаю…", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            try {
                client.restore(item);
                runOnUiThread(() -> {
                    all.remove(item);
                    count.setText(all.size() + " объектов");
                    render();
                    Toast.makeText(this, "Восстановлено", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось восстановить: " + shortError(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmEmptyTrash() {
        if (all.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Очистить удалённые?")
                .setMessage("Все материалы в разделе «Удалённые» будут окончательно удалены. Это действие необратимо.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить навсегда", (d, w) -> emptyTrash())
                .show();
    }

    private void emptyTrash() {
        worker.execute(() -> {
            try {
                client.emptyTrash();
                runOnUiThread(() -> {
                    all.clear();
                    count.setText("0 объектов");
                    render();
                    Toast.makeText(this, "Удалённые очищены", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Не удалось очистить: " + shortError(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private TextView pill(String label, int color, int bg) {
        TextView t = text(label, 10, true, color);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(bg, 11, 0, 0));
        return t;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
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

    private String shortError(Exception e) {
        String m = e == null ? "" : e.getMessage();
        return m == null || m.trim().isEmpty() ? "ошибка соединения" : m;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
