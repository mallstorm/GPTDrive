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

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float s = Math.min(getWidth(), getHeight()) * .30f;
        if (type == DOWNLOAD) drawDownload(c, cx, cy, s);
        else if (type == SHARE) drawShare(c, cx, cy, s);
        else drawInfo(c, cx, cy, s);
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

    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }
}
