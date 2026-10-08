package com.gptdrive.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Safe archive listing/extraction for ZIP, 7Z and RAR. */
final class ArchiveSupport {
    enum Kind { ZIP, SEVEN_Z, RAR }

    static final class Entry {
        final String name;
        final long size;
        final boolean directory;
        Entry(String name, long size, boolean directory) {
            this.name = name == null ? "" : name;
            this.size = size;
            this.directory = directory;
        }
    }

    private static final int MAX_FILES = 2000;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    private ArchiveSupport() {}

    static Kind kindFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".7z")) return Kind.SEVEN_Z;
        if (n.endsWith(".rar")) return Kind.RAR;
        return Kind.ZIP;
    }

    static List<Entry> list(File file, Kind kind, int limit) throws Exception {
        int max = Math.max(1, Math.min(limit, MAX_FILES));
        if (kind == Kind.SEVEN_Z) return list7z(file, max);
        if (kind == Kind.RAR) return listRar(file, max);
        return listZip(file, max);
    }

    static int extractToDownloads(Context context, File file, Kind kind, String archiveName) throws Exception {
        Counter counter = new Counter();
        if (kind == Kind.SEVEN_Z) extract7z(context, file, archiveName, counter);
        else if (kind == Kind.RAR) extractRar(context, file, archiveName, counter);
        else extractZip(context, file, archiveName, counter);
        return counter.count;
    }

    private static List<Entry> listZip(File file, int limit) throws Exception {
        ArrayList<Entry> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements() && result.size() < limit) {
                ZipEntry e = entries.nextElement();
                result.add(new Entry(e.getName(), e.getSize(), e.isDirectory()));
            }
        }
        return result;
    }

    private static List<Entry> list7z(File file, int limit) throws Exception {
        ArrayList<Entry> result = new ArrayList<>();
        try (SevenZFile seven = new SevenZFile(file)) {
            SevenZArchiveEntry e;
            while ((e = seven.getNextEntry()) != null && result.size() < limit) {
                result.add(new Entry(e.getName(), e.getSize(), e.isDirectory()));
            }
        }
        return result;
    }

    private static List<Entry> listRar(File file, int limit) throws Exception {
        ArrayList<Entry> result = new ArrayList<>();
        try (Archive archive = new Archive(file)) {
            for (FileHeader h : archive) {
                result.add(new Entry(h.getFileName(), h.getFullUnpackSize(), h.isDirectory()));
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    private static void extractZip(Context context, File file, String archiveName, Counter counter) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String path = safePath(e.getName());
                beforeEntry(counter, e.getSize());
                try (InputStream in = zip.getInputStream(e)) {
                    save(context, archiveName, path, in, counter);
                }
            }
        }
    }

    private static void extract7z(Context context, File file, String archiveName, Counter counter) throws Exception {
        try (SevenZFile seven = new SevenZFile(file)) {
            SevenZArchiveEntry e;
            while ((e = seven.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String path = safePath(e.getName());
                beforeEntry(counter, e.getSize());
                try (InputStream in = seven.getInputStream(e)) {
                    save(context, archiveName, path, in, counter);
                }
            }
        }
    }

    private static void extractRar(Context context, File file, String archiveName, Counter counter) throws Exception {
        try (Archive archive = new Archive(file)) {
            for (FileHeader h : archive) {
                if (h.isDirectory()) continue;
                String path = safePath(h.getFileName());
                beforeEntry(counter, h.getFullUnpackSize());
                try (InputStream in = archive.getInputStream(h)) {
                    save(context, archiveName, path, in, counter);
                }
            }
        }
    }

    private static void beforeEntry(Counter counter, long declaredSize) throws IOException {
        counter.count++;
        if (counter.count > MAX_FILES) throw new IOException("Архив содержит больше " + MAX_FILES + " файлов");
        if (declaredSize > MAX_FILE_BYTES) throw new IOException("Файл в архиве больше 256 МБ");
        if (declaredSize > 0 && counter.total + declaredSize > MAX_TOTAL_BYTES)
            throw new IOException("Распакованный архив больше 512 МБ");
    }

    private static void save(Context context, String archiveName, String path, InputStream in, Counter counter) throws Exception {
        String[] parts = path.split("/");
        String fileName = parts[parts.length - 1];
        if (fileName.isEmpty()) return;
        StringBuilder inner = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty()) continue;
            if (inner.length() > 0) inner.append('/');
            inner.append(parts[i]);
        }
        String root = safeFolder(baseName(archiveName));
        if (Build.VERSION.SDK_INT >= 29) {
            String rel = Environment.DIRECTORY_DOWNLOADS + "/GPTDrive/" + root;
            if (inner.length() > 0) rel += "/" + inner;
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri dest = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (dest == null) throw new IOException("Не удалось создать файл в Downloads");
            boolean ok = false;
            try (OutputStream out = context.getContentResolver().openOutputStream(dest, "w")) {
                if (out == null) throw new IOException("Хранилище Downloads недоступно");
                copyBounded(in, out, counter);
                ok = true;
            } finally {
                if (ok) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    context.getContentResolver().update(dest, done, null, null);
                } else {
                    context.getContentResolver().delete(dest, null, null);
                }
            }
        } else {
            File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (base == null) throw new IOException("Внешнее хранилище недоступно");
            File dir = new File(base, "GPTDrive/" + root + (inner.length() > 0 ? "/" + inner : ""));
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не удалось создать каталог");
            File dest = new File(dir, fileName);
            try (OutputStream out = new FileOutputStream(dest)) {
                copyBounded(in, out, counter);
            }
        }
    }

    private static void copyBounded(InputStream in, OutputStream out, Counter counter) throws IOException {
        byte[] buf = new byte[32 * 1024];
        long one = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            one += n;
            counter.total += n;
            if (one > MAX_FILE_BYTES) throw new IOException("Файл в архиве превысил 256 МБ");
            if (counter.total > MAX_TOTAL_BYTES) throw new IOException("Распакованный архив превысил 512 МБ");
            out.write(buf, 0, n);
        }
    }

    private static String safePath(String raw) throws IOException {
        if (raw == null) throw new IOException("Пустое имя файла в архиве");
        String path = raw.replace('\\', '/').trim();
        if (path.isEmpty() || path.startsWith("/") || path.contains(":") || path.indexOf('\0') >= 0)
            throw new IOException("Небезопасный путь в архиве");
        String[] parts = path.split("/");
        StringBuilder clean = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IOException("Небезопасный путь в архиве");
            String p = part.replaceAll("[<>:\"|?*]", "_");
            if (clean.length() > 0) clean.append('/');
            clean.append(p);
        }
        if (clean.length() == 0) throw new IOException("Пустое имя файла в архиве");
        return clean.toString();
    }

    private static String baseName(String name) {
        String n = name == null ? "archive" : name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.isEmpty() ? "archive" : n;
    }

    private static String safeFolder(String value) {
        String v = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (v.length() > 80) v = v.substring(0, 80);
        return v.isEmpty() ? "archive" : v;
    }

    private static final class Counter {
        int count;
        long total;
    }
}
