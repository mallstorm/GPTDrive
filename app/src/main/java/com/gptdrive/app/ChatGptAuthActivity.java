package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Clean in-app ChatGPT authorization flow.
 * A new account row may exist temporarily, but is never exposed as a registered
 * account and is never made active until /api/auth/session confirms login.
 */
public final class ChatGptAuthActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_NEW_ACCOUNT = "new_account";
    public static final int MAX_ACCOUNTS = 20;
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final String CHATGPT = "https://chatgpt.com";
    private static final String AUTH = "https://auth.openai.com";
    private static final String FILES = "https://files.oaiusercontent.com";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DriveDb db;
    private WebView web;
    private long targetId;
    private long previousActiveId;
    private boolean newAccount;
    private boolean authorized;
    private boolean closing;

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
        if (previousActiveId > 0) AccountSession.snapshot(this, previousActiveId);
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
                String name = user == null ? "" : user.optString("name", "").trim();
                String accessToken = root.optString("accessToken", "").trim();
                // Registered accounts must have a real ChatGPT identity. Anonymous
                // cookies from the login page are never enough.
                if (email.isEmpty() && accessToken.isEmpty()) return;
                authorized = true;
                runOnUiThread(() -> completeAuthorization(json, name, email));
            } catch (Exception ignored) {}
        }
    }

    private void completeAuthorization(String sessionJson, String name, String email) {
        if (closing) return;
        long finalId = targetId;

        if (newAccount && !email.isEmpty()) {
            for (AccountProfile a : db.listAccounts()) {
                if (a.id != targetId && a.email != null && email.equalsIgnoreCase(a.email.trim())) {
                    finalId = a.id;
                    break;
                }
            }
        }

        AccountSession.snapshot(this, finalId);
        LibraryBridge bridge = new LibraryBridge(db, finalId);
        bridge.captureAccount(sessionJson);
        if (!name.isEmpty() || !email.isEmpty()) db.updateAccountIdentity(finalId, name, email);
        db.setActiveAccount(finalId);

        if (newAccount && finalId != targetId) db.deleteAccount(targetId);
        cleanupEmptyPlaceholders(finalId);
        notifyRoots();

        closing = true;
        setResult(RESULT_OK);
        Toast.makeText(this, "Аккаунт подключён", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void cleanupEmptyPlaceholders(long keepId) {
        List<AccountProfile> accounts = db.listAccounts();
        for (AccountProfile a : accounts) {
            if (a.id == keepId || a.active) continue;
            boolean noIdentity = a.email == null || a.email.trim().isEmpty();
            boolean defaultName = "Аккаунт 1".equals(a.label) || "Новый аккаунт".equals(a.label);
            // Old builds could save anonymous login-page cookies into a placeholder.
            // Delete such rows once a real account exists.
            if (noIdentity && db.countAll(a.id) == 0 && defaultName) db.deleteAccount(a.id);
        }
    }

    private void cancelAuthorization() {
        if (closing) return;
        closing = true;
        if (newAccount) db.deleteAccount(targetId);
        restorePreviousCookies();
    }

    private void restorePreviousCookies() {
        CookieManager cm = CookieManager.getInstance();
        AccountProfile previous = previousActiveId > 0 ? db.accountById(previousActiveId) : null;
        cm.removeAllCookies(ok -> {
            if (previous != null) {
                restoreHeader(cm, CHATGPT, previous.cookieChatgpt);
                restoreHeader(cm, AUTH, previous.cookieAuth);
                restoreHeader(cm, FILES, previous.cookieFiles);
                db.setActiveAccount(previous.id);
            }
            cm.flush();
            notifyRoots();
        });
    }

    private static void restoreHeader(CookieManager cm, String url, String header) {
        if (header == null || header.trim().isEmpty()) return;
        String[] cookies = header.split(";\\s*");
        for (String pair : cookies) {
            pair = pair.trim();
            if (pair.isEmpty() || !pair.contains("=")) continue;
            try { cm.setCookie(url, pair + "; Path=/; Secure"); } catch (Exception ignored) {}
        }
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); }
        catch (Exception ignored) {}
    }

    @Override public void onBackPressed() {
        // Back always cancels account addition. No half-created account survives.
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
