package com.gptdrive.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * ChatGPT authorization.
 * Primary path reuses the user's regular browser session through a Custom Tab
 * and imports /api/auth/session JSON. The legacy isolated WebView login remains
 * available as a fallback.
 */
public final class ChatGptAuthActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_NEW_ACCOUNT = "new_account";
    public static final int MAX_ACCOUNTS = 20;

    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final String CHATGPT = "https://chatgpt.com";
    private static final String SESSION_URL = "https://chatgpt.com/api/auth/session";
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DriveDb db;
    private WebView web;
    private long targetId;
    private long previousActiveId;
    private boolean newAccount;
    private boolean authorized;
    private boolean closing;
    private boolean customTabOpened;
    private boolean manualLogin;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        targetId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, 0);
        newAccount = getIntent().getBooleanExtra(EXTRA_NEW_ACCOUNT, false);
        previousActiveId = db.activeAccountId();

        if (targetId <= 0 || db.accountById(targetId) == null) {
            Toast.makeText(this, "Не удалось начать авторизацию", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (previousActiveId > 0) AccountSession.snapshot(this, previousActiveId);
        buildChooser();
        if (state == null) handler.postDelayed(this::openCurrentBrowserSession, 250);
    }

    @Override protected void onResume() {
        super.onResume();
        if (customTabOpened && !manualLogin && !authorized && !closing) {
            handler.postDelayed(() -> tryImportClipboard(false), 180);
        }
    }

    private void buildChooser() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(34), dp(22), dp(26));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -1));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_gpt_drive_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView title = text("Подключить ChatGPT", 22, true, TEXT);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMargins(0, dp(14), 0, 0);
        root.addView(title, tp);

        TextView sub = text("Использовать текущую сессию браузера", 13, true, GREEN);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sup = new LinearLayout.LayoutParams(-1, -2);
        sup.setMargins(0, dp(5), 0, 0);
        root.addView(sub, sup);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(15), dp(17), dp(15));
        card.setBackground(round(Color.WHITE, 18, 0xFFE1E6E9, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(24), 0, 0);
        root.addView(card, cp);

        TextView info = text(
                "GPT Drive откроет служебную страницу ChatGPT в обычном браузере, где ты уже вошёл.\n\n" +
                "1. На странице с JSON выбери всё и скопируй.\n" +
                "2. Вернись в GPT Drive.\n" +
                "3. Сессия подхватится автоматически.\n\n" +
                "Пароль и повторный вход не требуются.", 12, false, TEXT);
        info.setLineSpacing(0, 1.18f);
        card.addView(info);

        TextView open = button("Открыть текущую сессию", GREEN, Color.WHITE);
        open.setOnClickListener(v -> openCurrentBrowserSession());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(50));
        op.setMargins(0, dp(22), 0, 0);
        root.addView(open, op);

        TextView paste = button("Вставить JSON из буфера", Color.WHITE, GREEN);
        paste.setBackground(round(Color.WHITE, 14, 0xFF8EDFC0, 1));
        paste.setOnClickListener(v -> tryImportClipboard(true));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(48));
        pp.setMargins(0, dp(9), 0, 0);
        root.addView(paste, pp);

        TextView fallback = text("Войти вручную во встроенном окне", 12, true, MUTED);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(8), 0, dp(8), 0);
        fallback.setOnClickListener(v -> startManualLogin());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(46));
        fp.setMargins(0, dp(8), 0, 0);
        root.addView(fallback, fp);

        TextView privacy = text("Session JSON хранится только внутри данных GPT Drive и используется для выбранного аккаунта.", 10, false, MUTED);
        privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(dp(12), dp(7), dp(12), 0);
        root.addView(privacy);

        setContentView(scroll);
    }

    private void openCurrentBrowserSession() {
        if (authorized || closing) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(SESSION_URL));
            Bundle extras = new Bundle();
            extras.putBinder("android.support.customtabs.extra.SESSION", null);
            intent.putExtras(extras);
            intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", GREEN);
            intent.putExtra("android.support.customtabs.extra.ENABLE_URLBAR_HIDING", true);
            intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
            customTabOpened = true;
            startActivity(intent);
        } catch (Exception e) {
            customTabOpened = false;
            Toast.makeText(this, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show();
        }
    }

    private void tryImportClipboard(boolean showError) {
        if (authorized || closing || manualLogin) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                if (showError) Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipData data = cm.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return;
            CharSequence cs = data.getItemAt(0).coerceToText(this);
            String json = normalizeJson(cs == null ? "" : cs.toString());
            if (!BrowserSessionStore.isUsable(json)) {
                if (showError) Toast.makeText(this, "В буфере нет session JSON ChatGPT", Toast.LENGTH_SHORT).show();
                return;
            }
            acceptSession(json, true);
        } catch (Exception e) {
            if (showError) Toast.makeText(this, "Не удалось прочитать session JSON", Toast.LENGTH_SHORT).show();
        }
    }

    private static String normalizeJson(String text) {
        if (text == null) return "";
        String s = text.trim();
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) return s.substring(first, last + 1);
        return s;
    }

    private void startManualLogin() {
        if (authorized || closing || manualLogin) return;
        manualLogin = true;
        buildWebView();
        prepareFreshLogin();
    }

    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new AuthBridge(), "GPTDriveAuth");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                if (url != null && url.toLowerCase(Locale.ROOT).contains("chatgpt.com")) {
                    checkSessionSoon(200);
                    checkSessionSoon(1000);
                    checkSessionSoon(2200);
                }
            }
        });
        setContentView(web);
    }

    private void prepareFreshLogin() {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> {
            cm.flush();
            if (!isFinishing() && web != null) web.loadUrl("https://chatgpt.com/auth/login");
        });
    }

    private void checkSessionSoon(long delayMs) {
        handler.postDelayed(() -> {
            if (authorized || closing || web == null) return;
            String js = "(function(){try{fetch('/api/auth/session',{credentials:'include'})" +
                    ".then(r=>r.text()).then(t=>{try{GPTDriveAuth.onSession(t)}catch(e){}}).catch(()=>{});}catch(e){}})();";
            web.evaluateJavascript(js, null);
        }, delayMs);
    }

    private final class AuthBridge {
        @JavascriptInterface public void onSession(String json) {
            if (authorized || closing || json == null || json.trim().isEmpty() || "null".equals(json.trim())) return;
            try {
                JSONObject root = new JSONObject(json);
                JSONObject user = root.optJSONObject("user");
                String email = user == null ? "" : user.optString("email", "").trim();
                String accessToken = root.optString("accessToken", "").trim();
                if (email.isEmpty() && accessToken.isEmpty()) return;
                runOnUiThread(() -> acceptSession(json, false));
            } catch (Exception ignored) {}
        }
    }

    private void acceptSession(String sessionJson, boolean browserImported) {
        if (closing || authorized) return;
        try {
            JSONObject root = new JSONObject(sessionJson);
            JSONObject user = root.optJSONObject("user");
            String email = user == null ? "" : user.optString("email", "").trim();
            String name = user == null ? "" : user.optString("name", "").trim();
            String accessToken = root.optString("accessToken", "").trim();
            if (browserImported && (email.isEmpty() || accessToken.isEmpty())) {
                Toast.makeText(this, "Session JSON неполный", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!browserImported && email.isEmpty() && accessToken.isEmpty()) return;

            long finalId = targetId;
            if (newAccount && !email.isEmpty()) {
                for (AccountProfile a : db.listAccounts()) {
                    if (a.id != targetId && a.email != null && email.equalsIgnoreCase(a.email.trim())) {
                        finalId = a.id;
                        break;
                    }
                }
            }

            if (browserImported) {
                BrowserSessionStore.save(this, finalId, sessionJson);
                AccountSession.markBrowserSession(this, finalId);
            } else {
                BrowserSessionStore.clear(this, finalId);
                AccountSession.snapshot(this, finalId);
            }

            LibraryBridge bridge = new LibraryBridge(db, finalId);
            bridge.captureAccount(sessionJson);
            if (!name.isEmpty() || !email.isEmpty()) db.updateAccountIdentity(finalId, name, email);
            db.setActiveAccount(finalId);

            if (newAccount && finalId != targetId) {
                BrowserSessionStore.clear(this, targetId);
                db.deleteAccount(targetId);
            }
            cleanupEmptyPlaceholders(finalId);
            notifyRoots();

            authorized = true;
            closing = true;
            setResult(RESULT_OK);
            Toast.makeText(this,
                    browserImported ? "Текущая сессия ChatGPT подключена" : "Аккаунт подключён",
                    Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось импортировать сессию", Toast.LENGTH_SHORT).show();
        }
    }

    private void cleanupEmptyPlaceholders(long keepId) {
        List<AccountProfile> accounts = db.listAccounts();
        for (AccountProfile a : accounts) {
            if (a.id == keepId || a.active) continue;
            boolean noIdentity = a.email == null || a.email.trim().isEmpty();
            boolean defaultName = "Аккаунт 1".equals(a.label) || "Новый аккаунт".equals(a.label);
            if (noIdentity && db.countAll(a.id) == 0 && defaultName) {
                BrowserSessionStore.clear(this, a.id);
                db.deleteAccount(a.id);
            }
        }
    }

    private void cancelAuthorization() {
        if (closing) return;
        closing = true;
        if (newAccount) {
            BrowserSessionStore.clear(this, targetId);
            db.deleteAccount(targetId);
        }
        if (manualLogin && previousActiveId > 0 && db.accountById(previousActiveId) != null) {
            AccountSession.restoreSaved(this, previousActiveId, this::notifyRoots);
        } else {
            notifyRoots();
        }
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); }
        catch (Exception ignored) {}
    }

    private TextView button(String value, int background, int foreground) {
        TextView t = text(value, 13, true, foreground);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(background, 14, 0, 0));
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

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        cancelAuthorization();
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (!authorized && !closing) cancelAuthorization();
        if (web != null) {
            web.removeJavascriptInterface("GPTDriveAuth");
            web.destroy();
        }
        super.onDestroy();
    }
}
