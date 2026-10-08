package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
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
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Dedicated route for official Google traffic.
 *
 * This is deliberately separated from the generic AI Smart DNS pool. Gemini
 * and Google AI hosts stay on SmartDnsManager, while Google account / One /
 * Play / payments / general Google hosts use only routes from the dedicated
 * Google Full registry. If every Google Full route fails, traffic falls back
 * to System DNS; it is never handed to GeoHide/Astracat/etc.
 */
final class GoogleFullRouter {
    static final String REGISTRY_URL =
            "https://raw.githubusercontent.com/mallstorm/OmniChat-DNS-Registry/main/google-full-routes.json";

    private static final String PREFS = "gptdrive_google_full_v1";
    private static final String ENABLED = "enabled";
    private static final String DISABLED = "disabled_routes";
    private static final String CACHE = "registry_cache";
    private static final String UPDATED = "registry_updated";

    private static final long REFRESH = 6L * 60L * 60L * 1000L;
    private static final long DNS_TTL = 60_000L;

    private static final ExecutorService WORK = Executors.newCachedThreadPool();
    private static final ExecutorService PROBES = Executors.newFixedThreadPool(2);
    private static final SecureRandom RAND = new SecureRandom();
    private static final ConcurrentHashMap<String, Stats> STATS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DnsCache> DNS_CACHE = new ConcurrentHashMap<>();

    private static volatile Context app;
    private static volatile boolean initialized;
    private static volatile List<Route> routes = Collections.emptyList();
    private static volatile String selectedId = "";
    private static volatile String registryError = "";

    static final class RouteView {
        final String id, name, protocol, endpoint, error, sourceUrl;
        final boolean selected, enabled, ok;
        final long testedAt, dnsMs, tlsMs, httpMs;
        final int statusCode;
        final double score;

        RouteView(Route r, Stats s, boolean sel, boolean en, double sc) {
            id = r.id;
            name = r.name;
            protocol = r.protocol;
            endpoint = r.host + ":" + r.port;
            sourceUrl = r.sourceUrl;
            selected = sel;
            enabled = en;
            score = sc;
            ok = s != null && s.testedAt > 0 && s.ok;
            testedAt = s == null ? 0 : s.testedAt;
            dnsMs = s == null ? -1 : s.dnsMs;
            tlsMs = s == null ? -1 : s.tlsMs;
            httpMs = s == null ? -1 : s.httpMs;
            statusCode = s == null ? 0 : s.statusCode;
            error = s == null ? "" : s.error;
        }
    }

    private static final class Route {
        final String id, name, protocol, host, sourceUrl;
        final int port, priority;
        final List<String> domains;

        Route(String i, String n, String proto, String h, int p, int pri,
              List<String> d, String src) {
            id = i;
            name = n;
            protocol = proto;
            host = h;
            port = p;
            priority = pri;
            domains = d;
            sourceUrl = src;
        }

        boolean matches(String hostName) {
            String h = hostName.toLowerCase(Locale.ROOT);
            for (String d : domains) {
                String x = d.toLowerCase(Locale.ROOT).trim().replaceFirst("^\\*?\\.", "");
                if (!x.isEmpty() && (h.equals(x) || h.endsWith("." + x))) return true;
            }
            return false;
        }
    }

    private static final class Stats {
        volatile boolean ok;
        volatile long testedAt;
        volatile long dnsMs = -1;
        volatile long tlsMs = -1;
        volatile long httpMs = -1;
        volatile int statusCode;
        volatile String error = "";
    }

    private static final class DnsCache {
        final long at;
        final List<InetAddress> addresses;
        DnsCache(long t, List<InetAddress> a) { at = t; addresses = a; }
    }

    private static final class ProbeResult {
        boolean ok;
        long dnsMs = -1;
        long tlsMs = -1;
        long httpMs = -1;
        int code;
        String error = "";
    }

    private GoogleFullRouter() {}

    static void initialize(Context context) {
        if (initialized) return;
        synchronized (GoogleFullRouter.class) {
            if (initialized) return;
            app = context.getApplicationContext();
            String cached = prefs().getString(CACHE, "");
            if (!cached.isEmpty()) install(cached);
            initialized = true;
        }
        if (isEnabled()) WORK.execute(() -> {
            refreshIfStale();
            sweep(false);
        });
    }

    static boolean isEnabled() {
        return app != null && prefs().getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        initialize(context);
        prefs().edit().putBoolean(ENABLED, enabled).apply();
        selectedId = "";
        DNS_CACHE.clear();
        if (enabled) refreshAndSweep(context, null);
    }

    static long lastUpdated() {
        return app == null ? 0 : prefs().getLong(UPDATED, 0);
    }

    static String lastRegistryError() { return registryError; }

    static boolean isGoogleFullHost(String host) {
        if (host == null || host.trim().isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (isGeminiHost(h)) return false;

        return h.startsWith("accounts.google.")
                || h.equals("accounts.google.com")
                || h.equals("myaccount.google.com")
                || h.equals("one.google.com")
                || h.equals("payments.google.com")
                || h.equals("play.google.com")
                || h.equals("play.googleapis.com")
                || h.equals("oauth2.googleapis.com")
                || h.equals("apis.google.com")
                || h.equals("accounts.youtube.com")
                || h.equals("android.clients.google.com")
                || h.equals("android.apis.google.com")
                || h.equals("www.google.com")
                || h.endsWith(".google.com")
                || h.endsWith(".googleapis.com")
                || h.endsWith(".googleusercontent.com")
                || h.endsWith(".gstatic.com")
                || h.matches(".*\\.google\\.[a-z]{2,3}(\\.[a-z]{2})?");
    }

    private static boolean isGeminiHost(String h) {
        return h.equals("gemini.google.com")
                || h.endsWith(".gemini.google.com")
                || h.equals("aistudio.google.com")
                || h.equals("ai.google.dev")
                || h.equals("generativelanguage.googleapis.com")
                || h.equals("daily-cloudcode-pa.googleapis.com")
                || h.equals("cloudcode-pa.googleapis.com")
                || h.equals("geminiweb-pa.clients6.google.com");
    }

    static Socket connect(Context context, String host, int port) throws Exception {
        initialize(context);
        if (!isEnabled() || !isGoogleFullHost(host)) return systemConnect(host, port);
        refreshIfStale();

        List<Route> candidates = ranked(host);
        Exception last = null;
        for (Route r : candidates) {
            try {
                List<InetAddress> addresses = resolve(r, host);
                Socket s = connectAddresses(addresses, port, 5500);
                selectedId = r.id;
                return s;
            } catch (Exception e) {
                last = e;
            }
        }

        // Important: fallback is System DNS, never the generic Smart DNS pool.
        try {
            return systemConnect(host, port);
        } catch (Exception e) {
            if (last != null) throw last;
            throw e;
        }
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
            try { sweep(true); }
            finally { if (done != null) done.run(); }
        });
    }

    static List<RouteView> routeViews() {
        List<Route> all = new ArrayList<>(routes);
        all.sort((a, b) -> {
            boolean ae = routeEnabled(a.id), be = routeEnabled(b.id);
            if (ae != be) return ae ? -1 : 1;
            int c = Double.compare(score(b), score(a));
            return c != 0 ? c : Integer.compare(a.priority, b.priority);
        });
        List<RouteView> out = new ArrayList<>();
        for (Route r : all) {
            out.add(new RouteView(r, STATS.get(r.id), r.id.equals(selectedId),
                    routeEnabled(r.id), score(r)));
        }
        return out;
    }

    static void setRouteEnabled(Context context, String id, boolean on) {
        initialize(context);
        Set<String> d = disabled();
        if (on) d.remove(id); else d.add(id);
        prefs().edit().putStringSet(DISABLED, d).apply();
        if (!on && id.equals(selectedId)) selectedId = "";
        DNS_CACHE.clear();
    }

    static String summary() {
        if (!isEnabled()) return "Google Full выключен";
        List<Route> x = ranked("accounts.google.com");
        if (x.isEmpty()) return "Google Full · нет маршрута";
        Route r = find(selectedId);
        if (r == null || !routeEnabled(r.id)) r = x.get(0);
        Stats s = STATS.get(r.id);
        String state = s == null || s.testedAt == 0 ? "не проверен" : (s.ok ? "✓" : "×");
        String latency = s != null && s.dnsMs >= 0 ? " · DNS " + s.dnsMs + " ms" : "";
        return "Google Full · " + r.name + " · " + state + latency;
    }

    private static void refreshIfStale() {
        if (app == null) return;
        if (System.currentTimeMillis() - lastUpdated() > REFRESH || routes.isEmpty()) {
            refreshRemote();
        }
    }

    private static void refreshRemote() {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(REGISTRY_URL).openConnection(Proxy.NO_PROXY);
            h.setConnectTimeout(8000);
            h.setReadTimeout(10000);
            h.setRequestProperty("Accept", "application/json");
            h.setRequestProperty("User-Agent", "GPTDrive-GoogleFull/1");
            if (h.getResponseCode() != 200) throw new IOException("Registry HTTP " + h.getResponseCode());
            String text = new String(readLimited(h.getInputStream(), 256 * 1024), StandardCharsets.UTF_8);
            List<Route> parsed = parse(text);
            if (parsed.isEmpty()) throw new IOException("В Google Full Registry нет маршрутов");
            routes = parsed;
            prefs().edit().putString(CACHE, text).putLong(UPDATED, System.currentTimeMillis()).apply();
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
            List<Route> p = parse(text);
            if (!p.isEmpty()) routes = p;
        } catch (Exception ignored) {}
    }

    private static List<Route> parse(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (root.optInt("version", 1) != 1) throw new IllegalArgumentException("Google Full Registry version");
        JSONArray a = root.optJSONArray("routes");
        List<Route> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !o.optBoolean("enabled", true)) continue;
            String proto = o.optString("protocol", "").trim().toLowerCase(Locale.ROOT);
            if (!"udp53".equals(proto)) continue;
            String host = o.optString("host", "").trim();
            if (host.isEmpty()) continue;
            int port = o.optInt("port", 53);
            int priority = o.optInt("priority", 100);
            String id = o.optString("id", "google-full-" + i);
            String name = o.optString("name", id);
            String source = o.optString("source_url", "");
            List<String> domains = new ArrayList<>();
            JSONArray ds = o.optJSONArray("domains");
            if (ds != null) {
                for (int j = 0; j < ds.length(); j++) {
                    String d = ds.optString(j).trim();
                    if (!d.isEmpty()) domains.add(d);
                }
            }
            if (domains.isEmpty()) {
                domains.add("google.com");
                domains.add("googleapis.com");
                domains.add("gstatic.com");
                domains.add("googleusercontent.com");
            }
            out.add(new Route(id, name, proto, host, port, priority, domains, source));
        }
        out.sort(Comparator.comparingInt(r -> r.priority));
        return out;
    }

    private static void sweep(boolean force) {
        if ((!isEnabled() && !force) || routes.isEmpty()) return;
        List<Future<?>> futures = new ArrayList<>();
        for (Route r : routes) {
            if (force || routeEnabled(r.id)) futures.add(PROBES.submit(() -> probe(r)));
        }
        for (Future<?> f : futures) {
            try { f.get(35, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        List<Route> x = ranked("accounts.google.com");
        selectedId = x.isEmpty() ? "" : x.get(0).id;
    }

    private static void probe(Route r) {
        Stats s = STATS.computeIfAbsent(r.id, k -> new Stats());
        ProbeResult auth = probeHost(r, "accounts.google.com", "/");
        ProbeResult one = probeHost(r, "one.google.com", "/");
        ProbeResult play = probeHost(r, "play.google.com", "/");

        s.ok = auth.ok && one.ok && play.ok;
        s.testedAt = System.currentTimeMillis();
        s.dnsMs = maxKnown(auth.dnsMs, one.dnsMs, play.dnsMs);
        s.tlsMs = maxKnown(auth.tlsMs, one.tlsMs, play.tlsMs);
        s.httpMs = maxKnown(auth.httpMs, one.httpMs, play.httpMs);
        s.statusCode = auth.code;

        List<String> errors = new ArrayList<>();
        if (!auth.ok) errors.add("Google Auth: " + shortError(auth));
        if (!one.ok) errors.add("Google One: " + shortError(one));
        if (!play.ok) errors.add("Google Play: " + shortError(play));
        s.error = join(errors);
    }

    private static ProbeResult probeHost(Route r, String host, String path) {
        ProbeResult p = new ProbeResult();
        SSLSocket ssl = null;
        long d0 = System.nanoTime();
        try {
            List<InetAddress> addresses = resolve(r, host);
            p.dnsMs = ms(d0);
            long t0 = System.nanoTime();
            Socket raw = connectAddresses(addresses, 443, 5000);
            ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, 443, true);
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            params.setServerNames(Collections.singletonList(new SNIHostName(host)));
            ssl.setSSLParameters(params);
            ssl.setSoTimeout(7000);
            ssl.startHandshake();
            p.tlsMs = ms(t0);

            long h0 = System.nanoTime();
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Connection: close\r\n"
                    + "Accept: text/html,*/*\r\n"
                    + "Accept-Encoding: identity\r\n"
                    + "User-Agent: Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/150.0 Mobile Safari/537.36\r\n\r\n";
            ssl.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            ssl.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(ssl.getInputStream(), StandardCharsets.ISO_8859_1));
            String first = br.readLine();
            if (first == null || !first.startsWith("HTTP/")) throw new IOException("HTTP probe");
            String[] parts = first.split("\\s+");
            p.code = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            p.httpMs = ms(h0);
            p.ok = p.code >= 200 && p.code < 500;
            if (!p.ok) p.error = "HTTP " + p.code;
        } catch (Exception e) {
            p.ok = false;
            p.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (p.dnsMs < 0) p.dnsMs = ms(d0);
            if (ssl != null) try { ssl.close(); } catch (Exception ignored) {}
        }
        return p;
    }

    private static List<Route> ranked(String host) {
        List<Route> out = new ArrayList<>();
        for (Route r : routes) if (r.matches(host) && routeEnabled(r.id)) out.add(r);
        out.sort((a, b) -> {
            int c = Double.compare(score(b), score(a));
            return c != 0 ? c : Integer.compare(a.priority, b.priority);
        });
        return out;
    }

    private static double score(Route r) {
        Stats s = STATS.get(r.id);
        if (s != null && s.testedAt > 0 && !s.ok) return -1000;
        if (s == null || s.testedAt == 0) return 50 + (1000 - Math.min(1000, r.priority)) / 500.0;
        double latency = Math.max(0, s.dnsMs) + Math.max(0, s.tlsMs) + Math.max(0, s.httpMs);
        return Math.max(1, 100 - Math.min(90, latency / 35.0));
    }

    private static Route find(String id) {
        if (id == null) return null;
        for (Route r : routes) if (id.equals(r.id)) return r;
        return null;
    }

    private static boolean routeEnabled(String id) { return !disabled().contains(id); }

    private static Set<String> disabled() {
        Set<String> s = prefs().getStringSet(DISABLED, Collections.emptySet());
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

    private static List<InetAddress> resolve(Route route, String host) throws Exception {
        String key = route.id + "|" + host.toLowerCase(Locale.ROOT);
        DnsCache cached = DNS_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at < DNS_TTL) return cached.addresses;

        // A-only on purpose: the Google Full exit is validated on its EU IPv4 path.
        List<InetAddress> addresses = udpDns(route.host, route.port, host, 1);
        LinkedHashMap<String, InetAddress> unique = new LinkedHashMap<>();
        for (InetAddress a : addresses) unique.put(a.getHostAddress(), a);
        List<InetAddress> out = new ArrayList<>(unique.values());
        if (out.isEmpty()) throw new IOException("Google Full DNS не вернул IPv4");
        DNS_CACHE.put(key, new DnsCache(now, out));
        return out;
    }

    private static List<InetAddress> udpDns(String dnsHost, int port, String host, int type) throws Exception {
        byte[] q = query(host, type);
        InetAddress server = InetAddress.getByName(dnsHost);
        byte[] buf = new byte[65535];
        DatagramPacket request = new DatagramPacket(q, q.length, server, port);
        DatagramPacket response = new DatagramPacket(buf, buf.length);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(4500);
            socket.send(request);
            socket.receive(response);
        }
        return parseDns(Arrays.copyOf(buf, response.getLength()), type);
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
            if (x.length == 0 || x.length > 63) throw new IOException("DNS label");
            d.writeByte(x.length);
            d.write(x);
        }
        d.writeByte(0);
        d.writeShort(type);
        d.writeShort(1);
        d.flush();
        return b.toByteArray();
    }

    private static List<InetAddress> parseDns(byte[] data, int wanted) throws Exception {
        List<InetAddress> out = new ArrayList<>();
        if (data.length < 12) return out;
        int qd = u16(data, 4), an = u16(data, 6), p = 12;
        for (int i = 0; i < qd; i++) {
            p = skipName(data, p) + 4;
            if (p > data.length) return out;
        }
        for (int i = 0; i < an && p < data.length; i++) {
            p = skipName(data, p);
            if (p + 10 > data.length) break;
            int type = u16(data, p);
            int cl = u16(data, p + 2);
            int len = u16(data, p + 8);
            p += 10;
            if (p + len > data.length) break;
            if (cl == 1 && type == wanted && type == 1 && len == 4) {
                out.add(InetAddress.getByAddress(Arrays.copyOfRange(data, p, p + len)));
            }
            p += len;
        }
        return out;
    }

    private static int skipName(byte[] data, int p) throws Exception {
        for (int guard = 0; guard < 128 && p < data.length; guard++) {
            int n = data[p] & 255;
            if (n == 0) return p + 1;
            if ((n & 0xC0) == 0xC0) return p + 2;
            p += 1 + n;
        }
        throw new IOException("DNS name");
    }

    private static int u16(byte[] d, int p) { return ((d[p] & 255) << 8) | (d[p + 1] & 255); }

    private static Socket connectAddresses(List<InetAddress> addresses, int port, int timeoutMs) throws Exception {
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
        return connectAddresses(Arrays.asList(InetAddress.getAllByName(host)), port, 5000);
    }

    private static long ms(long start) { return Math.max(0, (System.nanoTime() - start) / 1_000_000L); }

    private static long maxKnown(long... values) {
        long max = -1;
        for (long v : values) if (v >= 0) max = Math.max(max, v);
        return max;
    }

    private static String shortError(ProbeResult p) {
        if (p.error != null && !p.error.trim().isEmpty()) return p.error.trim();
        if (p.code > 0) return "HTTP " + p.code;
        return "недоступен";
    }

    private static String join(List<String> values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            if (b.length() > 0) b.append(" · ");
            b.append(v);
        }
        return b.toString();
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
}
