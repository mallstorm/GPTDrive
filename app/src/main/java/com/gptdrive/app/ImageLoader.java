package com.gptdrive.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageLoader {
    interface Callback { void onLoaded(Bitmap bitmap); }

    private static final int THUMB_MAX = 512;
    private static final int VIEWER_MAX = 2048;
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024) {
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
        String cacheKey = cacheKey(accountId, url, THUMB_MAX);
        view.setTag(url);
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null) { view.setImageBitmap(cached); return; }
        Context context = view.getContext().getApplicationContext();
        EXEC.execute(() -> {
            Bitmap b = fetch(context, accountId, url, THUMB_MAX);
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
        String cacheKey = cacheKey(accountId, url, VIEWER_MAX);
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null) { callback.onLoaded(cached); return; }
        Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            Bitmap b = fetch(app, accountId, url, VIEWER_MAX);
            if (b != null) CACHE.put(cacheKey, b);
            callback.onLoaded(b);
        });
    }

    static Bitmap fetch(Context context, long accountId, String url) {
        return fetch(context, accountId, url, VIEWER_MAX);
    }

    private static Bitmap fetch(Context context, long accountId, String url, int maxDimension) {
        try {
            File cached = ImageDiskCache.get(context, accountId, url);
            Bitmap decoded = decodeSampled(cached, maxDimension);
            if (decoded != null) return decoded;

            // Corrupt/partial cache entries are never sticky. Resolve a fresh signed URL once more.
            ImageDiskCache.invalidate(context, accountId, url);
            cached = ImageDiskCache.get(context, accountId, url);
            return decodeSampled(cached, maxDimension);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeSampled(File file, int maxDimension) {
        if (file == null || !file.isFile() || file.length() <= 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        int target = Math.max(128, maxDimension);
        while (largest / (sample * 2) >= target) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private static String cacheKey(long accountId, String url, int maxDimension) {
        return accountId + "|" + maxDimension + "|" + RemoteFileSource.stableUrl(url);
    }
}
