package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Locale;

/** Official Sign in with ChatGPT flow: system browser + loopback callback + PKCE/OIDC. */
public final class ChatGptOAuthActivity extends Activity {
    static final String EXTRA_ACCOUNT_ID = "account_id";
    static final String EXTRA_NEW_ACCOUNT = "new_account";
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final String AUTHORIZE = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String JWKS = "https://auth.openai.com/.well-known/jwks.json";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String ISSUER = "https://auth.openai.com";

    private final SecureRandom random = new SecureRandom();
    private DriveDb db;
    private long targetId;
    private boolean newAccount;
    private volatile boolean completed;
    private ServerSocket server;
    private TextView status;
    private String state;
    private String nonce;
    private String verifier;
    private String requestedClientId;
    private String redirectUri;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new DriveDb(this);
        targetId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, 0);
        newAccount = getIntent().getBooleanExtra(EXTRA_NEW_ACCOUNT, false);
        if (targetId <= 0 || db.accountById(targetId) == null) { finish(); return; }
        buildUi();
        new Thread(this::startFlow, "ChatGPT-OAuth").start();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(0xFFF7F9FA);
        ProgressBar p = new ProgressBar(this);
        root.addView(p, new LinearLayout.LayoutParams(dp(48), dp(48)));
        status = new TextView(this);
        status.setText("Открываю системный браузер…");
        status.setTextSize(16);
        status.setTextColor(0xFF101828);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(22), 0, 0);
        root.addView(status, sp);
        TextView hint = new TextView(this);
        hint.setText("После подтверждения ChatGPT браузер автоматически вернёт управление GPT Drive. OAuth используется только для идентификации аккаунта; Library подключается отдельно через Session JSON.");
        hint.setTextSize(12);
        hint.setTextColor(0xFF667085);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(0, dp(16), 0, 0);
        root.addView(hint, hp);
        setContentView(root);
    }

    private void startFlow() {
        try {
            ChatGptOAuthStore.Record saved = ChatGptOAuthStore.load(this, targetId);
            requestedClientId = saved == null ? "dynamic_agent_client" : saved.clientId;
            state = randomUrl(32);
            nonce = randomUrl(32);
            verifier = randomUrl(64);
            String challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
            server.setSoTimeout(300000);
            redirectUri = "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback";

            Uri.Builder u = Uri.parse(AUTHORIZE).buildUpon()
                    .appendQueryParameter("client_id", requestedClientId)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", redirectUri)
                    .appendQueryParameter("scope", "openid profile email")
                    .appendQueryParameter("resource", RESOURCE)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("ext_agent_host_id", ChatGptOAuthStore.hostId(this));
            if (saved == null) {
                u.appendQueryParameter("agent_name_hint", "GPT Drive");
            } else {
                if (saved.idToken != null && !saved.idToken.isEmpty()) u.appendQueryParameter("id_token_hint", saved.idToken);
                if (saved.email != null && !saved.email.isEmpty()) u.appendQueryParameter("login_hint", saved.email);
            }

            runOnUiThread(() -> {
                status.setText("Жду подтверждения в браузере…");
                try { startActivity(new Intent(Intent.ACTION_VIEW, u.build())); }
                catch (Exception e) { fail("Не найден системный браузер"); }
            });

            try (Socket socket = server.accept()) {
                BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String first = br.readLine();
                if (first == null || !first.startsWith("GET ")) throw new Exception("Некорректный OAuth callback");
                int a = first.indexOf(' '), z = first.indexOf(' ', a + 1);
                String target = first.substring(a + 1, z);
                Uri cb = Uri.parse("http://127.0.0.1" + target);
                String gotState = cb.getQueryParameter("state");
                String error = cb.getQueryParameter("error");
                if (!state.equals(gotState)) throw new Exception("OAuth state не совпал");
                if (error != null && !error.isEmpty()) throw new Exception("ChatGPT OAuth: " + error);
                String code = cb.getQueryParameter("code");
                if (code == null || code.isEmpty()) throw new Exception("OAuth code не получен");
                String callbackClient = cb.getQueryParameter("client_id");
                String issuedClient = requestedClientId;
                if ("dynamic_agent_client".equals(requestedClientId)) {
                    if (callbackClient == null || callbackClient.trim().isEmpty()) throw new Exception("OpenAI не вернул issued client_id");
                    issuedClient = callbackClient.trim();
                } else if (callbackClient != null && !callbackClient.isEmpty() && !requestedClientId.equals(callbackClient)) {
                    throw new Exception("OAuth client_id не совпал");
                }
                replyBrowser(socket, true, "GPT Drive подключён. Можно вернуться в приложение.");
                exchangeAndSave(code, issuedClient);
            }
        } catch (Exception e) {
            fail(e.getMessage() == null ? "Ошибка Sign in with ChatGPT" : e.getMessage());
        } finally {
            closeServer();
        }
    }

    private void exchangeAndSave(String code, String clientId) throws Exception {
        runOnUiThread(() -> status.setText("Проверяю OAuth и профиль…"));
        String form = "grant_type=authorization_code" +
                "&client_id=" + enc(clientId) +
                "&code=" + enc(code) +
                "&code_verifier=" + enc(verifier) +
                "&redirect_uri=" + enc(redirectUri) +
                "&resource=" + enc(RESOURCE);
        JSONObject token = postForm(TOKEN, form);
        String idToken = token.optString("id_token", "");
        if (idToken.isEmpty()) throw new Exception("OpenAI не вернул ID token");
        JSONObject claims = verifyIdToken(idToken, clientId, nonce);
        String subject = claims.optString("sub", "").trim();
        String email = claims.optString("email", "").trim();
        String name = claims.optString("name", "").trim();
        if (subject.isEmpty()) throw new Exception("ID token без subject");

        long finalId = targetId;
        if (newAccount && !email.isEmpty()) {
            for (AccountProfile x : db.listAccounts()) {
                if (x.id != targetId && email.equalsIgnoreCase(x.email)) {
                    ChatGptOAuthStore.Record old = ChatGptOAuthStore.load(this, x.id);
                    if (old != null && subject.equals(old.subject)) { finalId = x.id; break; }
                }
            }
        }
        boolean ok = ChatGptOAuthStore.save(this, finalId, clientId, idToken,
                token.optString("access_token", ""), token.optString("refresh_token", ""),
                token.optString("scope", ""), token.optLong("expires_in", 0), subject, email, name);
        if (!ok) throw new Exception("Не удалось сохранить OAuth профиль");
        db.updateAccountIdentity(finalId, name, email);
        db.setActiveAccount(finalId);
        if (newAccount && finalId != targetId) db.deleteAccount(targetId);
        notifyRoots();
        completed = true;
        runOnUiThread(() -> {
            Toast.makeText(this, "ChatGPT OAuth подключён. Для Library импортируй Session JSON.", Toast.LENGTH_LONG).show();
            setResult(RESULT_OK);
            finish();
        });
    }

    private JSONObject verifyIdToken(String jwt, String clientId, String expectedNonce) throws Exception {
        String[] p = jwt.split("\\.");
        if (p.length != 3) throw new Exception("Некорректный ID token");
        JSONObject header = new JSONObject(new String(Base64.decode(p[0], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        JSONObject claims = new JSONObject(new String(Base64.decode(p[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg", ""))) throw new Exception("Неподдерживаемый ID token alg");
        String kid = header.optString("kid", "");
        JSONObject jwks = getJson(JWKS);
        JSONArray keys = jwks.optJSONArray("keys");
        JSONObject key = null;
        if (keys != null) for (int i = 0; i < keys.length(); i++) {
            JSONObject k = keys.optJSONObject(i);
            if (k != null && kid.equals(k.optString("kid", ""))) { key = k; break; }
        }
        if (key == null) throw new Exception("Ключ подписи OpenAI не найден");
        BigInteger n = new BigInteger(1, Base64.decode(key.optString("n", ""), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        BigInteger e = new BigInteger(1, Base64.decode(key.optString("e", ""), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
        sig.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
        if (!sig.verify(Base64.decode(p[2], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING))) throw new Exception("Подпись ID token недействительна");
        if (!ISSUER.equals(claims.optString("iss", ""))) throw new Exception("Неверный issuer ID token");
        if (!audContains(claims.opt("aud"), clientId)) throw new Exception("Неверная audience ID token");
        if (claims.optLong("exp", 0) <= System.currentTimeMillis() / 1000L) throw new Exception("ID token истёк");
        if (!expectedNonce.equals(claims.optString("nonce", ""))) throw new Exception("OIDC nonce не совпал");
        return claims;
    }

    private static boolean audContains(Object aud, String clientId) {
        if (aud instanceof String) return clientId.equals(aud);
        if (aud instanceof JSONArray) {
            JSONArray a = (JSONArray) aud;
            for (int i = 0; i < a.length(); i++) if (clientId.equals(a.optString(i))) return true;
        }
        return false;
    }

    private static JSONObject postForm(String url, String form) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("POST"); h.setConnectTimeout(15000); h.setReadTimeout(30000); h.setDoOutput(true);
        h.setRequestProperty("Accept", "application/json");
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] b = form.getBytes(StandardCharsets.UTF_8); h.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = h.getOutputStream()) { o.write(b); }
        int code = h.getResponseCode(); String body = read(code >= 400 ? h.getErrorStream() : h.getInputStream()); h.disconnect();
        if (code < 200 || code >= 300) throw new Exception("OAuth token HTTP " + code);
        return new JSONObject(body);
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(30000); h.setRequestProperty("Accept", "application/json");
        int code = h.getResponseCode(); String body = read(code >= 400 ? h.getErrorStream() : h.getInputStream()); h.disconnect();
        if (code != 200) throw new Exception("JWKS HTTP " + code);
        return new JSONObject(body);
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder s = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] b = new char[4096]; int n; while ((n = r.read(b)) >= 0) s.append(b, 0, n);
        }
        return s.toString();
    }

    private static void replyBrowser(Socket socket, boolean ok, String text) {
        try {
            String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:32px'><h2>" +
                    (ok ? "GPT Drive" : "Ошибка") + "</h2><p>" + text + "</p><p>Это окно можно закрыть.</p></body>";
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream(); out.write(head.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
        } catch (Exception ignored) {}
    }

    private void fail(String message) {
        if (completed || isFinishing()) return;
        runOnUiThread(() -> {
            if (completed || isFinishing()) return;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (newAccount && !ChatGptOAuthStore.has(this, targetId) && !ChatGptSessionStore.has(this, targetId)) {
                AccountProfile a = db.accountById(targetId);
                if (a != null && !a.active) db.deleteAccount(targetId);
            }
            finish();
        });
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); } catch (Exception ignored) {}
    }

    private String randomUrl(int bytes) { byte[] b = new byte[bytes]; random.nextBytes(b); return b64url(b); }
    private static String b64url(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; } }
    private void closeServer() { try { if (server != null) server.close(); } catch (Exception ignored) {} }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() { closeServer(); super.onDestroy(); }
}
