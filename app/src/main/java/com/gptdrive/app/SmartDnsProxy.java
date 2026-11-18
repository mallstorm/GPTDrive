package com.gptdrive.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback CONNECT proxy used by both HttpURLConnection and Android WebView. */
final class SmartDnsProxy {
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Object LOCK = new Object();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Executor MAIN = command -> MAIN_HANDLER.post(command);

    private static volatile ServerSocket server;
    private static volatile int port;
    private static volatile Context app;
    private static volatile String webViewStatus = "";

    private SmartDnsProxy() {}

    static Proxy proxy(Context context) {
        SmartDnsManager.initialize(context);
        if (!SmartDnsManager.isEnabled()) return Proxy.NO_PROXY;
        try {
            ensureStarted(context.getApplicationContext());
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
        } catch (Exception e) {
            return Proxy.NO_PROXY;
        }
    }

    static String webViewStatus() { return webViewStatus; }

    /** Applies app-scoped Smart Routing to all WebViews without creating a second Android VPN. */
    static void applyWebViewRouting(Context context, Runnable done) {
        Context c = context.getApplicationContext();
        SmartDnsManager.initialize(c);
        Runnable callback = done == null ? () -> {} : done;
        MAIN.execute(() -> {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    webViewStatus = "WebView proxy override не поддерживается";
                    callback.run();
                    return;
                }
                ProxyController controller = ProxyController.getInstance();
                if (!SmartDnsManager.isEnabled()) {
                    controller.clearProxyOverride(MAIN, () -> {
                        webViewStatus = "WebView: System DNS";
                        callback.run();
                    });
                    return;
                }
                ensureStarted(c);
                ProxyConfig config = new ProxyConfig.Builder()
                        .addProxyRule("127.0.0.1:" + port)
                        .build();
                controller.setProxyOverride(config, MAIN, () -> {
                    webViewStatus = "WebView: Smart Routing · 127.0.0.1:" + port;
                    callback.run();
                });
            } catch (Throwable e) {
                webViewStatus = "WebView proxy error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                callback.run();
            }
        });
    }

    private static void ensureStarted(Context context) throws Exception {
        if (server != null && !server.isClosed()) return;
        synchronized (LOCK) {
            if (server != null && !server.isClosed()) return;
            app = context;
                ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", 0), 32);
            server = s;
            port = s.getLocalPort();
            Thread t = new Thread(SmartDnsProxy::acceptLoop, "gptdrive-smart-routing-proxy");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void acceptLoop() {
        while (true) {
            try {
                Socket client = server.accept();
                client.setSoTimeout(15000);
                IO.execute(() -> handle(client));
            } catch (Exception e) {
                if (server == null || server.isClosed()) return;
            }
        }
    }

    private static void handle(Socket client) {
        Socket upstream = null;
        try {
            byte[] header = readHeader(client.getInputStream(), 32768);
            String text = new String(header, StandardCharsets.ISO_8859_1);
            String first = text.split("\\r?\\n", 2)[0].trim();
            String[] parts = first.split("\\s+");
            if (parts.length < 2 || !"CONNECT".equalsIgnoreCase(parts[0])) {
                client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }
            HostPort hp = parseTarget(parts[1]);
            if (blockedTarget(hp.host, hp.port)) {
                client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }

            upstream = SmartDnsManager.connect(app, hp.host, hp.port);

            upstream.setSoTimeout(0);
            client.setSoTimeout(0);
            OutputStream co = client.getOutputStream();
            co.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: GPTDrive-SmartRouting/3\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            co.flush();
            final Socket u = upstream;
            IO.execute(() -> pump(client, u));
            pump(u, client);
        } catch (Exception e) {
            try {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {}
        } finally {
            close(client); close(upstream);
        }
    }

    private static void pump(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
        } catch (Exception ignored) {
        } finally {
            try { to.shutdownOutput(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readHeader(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int state = 0;
        while (out.size() < max) {
            int b = in.read(); if (b < 0) break; out.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = b == '\r' ? 1 : 0;
            if (state == 4) return out.toByteArray();
        }
        throw new java.io.IOException("Proxy header too large");
    }

    private static HostPort parseTarget(String raw) {
        String s = raw.trim();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            String host = end > 0 ? s.substring(1, end) : s;
            int p = end > 0 && end + 2 < s.length() ? Integer.parseInt(s.substring(end + 2)) : 443;
            return new HostPort(host, p);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) return new HostPort(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
        return new HostPort(s, 443);
    }

    /** The proxy is for the public internet only: no loopback/LAN targets, only web ports. */
    private static boolean blockedTarget(String host, int port) {
        if (host == null || host.isEmpty()) return true;
        if (port != 443 && port != 80 && port != 8443) return true;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true;
        boolean literal = h.indexOf(':') >= 0 || h.matches("[0-9.]+");
        if (!literal) return false;
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(h);
            return a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress();
        } catch (Exception e) { return true; }
    }

    private static void close(Socket s) { if (s != null) try { s.close(); } catch (Exception ignored) {} }
    private static final class HostPort { final String host; final int port; HostPort(String h, int p) { host = h; port = p; } }
}

