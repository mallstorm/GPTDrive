package com.gptdrive.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small custom vector-like action glyphs drawn on Canvas to keep viewer controls visually consistent. */
final class ViewerActionIconView extends View {
    static final int DOWNLOAD = 1;
    static final int SHARE = 2;
    static final int INFO = 3;
    static final int TRASH = 4;
    static final int CLOSE = 5;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int type;

    ViewerActionIconView(Context context, int type) {
        super(context);
        this.type = type;
        paint.setColor(0xFFF7F8FA);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.8f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setIconColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float s = Math.min(getWidth(), getHeight()) * .30f;
        if (type == DOWNLOAD) drawDownload(c, cx, cy, s);
        else if (type == SHARE) drawShare(c, cx, cy, s);
        else if (type == INFO) drawInfo(c, cx, cy, s);
        else if (type == TRASH) drawTrash(c, cx, cy, s);
        else drawClose(c, cx, cy, s);
    }

    private void drawDownload(Canvas c, float cx, float cy, float s) {
        c.drawLine(cx, cy - s, cx, cy + s * .35f, paint);
        c.drawLine(cx, cy + s * .35f, cx - s * .45f, cy - s * .08f, paint);
        c.drawLine(cx, cy + s * .35f, cx + s * .45f, cy - s * .08f, paint);
        c.drawLine(cx - s * .62f, cy + s * .72f, cx + s * .62f, cy + s * .72f, paint);
    }

    private void drawShare(Canvas c, float cx, float cy, float s) {
        float r = s * .20f;
        c.drawCircle(cx - s * .58f, cy, r, paint);
        c.drawCircle(cx + s * .55f, cy - s * .62f, r, paint);
        c.drawCircle(cx + s * .55f, cy + s * .62f, r, paint);
        c.drawLine(cx - s * .38f, cy - s * .08f, cx + s * .35f, cy - s * .52f, paint);
        c.drawLine(cx - s * .38f, cy + s * .08f, cx + s * .35f, cy + s * .52f, paint);
    }

    private void drawInfo(Canvas c, float cx, float cy, float s) {
        c.drawCircle(cx, cy, s * .83f, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy - s * .38f, s * .09f, paint);
        paint.setStyle(Paint.Style.STROKE);
        c.drawLine(cx, cy - s * .08f, cx, cy + s * .48f, paint);
    }

    private void drawTrash(Canvas c, float cx, float cy, float s) {
        float left = cx - s * .55f;
        float right = cx + s * .55f;
        float top = cy - s * .28f;
        float bottom = cy + s * .72f;
        c.drawLine(left, top, left + s * .12f, bottom, paint);
        c.drawLine(right, top, right - s * .12f, bottom, paint);
        c.drawLine(left + s * .12f, bottom, right - s * .12f, bottom, paint);
        c.drawLine(cx - s * .72f, cy - s * .52f, cx + s * .72f, cy - s * .52f, paint);
        c.drawLine(cx - s * .25f, cy - s * .78f, cx + s * .25f, cy - s * .78f, paint);
        c.drawLine(cx - s * .25f, cy - s * .78f, cx - s * .34f, cy - s * .52f, paint);
        c.drawLine(cx + s * .25f, cy - s * .78f, cx + s * .34f, cy - s * .52f, paint);
        c.drawLine(cx - s * .22f, cy - s * .04f, cx - s * .17f, cy + s * .48f, paint);
        c.drawLine(cx + s * .22f, cy - s * .04f, cx + s * .17f, cy + s * .48f, paint);
    }

    private void drawClose(Canvas c, float cx, float cy, float s) {
        c.drawLine(cx - s * .62f, cy - s * .62f, cx + s * .62f, cy + s * .62f, paint);
        c.drawLine(cx + s * .62f, cy - s * .62f, cx - s * .62f, cy + s * .62f, paint);
    }

    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }
}
