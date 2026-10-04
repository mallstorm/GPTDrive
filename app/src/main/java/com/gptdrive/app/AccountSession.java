package com.gptdrive.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

final class AccountSession {
    private static final String CHATGPT = "https://chatgpt.com";
    private static final String AUTH = "https://auth.openai.com";
    private static final String FILES = "https://files.oaiusercontent.com";
    private static final String BROWSER_SESSION_MARKER = "__GPTDRIVE_BROWSER_SESSION__";

    private AccountSession() {}

    static void snapshot(Context context, long accountId) {
        if (accountId <= 0) return;
        try {
            CookieManager cm = CookieManager.getInstance();
            String chatgpt = cm.getCookie(CHATGPT);
            String auth = cm.getCookie(AUTH);
            String files = cm.getCookie(FILES);
            DriveDb db = new DriveDb(context.getApplicationContext());
            AccountProfile existing = db.accountById(accountId);
            if (isEmpty(chatgpt) && existing != null && isBrowserSessionMarker(existing.cookieChatgpt)
                    && BrowserSessionStore.has(context, accountId)) {
                chatgpt = BROWSER_SESSION_MARKER;
            }
            db.saveCookies(accountId, chatgpt, auth, files);
            cm.flush();
        } catch (Exception ignored) {}
    }

    static void markBrowserSession(Context context, long accountId) {
        if (accountId <= 0) return;
        new DriveDb(context.getApplicationContext()).saveCookies(accountId, BROWSER_SESSION_MARKER, "", "");
    }

    static boolean isBrowserSession(AccountProfile profile, Context context) {
        return profile != null && isBrowserSessionMarker(profile.cookieChatgpt)
                && BrowserSessionStore.has(context, profile.id);
    }

    static void switchTo(Context context, long accountId, Runnable ready) {
        DriveDb db = new DriveDb(context.getApplicationContext());
        long current = db.activeAccountId();
        AccountProfile target = db.accountById(accountId);
        if (target == null) { runMain(ready); return; }

        CookieManager cm = CookieManager.getInstance();
        if (isBrowserSession(target, context)) {
            if (current != accountId && current > 0) snapshot(context, current);
            restoreSaved(context, accountId, ready);
            return;
        }

        String live = cm.getCookie(CHATGPT);
        if (current == accountId && live != null && !live.trim().isEmpty()) {
            runMain(ready);
            return;
        }

        if (current != accountId && current > 0) snapshot(context, current);
        restoreSaved(context, accountId, ready);
    }

    static void restoreSaved(Context context, long accountId, Runnable ready) {
        DriveDb db = new DriveDb(context.getApplicationContext());
        AccountProfile target = db.accountById(accountId);
        if (target == null) { runMain(ready); return; }
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> {
            if (!isBrowserSession(target, context)) restore(target);
            db.setActiveAccount(accountId);
            cm.flush();
            runMain(ready);
        });
    }

    static void startFresh(Context context, long accountId, Runnable ready) {
        DriveDb db = new DriveDb(context.getApplicationContext());
        long current = db.activeAccountId();
        if (current != accountId && current > 0) snapshot(context, current);
        BrowserSessionStore.clear(context, accountId);
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> {
            db.saveCookies(accountId, "", "", "");
            db.setActiveAccount(accountId);
            cm.flush();
            runMain(ready);
        });
    }

    static String cookieHeader(Context context, long accountId, String url) {
        try {
            DriveDb db = new DriveDb(context.getApplicationContext());
            AccountProfile a = db.accountById(accountId);
            if (a != null && isBrowserSessionMarker(a.cookieChatgpt)) return null;
            if (db.activeAccountId() == accountId) {
                String live = CookieManager.getInstance().getCookie(url);
                if (live != null && !live.trim().isEmpty()) return live;
            }
            if (a == null) return null;
            String host = url == null ? "" : url.toLowerCase();
            if (host.contains("chatgpt.com")) return emptyToNull(a.cookieChatgpt);
            if (host.contains("auth.openai.com")) return emptyToNull(a.cookieAuth);
            return emptyToNull(a.cookieFiles);
        } catch (Exception e) { return null; }
    }

    static String bearerToken(Context context, long accountId) {
        return BrowserSessionStore.accessToken(context, accountId);
    }

    private static void restore(AccountProfile a) {
        CookieManager cm = CookieManager.getInstance();
        setHeader(cm, CHATGPT, a.cookieChatgpt);
        setHeader(cm, AUTH, a.cookieAuth);
        setHeader(cm, FILES, a.cookieFiles);
    }

    private static void setHeader(CookieManager cm, String url, String header) {
        if (header == null || header.trim().isEmpty() || isBrowserSessionMarker(header)) return;
        String[] cookies = header.split(";\\s*");
        for (String pair : cookies) {
            pair = pair.trim();
            if (pair.isEmpty() || !pair.contains("=")) continue;
            try { cm.setCookie(url, pair + "; Path=/; Secure"); } catch (Exception ignored) {}
        }
    }

    private static boolean isBrowserSessionMarker(String value) {
        return BROWSER_SESSION_MARKER.equals(value == null ? "" : value.trim());
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private static String emptyToNull(String s) { return isEmpty(s) || isBrowserSessionMarker(s) ? null : s; }

    private static void runMain(Runnable r) {
        if (r == null) return;
        new Handler(Looper.getMainLooper()).post(r);
    }
}
