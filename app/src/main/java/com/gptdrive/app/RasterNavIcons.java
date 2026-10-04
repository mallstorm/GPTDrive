package com.gptdrive.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Friendly raster section icons used by the bottom navigation.
 * They intentionally look like small app illustrations rather than technical glyphs.
 */
final class RasterNavIcons {
    static final int FILES = 0;
    static final int IMAGES = 1;
    static final int PROJECTS = 2;
    static final int ACCOUNTS = 3;

    private static final int OUTLINE = 0xFF344054;
    private static final int WHITE = 0xFFF8FAFC;

    private RasterNavIcons() {}

    static Bitmap make(Context c, int type, int accent, int sizeDp) {
        int s = Math.max(40, Math.round(sizeDp * c.getResources().getDisplayMetrics().density));
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        float k = s / 64f;

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4.1f * k);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(OUTLINE);

        Paint white = new Paint(stroke);
        white.setStyle(Paint.Style.FILL);
        white.setColor(WHITE);

        Paint fill = new Paint(stroke);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(withAlpha(accent, 78));

        Paint accentSolid = new Paint(stroke);
        accentSolid.setStyle(Paint.Style.FILL);
        accentSolid.setColor(accent);

        if (type == FILES) drawFiles(canvas, stroke, white, fill, accentSolid, k);
        else if (type == IMAGES) drawImages(canvas, stroke, white, fill, accentSolid, k);
        else if (type == PROJECTS) drawProjects(canvas, stroke, white, fill, accentSolid, k);
        else drawAccounts(canvas, stroke, white, fill, accentSolid, k);
        return b;
    }

    private static void drawFiles(Canvas c, Paint stroke, Paint white, Paint fill, Paint accent, float k) {
        Path back = new Path();
        back.moveTo(9*k, 18*k);
        back.quadTo(9*k, 13*k, 14*k, 13*k);
        back.lineTo(27*k, 13*k);
        back.lineTo(33*k, 19*k);
        back.lineTo(51*k, 19*k);
        back.quadTo(55*k, 19*k, 55*k, 23*k);
        back.lineTo(48*k, 46*k);
        back.lineTo(12*k, 46*k);
        back.close();
        c.drawPath(back, fill);
        c.drawPath(back, stroke);

        RectF front = new RectF(8*k, 25*k, 56*k, 53*k);
        c.drawRoundRect(front, 6*k, 6*k, white);
        c.drawRoundRect(front, 6*k, 6*k, stroke);
        RectF stripe = new RectF(13*k, 28*k, 51*k, 32*k);
        c.drawRoundRect(stripe, 2*k, 2*k, accent);
    }

    private static void drawImages(Canvas c, Paint stroke, Paint white, Paint fill, Paint accent, float k) {
        RectF frame = new RectF(9*k, 9*k, 55*k, 55*k);
        c.drawRoundRect(frame, 6*k, 6*k, white);
        c.drawRoundRect(frame, 6*k, 6*k, stroke);

        Path mountains = new Path();
        mountains.moveTo(13*k, 49*k);
        mountains.lineTo(27*k, 34*k);
        mountains.lineTo(36*k, 43*k);
        mountains.lineTo(45*k, 32*k);
        mountains.lineTo(52*k, 49*k);
        mountains.close();
        c.drawPath(mountains, fill);
        c.drawPath(mountains, stroke);
        c.drawCircle(43*k, 21*k, 5*k, accent);
    }

    private static void drawProjects(Canvas c, Paint stroke, Paint white, Paint fill, Paint accent, float k) {
        Path back = new Path();
        back.moveTo(12*k, 15*k);
        back.lineTo(27*k, 15*k);
        back.lineTo(33*k, 21*k);
        back.lineTo(52*k, 21*k);
        back.quadTo(56*k, 21*k, 56*k, 25*k);
        back.lineTo(56*k, 45*k);
        back.lineTo(15*k, 45*k);
        back.close();
        c.drawPath(back, fill);
        c.drawPath(back, stroke);

        Path front = new Path();
        front.moveTo(8*k, 26*k);
        front.lineTo(23*k, 26*k);
        front.lineTo(28*k, 31*k);
        front.lineTo(53*k, 31*k);
        front.lineTo(53*k, 52*k);
        front.lineTo(8*k, 52*k);
        front.close();
        c.drawPath(front, white);
        c.drawPath(front, stroke);
        c.drawLine(14*k, 35*k, 46*k, 35*k, accent);
    }

    private static void drawAccounts(Canvas c, Paint stroke, Paint white, Paint fill, Paint accent, float k) {
        c.drawCircle(32*k, 21*k, 10*k, white);
        c.drawCircle(32*k, 21*k, 10*k, stroke);

        Path shoulders = new Path();
        shoulders.moveTo(12*k, 54*k);
        shoulders.quadTo(14*k, 36*k, 32*k, 36*k);
        shoulders.quadTo(50*k, 36*k, 52*k, 54*k);
        shoulders.close();
        c.drawPath(shoulders, fill);
        c.drawPath(shoulders, stroke);

        c.drawCircle(32*k, 21*k, 4.2f*k, accent);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
