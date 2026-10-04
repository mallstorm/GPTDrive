package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Stores the ChatGPT session JSON imported from the user's regular browser. */
final class BrowserSessionStore {
    private static final String PREFS = "chatgpt_browser_sessions";
    private static final String KEY_PREFIX = "session_";

    private BrowserSessionStore() {}

    static void save(Context context, long accountId, String sessionJson) {
        if (context == null || accountId <= 0 || !isUsable(sessionJson)) return;
        prefs(context).edit().putString(KEY_PREFIX + accountId, sessionJson.trim()).apply();
    }

    static String load(Context context, long accountId) {
        if (context == null || accountId <= 0) return "";
        return prefs(context).getString(KEY_PREFIX + accountId, "");
    }

    static boolean has(Context context, long accountId) {
        return isUsable(load(context, accountId));
    }

    static void clear(Context context, long accountId) {
        if (context == null || accountId <= 0) return;
        prefs(context).edit().remove(KEY_PREFIX + accountId).apply();
    }

    static boolean isUsable(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(json.trim());
            String token = root.optString("accessToken", "").trim();
            JSONObject user = root.optJSONObject("user");
            String email = user == null ? "" : user.optString("email", "").trim();
            return !token.isEmpty() && !email.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static String accessToken(Context context, long accountId) {
        try {
            JSONObject root = new JSONObject(load(context, accountId));
            return root.optString("accessToken", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    static String chatGptAccountId(Context context, long accountId) {
        try {
            JSONObject root = new JSONObject(load(context, accountId));
            JSONObject account = root.optJSONObject("account");
            if (account == null) return "";
            String id = account.optString("id", "").trim();
            if (!id.isEmpty()) return id;
            return account.optString("account_id", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
