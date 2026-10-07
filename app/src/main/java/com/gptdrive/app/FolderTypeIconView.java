package com.gptdrive.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Color-coded semantic folder with a small white purpose glyph. */
final class FolderTypeIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int resource;

    FolderTypeIconView(Context context, int resource) {
        super(context);
        this.resource = resource;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        int[] colors = colors();
        paint.setShader(new LinearGradient(0, 0, w, h, colors[0], colors[1], Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(w * .08f, h * .27f);
        path.lineTo(w * .35f, h * .27f);
        path.lineTo(w * .45f, h * .39f);
        path.lineTo(w * .91f, h * .39f);
        path.quadTo(w * .96f, h * .39f, w * .96f, h * .46f);
        path.lineTo(w * .91f, h * .89f);
        path.quadTo(w * .90f, h * .94f, w * .83f, h * .94f);
        path.lineTo(w * .13f, h * .94f);
        path.quadTo(w * .06f, h * .94f, w * .06f, h * .86f);
        path.lineTo(w * .08f, h * .27f);
        c.drawPath(path, paint);
        paint.setShader(null);
        paint.setColor(0x44FFFFFF);
        c.drawRoundRect(w * .08f, h * .42f, w * .94f, h * .60f, h * .08f, h * .08f, paint);
        drawGlyph(c, w * .61f, h * .68f, Math.min(w, h) * .20f);
    }

    private int[] colors() {
        if (resource == R.drawable.folder_images) return new int[]{0xFF23A8F2, 0xFF2684EA};
        if (resource == R.drawable.folder_projects) return new int[]{0xFF42D7A3, 0xFF18B989};
        if (resource == R.drawable.folder_generated) return new int[]{0xFFFFA52F, 0xFFFF7917};
        if (resource == R.drawable.folder_archived) return new int[]{0xFF9368F6, 0xFF7042E8};
        if (resource == R.drawable.folder_deleted) return new int[]{0xFFF45A7C, 0xFFE8325F};
        return new int[]{0xFFFFCE57, 0xFFFFB72E};
    }

    private void drawGlyph(Canvas c, float cx, float cy, float s) {
        paint.setColor(0xF5FFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, s * .13f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (resource == R.drawable.folder_images) {
            c.drawCircle(cx + s * .42f, cy - s * .42f, s * .16f, paint);
            path.reset(); path.moveTo(cx - s, cy + s * .55f); path.lineTo(cx - s * .30f, cy - s * .25f);
            path.lineTo(cx + s * .05f, cy + s * .15f); path.lineTo(cx + s * .43f, cy - s * .30f);
            path.lineTo(cx + s, cy + s * .55f); c.drawPath(path, paint);
        } else if (resource == R.drawable.folder_projects) {
            for (int i = 0; i < 3; i++) {
                float y = cy - s * .55f + i * s * .48f;
                path.reset(); path.moveTo(cx, y - s * .26f); path.lineTo(cx + s * .78f, y);
                path.lineTo(cx, y + s * .26f); path.lineTo(cx - s * .78f, y); path.close(); c.drawPath(path, paint);
            }
        } else if (resource == R.drawable.folder_generated) {
            paint.setStyle(Paint.Style.FILL);
            star(c, cx, cy, s * .72f); star(c, cx + s * .78f, cy - s * .55f, s * .32f);
        } else if (resource == R.drawable.folder_archived) {
            c.drawRoundRect(cx - s * .75f, cy - s * .65f, cx + s * .75f, cy + s * .65f, s * .12f, s * .12f, paint);
            c.drawLine(cx - s * .45f, cy - s * .22f, cx + s * .45f, cy - s * .22f, paint);
            c.drawLine(cx - s * .45f, cy + s * .20f, cx + s * .45f, cy + s * .20f, paint);
        } else if (resource == R.drawable.folder_deleted) {
            c.drawLine(cx - s * .72f, cy - s * .55f, cx + s * .72f, cy - s * .55f, paint);
            c.drawRoundRect(cx - s * .53f, cy - s * .35f, cx + s * .53f, cy + s * .70f, s * .08f, s * .08f, paint);
            c.drawLine(cx - s * .25f, cy - s * .82f, cx + s * .25f, cy - s * .82f, paint);
        } else {
            c.drawRoundRect(cx - s * .68f, cy - s * .72f, cx + s * .48f, cy + s * .72f, s * .08f, s * .08f, paint);
            c.drawLine(cx - s * .40f, cy - s * .22f, cx + s * .20f, cy - s * .22f, paint);
            c.drawLine(cx - s * .40f, cy + s * .15f, cx + s * .20f, cy + s * .15f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void star(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.moveTo(cx, cy - r); path.lineTo(cx + r * .24f, cy - r * .24f);
        path.lineTo(cx + r, cy); path.lineTo(cx + r * .24f, cy + r * .24f);
        path.lineTo(cx, cy + r); path.lineTo(cx - r * .24f, cy + r * .24f);
        path.lineTo(cx - r, cy); path.lineTo(cx - r * .24f, cy - r * .24f); path.close();
        c.drawPath(path, paint);
    }
}
