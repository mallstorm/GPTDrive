package com.gptdrive.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ImageViewerActivity extends Activity {
    public static final String EXTRA_ID = "id";
    private DriveItem item;
    private ImageView image;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        long id = getIntent().getLongExtra(EXTRA_ID, -1);
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
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17);
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
        status.setText("Загрузка изображения…");
        status.setTextColor(0xFFBBBBBB);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(10), dp(8), dp(10), dp(12));
        Button save = new Button(this);
        save.setText("Скачать");
        save.setAllCaps(false);
        save.setOnClickListener(v -> DriveActions.download(this, item));
        Button source = new Button(this);
        source.setText("Источник");
        source.setAllCaps(false);
        source.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(this, BrowserActivity.class);
            i.putExtra(BrowserActivity.EXTRA_SECTION, item.category);
            i.putExtra(BrowserActivity.EXTRA_URL, item.url);
            i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, item.accountId);
            startActivity(i);
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(source, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(actions);
        setContentView(root);
    }

    private void load() {
        ImageLoader.load(item.url, bitmap -> runOnUiThread(() -> {
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                status.setText(bitmap.getWidth() + " × " + bitmap.getHeight());
            } else {
                status.setText("Не удалось загрузить. Возможно, ссылка ChatGPT истекла — синхронизируй этот аккаунт заново.");
            }
        }));
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
