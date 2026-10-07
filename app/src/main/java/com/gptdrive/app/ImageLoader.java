package com.gptdrive.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stable preview loader backed by the persistent Thumbnail Index. */
final class ImageLoader {
    interface Callback { void onLoaded(Bitmap bitmap); }

    private static final int THUMB_MAX = 512;
    private static final int VIEWER_MAX = 2560;
    private static final ExecutorService THUMB_EXEC = Executors.newFixedThreadPool(4);
    private static final ExecutorService VIEWER_EXEC = Executors.newFixedThreadPool(2);
    private static final Set<String> PREFETCHING = ConcurrentHashMap.newKeySet();
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
            view.clearColorFilter();
            view.setImageBitmap(cached);
            return;
        }
        Context context = view.getContext().getApplicationContext();
        THUMB_EXEC.execute(() -> {
            Bitmap b = fetchThumb(context, accountId, stable);
            if (b != null) CACHE.put(cacheKey, b);
            view.post(() -> {
                Object tag = view.getTag();
                if (b != null && !b.isRecycled() && stable.equals(tag)) {
                    view.clearColorFilter();
                    view.setImageBitmap(b);
                }
            });
        });
    }

    static void prefetchThumb(Context context, long accountId, String url) {
        prefetchThumb(context, accountId, url, url, null);
    }

    static void prefetchThumb(Context context, long accountId, String identityUrl,
                              String sourceUrl, Runnable ready) {
        if (context == null || identityUrl == null || identityUrl.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        String stable = RemoteFileSource.stableUrl(identityUrl);
        String source = sourceUrl == null || sourceUrl.trim().isEmpty() ? stable : sourceUrl.trim();
        String key = cacheKey(accountId, stable, THUMB_MAX);
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            if (ready != null) ready.run();
            return;
        }
        if (!PREFETCHING.add(key)) return;
        THUMB_EXEC.execute(() -> {
            try {
                Bitmap b = fetchThumb(app, accountId, stable, source);
                if (b != null) {
                    CACHE.put(key, b);
                    if (ready != null) ready.run();
                }
            } finally {
                PREFETCHING.remove(key);
            }
        });
    }

    static void prefetchAround(Context context, long accountId, List<DriveItem> gallery, int center, int radius) {
        if (context == null || gallery == null || gallery.isEmpty()) return;
        int r = Math.max(1, radius);
        int from = Math.max(0, center - r);
        int to = Math.min(gallery.size() - 1, center + r);
        // Current and nearest neighbours first, then the rest of the window.
        for (int delta = 0; delta <= r; delta++) {
            int left = center - delta;
            int right = center + delta;
            if (left >= from && left < gallery.size()) prefetchThumb(context, accountId, gallery.get(left).url);
            if (delta > 0 && right <= to && right < gallery.size()) prefetchThumb(context, accountId, gallery.get(right).url);
        }
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
        return fetchThumb(context, accountId, url, url);
    }

    private static Bitmap fetchThumb(Context context, long accountId, String identityUrl,
                                     String sourceUrl) {
        try {
            return ThumbnailDiskCache.get(context, accountId, sourceUrl, identityUrl, THUMB_MAX);
        } catch (Exception first) {
            try {
                ImageDiskCache.invalidate(context, accountId, sourceUrl);
                return ThumbnailDiskCache.get(context, accountId, sourceUrl, identityUrl, THUMB_MAX);
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
