package com.gptdrive.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stable preview loader: small thumbnails are persistent and can load concurrently. */
final class ImageLoader {
    interface Callback { void onLoaded(Bitmap bitmap); }

    private static final int THUMB_MAX = 512;
    private static final int VIEWER_MAX = 2560;
    private static final ExecutorService THUMB_EXEC = Executors.newFixedThreadPool(4);
    private static final ExecutorService VIEWER_EXEC = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(48 * 1024) {
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
        String stable = RemoteFileSource.stableUrl(url);
        String cacheKey = cacheKey(accountId, stable, THUMB_MAX);
        view.setTag(stable);
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        Context context = view.getContext().getApplicationContext();
        THUMB_EXEC.execute(() -> {
            Bitmap b = fetchThumb(context, accountId, stable);
            if (b != null) CACHE.put(cacheKey, b);
            view.post(() -> {
                Object tag = view.getTag();
                if (b != null && !b.isRecycled() && stable.equals(tag)) view.setImageBitmap(b);
            });
        });
    }

    static void load(String url, Callback callback) {
        if (callback != null) callback.onLoaded(null);
    }

    static void load(Context context, long accountId, String url, Callback callback) {
        if (context == null || callback == null || url == null) return;
        String stable = RemoteFileSource.stableUrl(url);
        String cacheKey = cacheKey(accountId, stable, VIEWER_MAX);
        Bitmap cached = CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            callback.onLoaded(cached);
            return;
        }
        Context app = context.getApplicationContext();
        VIEWER_EXEC.execute(() -> {
            Bitmap b = fetch(app, accountId, stable, VIEWER_MAX);
            if (b != null) CACHE.put(cacheKey, b);
            callback.onLoaded(b);
        });
    }

    static Bitmap fetch(Context context, long accountId, String url) {
        return fetch(context, accountId, url, VIEWER_MAX);
    }

    private static Bitmap fetchThumb(Context context, long accountId, String url) {
        try {
            return ThumbnailDiskCache.get(context, accountId, url, THUMB_MAX);
        } catch (Exception first) {
            try {
                ImageDiskCache.invalidate(context, accountId, url);
                return ThumbnailDiskCache.get(context, accountId, url, THUMB_MAX);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static Bitmap fetch(Context context, long accountId, String url, int maxDimension) {
        try {
            File cached = ImageDiskCache.get(context, accountId, url);
            Bitmap decoded = decodeSampled(cached, maxDimension);
            if (decoded != null) return decoded;
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
