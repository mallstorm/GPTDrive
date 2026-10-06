package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen on-demand image viewer. No folder refresh is triggered when it closes. */
public final class ImageViewerActivity extends Activity {
    public static final String EXTRA_ID = "id";

    private DriveItem item;
    private ImageView image;
    private TextView status;
    private Button retry;
    private boolean loading;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        long id = getIntent().getLongExtra(EXTRA_ID, Long.MIN_VALUE);
        item = new DriveDb(this).byId(id);
        if (item == null) { finish(); return; }
        buildUi();
        load();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        Button back = new Button(this);
        back.setText("‹");
        back.setOnClickListener(v -> finish());
        TextView title = new TextView(this);
        title.setText(item.name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setMaxLines(1);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(bar);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xFF111111);
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));

        status = new TextView(this);
        status.setText("Получаю изображение из ChatGPT…");
        status.setTextColor(0xFFBBBBBB);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(10), dp(8), dp(10), dp(12));
        retry = new Button(this);
        retry.setText("Повторить");
        retry.setAllCaps(false);
        retry.setVisibility(android.view.View.GONE);
        retry.setOnClickListener(v -> load());
        Button save = new Button(this);
        save.setText("Скачать");
        save.setAllCaps(false);
        save.setOnClickListener(v -> DriveActions.download(this, item));
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(actions);
        setContentView(root);
    }

    private void load() {
        if (loading) return;
        loading = true;
        retry.setVisibility(android.view.View.GONE);
        status.setText("Получаю изображение из ChatGPT…");
        ImageLoader.load(this, item.accountId, item.url, bitmap -> runOnUiThread(() -> {
            loading = false;
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                status.setText(bitmap.getWidth() + " × " + bitmap.getHeight());
            } else {
                status.setText("Не удалось получить изображение. Обнови синхронизацию или повтори запрос.");
                retry.setVisibility(android.view.View.VISIBLE);
            }
        }));
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
