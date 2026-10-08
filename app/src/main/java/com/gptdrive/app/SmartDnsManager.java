package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * App-scoped Smart DNS router. It loads resolver definitions from the remote
 * registry, probes them, resolves selected domains over DoH/DoT and hands raw
 * sockets to the loopback CONNECT gateway. No Android VPNService is used.
 */
final class SmartDnsManager {
    static final String REGISTRY_URL =
            "https://raw.githubusercontent.com/mallstorm/OmniChat-DNS-Registry/main/dns-registry.json";

    private static final String PREFS = "gptdrive_smart_dns_v1";
    private static final String ENABLED = "enabled";
    private static final String AUTO = "auto_select";
    private static final String DISABLED = "disabled_routes";
    private static final String MANUAL = "manual_route";
    private static final String CACHE = "registry_cache";
    private static final String UPDATED = "registry_updated";

    private static final String CHATGPT_HOST = "chatgpt.com";
    private static final String CHATGPT_PATH = "/api/auth/session";
    private static final String GEMINI_HOST = "gemini.google.com";
    private static final String GOOGLE_AUTH_HOST = "accounts.google.com";
    private static final String GOOGLE_AUTH_CC_HOST = "accounts.google.com.ua";

    private static final long REFRESH = 6L * 60L * 60L * 1000L;
    private static final long DNS_TTL = 60_000L;
    private static final long LEASE = 5L * 60L * 1000L;

    private static final ExecutorService WORK = Executors.newCachedThreadPool();
    private static final ExecutorService PROBES = Executors.newFixedThreadPool(3);
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor();
    private static final SecureRandom RAND = new SecureRandom();

    private static final ConcurrentHashMap<String, Stats> STATS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DnsCache> DNS_CACHE = new ConcurrentHashMap<>();

    private static volatile Context app;
    private static volatile boolean initialized;
    private static volatile List<Resolver> resolvers = Collections.emptyList();
    private static volatile String selectedId = "";
    private static volatile String registryError = "";
    private static volatile long selectedUntil;

    static final class RouteView {
        final String id, name, mode, endpoint, error;
        final boolean selected, ok, enabled, blocked;
        final int statusCode;
        final long testedAt, dnsMs, tlsMs, httpMs;
        final double score;

        RouteView(Resolver r, Stats s, boolean sel, boolean en, double sc) {
            id = r.id;
            name = r.name;
            mode = r.mode;
            endpoint = r.endpoint;
            selected = sel;
            enabled = en;
            score = sc;
            ok = s != null && s.testedAt > 0 && s.chatgptOk;
            blocked = s != null && s.chatgptBlocked;
            statusCode = s == null ? 0 : s.chatgptCode;
            testedAt = s == null ? 0 : s.testedAt;
            dnsMs = s == null ? -1 : s.dns;
            tlsMs = s == null ? -1 : s.tls;
            httpMs = s == null ? -1 : s.http;
            error = s == null ? "" : s.error;
        }
    }

    private static final class Resolver {
        final String id, name, mode, endpoint;
        final int priority;
        final List<String> domains;

        Resolver(String i, String n, String m, String e, int p, List<String> d) {
            id = i;
            name = n;
            mode = m;
            endpoint = e;
            priority = p;
            domains = d;
        }

        boolean matches(String host) {
            if (domains.isEmpty()) return true;
            String h = host.toLowerCase(Locale.ROOT);
            for (String v : domains) {
                String x = v.toLowerCase(Locale.ROOT).trim().replaceFirst("^\\*?\\.", "");
                if (!x.isEmpty() && (h.equals(x) || h.endsWith("." + x))) return true;
            }

            if (isGoogleFamilyHost(h)) {
                for (String v : domains) {
                    String x = v.toLowerCase(Locale.ROOT);
                    if (x.contains("google.com")
                            || x.contains("googleapis.com")
                            || x.contains("googleusercontent.com")
                            || x.contains("gstatic.com")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static final class Stats {
        volatile boolean chatgptOk;
        volatile boolean chatgptBlocked;
        volatile boolean geminiTested;
        volatile boolean geminiOk;
        volatile boolean googleTested;
        volatile boolean googleOk;
        volatile boolean googleCcTested;
        volatile boolean googleCcOk;
        volatile int chatgptCode;
        volatile long testedAt;
        volatile long dns = -1;
        volatile long tls = -1;
        volatile long http = -1;
        volatile String error = "";
    }

    private static final class DnsCache {
        final long at;
        final List<InetAddress> addresses;
        DnsCache(long t, List<InetAddress> a) { at = t; addresses = a; }
    }

    private static final class HttpProbe {
        final int code;
        final String body;
        HttpProbe(int c, String b) { code = c; body = b == null ? "" : b; }
    }

    private static final class ProbeResult {
        boolean ok;
        boolean blocked;
        int code;
        long dns = -1;
        long tls = -1;
        long http = -1;
        String error = "";
    }

    private SmartDnsManager() {}

    static void initialize(Context context) {
        if (initialized) return;
        synchronized (SmartDnsManager.class) {
            if (initialized) return;
            app = context.getApplicationContext();
            String cached = prefs().getString(CACHE, "");
            if (!cached.isEmpty()) install(cached);
            initialized = true;
            TIMER.scheduleAtFixedRate(() -> {
                try {
                    if (isEnabled()) {
                        refreshIfStale();
                        sweep(false);
                    }
                } catch (Exception ignored) {}
            }, 2, 15, TimeUnit.MINUTES);
        }
        if (isEnabled()) WORK.execute(() -> {
            refreshIfStale();
            sweep(false);
        });
    }

    static boolean isEnabled() {
        return app != null && prefs().getBoolean(ENABLED, false);
    }

    static boolean isAuto() {
        return app == null || prefs().getBoolean(AUTO, true);
    }

    static long lastUpdated() {
        return app == null ? 0 : prefs().getLong(UPDATED, 0);
    }

    static String lastRegistryError() {
        return registryError;
    }

    static void setEnabled(Context context, boolean on) {
        initialize(context);
        prefs().edit().putBoolean(ENABLED, on).apply();
        resetSelection();
        SmartDnsProxy.applyWebViewRouting(context, null);
        if (on) {
            RoutingForegroundService.ensureStarted(context);
            refreshAndSweep(context, null);
        } else {
            RoutingForegroundService.stop(context);
        }
    }

    static void setAuto(Context context, boolean on) {
        initialize(context);
        prefs().edit().putBoolean(AUTO, on).apply();
        resetSelection();
        if (!on) {
            Resolver r = best(CHATGPT_HOST);
            if (r != null) {
                prefs().edit().putString(MANUAL, r.id).apply();
                selectedId = r.id;
                selectedUntil = Long.MAX_VALUE;
            }
        }
    }

    static void setManualRoute(Context context, String id) {
        initialize(context);
        Resolver r = find(id);
        if (r == null) return;
        setRouteEnabled(context, id, true);
        prefs().edit().putBoolean(AUTO, false).putString(MANUAL, id).apply();
        selectedId = id;
        selectedUntil = Long.MAX_VALUE;
        DNS_CACHE.clear();
    }

    static void setRouteEnabled(Context context, String id, boolean on) {
        initialize(context);
        Set<String> s = disabled();
        if (on) s.remove(id); else s.add(id);
        prefs().edit().putStringSet(DISABLED, s).apply();
        if (!on && id.equals(selectedId)) resetSelection();
        if (!on && id.equals(prefs().getString(MANUAL, ""))) {
            prefs().edit().remove(MANUAL).apply();
        }
        DNS_CACHE.clear();
    }

    static void disableFailedRoutes(Context context) {
        initialize(context);
        Set<String> d = disabled();
        for (Resolver r : resolvers) {
            Stats s = STATS.get(r.id);
            if (s != null && s.testedAt > 0 && !s.chatgptOk) d.add(r.id);
        }
        prefs().edit().putStringSet(DISABLED, d).apply();
        if (d.contains(selectedId)) resetSelection();
    }

    static void refreshAndSweep(Context context, Runnable done) {
        initialize(context);
        WORK.execute(() -> {
            try {
                refreshRemote();
                sweep(true);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    static void sweepNow(Context context, Runnable done) {
        initialize(context);
        WORK.execute(() -> {
            try {
                sweep(true);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    static void refreshIfStale() {
        if (app == null) return;
        if (System.currentTimeMillis() - lastUpdated() > REFRESH || resolvers.isEmpty()) {
            refreshRemote();
        }
    }

    static List<RouteView> routes() {
        long now = System.currentTimeMillis();
        Resolver chosen = choose(ranked(CHATGPT_HOST), now, CHATGPT_HOST);
        List<Resolver> all = new ArrayList<>(resolvers);
        all.sort((a, b) -> {
            boolean ae = routeEnabled(a.id), be = routeEnabled(b.id);
            if (ae != be) return ae ? -1 : 1;
            int c = Double.compare(score(b), score(a));
            return c != 0 ? c : Integer.compare(a.priority, b.priority);
        });
        List<RouteView> out = new ArrayList<>();
        for (Resolver r : all) {
            out.add(new RouteView(r, STATS.get(r.id),
                    chosen != null && r.id.equals(chosen.id),
                    routeEnabled(r.id), score(r)));
        }
        return out;
    }

    static String selectedSummary() {
        Resolver r = find(selectedId);
        if (r == null || !routeEnabled(r.id)) {
            r = choose(ranked(CHATGPT_HOST), System.currentTimeMillis(), CHATGPT_HOST);
        }
        if (r == null) return "System DNS";
        Stats s = STATS.get(r.id);
        String coverage = "";
        if (s != null && s.testedAt > 0) {
            coverage = " · GPT " + mark(s.chatgptOk)
                    + " · Gemini " + mark(s.geminiTested && s.geminiOk)
                    + " · Google " + mark((s.googleTested && s.googleOk)
                    || (s.googleCcTested && s.googleCcOk));
        }
        return (isAuto() ? "AUTO · " : "MANUAL · ") + r.name + " · " + r.mode
                + (s != null && s.dns >= 0 ? " · " + s.dns + " ms" : "")
                + coverage;
    }

    static Socket connect(Context context, String host, int port) throws Exception {
        initialize(context);
        if (!isEnabled()) return systemConnect(host, port);
        refreshIfStale();

        List<Resolver> list = ranked(host);
        Resolver first = choose(list, System.currentTimeMillis(), host);
        List<Resolver> order = new ArrayList<>();
        if (first != null) order.add(first);
        for (Resolver r : list) {
            if (first == null || !r.id.equals(first.id)) order.add(r);
        }

        Exception last = null;
        for (Resolver r : order) {
            long t = System.nanoTime();
            try {
                List<InetAddress> addresses = resolve(r, host);
                Socket s = connectAddresses(addresses, port);
                Stats st = STATS.computeIfAbsent(r.id, k -> new Stats());
                st.dns = ms(t);
                selectedId = r.id;
                selectedUntil = isAuto() ? System.currentTimeMillis() + LEASE : Long.MAX_VALUE;
                return s;
            } catch (Exception e) {
                last = e;
            }
        }

        try {
            return systemConnect(host, port);
        } catch (Exception e) {
            if (last != null) throw last;
            throw e;
        }
    }

    private static void refreshRemote() {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(REGISTRY_URL).openConnection(Proxy.NO_PROXY);
            h.setConnectTimeout(8000);
            h.setReadTimeout(10000);
            h.setRequestProperty("Accept", "application/json");
            h.setRequestProperty("User-Agent", "GPTDrive-DNS-Registry/3");
            if (h.getResponseCode() != 200) {
                throw new IOException("Registry HTTP " + h.getResponseCode());
            }
            String text = new String(readLimited(h.getInputStream(), 512 * 1024),
                    StandardCharsets.UTF_8);
            List<Resolver> parsed = parse(text);
            if (parsed.isEmpty()) throw new IOException("В реестре нет совместимых Smart DNS");
            resolvers = parsed;
            prefs().edit().putString(CACHE, text)
                    .putLong(UPDATED, System.currentTimeMillis()).apply();
            registryError = "";
            DNS_CACHE.clear();
        } catch (Exception e) {
            registryError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static void install(String text) {
        try {
            List<Resolver> p = parse(text);
            if (!p.isEmpty()) resolvers = p;
        } catch (Exception ignored) {}
    }

    private static List<Resolver> parse(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (root.optInt("version", 1) != 1) {
            throw new IllegalArgumentException("DNS Registry version");
        }
        JSONArray ps = root.optJSONArray("providers");
        List<Resolver> out = new ArrayList<>();
        if (ps == null) return out;

        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null || !p.optBoolean("enabled", true)) continue;

            JSONArray caps = p.optJSONArray("capabilities");
            boolean useful = caps == null || caps.length() == 0;
            if (caps != null) {
                for (int j = 0; j < caps.length(); j++) {
                    String cap = caps.optString(j);
                    if ("openai".equalsIgnoreCase(cap)
                            || "gemini".equalsIgnoreCase(cap)
                            || "google_auth".equalsIgnoreCase(cap)
                            || "google_ai_studio".equalsIgnoreCase(cap)) {
                        useful = true;
                    }
                }
            }
            if (!useful) continue;

            String base = p.optString("id", "provider-" + i);
            String name = p.optString("name", base);
            int pri = p.optInt("priority", 100);

            List<String> domains = new ArrayList<>();
            JSONArray da = p.optJSONArray("domains");
            if (da != null) {
                for (int j = 0; j < da.length(); j++) {
                    String x = da.optString(j).trim();
                    if (!x.isEmpty()) domains.add(x);
                }
            }

            JSONObject proto = p.optJSONObject("protocols");
            if (proto == null) continue;
            String doh = proto.optString("doh", "").trim();
            String dot = proto.optString("dot", "").trim();
            if (doh.startsWith("https://")) {
                out.add(new Resolver("remote:" + base + ":doh", name, "DoH", doh, pri, domains));
            }
            if (dot.startsWith("tls://")) {
                out.add(new Resolver("remote:" + base + ":dot", name, "DoT", dot, pri + 1, domains));
            }
        }
        out.sort(Comparator.comparingInt(r -> r.priority));
        return out;
    }

    private static void sweep(boolean force) {
        if ((!isEnabled() && !force) || resolvers.isEmpty()) return;

        List<Future<?>> futures = new ArrayList<>();
        for (Resolver r : resolvers) {
            if (r.matches(CHATGPT_HOST) && (force || routeEnabled(r.id))) {
                futures.add(PROBES.submit(() -> probe(r)));
            }
        }

        for (Future<?> f : futures) {
            try { f.get(30, TimeUnit.SECONDS); }
            catch (Exception ignored) {}
        }
        resetSelection();
        choose(ranked(CHATGPT_HOST), System.currentTimeMillis(), CHATGPT_HOST);
    }

    private static void probe(Resolver r) {
        Stats st = STATS.computeIfAbsent(r.id, k -> new Stats());

        ProbeResult chat = probeHttp(r, CHATGPT_HOST, CHATGPT_PATH, true);
        ProbeResult gemini = r.matches(GEMINI_HOST)
                ? probeHttp(r, GEMINI_HOST, "/", false) : null;
        ProbeResult google = r.matches(GOOGLE_AUTH_HOST)
                ? probeHttp(r, GOOGLE_AUTH_HOST, "/", false) : null;
        ProbeResult googleCc = r.matches(GOOGLE_AUTH_CC_HOST)
                ? probeHttp(r, GOOGLE_AUTH_CC_HOST, "/", false) : null;

        st.chatgptOk = chat.ok;
        st.chatgptBlocked = chat.blocked;
        st.chatgptCode = chat.code;
        st.dns = chat.dns;
        st.tls = chat.tls;
        st.http = chat.http;

        st.geminiTested = gemini != null;
        st.geminiOk = gemini != null && gemini.ok;
        st.googleTested = google != null;
        st.googleOk = google != null && google.ok;
        st.googleCcTested = googleCc != null;
        st.googleCcOk = googleCc != null && googleCc.ok;
        st.testedAt = System.currentTimeMillis();

        List<String> failures = new ArrayList<>();
        if (!chat.ok) failures.add(chat.blocked
                ? "ChatGPT: региональная блокировка"
                : "ChatGPT: " + shortError(chat));
        if (gemini != null && !gemini.ok) failures.add("Gemini: " + shortError(gemini));
        if (google != null && !google.ok) failures.add("Google auth: " + shortError(google));
        if (googleCc != null && !googleCc.ok) {
            failures.add("Google auth .com.ua: " + shortError(googleCc));
        }
        st.error = joinFailures(failures);
    }

    private static ProbeResult probeHttp(
            Resolver r, String host, String path, boolean detectRegionBlock) {
        ProbeResult pr = new ProbeResult();
        long d0 = System.nanoTime();
        SSLSocket ssl = null;
        try {
            List<InetAddress> addresses = resolve(r, host);
            pr.dns = ms(d0);

            long t0 = System.nanoTime();
            Socket raw = connectAddresses(addresses, 443, 4500);
            ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, 443, true);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            ssl.setSSLParameters(p);
            ssl.setSoTimeout(6000);
            ssl.startHandshake();
            pr.tls = ms(t0);

            long h0 = System.nanoTime();
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Connection: close\r\n"
                    + "Accept: application/json,text/html,text/plain,*/*\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "User-Agent: Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "Chrome/150.0 Mobile Safari/537.36\r\n\r\n";
            ssl.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            ssl.getOutputStream().flush();

            HttpProbe hp = readHttp(ssl.getInputStream());
            pr.http = ms(h0);
            pr.code = hp.code;

            String body = hp.body.toLowerCase(Locale.ROOT);
            pr.blocked = detectRegionBlock && (
                    hp.code == 451
                            || body.contains("unable to load site")
                            || body.contains("if you are using a vpn")
                            || body.contains("unsupported_country")
                            || body.contains("country is not supported")
                            || body.contains("not available in your country")
                            || body.contains("access denied due to region"));

            pr.ok = !pr.blocked && hp.code >= 200 && hp.code < 500;
            if (!pr.ok && !pr.blocked) pr.error = "HTTP " + hp.code;
        } catch (Exception e) {
            pr.ok = false;
            pr.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (pr.dns < 0) pr.dns = ms(d0);
            if (ssl != null) try { ssl.close(); } catch (Exception ignored) {}
        }
        return pr;
    }

    private static HttpProbe readHttp(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        String first = r.readLine();
        if (first == null || !first.startsWith("HTTP/")) {
            throw new IOException("HTTP probe");
        }
        String[] p = first.split("\\s+");
        int code = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        String line;
        while ((line = r.readLine()) != null && !line.isEmpty()) {}
        StringBuilder b = new StringBuilder();
        char[] buf = new char[2048];
        int n;
        while (b.length() < 49152 && (n = r.read(buf)) != -1) {
            b.append(buf, 0, Math.min(n, 49152 - b.length()));
        }
        return new HttpProbe(code, b.toString());
    }

    private static List<Resolver> ranked(String host) {
        List<Resolver> x = new ArrayList<>();
        for (Resolver r : resolvers) {
            if (r.matches(host) && routeEnabled(r.id)) x.add(r);
        }
        x.sort((a, b) -> {
            int c = Double.compare(scoreForHost(b, host), scoreForHost(a, host));
            return c != 0 ? c : Integer.compare(a.priority, b.priority);
        });
        return x;
    }

    private static Resolver best(String host) {
        List<Resolver> x = ranked(host);
        return x.isEmpty() ? null : x.get(0);
    }

    private static Resolver choose(List<Resolver> x, long now, String host) {
        if (x.isEmpty()) return null;

        if (!isAuto()) {
            Resolver manual = find(prefs().getString(MANUAL, ""));
            if (manual != null && x.contains(manual) && scoreForHost(manual, host) > -999) {
                selectedId = manual.id;
                selectedUntil = Long.MAX_VALUE;
                return manual;
            }
        }

        Resolver best = x.get(0);
        Resolver current = find(selectedId);
        if (isAuto() && current != null && selectedUntil > now
                && x.contains(current)
                && scoreForHost(current, host) >= scoreForHost(best, host) - 8) {
            return current;
        }
        selectedId = best.id;
        selectedUntil = isAuto() ? now + LEASE : Long.MAX_VALUE;
        return best;
    }

    private static double score(Resolver r) {
        Stats s = STATS.get(r.id);
        if (s != null && s.chatgptBlocked) return -1200;
        if (s != null && s.testedAt > 0 && !s.chatgptOk) return -1000;
        if (s == null || s.testedAt == 0) {
            return 50 + (1000 - Math.min(1000, r.priority)) / 500.0;
        }

        double latency = Math.max(0, s.dns) + Math.max(0, s.tls) + Math.max(0, s.http);
        double value = Math.max(1, 100 - Math.min(85, latency / 35.0));
        if (s.geminiTested) value += s.geminiOk ? 6 : -14;
        if (s.googleTested) value += s.googleOk ? 8 : -18;
        if (s.googleCcTested) value += s.googleCcOk ? 10 : -24;
        return value;
    }

    private static double scoreForHost(Resolver r, String host) {
        Stats s = STATS.get(r.id);
        double base = score(r);
        if (s == null || s.testedAt == 0) return base;

        String h = host.toLowerCase(Locale.ROOT);
        if (isGoogleAuthHost(h)) {
            if (h.startsWith("accounts.google.com.ua") && s.googleCcTested) {
                return s.googleCcOk ? base + 25 : -950;
            }
            if (s.googleTested) return s.googleOk ? base + 20 : -950;
        }
        if (isGeminiHost(h) && s.geminiTested) {
            return s.geminiOk ? base + 15 : -930;
        }
        return base;
    }

    private static Resolver find(String id) {
        if (id == null) return null;
        for (Resolver r : resolvers) if (id.equals(r.id)) return r;
        return null;
    }

    private static boolean routeEnabled(String id) {
        return !disabled().contains(id);
    }

    private static Set<String> disabled() {
        Set<String> s = prefs().getStringSet(DISABLED, Collections.emptySet());
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

    private static void resetSelection() {
        selectedId = "";
        selectedUntil = 0;
        DNS_CACHE.clear();
    }

    private static boolean isGoogleAuthHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.startsWith("accounts.google.")
                || h.equals("accounts.google.com")
                || h.equals("oauth2.googleapis.com")
                || h.equals("apis.google.com")
                || h.equals("myaccount.google.com")
                || h.equals("accounts.youtube.com")
                || h.endsWith(".googleusercontent.com")
                || h.endsWith(".gstatic.com");
    }

    private static boolean isGeminiHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("gemini.google.com")
                || h.endsWith(".gemini.google.com")
                || h.equals("generativelanguage.googleapis.com")
                || h.equals("aistudio.google.com");
    }

    private static boolean isGoogleFamilyHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return isGoogleAuthHost(h)
                || isGeminiHost(h)
                || h.endsWith(".googleapis.com")
                || h.endsWith(".googleusercontent.com")
                || h.endsWith(".gstatic.com")
                || h.endsWith(".google.com")
                || h.matches(".*\\.google\\.[a-z]{2,3}(\\.[a-z]{2})?");
    }

    private static List<InetAddress> resolve(Resolver r, String host) throws Exception {
        String key = r.id + "|" + host.toLowerCase(Locale.ROOT);
        DnsCache cached = DNS_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at < DNS_TTL) return cached.addresses;

        List<InetAddress> out = new ArrayList<>();
        if ("DoH".equals(r.mode)) {
            out.addAll(doh(r.endpoint, host, 1));
            out.addAll(doh(r.endpoint, host, 28));
        } else {
            out.addAll(dot(r.endpoint, host, 1));
            out.addAll(dot(r.endpoint, host, 28));
        }

        LinkedHashMap<String, InetAddress> unique = new LinkedHashMap<>();
        for (InetAddress a : out) unique.put(a.getHostAddress(), a);
        List<InetAddress> addresses = new ArrayList<>(unique.values());
        if (addresses.isEmpty()) throw new IOException("DNS не вернул адреса");
        DNS_CACHE.put(key, new DnsCache(now, addresses));
        return addresses;
    }

    private static List<InetAddress> doh(String endpoint, String host, int type)
            throws Exception {
        byte[] q = query(host, type);
        HttpsURLConnection h =
                (HttpsURLConnection) new URL(endpoint).openConnection(Proxy.NO_PROXY);
        h.setRequestMethod("POST");
        h.setConnectTimeout(5000);
        h.setReadTimeout(5000);
        h.setDoOutput(true);
        h.setFixedLengthStreamingMode(q.length);
        h.setRequestProperty("Content-Type", "application/dns-message");
        h.setRequestProperty("Accept", "application/dns-message");
        h.setRequestProperty("Accept-Encoding", "identity");
        try (OutputStream o = h.getOutputStream()) { o.write(q); }
        if (h.getResponseCode() != 200) {
            throw new IOException("DoH HTTP " + h.getResponseCode());
        }
        byte[] b = readLimited(h.getInputStream(), 65536);
        h.disconnect();
        return parseDns(b, type);
    }

    private static List<InetAddress> dot(String endpoint, String host, int type)
            throws Exception {
        URI u = URI.create(endpoint);
        String dnsHost = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 853;

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(dnsHost, port), 5000);
        raw.setSoTimeout(5000);

        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(raw, dnsHost, port, true);
        SSLParameters p = s.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        p.setServerNames(Collections.singletonList(new SNIHostName(dnsHost)));
        s.setSSLParameters(p);
        s.startHandshake();

        byte[] q = query(host, type);
        DataOutputStream o = new DataOutputStream(s.getOutputStream());
        o.writeShort(q.length);
        o.write(q);
        o.flush();

        DataInputStream i = new DataInputStream(
                new BufferedInputStream(s.getInputStream()));
        int n = i.readUnsignedShort();
        if (n <= 0 || n > 65535) throw new IOException("DoT length");
        byte[] b = new byte[n];
        i.readFully(b);
        s.close();
        return parseDns(b, type);
    }

    private static byte[] query(String host, int type) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeShort(RAND.nextInt(65536));
        d.writeShort(0x0100);
        d.writeShort(1);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        for (String label : host.split("\\.")) {
            byte[] x = label.getBytes(StandardCharsets.US_ASCII);
            d.writeByte(x.length);
            d.write(x);
        }
        d.writeByte(0);
        d.writeShort(type);
        d.writeShort(1);
        d.flush();
        return b.toByteArray();
    }

    private static List<InetAddress> parseDns(byte[] d, int wanted) throws Exception {
        List<InetAddress> out = new ArrayList<>();
        if (d.length < 12) return out;
        int qd = u16(d, 4), an = u16(d, 6), p = 12;

        for (int x = 0; x < qd; x++) {
            p = skipName(d, p) + 4;
            if (p > d.length) return out;
        }

        for (int x = 0; x < an && p < d.length; x++) {
            p = skipName(d, p);
            if (p + 10 > d.length) break;
            int type = u16(d, p);
            int cl = u16(d, p + 2);
            int len = u16(d, p + 8);
            p += 10;
            if (p + len > d.length) break;

            if (cl == 1 && type == wanted
                    && ((type == 1 && len == 4) || (type == 28 && len == 16))) {
                byte[] ip = Arrays.copyOfRange(d, p, p + len);
                out.add(InetAddress.getByAddress(ip));
            }
            p += len;
        }
        return out;
    }

    private static int skipName(byte[] d, int p) throws Exception {
        for (int g = 0; g < 128 && p < d.length; g++) {
            int n = d[p] & 255;
            if (n == 0) return p + 1;
            if ((n & 0xC0) == 0xC0) return p + 2;
            p += 1 + n;
        }
        throw new IOException("DNS name");
    }

    private static int u16(byte[] d, int p) {
        return ((d[p] & 255) << 8) | (d[p + 1] & 255);
    }

    private static Socket connectAddresses(List<InetAddress> addresses, int port)
            throws Exception {
        return connectAddresses(addresses, port, 5000);
    }

    private static Socket connectAddresses(
            List<InetAddress> addresses, int port, int timeoutMs) throws Exception {
        Exception last = null;
        for (InetAddress ip : addresses) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(ip, port), timeoutMs);
                s.setSoTimeout(45000);
                return s;
            } catch (Exception e) {
                last = e;
                try { s.close(); } catch (Exception ignored) {}
            }
        }
        throw last == null ? new IOException("No addresses") : last;
    }

    private static Socket systemConnect(String host, int port) throws Exception {
        return connectAddresses(Arrays.asList(InetAddress.getAllByName(host)), port);
    }

    private static long ms(long t) {
        return Math.max(0, (System.nanoTime() - t) / 1_000_000L);
    }

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        try (InputStream i = in; ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int total = 0, n;
            while ((n = i.read(b)) != -1) {
                total += n;
                if (total > max) throw new IOException("Response too large");
                o.write(b, 0, n);
            }
            return o.toByteArray();
        }
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String mark(boolean ok) {
        return ok ? "✓" : "×";
    }

    private static String shortError(ProbeResult p) {
        if (p == null) return "не проверено";
        if (p.blocked) return "region blocked";
        if (p.error != null && !p.error.trim().isEmpty()) return p.error.trim();
        if (p.code > 0) return "HTTP " + p.code;
        return "недоступен";
    }

    private static String joinFailures(List<String> failures) {
        if (failures == null || failures.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String f : failures) {
            if (b.length() > 0) b.append(" · ");
            b.append(f);
        }
        return b.toString();
    }
}
