package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Project roots. Each row opens the actual project_files relation instead of the old catch-all Library. */
public final class ProjectBrowserActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;
    private static final int GREEN = 0xFF08A66D;

    private DriveDb db;
    private DriveCollections collections;
    private long accountId;
    private LinearLayout list;
    private TextView count;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = DriveDb.get(this);
        collections = new DriveCollections(db);
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(12), 0);
        TextView back = text("‹", 29, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(54)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.addView(text("Проекты", 20, true));
        count = text("0 проектов", 10, false);
        count.setTextColor(MUTED);
        titles.addView(count);
        bar.addView(titles, new LinearLayout.LayoutParams(0, dp(54), 1));
        TextView sync = text("Синхр.", 10, true);
        sync.setTextColor(GREEN);
        sync.setGravity(Gravity.CENTER);
        sync.setBackground(round(0xFFE5F8F1, 11, 0, 0));
        sync.setOnClickListener(v -> openSync());
        bar.addView(sync, new LinearLayout.LayoutParams(dp(70), dp(36)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(60)));

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(8), dp(14), dp(20));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void render() {
        List<DriveCollections.ProjectEntry> projects = collections.projects(accountId);
        count.setText(projects.size() + " проектов");
        list.removeAllViews();
        if (projects.isEmpty()) {
            TextView empty = text("Проекты пока не найдены. После синхронизации здесь появятся реальные папки проектов ChatGPT.", 12, false);
            empty.setTextColor(MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(60), dp(18), dp(20));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (DriveCollections.ProjectEntry p : projects) list.addView(projectRow(p));
    }

    private View projectRow(DriveCollections.ProjectEntry p) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(8), dp(7));
        row.setBackground(round(Color.WHITE, 15, 0xFFE4E8EB, 1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.folder_projects);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(50)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(9), 0, 0, 0);
        TextView name = text(p.name, 14, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        String meta = p.files + " файлов";
        if (p.updated > 0) meta += " • " + new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(p.updated));
        TextView m = text(meta, 10, false);
        m.setTextColor(MUTED);
        info.addView(name);
        info.addView(m);
        row.addView(info, new LinearLayout.LayoutParams(0, dp(54), 1));

        TextView arrow = text("›", 27, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextColor(0xFF475467);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(48)));
        row.setOnClickListener(v -> openProject(p));

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(72));
        rp.setMargins(0, 0, 0, dp(7));
        row.setLayoutParams(rp);
        return row;
    }

    private void openProject(DriveCollections.ProjectEntry p) {
        Intent i = new Intent(this, NativeBrowserActivity.class);
        i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY, "Projects");
        i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(NativeBrowserActivity.EXTRA_PROJECT_ROW_ID, p.rowId);
        startActivity(i);
    }

    private void openSync() {
        Intent i = new Intent(this, BrowserActivity.class);
        i.putExtra(BrowserActivity.EXTRA_SECTION, "Projects");
        i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, accountId);
        startActivity(i);
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

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
