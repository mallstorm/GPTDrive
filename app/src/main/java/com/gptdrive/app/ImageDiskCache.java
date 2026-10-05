package com.gptdrive.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

/** Disk cache keyed only by stable account + file reference; signed URLs are never persisted. */
final class ImageDiskCache {
    private static final long MAX_BYTES = 160L * 1024L * 1024L;
    private static final long TARGET_BYTES = 120L * 1024L * 1024L;
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private ImageDiskCache() {}

    static File get(Context context, long accountId, String sourceUrl) throws Exception {
        Context app = context.getApplicationContext();
        String stable = RemoteFileSource.stableUrl(sourceUrl);
        String key = accountId + "|" + stable;
        String digest = sha256(key);
        File dir = new File(app.getCacheDir(), "gptdrive-images");
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory())
            throw new java.io.IOException("Не удалось создать image cache");
        File dst = new File(dir, digest + ".bin");
        if (dst.isFile() && dst.length() > 0) {
            dst.setLastModified(System.currentTimeMillis());
            return dst;
        }

        Object lock = LOCKS.computeIfAbsent(digest, k -> new Object());
        try {
            synchronized (lock) {
                if (dst.isFile() && dst.length() > 0) {
                    dst.setLastModified(System.currentTimeMillis());
                    return dst;
                }
                File tmp = new File(dir, digest + ".part");
                if (tmp.exists()) tmp.delete();
                boolean ok = false;
                try (RemoteFileSource.Connection c = RemoteFileSource.open(app, accountId, stable);
                     InputStream in = c.inputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    out.getFD().sync();
                    ok = tmp.length() > 0;
                } finally {
                    if (!ok && tmp.exists()) tmp.delete();
                }
                if (!ok) throw new java.io.IOException("Пустое изображение");
                if (dst.exists()) dst.delete();
                if (!tmp.renameTo(dst)) {
                    copy(tmp, dst);
                    tmp.delete();
                }
                dst.setLastModified(System.currentTimeMillis());
                prune(dir);
                return dst;
            }
        } finally {
            LOCKS.remove(digest, lock);
        }
    }

    static void invalidate(Context context, long accountId, String sourceUrl) {
        try {
            String key = accountId + "|" + RemoteFileSource.stableUrl(sourceUrl);
            File f = new File(new File(context.getApplicationContext().getCacheDir(), "gptdrive-images"), sha256(key) + ".bin");
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".bin"));
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File f : files) total += Math.max(0, f.length());
        if (total <= MAX_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= TARGET_BYTES) break;
            long size = Math.max(0, f.length());
            if (f.delete()) total -= size;
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
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
        for (byte b : d) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }
}
