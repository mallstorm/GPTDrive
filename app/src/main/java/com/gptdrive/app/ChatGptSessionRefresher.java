package com.gptdrive.app;

import android.content.Context;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renews the short-lived accessToken through /api/auth/session using the account's long-lived
 * session cookies. If the cookies are gone or rejected, refresh is impossible and the caller must
 * fall back to "needs login" (WebView sign-in).
 */
final class ChatGptSessionRefresher {
    private ChatGptSessionRefresher() {}

    static synchronized boolean refresh(Context context, long accountId) {
        try {
            String cookie = AccountSession.cookieHeader(context, accountId, "https://chatgpt.com");
            if (cookie == null || cookie.trim().isEmpty()) return false;
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Cookie", cookie);
            h.put("Accept", "application/json");
            h.put("User-Agent", AppIdentity.userAgent());
            h.put("Cache-Control", "no-cache");
            HttpResponse r = new AndroidHttpTransport(context).execute(new HttpRequest(
                    "GET", "https://chatgpt.com/api/auth/session", h, null, true, false,
                    10000, 15000, 512 * 1024));
            if (r.code != 200) return false;
            String body = r.bodyUtf8().trim();
            JSONObject o = new JSONObject(body);
            if (o.optString("accessToken", "").trim().isEmpty()) return false;
            return ChatGptSessionStore.save(context, accountId, body);
        } catch (Exception e) {
            return false;
        }
    }
}
