package com.gptdrive.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class SyncBarsView extends View {
    private static final int BAR_COUNT = 46;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active = true;
    private float activity = 1f;
    private long started = System.currentTimeMillis();

    SyncBarsView(Context context) {
        super(context);
        paint.setColor(0xFF08A66D);
        paint.setStyle(Paint.Style.FILL);
    }

    void setActive(boolean value) {
        active = value;
        invalidate();
    }

    void pulse(float strength) {
        activity = Math.max(activity, Math.min(1.8f, strength));
        started = System.currentTimeMillis();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float gap = Math.max(2f, w / 220f);
        float barW = Math.max(3f, (w - gap * (BAR_COUNT - 1)) / BAR_COUNT);
        float base = h * 0.84f;
        float maxH = h * 0.66f;
        long now = System.currentTimeMillis();
        float t = (now - started) / 170f;

        if (activity > 0.25f) activity *= 0.988f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float wave1 = (float) ((Math.sin(t + i * 0.57f) + 1f) * 0.5f);
            float wave2 = (float) ((Math.sin(t * 0.63f + i * 1.31f) + 1f) * 0.5f);
            float envelope = 0.35f + 0.65f * (float) Math.sin(Math.PI * (i + 1f) / (BAR_COUNT + 1f));
            float motion = active ? (0.18f + 0.82f * (wave1 * 0.62f + wave2 * 0.38f)) : 0.12f;
            float level = Math.min(1f, motion * envelope * Math.max(0.35f, activity));
            float bh = Math.max(h * 0.08f, maxH * level);
            float left = i * (barW + gap);
            RectF r = new RectF(left, base - bh, left + barW, base);
            float radius = Math.min(barW * 0.5f, 4f);
            paint.setAlpha(active ? 210 + (int) (45 * wave1) : 115);
            canvas.drawRoundRect(r, radius, radius, paint);
        }

        if (active) postInvalidateOnAnimation();
    }
}
