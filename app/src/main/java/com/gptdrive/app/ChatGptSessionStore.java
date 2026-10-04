package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Stores the short-lived ChatGPT backend session for one GPT Drive account. */
final class ChatGptSessionStore {
    private static final String PREFS = "chatgpt_backend_sessions";
    private static final String PREFIX = "acct_";

    private ChatGptSessionStore() {}

    static boolean save(Context context, long localAccountId, String sessionJson) {
        if (context == null || localAccountId <= 0 || sessionJson == null) return false;
        try {
            JSONObject root = new JSONObject(sessionJson.trim());
            String token = root.optString("accessToken", root.optString("access_token", "")).trim();
            if (token.isEmpty()) return false;
            String remoteAccountId = extractRemoteAccountId(root, token);
            JSONObject user = root.optJSONObject("user");
            String email = user == null ? "" : user.optString("email", "").trim();
            JSONObject out = new JSONObject();
            out.put("accessToken", token);
            out.put("accountId", remoteAccountId);
            out.put("email", email);
            out.put("savedAt", System.currentTimeMillis());
            prefs(context).edit().putString(PREFIX + localAccountId, out.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Session load(Context context, long localAccountId) {
        if (context == null || localAccountId <= 0) return null;
        String raw = prefs(context).getString(PREFIX + localAccountId, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            String token = o.optString("accessToken", "").trim();
            if (token.isEmpty()) return null;
            return new Session(token,
                    o.optString("accountId", "").trim(),
                    o.optString("email", "").trim(),
                    o.optLong("savedAt", 0));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean has(Context context, long localAccountId) {
        return load(context, localAccountId) != null;
    }

    static void clear(Context context, long localAccountId) {
        if (context == null || localAccountId <= 0) return;
        prefs(context).edit().remove(PREFIX + localAccountId).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String extractRemoteAccountId(JSONObject root, String token) {
        try {
            JSONObject account = root.optJSONObject("account");
            if (account != null) {
                String id = first(account.optString("id", ""), account.optString("account_id", ""));
                if (!id.isEmpty()) return id;
            }
            JSONObject session = root.optJSONObject("session");
            if (session != null) {
                JSONObject nested = session.optJSONObject("account");
                if (nested != null) {
                    String id = first(nested.optString("id", ""), nested.optString("account_id", ""));
                    if (!id.isEmpty()) return id;
                }
            }
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                JSONObject jwt = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
                String direct = first(jwt.optString("chatgpt_account_id", ""), jwt.optString("account_id", ""));
                if (!direct.isEmpty()) return direct;
                JSONObject auth = jwt.optJSONObject("https://api.openai.com/auth");
                if (auth != null) {
                    String id = first(auth.optString("chatgpt_account_id", ""), auth.optString("account_id", ""));
                    if (!id.isEmpty()) return id;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    static final class Session {
        final String accessToken;
        final String accountId;
        final String email;
        final long savedAt;
        Session(String accessToken, String accountId, String email, long savedAt) {
            this.accessToken = accessToken;
            this.accountId = accountId;
            this.email = email;
            this.savedAt = savedAt;
        }
    }
}
