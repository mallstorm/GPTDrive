package com.gptdrive.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Compact 3x3 graphical-pattern input with Android-style skipped-dot handling. */
final class PatternLockView extends View {
    interface Listener { void onPatternComplete(String pattern); }

    private static final int GREEN = 0xFF08A66D;
    private static final int DOT = 0xFF98A2B3;
    private static final int ERROR = 0xFFD92D20;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] xs = new float[9];
    private final float[] ys = new float[9];
    private final List<Integer> selected = new ArrayList<>();
    private Listener listener;
    private float touchX;
    private float touchY;
    private boolean drawing;
    private boolean error;

    PatternLockView(Context context) { super(context); init(); }
    PatternLockView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setBackgroundColor(Color.TRANSPARENT);
        setFocusable(true);
        dotPaint.setStyle(Paint.Style.STROKE);
        dotPaint.setStrokeWidth(dp(2));
        dotPaint.setColor(DOT);
        selectedPaint.setStyle(Paint.Style.FILL);
        selectedPaint.setColor(GREEN);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(5));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(GREEN);
    }

    void setListener(Listener listener) { this.listener = listener; }

    void clearPattern() {
        selected.clear();
        drawing = false;
        error = false;
        invalidate();
    }

    void showError() {
        error = true;
        invalidate();
        postDelayed(this::clearPattern, 520);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float side = Math.min(w, h);
        float left = (w - side) / 2f;
        float top = (h - side) / 2f;
        float pad = side * 0.18f;
        float span = (side - pad * 2f) / 2f;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                int i = r * 3 + c;
                xs[i] = left + pad + c * span;
                ys[i] = top + pad + r * span;
            }
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int active = error ? ERROR : GREEN;
        linePaint.setColor(active);
        selectedPaint.setColor(active);

        if (!selected.isEmpty()) {
            Path path = new Path();
            int first = selected.get(0);
            path.moveTo(xs[first], ys[first]);
            for (int i = 1; i < selected.size(); i++) {
                int n = selected.get(i);
                path.lineTo(xs[n], ys[n]);
            }
            if (drawing) path.lineTo(touchX, touchY);
            canvas.drawPath(path, linePaint);
        }

        float outer = dp(14);
        float inner = dp(7);
        for (int i = 0; i < 9; i++) {
            if (selected.contains(i)) {
                canvas.drawCircle(xs[i], ys[i], outer, selectedPaint);
                Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
                hole.setColor(Color.WHITE);
                canvas.drawCircle(xs[i], ys[i], inner, hole);
            } else {
                canvas.drawCircle(xs[i], ys[i], outer, dotPaint);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                selected.clear();
                error = false;
                drawing = true;
                touchX = event.getX();
                touchY = event.getY();
                addNode(hit(event.getX(), event.getY()));
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                touchX = event.getX();
                touchY = event.getY();
                int hit = hit(touchX, touchY);
                addNode(hit);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drawing = false;
                touchX = event.getX();
                touchY = event.getY();
                invalidate();
                if (event.getActionMasked() == MotionEvent.ACTION_UP && !selected.isEmpty() && listener != null)
                    listener.onPatternComplete(encode());
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void addNode(int node) {
        if (node < 0 || selected.contains(node)) return;
        if (!selected.isEmpty()) {
            int last = selected.get(selected.size() - 1);
            int lr = last / 3, lc = last % 3;
            int nr = node / 3, nc = node % 3;
            if (Math.abs(lr - nr) == 2 || Math.abs(lc - nc) == 2) {
                if ((lr + nr) % 2 == 0 && (lc + nc) % 2 == 0) {
                    int mid = ((lr + nr) / 2) * 3 + ((lc + nc) / 2);
                    if (!selected.contains(mid)) selected.add(mid);
                }
            }
        }
        selected.add(node);
    }

    private int hit(float x, float y) {
        float radius = dp(34);
        float r2 = radius * radius;
        for (int i = 0; i < 9; i++) {
            float dx = x - xs[i];
            float dy = y - ys[i];
            if (dx * dx + dy * dy <= r2) return i;
        }
        return -1;
    }

    private String encode() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < selected.size(); i++) {
            if (i > 0) out.append('-');
            out.append(selected.get(i));
        }
        return out.toString();
    }

    static int nodeCount(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) return 0;
        return pattern.split("-").length;
    }

    private float dp(int n) { return n * getResources().getDisplayMetrics().density; }
}
