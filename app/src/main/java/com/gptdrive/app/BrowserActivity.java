package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native synchronization host. Authentication is intentionally outside WebView. */
public final class BrowserActivity extends Activity {
    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_NEW_LOGIN = "new_login";

    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int WARNING = 0xFFB54708;
    private static final int BG = 0xFFF7F9FA;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DriveDb db;
    private long accountId;
    private boolean syncing;
    private boolean finished;
    private boolean waitingForSession;
    private long sessionSavedAtBeforePrompt;
    private FrameLayout root;
    private TextView syncAccount;
    private TextView syncStatus;
    private TextView syncCount;
    private TextView syncBytes;
    private TextView syncButton;
    private SyncBarsView bars;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        String requestedUrl = getIntent().getStringExtra(EXTRA_URL);
        boolean freshLogin = getIntent().getBooleanExtra(EXTRA_NEW_LOGIN, false);
        if (freshLogin) {
            launchOAuth(false);
            finish();
            return;
        }
        if (requestedUrl != null && !requestedUrl.trim().isEmpty()) {
            openSourceInSystemBrowser(requestedUrl);
            finish();
            return;
        }

        buildSyncUi();
        if (ChatGptSessionStore.has(this, accountId)) startNativeSync();
        else requireLibrarySession("Для синхронизации Library нужен Session JSON.");
    }

    @Override protected void onResume() {
        super.onResume();
        if (!waitingForSession || syncing || finished) return;
        ChatGptSessionStore.Session s = ChatGptSessionStore.load(this, accountId);
        if (s != null && s.savedAt > sessionSavedAtBeforePrompt) {
            waitingForSession = false;
            startNativeSync();
        }
    }

    private void buildSyncUi() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(22));
        panel.setBackgroundColor(BG);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_gpt_drive_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("GPT Drive", 20, true, TEXT));
        titles.addView(text("Native API Sync · " + AppIdentity.providerLabel(), 10, false, MUTED));
        header.addView(titles, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView close = text("×", 27, false, MUTED);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> finishDone());
        header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(round(Color.WHITE, 20, 0xFFE5E9EC, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(22), 0, 0);
        panel.addView(card, cp);

        AccountProfile a = db.accountById(accountId);
        syncAccount = text(a == null ? "ChatGPT" : a.displayName() + "  ·  " + a.planLabel(), 15, true, TEXT);
        card.addView(syncAccount);
        syncStatus = text("Подготовка native API…", 12, false, MUTED);
        syncStatus.setPadding(0, dp(5), 0, 0);
        card.addView(syncStatus);

        bars = new SyncBarsView(this);
        bars.setActive(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(112));
        bp.setMargins(0, dp(18), 0, dp(8));
        card.addView(bars, bp);

        LinearLayout stats = new LinearLayout(this);
        syncCount = text("Найдено: 0", 13, true, TEXT);
        syncBytes = text(storageText(), 11, false, MUTED);
        syncBytes.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        stats.addView(syncCount, new LinearLayout.LayoutParams(0, dp(34), 1));
        stats.addView(syncBytes, new LinearLayout.LayoutParams(0, dp(34), 1));
        card.addView(stats);

        TextView note = text("Library читается через POST /backend-api/files/library с согласованием пагинации. Файлы открываются по file_id через свежую подписанную ссылку. Chat Mirror инкрементально сохраняет полные conversation mapping в локальный gzip-архив и не удаляет копию, если чат исчез с сервера.", 10, false, MUTED);
        note.setPadding(0, dp(10), 0, 0);
        note.setLineSpacing(0, 1.12f);
        card.addView(note);

        syncButton = text("Синхронизировать", 13, true, Color.WHITE);
        syncButton.setGravity(Gravity.CENTER);
        syncButton.setBackground(round(GREEN, 14, 0, 0));
        syncButton.setOnClickListener(v -> {
            if (finished) finishDone();
            else if (waitingForSession) launchSessionImport();
            else if (!syncing) startNativeSync();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
        sp.setMargins(0, dp(20), 0, 0);
        panel.addView(syncButton, sp);
    }

    private void startNativeSync() {
        if (syncing || finished) return;
        if (!ChatGptSessionStore.has(this, accountId)) {
            requireLibrarySession("Library Session не подключена.");
            return;
        }
        waitingForSession = false;
        syncing = true;
        bars.setActive(true);
        syncButton.setEnabled(false);
        syncButton.setAlpha(.55f);
        syncButton.setText("Идёт синхронизация…");
        syncStatus.setTextColor(MUTED);
        setStatus("Подключаюсь к ChatGPT backend…", 0);
        worker.execute(() -> {
            try {
                ChatGptApiClient client = new ChatGptApiClient(this, db, accountId);
                ChatGptApiClient.SyncResult result = client.sync((phase, part, total, objects) ->
                        runOnUiThread(() -> setStatus(phase + " " + part + "/" + total + " · " + objects, objects)));
                runOnUiThread(() -> completeSync(result));
            } catch (ChatGptApiClient.AuthException e) {
                runOnUiThread(() -> requireLibrarySession(e.getMessage() == null ? "Session ChatGPT устарела." : e.getMessage()));
            } catch (Exception e) {
                runOnUiThread(() -> failSync(e.getMessage()));
            }
        });
    }

    private void requireLibrarySession(String message) {
        syncing = false;
        finished = false;
        waitingForSession = true;
        ChatGptSessionStore.Session old = ChatGptSessionStore.load(this, accountId);
        sessionSavedAtBeforePrompt = old == null ? 0 : old.savedAt;
        if (bars != null) bars.setActive(false);
        if (syncStatus != null) {
            syncStatus.setTextColor(WARNING);
            syncStatus.setText((message == null ? "Нужен Session JSON." : message) + " Открой импорт Session JSON и обнови Library-доступ.");
        }
        if (syncButton != null) {
            syncButton.setText("Обновить Session JSON");
            syncButton.setEnabled(true);
            syncButton.setAlpha(1f);
        }
    }

    private void launchSessionImport() {
        ChatGptSessionStore.Session old = ChatGptSessionStore.load(this, accountId);
        sessionSavedAtBeforePrompt = old == null ? 0 : old.savedAt;
        Intent i = new Intent(this, SessionImportActivity.class);
        i.putExtra(SessionImportActivity.EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(SessionImportActivity.EXTRA_NEW_ACCOUNT, false);
        startActivity(i);
    }

    private void launchOAuth(boolean newAccount) {
        Intent i = new Intent(this, ChatGptOAuthActivity.class);
        i.putExtra(ChatGptOAuthActivity.EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(ChatGptOAuthActivity.EXTRA_NEW_ACCOUNT, newAccount);
        startActivity(i);
    }

    private void openSourceInSystemBrowser(String source) {
        String target = source;
        String conversationId = ChatGptApiClient.extractConversationId(source);
        if (!conversationId.isEmpty()) target = "https://chatgpt.com/c/" + Uri.encode(conversationId);
        else if (!source.startsWith("http://") && !source.startsWith("https://")) target = "https://chatgpt.com/library";
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target))); }
        catch (Exception ignored) {}
    }

    private void completeSync(ChatGptApiClient.SyncResult r) {
        syncing = false;
        finished = true;
        waitingForSession = false;
        notifyRoots();
        AccountProfile a = db.accountById(accountId);
        if (a != null) syncAccount.setText(a.displayName() + "  ·  " + a.planLabel());

        if (r.fullyFresh() && r.mirrorFresh) {
            syncStatus.setTextColor(MUTED);
            syncStatus.setText("Готово · Library " + r.library + " · Images " + r.images +
                    " · Projects " + r.projects + " · Archive " + r.archived +
                    " · Mirror " + r.mirroredChats + "/" + r.conversations);
            syncButton.setText("Готово");
        } else {
            StringBuilder warning = new StringBuilder("Library синхронизирована");
            if (!r.imagesFresh) warning.append(" · Images: сохранён предыдущий снимок");
            if (!r.projectsFresh) warning.append(" · Projects: сохранён предыдущий снимок");
            if (!r.archivedFresh) warning.append(" · Archive: сохранён предыдущий снимок");
            if (!r.mirrorFresh) warning.append(" · Chat Mirror: локальный архив сохранён");
            syncStatus.setTextColor(WARNING);
            syncStatus.setText(warning.toString());
            syncButton.setText("Готово с предупреждением");
        }

        String strategy = r.libraryStrategy < 0 ? "initial" : Integer.toString(r.libraryStrategy);
        String mirror = " · Mirror " + r.mirroredChats + "/" + r.conversations;
        if (r.mirrorPending > 0) mirror += " (ещё " + r.mirrorPending + ")";
        if (r.mirrorMissing > 0) mirror += " · локально сохранено удалённых: " + r.mirrorMissing;
        syncCount.setText("Library pages: " + r.pages + " · strategy " + strategy + mirror);
        syncBytes.setText(storageText());
        bars.setActive(false);
        syncButton.setEnabled(true);
        syncButton.setAlpha(1f);
    }

    private void failSync(String message) {
        syncing = false;
        finished = false;
        waitingForSession = false;
        bars.setActive(false);
        syncStatus.setTextColor(WARNING);
        syncStatus.setText(message == null || message.trim().isEmpty() ? "Ошибка синхронизации" : message);
        syncButton.setText("Повторить");
        syncButton.setEnabled(true);
        syncButton.setAlpha(1f);
    }

    private void setStatus(String status, int objects) {
        if (syncStatus != null) syncStatus.setText(status);
        if (syncCount != null) syncCount.setText("Найдено: " + objects);
        if (bars != null) { bars.setActive(true); bars.pulse(1f); }
    }

    private String storageText() {
        AccountProfile a = db.accountById(accountId);
        if (a != null && a.hasRemoteQuota()) return MainActivity.formatBytes(a.remoteUsedBytes) + " / " + MainActivity.formatBytes(a.remoteQuotaBytes);
        long known = db.knownStorageBytes(accountId);
        return known > 0 ? "Индекс: " + MainActivity.formatBytes(known) : "Объём: —";
    }

    private void finishDone() {
        notifyRoots();
        finish();
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
