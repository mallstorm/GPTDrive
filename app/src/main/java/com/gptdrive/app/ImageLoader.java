package com.gptdrive.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;
import android.webkit.CookieManager;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageLoader {
    interface Callback { void onLoaded(Bitmap bitmap); }

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private ImageLoader() {}

    static void load(ImageView view, String url) {
        if (view == null || url == null) return;
        view.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        EXEC.execute(() -> {
            Bitmap b = fetch(url);
            if (b != null) CACHE.put(url, b);
            view.post(() -> {
                Object tag = view.getTag();
                if (b != null && url.equals(tag)) view.setImageBitmap(b);
            });
        });
    }

    static void load(String url, Callback callback) {
        Bitmap cached = CACHE.get(url);
        if (cached != null) { callback.onLoaded(cached); return; }
        EXEC.execute(() -> {
            Bitmap b = fetch(url);
            if (b != null) CACHE.put(url, b);
            callback.onLoaded(b);
        });
    }

    static Bitmap fetch(String url) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(true);
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) h.setRequestProperty("Cookie", cookie);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 Android GPTDrive/0.2");
            h.connect();
            if (h.getResponseCode() < 200 || h.getResponseCode() >= 400) return null;
            try (InputStream in = h.getInputStream()) { return BitmapFactory.decodeStream(in); }
        } catch (Exception e) {
            return null;
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
