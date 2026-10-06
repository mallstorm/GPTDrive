package com.gptdrive.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent small previews. Unlike ImageDiskCache this stores scaled JPEG thumbnails, not originals. */
final class ThumbnailDiskCache {
    private static final long MAX_BYTES = 192L * 1024L * 1024L;
    private static final long TARGET_BYTES = 144L * 1024L * 1024L;
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Object PRUNE_LOCK = new Object();

    private ThumbnailDiskCache() {}

    static Bitmap get(Context context, long accountId, String sourceUrl, int maxDimension) throws Exception {
        Context app = context.getApplicationContext();
        String stable = RemoteFileSource.stableUrl(sourceUrl);
        String digest = sha256(accountId + "|" + maxDimension + "|" + stable);
        File dir = new File(app.getCacheDir(), "gptdrive-thumbs-v2");
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) throw new java.io.IOException("Не удалось создать cache превью");
        File dst = new File(dir, digest + ".jpg");

        Bitmap cached = decode(dst);
        if (cached != null) {
            dst.setLastModified(System.currentTimeMillis());
            return cached;
        }
        if (dst.exists()) dst.delete();

        Object lock = LOCKS.computeIfAbsent(digest, k -> new Object());
        try {
            synchronized (lock) {
                cached = decode(dst);
                if (cached != null) {
                    dst.setLastModified(System.currentTimeMillis());
                    return cached;
                }

                File original = ImageDiskCache.get(app, accountId, stable);
                Bitmap source = decodeSampled(original, Math.max(maxDimension, 256));
                if (source == null) throw new java.io.IOException("Не удалось декодировать превью");
                Bitmap thumb = fit(source, maxDimension);
                if (thumb != source) source.recycle();

                File tmp = new File(dir, digest + ".part");
                if (tmp.exists()) tmp.delete();
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    if (!thumb.compress(Bitmap.CompressFormat.JPEG, 84, out)) throw new java.io.IOException("Не удалось сохранить превью");
                    out.getFD().sync();
                }
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(tmp); FileOutputStream out = new FileOutputStream(dst)) {
                        byte[] b = new byte[32 * 1024]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    }
                    tmp.delete();
                }
                dst.setLastModified(System.currentTimeMillis());
                prune(dir, dst);
                return thumb;
            }
        } finally {
            LOCKS.remove(digest, lock);
        }
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

    private static void prune(File dir, File protectedFile) {
        if (dir == null || !dir.isDirectory()) return;
        synchronized (PRUNE_LOCK) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
            if (files == null) return;
            long total = 0;
            for (File f : files) total += Math.max(0, f.length());
            if (total <= MAX_BYTES) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File f : files) {
                if (total <= TARGET_BYTES) break;
                if (protectedFile != null && f.getAbsolutePath().equals(protectedFile.getAbsolutePath())) continue;
                long size = Math.max(0, f.length());
                if (f.delete()) total -= size;
            }
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
