package com.gptdrive.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent thumbnail index.
 *
 * Important: thumbnails are deliberately stored under filesDir, not cacheDir, so Android does not
 * evict them as ordinary cache. There is no artificial size ceiling. The nominal 768 MiB budget is
 * only a planning target; the index may grow beyond it while the device has enough free storage.
 * Old thumbnails are removed only by the low-storage safety guard or together with app data.
 */
final class ThumbnailDiskCache {
    static final long NOMINAL_BUDGET_BYTES = 768L * 1024L * 1024L;
    private static final long MIN_FREE_BYTES = 768L * 1024L * 1024L;
    private static final long TARGET_FREE_BYTES = 1536L * 1024L * 1024L;
    private static final String DIR_NAME = "thumbnail-index-v3";
    private static final String LEGACY_DIR_NAME = "gptdrive-thumbs-v2";

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Object PRUNE_LOCK = new Object();

    private ThumbnailDiskCache() {}

    static Bitmap get(Context context, long accountId, String sourceUrl, int maxDimension) throws Exception {
        return get(context, accountId, sourceUrl, sourceUrl, maxDimension);
    }

    static Bitmap get(Context context, long accountId, String sourceUrl,
                      String identityUrl, int maxDimension) throws Exception {
        Context app = context.getApplicationContext();
        String stableSource = RemoteFileSource.stableUrl(sourceUrl);
        String identity = stableIdentity(RemoteFileSource.stableUrl(identityUrl));
        String digest = sha256(accountId + "|" + maxDimension + "|" + identity);
        File dir = indexDir(app);
        File dst = new File(dir, digest + ".jpg");

        Bitmap cached = decode(dst);
        if (cached != null) {
            touch(dst);
            return cached;
        }
        if (dst.exists()) dst.delete();

        // One-time lazy migration from the old disposable cache. This preserves already downloaded
        // previews where the legacy key is still compatible.
        migrateLegacyIfPresent(app, accountId, stableSource, maxDimension, dst);
        cached = decode(dst);
        if (cached != null) {
            touch(dst);
            return cached;
        }

        Object lock = LOCKS.computeIfAbsent(digest, k -> new Object());
        try {
            synchronized (lock) {
                cached = decode(dst);
                if (cached != null) {
                    touch(dst);
                    return cached;
                }

                File original = ImageDiskCache.get(app, accountId, stableSource);
                Bitmap source = decodeSampled(original, Math.max(maxDimension, 300));
                if (source == null) throw new java.io.IOException("Не удалось декодировать превью");
                Bitmap thumb = fit(source, maxDimension);
                if (thumb != source) source.recycle();

                File tmp = new File(dir, digest + ".part");
                if (tmp.exists()) tmp.delete();
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    if (!thumb.compress(Bitmap.CompressFormat.JPEG, 82, out))
                        throw new java.io.IOException("Не удалось сохранить превью");
                    out.getFD().sync();
                }
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) {
                    copy(tmp, dst);
                    tmp.delete();
                }
                touch(dst);
                lowStorageGuard(dir, dst);
                // The original was only needed to build the preview; do not keep megabytes per file.
                ImageDiskCache.invalidate(app, accountId, stableSource);
                return thumb;
            }
        } finally {
            LOCKS.remove(digest, lock);
        }
    }

    static long sizeBytes(Context context) {
        if (context == null) return 0;
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files == null) return 0;
        long total = 0;
        for (File f : files) total += Math.max(0, f.length());
        return total;
    }

    static int itemCount(Context context) {
        if (context == null) return 0;
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        return files == null ? 0 : files.length;
    }

    private static File indexDir(Context app) throws Exception {
        File dir = new File(app.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory())
            throw new java.io.IOException("Не удалось создать постоянный индекс превью");
        return dir;
    }

    private static String stableIdentity(String value) {
        String fileId = RemoteFileSource.extractFileId(value);
        if (fileId != null && !fileId.trim().isEmpty()) return "file:" + fileId.trim();
        if (value == null) return "";
        String s = value.trim();
        try {
            Uri u = Uri.parse(s);
            if (("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) && u.getHost() != null) {
                // Signed download URLs usually rotate only query tokens/signatures. Keep the stable
                // origin + path so the same remote image reuses the same thumbnail after re-login.
                String path = u.getEncodedPath();
                return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT) +
                        (path == null ? "" : path);
            }
        } catch (Exception ignored) {}
        return s;
    }

    private static void migrateLegacyIfPresent(Context app, long accountId, String stableSource,
                                               int maxDimension, File dst) {
        if (dst.exists()) return;
        try {
            String oldDigest = sha256(accountId + "|" + maxDimension + "|" + stableSource);
            File old = new File(new File(app.getCacheDir(), LEGACY_DIR_NAME), oldDigest + ".jpg");
            if (!old.isFile() || old.length() <= 0) return;
            File tmp = new File(dst.getParentFile(), dst.getName() + ".migrate");
            copy(old, tmp);
            if (decode(tmp) == null) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(dst)) {
                copy(tmp, dst);
                tmp.delete();
            }
            touch(dst);
        } catch (Exception ignored) {}
    }

    private static Bitmap decode(File file) {
        if (file == null || !file.isFile() || file.length() <= 0) return null;
        try { return BitmapFactory.decodeFile(file.getAbsolutePath()); }
        catch (Exception e) { return null; }
    }

    private static Bitmap decodeSampled(File file, int maxDimension) {
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / (sample * 2) >= maxDimension) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private static Bitmap fit(Bitmap source, int maxDimension) {
        int largest = Math.max(source.getWidth(), source.getHeight());
        if (largest <= maxDimension) return source;
        float scale = maxDimension / (float) largest;
        int w = Math.max(1, Math.round(source.getWidth() * scale));
        int h = Math.max(1, Math.round(source.getHeight() * scale));
        return Bitmap.createScaledBitmap(source, w, h, true);
    }

    /**
     * No quota-based pruning. We only protect the phone from genuinely running out of space.
     * If free space falls below 768 MiB, delete least-recently-used thumbnails until roughly
     * 1.5 GiB is free again. This means 768 MiB is not an index ceiling.
     */
    private static void lowStorageGuard(File dir, File protectedFile) {
        if (dir == null || !dir.isDirectory()) return;
        synchronized (PRUNE_LOCK) {
            long free = dir.getUsableSpace();
            if (free >= MIN_FREE_BYTES) return;
            File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
            if (files == null || files.length == 0) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File f : files) {
                if (dir.getUsableSpace() >= TARGET_FREE_BYTES) break;
                if (protectedFile != null && sameFile(f, protectedFile)) continue;
                f.delete();
            }
        }
    }

    private static void touch(File file) {
        if (file != null) file.setLastModified(System.currentTimeMillis());
    }

    private static boolean sameFile(File a, File b) {
        try { return a.getCanonicalPath().equals(b.getCanonicalPath()); }
        catch (Exception e) { return a.getAbsolutePath().equals(b.getAbsolutePath()); }
    }

    private static void copy(File from, File to) throws Exception {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            out.getFD().sync();
        }
    }

    private static String sha256(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte b : d) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }
}
