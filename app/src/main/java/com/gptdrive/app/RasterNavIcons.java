package com.gptdrive.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

final class RasterNavIcons {
    static final int FILES = 0;
    static final int IMAGES = 1;
    static final int PROJECTS = 2;
    static final int ACCOUNTS = 3;

    private RasterNavIcons() {}

    static Bitmap make(Context c, int type, int color, int sizeDp) {
        int s = Math.max(32, Math.round(sizeDp * c.getResources().getDisplayMetrics().density));
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        float k = s / 64f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(4.6f * k);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);

        if (type == FILES) drawFiles(canvas, p, k);
        else if (type == IMAGES) drawImages(canvas, p, k);
        else if (type == PROJECTS) drawProjects(canvas, p, k);
        else drawAccounts(canvas, p, k);
        return b;
    }

    private static void drawFiles(Canvas c, Paint p, float k) {
        Paint fill = new Paint(p);
        fill.setStyle(Paint.Style.FILL);
        fill.setAlpha(32);
        Path body = new Path();
        body.moveTo(9*k, 22*k); body.lineTo(26*k, 22*k); body.lineTo(31*k, 27*k); body.lineTo(55*k, 27*k);
        body.lineTo(55*k, 49*k); body.quadTo(55*k, 53*k, 51*k, 53*k); body.lineTo(13*k, 53*k); body.quadTo(9*k, 53*k, 9*k, 49*k); body.close();
        c.drawPath(body, fill);
        c.drawPath(body, p);
        Path back = new Path();
        back.moveTo(13*k, 22*k); back.lineTo(13*k, 14*k); back.lineTo(29*k, 14*k); back.lineTo(34*k, 19*k); back.lineTo(51*k, 19*k);
        c.drawPath(back, p);
    }

    private static void drawImages(Canvas c, Paint p, float k) {
        RectF r = new RectF(10*k, 10*k, 54*k, 54*k);
        c.drawRoundRect(r, 5*k, 5*k, p);
        Paint fill = new Paint(p); fill.setStyle(Paint.Style.FILL); fill.setAlpha(30);
        Path m = new Path();
        m.moveTo(15*k, 48*k); m.lineTo(28*k, 34*k); m.lineTo(36*k, 41*k); m.lineTo(44*k, 31*k); m.lineTo(51*k, 48*k); m.close();
        c.drawPath(m, fill); c.drawPath(m, p);
        Paint dot = new Paint(p); dot.setStyle(Paint.Style.FILL);
        c.drawCircle(24*k, 23*k, 4.2f*k, dot);
    }

    private static void drawProjects(Canvas c, Paint p, float k) {
        Path f = new Path();
        f.moveTo(9*k, 18*k); f.lineTo(25*k, 18*k); f.lineTo(30*k, 24*k); f.lineTo(55*k, 24*k); f.lineTo(55*k, 51*k); f.lineTo(9*k, 51*k); f.close();
        Paint fill = new Paint(p); fill.setStyle(Paint.Style.FILL); fill.setAlpha(24);
        c.drawPath(f, fill); c.drawPath(f, p);
        p.setStrokeWidth(3.7f*k);
        c.drawLine(19*k, 37*k, 25*k, 32*k, p); c.drawLine(19*k, 37*k, 25*k, 42*k, p);
        c.drawLine(45*k, 37*k, 39*k, 32*k, p); c.drawLine(45*k, 37*k, 39*k, 42*k, p);
    }

    private static void drawAccounts(Canvas c, Paint p, float k) {
        Paint fill = new Paint(p); fill.setStyle(Paint.Style.FILL); fill.setAlpha(28);
        c.drawCircle(32*k, 21*k, 9*k, fill); c.drawCircle(32*k, 21*k, 9*k, p);
        Path s = new Path(); s.moveTo(14*k, 53*k); s.quadTo(16*k, 36*k, 32*k, 36*k); s.quadTo(48*k, 36*k, 50*k, 53*k);
        c.drawPath(s, fill); c.drawPath(s, p);
    }
}
