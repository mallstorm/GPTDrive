package com.gptdrive.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** Experimental local import of /api/auth/session JSON for ChatGPT Library access. */
public final class SessionImportActivity extends Activity {
    static final String EXTRA_ACCOUNT_ID = "account_id";
    static final String EXTRA_NEW_ACCOUNT = "new_account";
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final String SESSION_URL = "https://chatgpt.com/api/auth/session";
    private static final int GREEN = 0xFF08A66D;
    private DriveDb db;
    private long targetId;
    private boolean newAccount;
    private boolean imported;
    private EditText editor;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new DriveDb(this);
        targetId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, 0);
        newAccount = getIntent().getBooleanExtra(EXTRA_NEW_ACCOUNT, false);
        if (targetId <= 0 || db.accountById(targetId) == null) { finish(); return; }
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(0xFFF7F9FA);
        scroll.addView(root);

        TextView title = text("Session JSON для Library", 21, true);
        root.addView(title);
        TextView info = text("Экспериментальный доступ к ChatGPT Library. OAuth и Session JSON хранятся раздельно. Открой ссылку в системном браузере, скопируй полный JSON и вставь его сюда. Данные обрабатываются локально.", 12, false);
        info.setTextColor(0xFF667085);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2); ip.setMargins(0, dp(8), 0, dp(14));
        root.addView(info, ip);

        TextView open = button("1. Открыть /api/auth/session в браузере", false);
        open.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SESSION_URL))); }
            catch (Exception e) { Toast.makeText(this, "Системный браузер не найден", Toast.LENGTH_SHORT).show(); }
        });
        root.addView(open, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView paste = button("2. Вставить из буфера", false);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(50)); pp.setMargins(0, dp(10), 0, 0);
        root.addView(paste, pp);

        editor = new EditText(this);
        editor.setHint("Вставь полный JSON из /api/auth/session");
        editor.setTextSize(12);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setBackground(round(Color.WHITE, 14, 0xFFD0D5DD, 1));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(260)); ep.setMargins(0, dp(10), 0, 0);
        root.addView(editor, ep);
        paste.setOnClickListener(v -> pasteClipboard());

        TextView importBtn = button("Импортировать Session JSON", true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(54)); bp.setMargins(0, dp(14), 0, 0);
        root.addView(importBtn, bp);
        importBtn.setOnClickListener(v -> importSession());

        TextView warn = text("Важно: если страница показывает только WARNING_BANNER без accessToken, это не полноценный Session JSON и GPT Drive его не примет.", 11, false);
        warn.setTextColor(0xFFB54708);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2); wp.setMargins(0, dp(12), 0, 0);
        root.addView(warn, wp);
        setContentView(scroll);
    }

    private void pasteClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) { Toast.makeText(this, "Буфер пуст", Toast.LENGTH_SHORT).show(); return; }
        ClipData c = cm.getPrimaryClip();
        if (c == null || c.getItemCount() == 0) return;
        CharSequence s = c.getItemAt(0).coerceToText(this);
        if (s != null) editor.setText(s.toString());
    }

    private void importSession() {
        String raw = editor.getText() == null ? "" : editor.getText().toString().trim();
        if (raw.isEmpty()) { Toast.makeText(this, "Вставь Session JSON", Toast.LENGTH_SHORT).show(); return; }
        try {
            JSONObject root = new JSONObject(raw);
            String token = root.optString("accessToken", root.optString("access_token", "")).trim();
            if (token.isEmpty()) {
                if (root.has("WARNING_BANNER")) throw new Exception("Получен только WARNING_BANNER — accessToken отсутствует");
                throw new Exception("В JSON нет accessToken");
            }
            JSONObject user = root.optJSONObject("user");
            String email = user == null ? "" : user.optString("email", "").trim();
            String name = user == null ? "" : user.optString("name", "").trim();

            ChatGptOAuthStore.Record oauth = ChatGptOAuthStore.load(this, targetId);
            if (oauth != null && !oauth.email.isEmpty() && !email.isEmpty() && !oauth.email.equalsIgnoreCase(email)) {
                throw new Exception("Session JSON принадлежит другому аккаунту: " + email);
            }
            if (!ChatGptSessionStore.save(this, targetId, raw)) throw new Exception("Не удалось сохранить Session JSON");
            new LibraryBridge(db, targetId).captureAccount(raw);
            if (!name.isEmpty() || !email.isEmpty()) db.updateAccountIdentity(targetId, name, email);
            db.setActiveAccount(targetId);
            imported = true;
            notifyRoots();
            Toast.makeText(this, "Library session подключена", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage() == null ? "Некорректный Session JSON" : e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView button(String s, boolean primary) {
        TextView t = text(s, 13, true);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(primary ? Color.WHITE : GREEN);
        t.setBackground(round(primary ? GREEN : Color.WHITE, 14, 0xFF8EDFC0, primary ? 0 : 1));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(0xFF101828);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke); return g;
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); } catch (Exception ignored) {}
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (!imported && newAccount && !ChatGptOAuthStore.has(this, targetId) && !ChatGptSessionStore.has(this, targetId)) {
            AccountProfile a = db.accountById(targetId);
            if (a != null && !a.active) db.deleteAccount(targetId);
        }
        super.onDestroy();
    }
}
