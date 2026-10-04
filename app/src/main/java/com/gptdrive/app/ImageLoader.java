package com.gptdrive.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
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
        Context context = view.getContext().getApplicationContext();
        long accountId = new DriveDb(context).activeAccountId();
        load(view, accountId, url);
    }

    static void load(ImageView view, long accountId, String url) {
        if (view == null || url == null) return;
        String cacheKey = cacheKey(accountId, url);
        view.setTag(url);
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null) { view.setImageBitmap(cached); return; }
        Context context = view.getContext().getApplicationContext();
        EXEC.execute(() -> {
            Bitmap b = fetch(context, accountId, url);
            if (b != null) CACHE.put(cacheKey, b);
            view.post(() -> {
                Object tag = view.getTag();
                if (b != null && url.equals(tag)) view.setImageBitmap(b);
            });
        });
    }

    static void load(String url, Callback callback) {
        if (callback == null) return;
        callback.onLoaded(null);
    }

    static void load(Context context, long accountId, String url, Callback callback) {
        if (context == null || callback == null || url == null) return;
        String cacheKey = cacheKey(accountId, url);
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null) { callback.onLoaded(cached); return; }
        Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            Bitmap b = fetch(app, accountId, url);
            if (b != null) CACHE.put(cacheKey, b);
            callback.onLoaded(b);
        });
    }

    static Bitmap fetch(Context context, long accountId, String url) {
        try (RemoteFileSource.Connection c = RemoteFileSource.open(context, accountId, url);
             InputStream in = c.inputStream()) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static String cacheKey(long accountId, String url) {
        return accountId + "|" + RemoteFileSource.stableUrl(url);
    }
}
