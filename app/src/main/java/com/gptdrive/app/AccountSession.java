package com.gptdrive.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

final class AccountSession {
    private static final String CHATGPT = "https://chatgpt.com";
    private static final String AUTH = "https://auth.openai.com";
    private static final String FILES = "https://files.oaiusercontent.com";

    private AccountSession() {}

    static void snapshot(Context context, long accountId) {
        try {
            CookieManager cm = CookieManager.getInstance();
            String chatgpt = cm.getCookie(CHATGPT);
            String auth = cm.getCookie(AUTH);
            String files = cm.getCookie(FILES);
            new DriveDb(context.getApplicationContext()).saveCookies(accountId, chatgpt, auth, files);
            cm.flush();
        } catch (Exception ignored) {}
    }

    static void switchTo(Context context, long accountId, Runnable ready) {
        DriveDb db = new DriveDb(context.getApplicationContext());
        long current = db.activeAccountId();
        AccountProfile target = db.accountById(accountId);
        if (target == null) { runMain(ready); return; }

        CookieManager cm = CookieManager.getInstance();
        String live = cm.getCookie(CHATGPT);
        if (current == accountId && live != null && !live.trim().isEmpty()) {
            runMain(ready);
            return;
        }

        if (current != accountId) snapshot(context, current);
        cm.removeAllCookies(ok -> {
            restore(target);
            db.setActiveAccount(accountId);
            cm.flush();
            runMain(ready);
        });
    }

    static void startFresh(Context context, long accountId, Runnable ready) {
        DriveDb db = new DriveDb(context.getApplicationContext());
        long current = db.activeAccountId();
        if (current != accountId) snapshot(context, current);
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
            if (db.activeAccountId() == accountId) {
                String live = CookieManager.getInstance().getCookie(url);
                if (live != null && !live.trim().isEmpty()) return live;
            }
            AccountProfile a = db.accountById(accountId);
            if (a == null) return null;
            String host = url == null ? "" : url.toLowerCase();
            if (host.contains("chatgpt.com")) return emptyToNull(a.cookieChatgpt);
            if (host.contains("auth.openai.com")) return emptyToNull(a.cookieAuth);
            return emptyToNull(a.cookieFiles);
        } catch (Exception e) { return null; }
    }

    private static void restore(AccountProfile a) {
        CookieManager cm = CookieManager.getInstance();
        setHeader(cm, CHATGPT, a.cookieChatgpt);
        setHeader(cm, AUTH, a.cookieAuth);
        setHeader(cm, FILES, a.cookieFiles);
    }

    private static void setHeader(CookieManager cm, String url, String header) {
        if (header == null || header.trim().isEmpty()) return;
        String[] cookies = header.split(";\\s*");
        for (String pair : cookies) {
            pair = pair.trim();
            if (pair.isEmpty() || !pair.contains("=")) continue;
            try { cm.setCookie(url, pair + "; Path=/; Secure"); } catch (Exception ignored) {}
        }
    }

    private static String emptyToNull(String s) { return s == null || s.trim().isEmpty() ? null : s; }

    private static void runMain(Runnable r) {
        if (r == null) return;
        new Handler(Looper.getMainLooper()).post(r);
    }
}
