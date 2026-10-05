package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Stores Sign in with ChatGPT identity separately from Library session credentials. */
final class ChatGptOAuthStore {
    private static final String PREFS = "chatgpt_oauth_accounts";
    private static final String PREFIX = "acct_";
    private static final String HOST_ID = "ext_agent_host_id";
    private static final String KEY_ALIAS = "GPTDrive.OAuthIdentity.v1";

    private ChatGptOAuthStore() {}

    static String hostId(Context context) {
        SharedPreferences p = prefs(context);
        String v = p.getString(HOST_ID, "");
        if (v != null && !v.trim().isEmpty()) return v.trim();
        v = "urn:uuid:" + java.util.UUID.randomUUID();
        p.edit().putString(HOST_ID, v).apply();
        return v;
    }

    static boolean save(Context context, long accountId, String clientId, String idToken,
                        String accessToken, String refreshToken, String scope,
                        long expiresIn, String subject, String email, String name) {
        if (context == null || accountId <= 0 || clientId == null || clientId.trim().isEmpty()) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("client_id", clientId.trim());
            o.put("id_token", nz(idToken));
            o.put("access_token", nz(accessToken));
            o.put("refresh_token", nz(refreshToken));
            o.put("scope", nz(scope));
            o.put("expires_in", Math.max(0, expiresIn));
            o.put("saved_at", System.currentTimeMillis());
            o.put("subject", nz(subject));
            o.put("email", nz(email));
            o.put("name", nz(name));
            o.put("ext_agent_host_id", hostId(context));
            String sealed = SecureJsonStore.seal(KEY_ALIAS, o.toString());
            prefs(context).edit().putString(PREFIX + accountId, sealed).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Record load(Context context, long accountId) {
        if (context == null || accountId <= 0) return null;
        SharedPreferences p = prefs(context);
        String stored = p.getString(PREFIX + accountId, "");
        if (stored == null || stored.trim().isEmpty()) return null;
        try {
            boolean legacyPlaintext = !SecureJsonStore.isSealed(stored);
            String raw = SecureJsonStore.open(KEY_ALIAS, stored);
            JSONObject o = new JSONObject(raw);
            String clientId = o.optString("client_id", "").trim();
            if (clientId.isEmpty()) return null;
            Record result = new Record(
                    clientId,
                    o.optString("id_token", ""),
                    o.optString("access_token", ""),
                    o.optString("refresh_token", ""),
                    o.optString("scope", ""),
                    o.optLong("expires_in", 0),
                    o.optLong("saved_at", 0),
                    o.optString("subject", ""),
                    o.optString("email", ""),
                    o.optString("name", ""),
                    o.optString("ext_agent_host_id", ""));
            if (legacyPlaintext) {
                try { p.edit().putString(PREFIX + accountId, SecureJsonStore.seal(KEY_ALIAS, raw)).apply(); }
                catch (Exception ignored) {}
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean has(Context context, long accountId) { return load(context, accountId) != null; }

    static void clear(Context context, long accountId) {
        if (context == null || accountId <= 0) return;
        prefs(context).edit().remove(PREFIX + accountId).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static final class Record {
        final String clientId, idToken, accessToken, refreshToken, scope;
        final long expiresIn, savedAt;
        final String subject, email, name, hostId;
        Record(String clientId, String idToken, String accessToken, String refreshToken, String scope,
               long expiresIn, long savedAt, String subject, String email, String name, String hostId) {
            this.clientId = clientId; this.idToken = idToken; this.accessToken = accessToken;
            this.refreshToken = refreshToken; this.scope = scope; this.expiresIn = expiresIn; this.savedAt = savedAt;
            this.subject = subject; this.email = email; this.name = name; this.hostId = hostId;
        }
    }
}
