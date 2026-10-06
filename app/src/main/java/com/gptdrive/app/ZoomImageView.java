package com.gptdrive.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Lightweight matrix image view: pinch, double-tap zoom, pan and edge-aware gallery swipes. */
final class ZoomImageView extends ImageView {
    interface Listener {
        void onSingleTap();
        void onSwipeLeft();
        void onSwipeRight();
    }

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Listener listener;
    private float minScale = 1f;
    private float maxScale = 5f;
    private float userScale = 1f;
    private float lastX;
    private float lastY;
    private boolean dragging;

    ZoomImageView(Context c) { this(c, null); }
    ZoomImageView(Context c, AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.MATRIX);
        setBackgroundColor(0xFF000000);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (getDrawable() == null) return false;
                float factor = d.getScaleFactor();
                float target = clamp(userScale * factor, minScale, maxScale);
                factor = target / Math.max(0.001f, userScale);
                userScale = target;
                matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                constrain();
                setImageMatrix(matrix);
                return true;
            }
        });
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onSingleTap();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (getDrawable() == null) return true;
                if (userScale > 1.15f) resetZoom();
                else zoomTo(2.5f, e.getX(), e.getY());
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || userScale > 1.05f) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(70) || Math.abs(dx) < Math.abs(dy) * 1.25f) return false;
                if (listener != null) {
                    if (dx < 0) listener.onSwipeLeft(); else listener.onSwipeRight();
                }
                return true;
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetZoom);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::resetZoom);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX(); lastY = event.getY(); dragging = false; break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && userScale > 1.01f) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(2)) dragging = true;
                    matrix.postTranslate(dx, dy);
                    constrain();
                    setImageMatrix(matrix);
                }
                lastX = event.getX(); lastY = event.getY(); break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false; break;
        }
        return true;
    }

    void resetZoom() {
        Drawable d = getDrawable();
        int vw = getWidth(), vh = getHeight();
        if (d == null || vw <= 0 || vh <= 0 || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
        matrix.reset();
        float sx = vw / (float) d.getIntrinsicWidth();
        float sy = vh / (float) d.getIntrinsicHeight();
        float fit = Math.min(sx, sy);
        float w = d.getIntrinsicWidth() * fit;
        float h = d.getIntrinsicHeight() * fit;
        matrix.postScale(fit, fit);
        matrix.postTranslate((vw - w) / 2f, (vh - h) / 2f);
        userScale = 1f;
        setImageMatrix(matrix);
    }

    private void zoomTo(float target, float px, float py) {
        target = clamp(target, minScale, maxScale);
        float factor = target / Math.max(0.001f, userScale);
        userScale = target;
        matrix.postScale(factor, factor, px, py);
        constrain();
        setImageMatrix(matrix);
    }

    private void constrain() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = getWidth() / 2f - r.centerX();
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = getHeight() / 2f - r.centerY();
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    private float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
