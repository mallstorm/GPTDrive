package com.gptdrive.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Universal internal viewer for cloud files, device files and external ACTION_VIEW intents. */
public final class FilePreviewActivity extends Activity {
    public static final String EXTRA_ID = "file_id";
    public static final String EXTRA_URI = "content_uri";
    public static final String EXTRA_NAME = "display_name";
    public static final String EXTRA_MIME = "mime_type";

    private FrameLayout content;
    private TextView title;
    private DriveItem item;
    private Uri localUri;
    private String displayName = "Файл";
    private String mime = "";
    private File temporary;
    private volatile boolean destroyed;

    private ExoPlayer player;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfDescriptor;
    private ImageView pdfImage;
    private TextView pdfPageLabel;
    private TextView pdfSearchStatus;
    private File pdfFile;
    private int pdfPage;
    private float pdfZoom = 1f;
    private int pdfRenderGeneration;
    private final ArrayList<Integer> pdfHits = new ArrayList<>();
    private int pdfHitCursor = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        PDFBoxResourceLoader.init(getApplicationContext());

        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        if (id >= 0) item = new DriveDb(this).byId(id);
        String uri = getIntent().getStringExtra(EXTRA_URI);
        if (uri != null && !uri.trim().isEmpty()) localUri = Uri.parse(uri);
        if (localUri == null && getIntent().getData() != null && Intent.ACTION_VIEW.equals(getIntent().getAction()))
            localUri = getIntent().getData();

        if (item != null) {
            displayName = item.name == null || item.name.trim().isEmpty() ? "Файл" : item.name;
            mime = item.mime == null ? "" : item.mime;
        } else if (localUri != null) {
            String n = getIntent().getStringExtra(EXTRA_NAME);
            String m = getIntent().getStringExtra(EXTRA_MIME);
            displayName = n == null || n.trim().isEmpty() ? queryDisplayName(localUri) : n;
            mime = m == null || m.trim().isEmpty() ? safeMime(localUri) : m;
        }

        buildUi();
        if (item == null && localUri == null) {
            showMessage("Файл не найден");
            return;
        }
        showMessage("Загрузка файла…");
        new Thread(this::load, "gptdrive-preview-load").start();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F9FA);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), 0, dp(12), 0);
        TextView back = text("‹", 32, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(56)));
        title = text(displayName, 17, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(58)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void load() {
        try {
            String suffix = suffix(displayName);
            File cache = File.createTempFile("gptdrive-preview-", suffix, getCacheDir());
            temporary = cache;
            long max = maxBytes(displayName, mime);
            try (InputStream in = openSource(); OutputStream out = new FileOutputStream(cache)) {
                copyBounded(in, out, max);
            }
            if (destroyed) return;
            runOnUiThread(() -> dispatch(cache));
        } catch (Exception e) {
            if (!destroyed) runOnUiThread(() -> showMessage("Не удалось открыть: " + shortError(e)));
        }
    }

    private InputStream openSource() throws Exception {
        if (item != null) {
            RemoteFileSource.Connection connection = RemoteFileSource.open(this, item.accountId, item.url);
            return new ConnectionInputStream(connection);
        }
        InputStream in = getContentResolver().openInputStream(localUri);
        if (in == null) throw new IOException("Не удалось прочитать выбранный файл");
        return in;
    }

    private void dispatch(File file) {
        String name = lower(displayName);
        String type = lower(mime);
        if (type.startsWith("image/") || name.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic|heif|avif)$")) {
            showImage(file);
        } else if (name.endsWith(".md") || name.endsWith(".markdown")) {
            showMarkdown(file);
        } else if (DriveActions.isText(name, type)) {
            showText(file, name);
        } else if (name.endsWith(".pdf") || "application/pdf".equals(type)) {
            showPdf(file);
        } else if (DriveActions.isArchive(name, type)) {
            showArchive(file, ArchiveSupport.kindFor(name));
        } else if (DriveActions.isOffice(name, type)) {
            showOffice(file, name);
        } else if (DriveActions.isMedia(name, type)) {
            showMedia(file);
        } else {
            showUnsupported();
        }
    }

    private void showImage(File file) {
        showMessage("Открываю изображение…");
        new Thread(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                int sample = 1;
                while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                if (bitmap == null) throw new IOException("Формат изображения не поддерживается системой");
                if (!destroyed) runOnUiThread(() -> {
                    ScrollView scroll = new ScrollView(this);
                    ImageView image = new ImageView(this);
                    image.setAdjustViewBounds(true);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    image.setImageBitmap(bitmap);
                    image.setBackgroundColor(Color.BLACK);
                    scroll.addView(image, new ScrollView.LayoutParams(-1, -2));
                    setContent(scroll);
                });
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> showMessage("Ошибка изображения: " + shortError(e)));
            }
        }, "gptdrive-image").start();
    }

    private void showMarkdown(File file) {
        showMessage("Форматирую Markdown…");
        new Thread(() -> {
            try {
                String markdown = readUtf8(file, 8L * 1024 * 1024);
                String html = MarkdownRenderer.toHtml(markdown);
                if (!destroyed) runOnUiThread(() -> {
                    WebView web = new WebView(this);
                    WebSettings s = web.getSettings();
                    s.setJavaScriptEnabled(false);
                    s.setAllowFileAccess(false);
                    s.setAllowContentAccess(false);
                    s.setBuiltInZoomControls(true);
                    s.setDisplayZoomControls(false);
                    web.setBackgroundColor(0xFFF7F9FA);
                    web.setWebViewClient(new WebViewClient());
                    web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                    setContent(web);
                });
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> showMessage("Ошибка Markdown: " + shortError(e)));
            }
        }, "gptdrive-markdown").start();
    }

    private void showText(File file, String name) {
        showMessage("Читаю текст…");
        new Thread(() -> {
            try {
                String value = readUtf8(file, 8L * 1024 * 1024);
                if (name.endsWith(".json")) value = prettyJson(value);
                final String shown = value;
                if (!destroyed) runOnUiThread(() -> {
                    LinearLayout body = scrollBody();
                    TextView t = label(body, shown, 14);
                    t.setTextIsSelectable(true);
                    t.setTypeface(Typeface.MONOSPACE);
                });
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> showMessage("Ошибка текста: " + shortError(e)));
            }
        }, "gptdrive-text").start();
    }

    private void showMedia(File file) {
        releasePlayer();
        PlayerView playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setBackgroundColor(Color.BLACK);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                Toast.makeText(FilePreviewActivity.this,
                        "Не удалось воспроизвести этот кодек. Можно открыть файл внешним плеером.", Toast.LENGTH_LONG).show();
            }
        });
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
        player.play();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.BLACK);
        box.addView(playerView, new LinearLayout.LayoutParams(-1, 0, 1));
        Button external = new Button(this);
        external.setText("Открыть внешним плеером");
        external.setOnClickListener(v -> openExternal());
        box.addView(external, new LinearLayout.LayoutParams(-1, dp(52)));
        setContent(box);
    }

    private void showArchive(File file, ArchiveSupport.Kind kind) {
        showMessage("Читаю архив…");
        new Thread(() -> {
            try {
                List<ArchiveSupport.Entry> entries = ArchiveSupport.list(file, kind, 1000);
                if (!destroyed) runOnUiThread(() -> {
                    LinearLayout body = scrollBody();
                    label(body, archiveTitle(kind) + " · " + entries.size() + (entries.size() >= 1000 ? "+" : "") + " записей", 13).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    for (ArchiveSupport.Entry e : entries) {
                        String size = e.directory ? "" : "  ·  " + humanBytes(e.size);
                        label(body, (e.directory ? "📁 " : "📄 ") + e.name + size, 12);
                    }
                    if (entries.size() >= 1000) label(body, "Показаны первые 1000 записей.", 11);
                    Button extract = new Button(this);
                    extract.setText("Извлечь в Загрузки / GPTDrive");
                    extract.setOnClickListener(v -> extractArchive(file, kind, extract, body));
                    body.addView(extract, new LinearLayout.LayoutParams(-1, dp(54)));
                    Button external = new Button(this);
                    external.setText("Открыть другим архиватором");
                    external.setOnClickListener(v -> openExternal());
                    body.addView(external, new LinearLayout.LayoutParams(-1, dp(54)));
                });
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> archiveError(e));
            }
        }, "gptdrive-archive-list").start();
    }

    private void extractArchive(File file, ArchiveSupport.Kind kind, Button button, LinearLayout body) {
        button.setEnabled(false);
        button.setText("Распаковка…");
        new Thread(() -> {
            try {
                int count = ArchiveSupport.extractToDownloads(this, file, kind, displayName);
                if (!destroyed) runOnUiThread(() -> {
                    label(body, "Готово. Извлечено файлов: " + count, 12);
                    button.setText("Извлечено");
                });
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> {
                    label(body, "Ошибка распаковки: " + shortError(e), 12);
                    button.setEnabled(true);
                    button.setText("Повторить распаковку");
                });
            }
        }, "gptdrive-archive-extract").start();
    }

    private void archiveError(Exception error) {
        LinearLayout body = scrollBody();
        label(body, "Не удалось прочитать архив: " + shortError(error), 13);
        label(body, "Зашифрованные или редкие варианты архива можно передать установленному архиватору Android.", 12);
        Button external = new Button(this);
        external.setText("Открыть другим архиватором");
        external.setOnClickListener(v -> openExternal());
        body.addView(external, new LinearLayout.LayoutParams(-1, dp(54)));
    }

    private void showOffice(File file, String name) {
        showMessage("Разбираю документ…");
        new Thread(() -> {
            try {
                String result;
                if (name.endsWith(".docx")) result = previewDocx(file);
                else if (name.endsWith(".xlsx")) result = previewXlsx(file);
                else result = previewPptx(file);
                final String shown = result;
                if (!destroyed) runOnUiThread(() -> {
                    LinearLayout body = scrollBody();
                    label(body, "Быстрый встроенный предпросмотр", 12).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    TextView text = label(body, shown, 14);
                    text.setTextIsSelectable(true);
                    Button external = new Button(this);
                    external.setText("Открыть в Office-приложении");
                    external.setOnClickListener(v -> openExternal());
                    body.addView(external, new LinearLayout.LayoutParams(-1, dp(54)));
                });
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> {
                    LinearLayout body = scrollBody();
                    label(body, "Не удалось построить предпросмотр документа: " + shortError(e), 13);
                    Button external = new Button(this);
                    external.setText("Открыть в Office-приложении");
                    external.setOnClickListener(v -> openExternal());
                    body.addView(external, new LinearLayout.LayoutParams(-1, dp(54)));
                });
            }
        }, "gptdrive-office").start();
    }

    private void showPdf(File file) {
        closePdf();
        pdfFile = file;
        try {
            pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(pdfDescriptor);
            if (pdfRenderer.getPageCount() == 0) throw new IOException("PDF не содержит страниц");
            pdfPage = 0;
            pdfZoom = 1f;
            pdfHits.clear();
            pdfHitCursor = -1;

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(8), dp(6), dp(8), dp(8));

            LinearLayout nav = new LinearLayout(this);
            nav.setGravity(Gravity.CENTER_VERTICAL);
            Button prev = smallButton("‹");
            Button next = smallButton("›");
            Button minus = smallButton("−");
            Button plus = smallButton("+");
            pdfPageLabel = text("", 12, true);
            pdfPageLabel.setGravity(Gravity.CENTER);
            pdfPageLabel.setOnClickListener(v -> choosePdfPage());
            prev.setOnClickListener(v -> { if (pdfPage > 0) { pdfPage--; renderPdfPage(); } });
            next.setOnClickListener(v -> { if (pdfRenderer != null && pdfPage + 1 < pdfRenderer.getPageCount()) { pdfPage++; renderPdfPage(); } });
            minus.setOnClickListener(v -> { pdfZoom = Math.max(.7f, pdfZoom - .25f); renderPdfPage(); });
            plus.setOnClickListener(v -> { pdfZoom = Math.min(3f, pdfZoom + .25f); renderPdfPage(); });
            nav.addView(prev, new LinearLayout.LayoutParams(dp(48), dp(44)));
            nav.addView(pdfPageLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
            nav.addView(next, new LinearLayout.LayoutParams(dp(48), dp(44)));
            nav.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(44)));
            nav.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(44)));
            root.addView(nav);

            LinearLayout searchRow = new LinearLayout(this);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            EditText query = new EditText(this);
            query.setSingleLine(true);
            query.setHint("Поиск по PDF");
            query.setTextSize(12);
            Button search = smallButton("Найти");
            Button nextHit = smallButton("Далее");
            pdfSearchStatus = text("", 10, false);
            search.setOnClickListener(v -> searchPdf(query.getText().toString()));
            nextHit.setOnClickListener(v -> nextPdfHit());
            searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(46), 1));
            searchRow.addView(search, new LinearLayout.LayoutParams(dp(72), dp(44)));
            searchRow.addView(nextHit, new LinearLayout.LayoutParams(dp(72), dp(44)));
            root.addView(searchRow);
            root.addView(pdfSearchStatus, new LinearLayout.LayoutParams(-1, dp(26)));

            ScrollView scroll = new ScrollView(this);
            pdfImage = new ImageView(this);
            pdfImage.setAdjustViewBounds(true);
            pdfImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pdfImage.setBackgroundColor(Color.WHITE);
            scroll.addView(pdfImage, new ScrollView.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            setContent(root);
            renderPdfPage();
        } catch (Exception e) {
            closePdf();
            showMessage("Ошибка PDF: " + shortError(e));
        }
    }

    private void renderPdfPage() {
        if (pdfRenderer == null || pdfImage == null) return;
        final int pageIndex = pdfPage;
        final float zoom = pdfZoom;
        final int generation = ++pdfRenderGeneration;
        pdfPageLabel.setText((pageIndex + 1) + " / " + pdfRenderer.getPageCount() + "  ·  " + Math.round(zoom * 100) + "%");
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                synchronized (this) {
                    if (destroyed || pdfRenderer == null || pageIndex >= pdfRenderer.getPageCount()) return;
                    try (PdfRenderer.Page page = pdfRenderer.openPage(pageIndex)) {
                        int target = Math.max(getResources().getDisplayMetrics().widthPixels - dp(20), 600);
                        int width = Math.min(2400, Math.max(400, Math.round(target * zoom)));
                        int height = Math.max(1, (int) ((long) width * page.getHeight() / Math.max(1, page.getWidth())));
                        long pixels = (long) width * height;
                        if (pixels > 14_000_000L) {
                            double scale = Math.sqrt(14_000_000d / pixels);
                            width = Math.max(400, (int) (width * scale));
                            height = Math.max(1, (int) (height * scale));
                        }
                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                }
                Bitmap finalBitmap = bitmap;
                if (!destroyed) runOnUiThread(() -> {
                    if (generation != pdfRenderGeneration || pdfImage == null) {
                        finalBitmap.recycle();
                        return;
                    }
                    if (pdfImage.getDrawable() instanceof BitmapDrawable) {
                        Bitmap old = ((BitmapDrawable) pdfImage.getDrawable()).getBitmap();
                        pdfImage.setImageBitmap(finalBitmap);
                        if (old != null && old != finalBitmap && !old.isRecycled()) old.recycle();
                    } else pdfImage.setImageBitmap(finalBitmap);
                });
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                if (!destroyed) runOnUiThread(() -> Toast.makeText(this, "Страница PDF: " + shortError(e), Toast.LENGTH_SHORT).show());
            }
        }, "gptdrive-pdf-render").start();
    }

    private void choosePdfPage() {
        if (pdfRenderer == null) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(pdfPage + 1));
        input.selectAll();
        new AlertDialog.Builder(this).setTitle("Перейти к странице")
                .setView(input)
                .setPositiveButton("Открыть", (d, w) -> {
                    try {
                        int page = Integer.parseInt(input.getText().toString().trim()) - 1;
                        if (pdfRenderer != null && page >= 0 && page < pdfRenderer.getPageCount()) {
                            pdfPage = page;
                            renderPdfPage();
                        }
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Отмена", null).show();
    }

    private void searchPdf(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || pdfFile == null || pdfSearchStatus == null) return;
        pdfSearchStatus.setText("Поиск…");
        new Thread(() -> {
            ArrayList<Integer> hits = new ArrayList<>();
            try (PDDocument document = PDDocument.load(pdfFile)) {
                PDFTextStripper stripper = new PDFTextStripper();
                int pages = document.getNumberOfPages();
                for (int p = 1; p <= pages && !destroyed; p++) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    String text = stripper.getText(document);
                    if (text != null && text.toLowerCase(Locale.ROOT).contains(q)) hits.add(p - 1);
                }
                if (!destroyed) runOnUiThread(() -> {
                    pdfHits.clear();
                    pdfHits.addAll(hits);
                    if (hits.isEmpty()) {
                        pdfHitCursor = -1;
                        pdfSearchStatus.setText("Совпадений нет");
                    } else {
                        pdfHitCursor = 0;
                        pdfPage = hits.get(0);
                        pdfSearchStatus.setText("Найдено на страницах: " + hits.size() + " · результат 1/" + hits.size());
                        renderPdfPage();
                    }
                });
            } catch (Exception e) {
                if (!destroyed) runOnUiThread(() -> pdfSearchStatus.setText("Поиск недоступен: " + shortError(e)));
            }
        }, "gptdrive-pdf-search").start();
    }

    private void nextPdfHit() {
        if (pdfHits.isEmpty()) return;
        pdfHitCursor = (pdfHitCursor + 1) % pdfHits.size();
        pdfPage = pdfHits.get(pdfHitCursor);
        pdfSearchStatus.setText("Найдено на страницах: " + pdfHits.size() + " · результат " + (pdfHitCursor + 1) + "/" + pdfHits.size());
        renderPdfPage();
    }

    private String previewDocx(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            String xml = readZipText(zip, "word/document.xml", 12L * 1024 * 1024);
            xml = xml.replaceAll("<w:tab[^>]*/>", "\t").replaceAll("<w:br[^>]*/>", "\n")
                    .replaceAll("</w:p>", "\n").replaceAll("</w:tr>", "\n")
                    .replaceAll("</w:tc>", "\t");
            return xmlText(xml).replaceAll("\n{3,}", "\n\n").trim();
        }
    }

    private String previewPptx(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ArrayList<String> slides = new ArrayList<>();
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName();
                if (name.matches("ppt/slides/slide\\d+\\.xml")) slides.add(name);
            }
            slides.sort(Comparator.comparingInt(FilePreviewActivity::numberInName));
            StringBuilder out = new StringBuilder();
            int n = 1;
            for (String slide : slides) {
                String xml = readZipText(zip, slide, 4L * 1024 * 1024);
                xml = xml.replaceAll("</a:p>", "\n").replaceAll("</a:t>", " ");
                String text = xmlText(xml).replaceAll("[ \t]+\n", "\n").trim();
                out.append("Слайд ").append(n++).append("\n").append(text).append("\n\n");
                if (out.length() > 2_000_000) break;
            }
            return out.length() == 0 ? "Текст слайдов не найден." : out.toString().trim();
        }
    }

    private String previewXlsx(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            ArrayList<String> shared = new ArrayList<>();
            ZipEntry sharedEntry = zip.getEntry("xl/sharedStrings.xml");
            if (sharedEntry != null) {
                String xml = readZipText(zip, "xl/sharedStrings.xml", 12L * 1024 * 1024);
                Matcher si = Pattern.compile("<si[^>]*>(.*?)</si>", Pattern.DOTALL).matcher(xml);
                while (si.find() && shared.size() < 200_000) shared.add(xmlText(si.group(1)).trim());
            }
            ArrayList<String> sheets = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("xl/worksheets/sheet\\d+\\.xml")) sheets.add(name);
            }
            sheets.sort(Comparator.comparingInt(FilePreviewActivity::numberInName));
            StringBuilder out = new StringBuilder();
            Pattern rowP = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL);
            Pattern cellP = Pattern.compile("<c([^>]*)>(.*?)</c>", Pattern.DOTALL);
            Pattern valueP = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);
            int sheetNo = 1;
            for (String sheet : sheets) {
                out.append("Лист ").append(sheetNo++).append("\n");
                String xml = readZipText(zip, sheet, 16L * 1024 * 1024);
                Matcher rows = rowP.matcher(xml);
                int rowCount = 0;
                while (rows.find() && rowCount++ < 1000 && out.length() < 2_000_000) {
                    Matcher cells = cellP.matcher(rows.group(1));
                    boolean first = true;
                    while (cells.find()) {
                        if (!first) out.append('\t');
                        first = false;
                        String attrs = cells.group(1);
                        String body = cells.group(2);
                        String value = "";
                        Matcher vm = valueP.matcher(body);
                        if (vm.find()) value = xmlText(vm.group(1));
                        else if (body.contains("<t")) value = xmlText(body);
                        if (attrs.contains("t=\"s\"")) {
                            try {
                                int index = Integer.parseInt(value.trim());
                                if (index >= 0 && index < shared.size()) value = shared.get(index);
                            } catch (Exception ignored) {}
                        }
                        out.append(value);
                    }
                    out.append('\n');
                }
                out.append('\n');
            }
            return out.length() == 0 ? "Данные таблицы не найдены." : out.toString().trim();
        }
    }

    private static int numberInName(String name) {
        Matcher m = Pattern.compile("(\\d+)").matcher(name);
        int last = 0;
        while (m.find()) {
            try { last = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return last;
    }

    private static String readZipText(ZipFile zip, String path, long max) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IOException("В документе отсутствует " + path);
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > max) throw new IOException("Слишком большой внутренний XML");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String xmlText(String xml) {
        return xml.replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#10;", "\n");
    }

    private void showUnsupported() {
        LinearLayout body = scrollBody();
        label(body, "Для этого формата нет встроенного просмотрщика.", 13);
        Button external = new Button(this);
        external.setText("Открыть приложением Android");
        external.setOnClickListener(v -> openExternal());
        body.addView(external, new LinearLayout.LayoutParams(-1, dp(54)));
        if (item != null) {
            Button download = new Button(this);
            download.setText("Скачать файл");
            download.setOnClickListener(v -> DriveActions.download(this, item));
            body.addView(download, new LinearLayout.LayoutParams(-1, dp(54)));
        }
    }

    private void openExternal() {
        if (item != null) DriveActions.openExternal(this, item);
        else if (localUri != null) DriveActions.openLocalExternal(this, localUri, displayName, mime);
    }

    private LinearLayout scrollBody() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(10), dp(14), dp(28));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        setContent(scroll);
        return body;
    }

    private void showMessage(String value) {
        LinearLayout body = scrollBody();
        label(body, value, 13);
    }

    private void setContent(View view) {
        releasePlayerIfReplacing(view);
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView label(LinearLayout parent, String value, int size) {
        TextView v = text(value, size, false);
        v.setPadding(dp(5), dp(6), dp(5), dp(6));
        parent.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(0xFF182230);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button smallButton(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setPadding(dp(2), 0, dp(2), 0);
        return b;
    }

    private String readUtf8(File file, long max) throws Exception {
        if (file.length() > max) throw new IOException("Файл слишком большой для текстового просмотра");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > max) throw new IOException("Текст превысил безопасный лимит");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String prettyJson(String value) {
        try {
            String t = value.trim();
            if (t.startsWith("{")) return new JSONObject(t).toString(2);
            if (t.startsWith("[")) return new JSONArray(t).toString(2);
        } catch (Exception ignored) {}
        return value;
    }

    private void copyBounded(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) throw new IOException("Файл больше " + humanBytes(max) + ". Используйте скачивание или внешнее приложение.");
            out.write(buf, 0, n);
        }
    }

    private static long maxBytes(String name, String mime) {
        String n = lower(name), m = lower(mime);
        if (DriveActions.isMedia(n, m) || DriveActions.isArchive(n, m)) return 512L * 1024 * 1024;
        if (n.endsWith(".pdf") || "application/pdf".equals(m)) return 256L * 1024 * 1024;
        if (DriveActions.isOffice(n, m)) return 128L * 1024 * 1024;
        return 128L * 1024 * 1024;
    }

    private static String suffix(String name) {
        if (name == null) return ".tmp";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 12) return ".tmp";
        String suffix = name.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        return suffix.length() < 2 ? ".tmp" : suffix;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String value = c.getString(0);
                if (value != null && !value.trim().isEmpty()) return value;
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "Файл" : last;
    }

    private String safeMime(Uri uri) {
        try {
            String value = getContentResolver().getType(uri);
            return value == null ? "" : value;
        } catch (Exception ignored) { return ""; }
    }

    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }

    private static String archiveTitle(ArchiveSupport.Kind kind) {
        return kind == ArchiveSupport.Kind.RAR ? "RAR" : kind == ArchiveSupport.Kind.SEVEN_Z ? "7-Zip" : "ZIP";
    }

    private static String humanBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f КБ", kb);
        double mb = kb / 1024d;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f МБ", mb);
        return String.format(Locale.ROOT, "%.1f ГБ", mb / 1024d);
    }

    private static String shortError(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) return e == null ? "неизвестная ошибка" : e.getClass().getSimpleName();
        return e.getMessage();
    }

    private void releasePlayerIfReplacing(View incoming) {
        if (!(incoming instanceof LinearLayout && containsPlayerView((LinearLayout) incoming))) releasePlayer();
    }

    private boolean containsPlayerView(LinearLayout layout) {
        for (int i = 0; i < layout.getChildCount(); i++) if (layout.getChildAt(i) instanceof PlayerView) return true;
        return false;
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private synchronized void closePdf() {
        pdfRenderGeneration++;
        if (pdfRenderer != null) {
            try { pdfRenderer.close(); } catch (Exception ignored) {}
            pdfRenderer = null;
        }
        if (pdfDescriptor != null) {
            try { pdfDescriptor.close(); } catch (Exception ignored) {}
            pdfDescriptor = null;
        }
        pdfImage = null;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onPause() {
        if (player != null) player.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        releasePlayer();
        closePdf();
        if (temporary != null) temporary.delete();
        super.onDestroy();
    }

    /** Closes the remote HTTP connection together with its stream. */
    private static final class ConnectionInputStream extends InputStream {
        private final RemoteFileSource.Connection connection;
        private final InputStream delegate;
        ConnectionInputStream(RemoteFileSource.Connection connection) throws Exception {
            this.connection = connection;
            this.delegate = connection.inputStream();
        }
        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return delegate.read(b, off, len); }
        @Override public long skip(long n) throws IOException { return delegate.skip(n); }
        @Override public int available() throws IOException { return delegate.available(); }
        @Override public void close() throws IOException {
            IOException first = null;
            try { delegate.close(); } catch (IOException e) { first = e; }
            try { connection.close(); } catch (Exception e) { if (first == null && e instanceof IOException) first = (IOException) e; }
            if (first != null) throw first;
        }
    }
}
