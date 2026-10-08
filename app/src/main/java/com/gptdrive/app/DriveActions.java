package com.gptdrive.app;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

final class DriveActions {
    static final String LINK_MIME = "application/x-gptdrive-link";
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private DriveActions() {}

    static boolean isLink(DriveItem item) { return item != null && LINK_MIME.equals(item.mime); }
    static boolean isImage(DriveItem item) { return item != null && item.mime != null && item.mime.startsWith("image/"); }

    static void open(Context context, DriveItem item) {
        if (item == null) return;
        if (isLink(item)) {
            Intent i = new Intent(context, BrowserActivity.class);
            i.putExtra(BrowserActivity.EXTRA_SECTION, item.category);
            i.putExtra(BrowserActivity.EXTRA_URL, item.url);
            i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, item.accountId);
            context.startActivity(i);
            return;
        }

        String name = lower(item.name);
        String mime = lower(item.mime);
        String preference = fileMode(context);
        if ("external".equals(preference)) {
            openExternal(context, item);
            return;
        }

        if (isImage(item) || isImageName(name) || mime.startsWith("image/")) {
            Intent i = new Intent(context, ImageViewerActivity.class);
            i.putExtra(ImageViewerActivity.EXTRA_ID, item.id);
            context.startActivity(i);
            return;
        }

        if (supportsInternal(name, mime)) {
            // Auto uses a real Office app when available because it can preserve layout/editing;
            // Internal explicitly keeps Office documents inside GPT Drive.
            if ("auto".equals(preference) && isOffice(name, mime) && hasExternalHandler(context, cloudUri(item), resolvedMime(name, mime))) {
                openExternal(context, item);
                return;
            }
            Intent i = new Intent(context, FilePreviewActivity.class);
            i.putExtra(FilePreviewActivity.EXTRA_ID, item.id);
            context.startActivity(i);
            return;
        }
        openExternal(context, item);
    }

    static void openLocal(Context context, Uri uri) {
        if (uri == null) return;
        String name = displayName(context, uri);
        String mime = context.getContentResolver().getType(uri);
        if (mime == null) mime = "";
        String lowName = lower(name);
        String lowMime = lower(mime);
        String preference = fileMode(context);

        if ("external".equals(preference)) {
            openLocalExternal(context, uri, name, mime);
            return;
        }
        if (supportsInternal(lowName, lowMime)) {
            if ("auto".equals(preference) && isOffice(lowName, lowMime)
                    && hasExternalHandler(context, uri, resolvedMime(lowName, lowMime))) {
                openLocalExternal(context, uri, name, mime);
                return;
            }
            Intent i = new Intent(context, FilePreviewActivity.class);
            i.putExtra(FilePreviewActivity.EXTRA_URI, uri.toString());
            i.putExtra(FilePreviewActivity.EXTRA_NAME, name);
            i.putExtra(FilePreviewActivity.EXTRA_MIME, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(i);
            return;
        }
        openLocalExternal(context, uri, name, mime);
    }

    static void openExternal(Context context, DriveItem item) {
        if (item == null) return;
        String name = lower(item.name);
        String mime = lower(item.mime);
        Uri uri = cloudUri(item);
        launchExternal(context, uri, resolvedMime(name, mime), "Открыть файл");
    }

    static void openLocalExternal(Context context, Uri uri, String name, String mime) {
        launchExternal(context, uri, resolvedMime(lower(name), lower(mime)), "Открыть файл");
    }

    private static void launchExternal(Context context, Uri uri, String mime, String title) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(i, title));
        } catch (Exception e) {
            Toast.makeText(context, "Нет приложения для открытия этого формата", Toast.LENGTH_LONG).show();
        }
    }

    private static boolean hasExternalHandler(Context context, Uri uri, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return !context.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Uri cloudUri(DriveItem item) {
        return DocumentsContract.buildDocumentUri(AUTHORITY, "file:" + item.id);
    }

    static boolean supportsInternal(String name, String mime) {
        return isImageName(name) || mime.startsWith("image/") || isText(name, mime) ||
                name.endsWith(".pdf") || "application/pdf".equals(mime) ||
                isArchive(name, mime) || isOffice(name, mime) || isMedia(name, mime);
    }

    static boolean isText(String name, String mime) {
        return mime.startsWith("text/") || "application/json".equals(mime) ||
                name.matches(".*\\.(md|markdown|txt|log|json|xml|yaml|yml|csv|ini|cfg|conf|properties|java|kt|kts|js|ts|py|c|cc|cpp|h|hpp|css|html|htm|sql|sh|bat|ps1)$");
    }

    static boolean isArchive(String name, String mime) {
        return name.matches(".*\\.(zip|7z|rar)$") || "application/zip".equals(mime) ||
                "application/x-7z-compressed".equals(mime) || "application/vnd.rar".equals(mime) ||
                "application/x-rar-compressed".equals(mime);
    }

    static boolean isOffice(String name, String mime) {
        return name.matches(".*\\.(docx|xlsx|pptx)$") ||
                mime.contains("wordprocessingml") || mime.contains("spreadsheetml") || mime.contains("presentationml");
    }

    static boolean isMedia(String name, String mime) {
        return mime.startsWith("video/") || mime.startsWith("audio/") ||
                name.matches(".*\\.(mp4|m4v|mkv|webm|3gp|mov|avi|ts|m2ts|mp3|m4a|aac|wav|flac|ogg|opus)$");
    }

    private static boolean isImageName(String name) {
        return name.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic|heif|avif)$");
    }

    private static String fileMode(Context context) {
        return context.getSharedPreferences("file-handlers", Context.MODE_PRIVATE).getString("mode", "auto");
    }

    static String inferredMime(String name) {
        String n = lower(name);
        if (n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".xml")) return "text/xml";
        if (n.endsWith(".yaml") || n.endsWith(".yml")) return "text/yaml";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".7z")) return "application/x-7z-compressed";
        if (n.endsWith(".rar")) return "application/vnd.rar";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        return "*/*";
    }

    private static String resolvedMime(String name, String mime) {
        return mime == null || mime.isEmpty() || "application/octet-stream".equals(mime) ? inferredMime(name) : mime;
    }

    private static String displayName(Context context, Uri uri) {
        String result = null;
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) result = c.getString(0);
        } catch (Exception ignored) {}
        if (result == null || result.trim().isEmpty()) result = uri.getLastPathSegment();
        return result == null || result.trim().isEmpty() ? "Файл" : result;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static void download(Context context, DriveItem item) {
        if (item == null || isLink(item)) return;
        Context app = context.getApplicationContext();
        Toast.makeText(context, "Получаю свежую ссылку…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 29) streamToDownloads(app, item);
                else enqueueLegacyDownload(app, item);
            } catch (Exception e) {
                post(app, "Не удалось получить файл: " + shortError(e), Toast.LENGTH_LONG);
            }
        }, "gptdrive-download").start();
    }

    private static void streamToDownloads(Context app, DriveItem item) throws Exception {
        String name = item.name == null || item.name.trim().isEmpty() ? "ChatGPT-file" : safeFileName(item.name);
        String mime = item.mime == null || item.mime.trim().isEmpty() ? inferredMime(name) : item.mime;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri target = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (target == null) throw new java.io.IOException("Не удалось создать файл в Downloads");
        boolean ok = false;
        try (RemoteFileSource.Connection c = RemoteFileSource.open(app, item.accountId, item.url);
             InputStream in = c.inputStream();
             OutputStream out = app.getContentResolver().openOutputStream(target, "w")) {
            if (out == null) throw new java.io.IOException("Downloads недоступен");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            ok = true;
        } finally {
            if (ok) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                app.getContentResolver().update(target, done, null, null);
            } else app.getContentResolver().delete(target, null, null);
        }
        post(app, "Скачано: " + name, Toast.LENGTH_SHORT);
    }

    private static void enqueueLegacyDownload(Context app, DriveItem item) throws Exception {
        String resolved = RemoteFileSource.resolve(app, item.accountId, item.url);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(resolved));
        r.addRequestHeader("Referer", "https://chatgpt.com/");
        r.addRequestHeader("User-Agent", "Mozilla/5.0 Android GPTDrive/0.12.4");
        if (resolved.startsWith("https://chatgpt.com/")) {
            ChatGptSessionStore.Session s = ChatGptSessionStore.load(app, item.accountId);
            if (s != null && s.accessToken != null && !s.accessToken.isEmpty()) {
                r.addRequestHeader("Authorization", "Bearer " + s.accessToken);
                if (s.accountId != null && !s.accountId.isEmpty()) r.addRequestHeader("chatgpt-account-id", s.accountId);
            }
            String cookie = AccountSession.cookieHeader(app, item.accountId, resolved);
            if (cookie != null && !cookie.trim().isEmpty()) r.addRequestHeader("Cookie", cookie);
        }
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        String name = item.name == null || item.name.trim().isEmpty()
                ? URLUtil.guessFileName(resolved, null, item.mime) : safeFileName(item.name);
        r.setTitle(name);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        ((DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
        post(app, "Скачивание: " + name, Toast.LENGTH_SHORT);
    }

    private static void post(Context app, String text, int duration) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Toast.makeText(app, text, duration).show());
    }

    static String shortType(DriveItem item) {
        if (item == null) return "";
        if (isLink(item)) return "ChatGPT";
        String name = lower(item.name);
        String m = lower(item.mime);
        if (m.startsWith("image/") || isImageName(name)) return "Изображение";
        if (m.equals("application/pdf") || name.endsWith(".pdf")) return "PDF";
        if (isOffice(name, m)) return name.endsWith(".xlsx") ? "XLSX" : name.endsWith(".pptx") ? "PPTX" : "DOCX";
        if (name.endsWith(".7z")) return "7Z";
        if (name.endsWith(".rar")) return "RAR";
        if (name.endsWith(".zip") || m.contains("zip")) return "ZIP";
        if (m.contains("android.package") || name.endsWith(".apk")) return "APK";
        if (isMedia(name, m)) return m.startsWith("audio/") ? "Аудио" : "Видео";
        if (m.equals("application/json")) return "JSON";
        if (m.startsWith("text/") || isText(name, m)) return "Текст";
        return "Файл";
    }

    private static String safeFileName(String s) {
        String v = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (v.length() > 140) v = v.substring(0, 140);
        return v.isEmpty() ? "ChatGPT-file" : v;
    }

    private static String shortError(Exception e) {
        String m = e == null ? "" : e.getMessage();
        return m == null || m.trim().isEmpty() ? "ошибка соединения" : m;
    }
}
