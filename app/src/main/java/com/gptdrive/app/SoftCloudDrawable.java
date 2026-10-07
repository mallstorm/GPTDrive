package com.gptdrive.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Very light cloud-tinted backdrop shared by the folder home and file browser. */
final class SoftCloudDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override public void draw(Canvas canvas) {
        float w = getBounds().width();
        float h = getBounds().height();
        paint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xFFF9FCFF, 0xFFF2FAF7, 0xFFF2F7FF},
                new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(getBounds(), paint);
        paint.setShader(null);
        cloud(canvas, w * .16f, h * .22f, w * .20f, 0x0D12B981);
        cloud(canvas, w * .82f, h * .40f, w * .25f, 0x0D38BDF8);
        cloud(canvas, w * .45f, h * .70f, w * .28f, 0x0C7C3AED);
        cloud(canvas, w * .08f, h * .93f, w * .18f, 0x0B14B8A6);
    }

    private void cloud(Canvas c, float x, float y, float r, int color) {
        paint.setColor(color);
        c.drawCircle(x, y, r * .54f, paint);
        c.drawCircle(x + r * .48f, y + r * .05f, r * .42f, paint);
        c.drawCircle(x - r * .42f, y + r * .12f, r * .34f, paint);
        c.drawRoundRect(x - r * .70f, y, x + r * .86f, y + r * .47f,
                r * .22f, r * .22f, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
