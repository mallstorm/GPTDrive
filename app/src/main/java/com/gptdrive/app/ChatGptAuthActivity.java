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

/** In-app ChatGPT authorization with reuse of the existing GPT Drive WebView session. */
public final class ChatGptAuthActivity extends Activity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_NEW_ACCOUNT = "new_account";
    public static final int MAX_ACCOUNTS = 20;
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final String CHATGPT = "https://chatgpt.com";
    private static final String HOME = "https://chatgpt.com/";
    private static final String LOGIN = "https://chatgpt.com/auth/login";
    private static final String SESSION = "https://chatgpt.com/api/auth/session";
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
    private boolean freshStarted;
    private boolean decisionOpen;
    private boolean loginOpened;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = DriveDb.get(this);
        targetId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, 0);
        newAccount = getIntent().getBooleanExtra(EXTRA_NEW_ACCOUNT, false);
        previousActiveId = db.activeAccountId();

        if (targetId <= 0 || db.accountById(targetId) == null) {
            Toast.makeText(this, "Не удалось начать авторизацию", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        buildWebView();
        prepareQuickLogin();
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
        // Browser-like UA built from the installed WebView: drops the "; wv" marker and "Version/x"
        // token that make identity providers refuse embedded browsers, but keeps the real Chrome version.
        String ua = browserUserAgent(this);
        s.setUserAgentString(ua);
        AppIdentity.rememberWebUserAgent(this, ua);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new AuthBridge(), "GPTDriveAuth");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                android.net.Uri u = request.getUrl();
                String scheme = u == null ? "" : String.valueOf(u.getScheme()).toLowerCase(Locale.ROOT);
                return !("https".equals(scheme) || "http".equals(scheme) || "about".equals(scheme));
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageHost = hostOf(url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageHost = hostOf(url);
                CookieManager.getInstance().flush();
                String u = url == null ? "" : url.toLowerCase(Locale.ROOT);

                // Never leave the user parked on raw /api/auth/session JSON.
                // If an old navigation path lands there, read it once and return
                // to the normal ChatGPT page while the bridge decides whether a
                // real authenticated session exists.
                if (u.contains("chatgpt.com/api/auth/session")) {
                    captureSessionDocument();
                    handler.postDelayed(() -> {
                        if (!authorized && !closing && web != null && !isFinishing()) web.loadUrl(HOME);
                    }, 120);
                    return;
                }

                if (u.contains("chatgpt.com")) {
                    if (!u.contains("/auth/login")) loginOpened = false;
                    checkSessionSoon(150);
                    checkSessionSoon(700);
                    checkSessionSoon(1600);
                }
            }
        });
        setContentView(web);
    }

    /**
     * Important: never clear the live WebView cookie jar before trying it.
     * v0.6.3/v0.6.4 could wipe a perfectly valid session after a 3.2s timeout.
     */
    private void prepareQuickLogin() {
        if (previousActiveId > 0) AccountSession.snapshot(this, previousActiveId);

        CookieManager cm = CookieManager.getInstance();
        String live = cm.getCookie(CHATGPT);
        if (live != null && !live.trim().isEmpty()) {
            probeSession();
            return;
        }

        long quickId = newAccount ? previousActiveId : targetId;
        if (quickId > 0 && db.accountById(quickId) != null) {
            AccountSession.switchTo(this, quickId, () -> {
                if (web == null || isFinishing()) return;
                probeSession();
            });
            return;
        }

        // No reusable GPT Drive WebView session exists. Open login without first
        // deleting cookies; login providers may still have useful state.
        openLoginPreservingSession();
    }

    /**
     * Probe from a normal ChatGPT page instead of navigating the WebView to
     * /api/auth/session. The session JSON is fetched invisibly by JavaScript.
     */
    private void probeSession() {
        if (authorized || closing || web == null || isFinishing()) return;
        loginOpened = false;
        web.loadUrl(HOME);
        handler.postDelayed(() -> {
            if (!authorized && !decisionOpen && !freshStarted && !loginOpened) {
                openLoginPreservingSession();
            }
        }, 5000);
    }

    /** Do not erase cookies on an automatic fallback. */
    private void openLoginPreservingSession() {
        if (authorized || closing || web == null || isFinishing() || loginOpened) return;
        loginOpened = true;
        web.loadUrl(LOGIN);
    }

    /** Only an explicit request for another account is allowed to clear the current cookie jar. */
    private void startFreshLogin() {
        if (freshStarted || authorized || closing) return;
        freshStarted = true;
        decisionOpen = false;
        loginOpened = true;
        AccountSession.startFresh(this, targetId, () -> {
            if (!isFinishing() && web != null) web.loadUrl(LOGIN);
        });
    }

    private void captureSessionDocument() {
        if (authorized || closing || web == null) return;
        String js = "(function(){try{var t=(document.body&&document.body.innerText)||document.documentElement.innerText||'';" +
                "if(t){GPTDriveAuth.onSession(t)}}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    private void checkSessionSoon(long delayMs) {
        handler.postDelayed(() -> {
            if (authorized || closing || web == null) return;
            String current = web.getUrl();
            if (current == null || !current.toLowerCase(Locale.ROOT).contains("chatgpt.com")) return;
            String js = "(function(){try{fetch('" + SESSION + "',{credentials:'include',cache:'no-store'})" +
                    ".then(r=>r.text()).then(t=>{try{GPTDriveAuth.onSession(t)}catch(e){}}).catch(()=>{});}catch(e){}})();";
            web.evaluateJavascript(js, null);
        }, delayMs);
    }

    private final class AuthBridge {
        @JavascriptInterface public void onSession(String json) {
            String host = pageHost;
            if (!(host.equals("chatgpt.com") || host.endsWith(".chatgpt.com"))) return;
            if (authorized || closing || json == null || json.trim().isEmpty() || "null".equals(json.trim())) return;
            try {
                String raw = normalizeJson(json);
                JSONObject root = new JSONObject(raw);
                JSONObject user = root.optJSONObject("user");
                String email = user == null ? "" : user.optString("email", "").trim();
                String name = user == null ? "" : user.optString("name", "").trim();
                String accessToken = root.optString("accessToken", root.optString("access_token", "")).trim();

                if (email.isEmpty() && accessToken.isEmpty()) {
                    boolean warningOnly = root.has("WARNING_BANNER");
                    if (warningOnly) {
                        runOnUiThread(() -> {
                            if (authorized || closing || decisionOpen || freshStarted) return;
                            // WARNING_BANNER by itself is an unauthenticated session
                            // response. Move straight into the login page instead of
                            // leaving the raw warning JSON visible forever.
                            openLoginPreservingSession();
                        });
                    }
                    return;
                }

                runOnUiThread(() -> {
                    loginOpened = false;
                    if (newAccount && !freshStarted) showQuickAccountChoice(raw, name, email);
                    else { authorized = true; completeAuthorization(raw, name, email); }
                });
            } catch (Exception ignored) {}
        }
    }

    private volatile String pageHost = "";

    private static String hostOf(String url) {
        try {
            String h = android.net.Uri.parse(url == null ? "" : url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) { return ""; }
    }

    private static String browserUserAgent(android.content.Context c) {
        String fallback = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
        try {
            String ua = WebSettings.getDefaultUserAgent(c);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/[0-9.]+").matcher(ua);
            if (!m.find()) return fallback;
            String chrome = m.group();
            return "Mozilla/5.0 (Linux; Android " + android.os.Build.VERSION.RELEASE
                    + "; K) AppleWebKit/537.36 (KHTML, like Gecko) " + chrome + " Mobile Safari/537.36";
        } catch (Exception e) { return fallback; }
    }

    private static String normalizeJson(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
            try { return new org.json.JSONTokener(v).nextValue().toString(); }
            catch (Exception ignored) {}
        }
        return v;
    }

    private void showQuickAccountChoice(String json, String name, String email) {
        if (authorized || closing || decisionOpen || freshStarted) return;
        decisionOpen = true;
        String who = email == null || email.trim().isEmpty() ? "текущий ChatGPT аккаунт" : email.trim();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Найдена активная сессия")
                .setMessage("Подключить " + who + " без повторного входа?")
                .setPositiveButton("Подключить", (d, w) -> {
                    decisionOpen = false; authorized = true; completeAuthorization(json, name, email);
                })
                .setNegativeButton("Другой аккаунт", (d, w) -> {
                    decisionOpen = false; startFreshLogin();
                })
                .setOnCancelListener(d -> { decisionOpen = false; })
                .show();
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
        ChatGptSessionStore.save(this, finalId, sessionJson);
        LibraryBridge bridge = new LibraryBridge(db, finalId);
        bridge.captureAccount(sessionJson);
        if (!name.isEmpty() || !email.isEmpty()) db.updateAccountIdentity(finalId, name, email);
        db.setActiveAccount(finalId);

        if (newAccount && finalId != targetId) {
            ChatGptSessionStore.clear(this, targetId);
            db.deleteAccount(targetId);
        }
        cleanupEmptyPlaceholders(finalId);
        notifyRoots();
        AutoSync.startNow(this, finalId);

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
            if (noIdentity && db.countAll(a.id) == 0 && defaultName) {
                ChatGptSessionStore.clear(this, a.id);
                db.deleteAccount(a.id);
            }
        }
    }

    private void cancelAuthorization() {
        if (closing) return;
        closing = true;
        if (newAccount) {
            ChatGptSessionStore.clear(this, targetId);
            db.deleteAccount(targetId);
        }
        restorePreviousCookies();
    }

    private void restorePreviousCookies() {
        CookieManager cm = CookieManager.getInstance();
        AccountProfile previous = previousActiveId > 0 ? db.accountById(previousActiveId) : null;
        if (previous == null) {
            cm.flush();
            notifyRoots();
            return;
        }
        cm.removeAllCookies(ok -> {
            restoreHeader(cm, CHATGPT, previous.cookieChatgpt);
            restoreHeader(cm, AUTH, previous.cookieAuth);
            restoreHeader(cm, FILES, previous.cookieFiles);
            db.setActiveAccount(previous.id);
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
        cancelAuthorization();
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (isFinishing() && !authorized && !closing) cancelAuthorization();
        if (web != null) {
            web.removeJavascriptInterface("GPTDriveAuth");
            web.destroy();
        }
        super.onDestroy();
    }
}
