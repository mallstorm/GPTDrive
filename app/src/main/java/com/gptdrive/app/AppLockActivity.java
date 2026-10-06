package com.gptdrive.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Full-screen app lock. Supports fingerprint with graphical-pattern fallback and pattern setup. */
public final class AppLockActivity extends Activity {
    public static final String EXTRA_SETUP = "setup";

    private static final int GREEN = 0xFF08A66D;
    private static final int TEXT = 0xFF101828;
    private static final int MUTED = 0xFF667085;
    private static final int BG = 0xFFF7F9FA;

    private AppLockStore store;
    private PatternLockView patternView;
    private TextView title;
    private TextView hint;
    private TextView biometricButton;
    private boolean setup;
    private String firstPattern = "";
    private CancellationSignal biometricSignal;
    private boolean promptShown;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new AppLockStore(this);
        setup = getIntent().getBooleanExtra(EXTRA_SETUP, false);
        buildUi();
        if (!setup && !store.isEnabled()) succeed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!setup && store.isBiometricEnabled() && BiometricAuthHelper.isAvailable(this) && !promptShown) {
            promptShown = true;
            patternView.postDelayed(this::startBiometric, 220);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(34), dp(28), dp(22));
        root.setBackgroundColor(BG);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_gpt_drive_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(logo, new LinearLayout.LayoutParams(dp(74), dp(74)));

        title = text(setup ? "Создайте графический ключ" : "GPT Drive заблокирован", 22, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMargins(0, dp(16), 0, dp(6));
        root.addView(title, tp);

        hint = text(setup ? "Соедините минимум 4 точки" : "Нарисуйте графический ключ", 13, false);
        hint.setTextColor(MUTED);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        patternView = new PatternLockView(this);
        patternView.setListener(setup ? this::handleSetupPattern : this::handleUnlockPattern);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, 0, 1);
        pp.setMargins(0, dp(8), 0, dp(8));
        root.addView(patternView, pp);

        biometricButton = text("Использовать отпечаток пальца", 13, true);
        biometricButton.setTextColor(GREEN);
        biometricButton.setGravity(Gravity.CENTER);
        biometricButton.setBackground(round(Color.WHITE, 14, 0xFFD9E1E5, 1));
        biometricButton.setOnClickListener(v -> startBiometric());
        boolean showBio = !setup && store.isBiometricEnabled() && BiometricAuthHelper.isAvailable(this);
        biometricButton.setVisibility(showBio ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(48));
        bp.setMargins(0, dp(4), 0, dp(4));
        root.addView(biometricButton, bp);

        TextView privacy = text(setup
                ? "Ключ хранится только на устройстве в виде стойкого хеша."
                : "Данные GPT Drive останутся скрыты до разблокировки.", 10, false);
        privacy.setTextColor(MUTED);
        privacy.setGravity(Gravity.CENTER);
        root.addView(privacy, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void handleSetupPattern(String pattern) {
        if (PatternLockView.nodeCount(pattern) < 4) {
            hint.setText("Нужно соединить минимум 4 точки");
            patternView.showError();
            return;
        }
        if (firstPattern.isEmpty()) {
            firstPattern = pattern;
            title.setText("Повторите ключ");
            hint.setText("Нарисуйте тот же рисунок ещё раз");
            patternView.postDelayed(patternView::clearPattern, 220);
            return;
        }
        if (!firstPattern.equals(pattern)) {
            firstPattern = "";
            title.setText("Ключи не совпали");
            hint.setText("Создайте новый рисунок и повторите его");
            patternView.showError();
            return;
        }
        if (!store.savePattern(pattern)) {
            Toast.makeText(this, "Не удалось сохранить графический ключ", Toast.LENGTH_LONG).show();
            firstPattern = "";
            patternView.showError();
            return;
        }
        setResult(RESULT_OK);
        finish();
    }

    private void handleUnlockPattern(String pattern) {
        if (store.verifyPattern(pattern)) {
            succeed();
            return;
        }
        title.setText("Неверный графический ключ");
        hint.setText("Попробуйте ещё раз или используйте отпечаток");
        patternView.showError();
    }

    private void startBiometric() {
        if (setup || !store.isBiometricEnabled()) return;
        cancelBiometric();
        hint.setText("Приложите палец к сканеру");
        biometricSignal = BiometricAuthHelper.authenticate(this, new BiometricAuthHelper.Callback() {
            @Override public void onSuccess() { succeed(); }
            @Override public void onFailure(String message) {
                if (isFinishing()) return;
                hint.setText(message == null || message.trim().isEmpty()
                        ? "Нарисуйте графический ключ" : message);
            }
        });
    }

    private void succeed() {
        cancelBiometric();
        AppLockStore.markSessionUnlocked();
        setResult(RESULT_OK);
        finish();
    }

    private void cancelBiometric() {
        if (biometricSignal != null) {
            try { biometricSignal.cancel(); } catch (Exception ignored) {}
            biometricSignal = null;
        }
    }

    @Override public void onBackPressed() {
        if (setup) {
            setResult(RESULT_CANCELED);
            finish();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override protected void onDestroy() {
        cancelBiometric();
        super.onDestroy();
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
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
