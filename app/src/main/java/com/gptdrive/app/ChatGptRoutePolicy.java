package com.gptdrive.app;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Domain boundary and bounded HTTP decoding for unauthenticated ChatGPT probes. */
final class ChatGptRoutePolicy {
    private static final int MAX_BODY = 49152;
    private ChatGptRoutePolicy() {}

    static boolean isOpenAiHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        for (String suffix : new String[]{"chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com"}) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        }
        return false;
    }

    static boolean isRegionBlocked(int code, String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return code == 451 || b.contains("unsupported_country")
                || b.contains("unsupported country") || b.contains("country is not supported")
                || b.contains("country, region, or territory not supported")
                || b.contains("not available in your country")
                || b.contains("access denied due to region") || b.contains("unable to load site");
    }

    static boolean isSessionResponse(int code, String body) {
        if (code != 200 || isRegionBlocked(code, body) || body == null) return false;
        String b = body.trim();
        if (!b.startsWith("{") || !b.endsWith("}")) return false;
        try {
            JSONObject json = new JSONObject(b);
            // An empty object is the normal logged-out session; no user credentials
            // are sent by diagnostics. An error/challenge page is never healthy.
            return !json.has("error") && !json.has("errors")
                    && (json.length() == 0 || json.has("user") || json.has("accessToken")
                        || json.has("expires") || json.has("WARNING_BANNER"));
        } catch (Exception e) { return false; }
    }

    static final class HttpResponse {
        final int code;
        final String body;
        HttpResponse(int code, String body) { this.code = code; this.body = body; }
    }

    static HttpResponse readResponse(InputStream in) throws IOException {
        String status = line(in);
        if (!status.startsWith("HTTP/")) throw new IOException("Invalid HTTP status");
        String[] parts = status.split("\\s+");
        int code;
        try { code = Integer.parseInt(parts[1]); }
        catch (Exception e) { throw new IOException("Invalid HTTP code", e); }
        Map<String, String> headers = new HashMap<>();
        int total = status.length();
        for (;;) {
            String h = line(in);
            total += h.length() + 2;
            if (total > 32768) throw new IOException("HTTP headers too large");
            if (h.isEmpty()) break;
            int colon = h.indexOf(':');
            if (colon > 0) headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        // Don't wait for an HTML challenge body to finish before reporting failure.
        if (code != 200) return new HttpResponse(code, "");
        String encoding = headers.get("content-encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Unexpected HTTP encoding");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String transfer = headers.get("transfer-encoding");
        if (transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) {
            for (;;) {
                String size = line(in).split(";", 2)[0].trim();
                int n;
                try { n = Integer.parseInt(size, 16); }
                catch (Exception e) { throw new IOException("Invalid HTTP chunk", e); }
                if (n < 0 || n > MAX_BODY - body.size()) throw new IOException("HTTP body too large");
                if (n == 0) break;
                copy(in, body, n);
                if (!line(in).isEmpty()) throw new IOException("Invalid chunk terminator");
            }
        } else if (headers.containsKey("content-length")) {
            int n;
            try { n = Integer.parseInt(headers.get("content-length")); }
            catch (Exception e) { throw new IOException("Invalid Content-Length", e); }
            if (n < 0 || n > MAX_BODY) throw new IOException("HTTP body too large");
            copy(in, body, n);
        } else {
            byte[] bytes = new byte[2048];
            int n;
            while ((n = in.read(bytes)) != -1) {
                if (n > MAX_BODY - body.size()) throw new IOException("HTTP body too large");
                body.write(bytes, 0, n);
            }
        }
        return new HttpResponse(code, new String(body.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int n) throws IOException {
        byte[] bytes = new byte[2048];
        while (n > 0) {
            int read = in.read(bytes, 0, Math.min(bytes.length, n));
            if (read < 0) throw new IOException("Truncated HTTP body");
            out.write(bytes, 0, read);
            n -= read;
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (;;) {
            int b = in.read();
            if (b < 0) throw new IOException("Truncated HTTP headers");
            if (b == '\n') break;
            if (out.size() >= 8192) throw new IOException("HTTP line too large");
            out.write(b);
        }
        String value = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        return value.endsWith("\r") ? value.substring(0, value.length() - 1) : value;
    }
}
