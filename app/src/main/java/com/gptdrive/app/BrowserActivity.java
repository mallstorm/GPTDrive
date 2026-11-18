package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native synchronization host. Sync itself runs in SyncForegroundService. */
public final class BrowserActivity extends Activity {
    public static final String EXTRA_SECTION = "section";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    public static final String EXTRA_NEW_LOGIN = "new_login";
    public static final String EXTRA_ATTACH_ONLY = "attach_only";

    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int WARNING = 0xFFB54708;
    private static final int BG = 0xFFF7F9FA;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            renderRuntimeState();
            handler.postDelayed(this, 400);
        }
    };

    private DriveDb db;
    private long accountId;
    private boolean syncing;
    private boolean finished;
    private boolean waitingForSession;
    private boolean polling;
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
        db = DriveDb.get(this);
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        String requestedUrl = getIntent().getStringExtra(EXTRA_URL);
        if (requestedUrl != null && !requestedUrl.trim().isEmpty()) {
            openSourceInSystemBrowser(requestedUrl);
            finish();
            return;
        }

        buildSyncUi();
        boolean freshLogin = getIntent().getBooleanExtra(EXTRA_NEW_LOGIN, false);
        boolean attachOnly = getIntent().getBooleanExtra(EXTRA_ATTACH_ONLY, false);
        if (freshLogin) {
            requireLibrarySession("Обнови вход ChatGPT перед синхронизацией.");
            launchChatGptLogin();
            return;
        }

        SyncRuntimeState.Snapshot snapshot = SyncRuntimeState.read(this, accountId);
        if (snapshot.running()) {
            renderRuntimeState();
        } else if (attachOnly) {
            renderRuntimeState();
        } else if (ChatGptSessionStore.has(this, accountId)) {
            startNativeSync();
        } else {
            requireLibrarySession("Для синхронизации нужен рабочий вход ChatGPT.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForSession && !syncing && !finished) {
            ChatGptSessionStore.Session s = ChatGptSessionStore.load(this, accountId);
            if (s != null && s.savedAt > sessionSavedAtBeforePrompt) {
                waitingForSession = false;
                startNativeSync();
            }
        }
        renderRuntimeState();
        startPolling();
    }

    @Override protected void onPause() {
        stopPolling();
        super.onPause();
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(poller);
    }

    private void stopPolling() {
        polling = false;
        handler.removeCallbacks(poller);
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
        titles.addView(text("Background Metadata Sync · " + AppIdentity.providerLabel(), 10, false, MUTED));
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
        syncStatus = text("Подготовка метаданных…", 12, false, MUTED);
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

        TextView note = text("Синхронизация идёт в фоновом foreground-service и не прерывается при сворачивании экрана. Счётчик хранится отдельно от Activity. Загружаются только метаданные; содержимое файлов остаётся облачным.", 10, false, MUTED);
        note.setPadding(0, dp(10), 0, 0);
        note.setLineSpacing(0, 1.12f);
        card.addView(note);

        syncButton = text("Синхронизировать", 13, true, Color.WHITE);
        syncButton.setGravity(Gravity.CENTER);
        syncButton.setBackground(round(GREEN, 14, 0, 0));
        syncButton.setOnClickListener(v -> {
            if (finished) finishDone();
            else if (waitingForSession) launchChatGptLogin();
            else if (!syncing) startNativeSync();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
        sp.setMargins(0, dp(20), 0, 0);
        panel.addView(syncButton, sp);
    }

    private void startNativeSync() {
        if (syncing) return;
        if (!ChatGptSessionStore.has(this, accountId)) {
            requireLibrarySession("Рабочая backend-сессия ChatGPT не найдена.");
            return;
        }
        waitingForSession = false;
        finished = false;
        syncing = true;
        SyncRuntimeState.begin(this, accountId);
        showRunning("Получаю метаданные ChatGPT…", SyncRuntimeState.read(this, accountId).objects);
        try {
            SyncForegroundService.start(this, accountId);
        } catch (Exception e) {
            String m = e.getMessage();
            SyncRuntimeState.error(this, accountId, m == null ? "Не удалось запустить фоновую синхронизацию" : m);
            renderRuntimeState();
        }
    }

    private void renderRuntimeState() {
        if (syncStatus == null) return;
        SyncRuntimeState.Snapshot s = SyncRuntimeState.read(this, accountId);
        if (s.running()) {
            syncing = true;
            finished = false;
            waitingForSession = false;
            String phase = s.phase == null || s.phase.trim().isEmpty() ? "Синхронизация" : s.phase;
            if (s.total > 0) phase += " " + s.part + "/" + s.total;
            showRunning(phase, s.objects);
            return;
        }
        if (s.success()) {
            syncing = false;
            finished = true;
            waitingForSession = false;
            bars.setActive(false);
            AccountProfile a = db.accountById(accountId);
            if (a != null) syncAccount.setText(a.displayName() + "  ·  " + a.planLabel());
            boolean allFresh = s.imagesFresh && s.projectsFresh && s.archivedFresh;
            syncStatus.setTextColor(allFresh ? MUTED : WARNING);
            syncStatus.setText((allFresh ? "Готово" : "Готово с предупреждением") +
                    " · Library " + s.library + " · Images " + s.images +
                    " · Projects " + s.projects + " · Archive " + s.archived);
            String strategy = s.strategy < 0 ? "initial" : Integer.toString(s.strategy);
            syncCount.setText("Library pages: " + s.pages + " · strategy " + strategy + " · metadata-only");
            syncBytes.setText(storageText());
            syncButton.setText(allFresh ? "Готово" : "Готово с предупреждением");
            syncButton.setEnabled(true);
            syncButton.setAlpha(1f);
            return;
        }
        if (s.auth()) {
            if (!waitingForSession) {
                ChatGptSessionStore.Session old = ChatGptSessionStore.load(this, accountId);
                sessionSavedAtBeforePrompt = old == null ? 0 : old.savedAt;
            }
            syncing = false;
            finished = false;
            waitingForSession = true;
            bars.setActive(false);
            syncStatus.setTextColor(WARNING);
            syncStatus.setText((s.message == null || s.message.trim().isEmpty() ? "Сессия ChatGPT недействительна." : s.message) + " Обнови вход через встроенное окно.");
            syncButton.setText("Войти / обновить ChatGPT");
            syncButton.setEnabled(true);
            syncButton.setAlpha(1f);
            return;
        }
        if (s.error()) {
            syncing = false;
            finished = false;
            waitingForSession = false;
            bars.setActive(false);
            syncStatus.setTextColor(WARNING);
            syncStatus.setText(s.message == null || s.message.trim().isEmpty() ? "Ошибка синхронизации" : s.message);
            syncCount.setText("Найдено: " + s.objects);
            syncButton.setText("Повторить");
            syncButton.setEnabled(true);
            syncButton.setAlpha(1f);
        }
    }

    private void showRunning(String status, int objects) {
        bars.setActive(true);
        bars.pulse(1f);
        syncStatus.setTextColor(MUTED);
        syncStatus.setText(status);
        syncCount.setText("Найдено: " + objects);
        syncBytes.setText(storageText());
        syncButton.setText("Идёт в фоне…");
        syncButton.setEnabled(false);
        syncButton.setAlpha(.55f);
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
            syncStatus.setText((message == null ? "Нужен вход ChatGPT." : message) + " Обнови вход через встроенное окно.");
        }
        if (syncButton != null) {
            syncButton.setText("Войти / обновить ChatGPT");
            syncButton.setEnabled(true);
            syncButton.setAlpha(1f);
        }
    }

    private void launchChatGptLogin() {
        ChatGptSessionStore.Session old = ChatGptSessionStore.load(this, accountId);
        sessionSavedAtBeforePrompt = old == null ? 0 : old.savedAt;
        Intent i = new Intent(this, ChatGptAuthActivity.class);
        i.putExtra(ChatGptAuthActivity.EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(ChatGptAuthActivity.EXTRA_NEW_ACCOUNT, false);
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

    private String storageText() {
        AccountProfile a = db.accountById(accountId);
        if (a != null && a.hasRemoteQuota()) return MainActivity.formatBytes(a.remoteUsedBytes) + " / " + MainActivity.formatBytes(a.remoteQuotaBytes);
        long known = db.knownStorageBytes(accountId);
        return known > 0 ? "Удалённые файлы: " + MainActivity.formatBytes(known) : "Удалённый объём: —";
    }

    private void finishDone() {
        notifyRoots();
        finish();
    }

    private void notifyRoots() {
        try { getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        stopPolling();
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
