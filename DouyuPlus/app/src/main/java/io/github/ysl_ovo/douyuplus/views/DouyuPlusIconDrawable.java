package io.github.ysl_ovo.douyuplus.views;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 斗鱼Plus (DouyuPlus) 原生矢量图标 Drawable:
 * 齿轮外观 + 中心 Plus 加号，完美呼应斗鱼顶部栏设计语言与模块定位。
 */
public class DouyuPlusIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int iconColor = Color.parseColor("#333333");

    public DouyuPlusIconDrawable() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setIconColor(int color) {
        if (this.iconColor != color) {
            this.iconColor = color;
            invalidateSelf();
        }
    }

    public int getIconColor() {
        return iconColor;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float width = bounds.width();
        float height = bounds.height();
        if (width <= 0 || height <= 0) return;

        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        float size = Math.min(width, height);
        float stroke = size * 0.085f;

        paint.setColor(iconColor);

        // 1. 绘制 6 个齿轮凸起 (外齿)
        float rOuter = size * 0.42f;
        float rBase = size * 0.28f;
        float toothStroke = stroke * 1.55f;

        paint.setStrokeWidth(toothStroke);
        for (int i = 0; i < 6; i++) {
            double ang = i * (Math.PI / 3.0);
            float x0 = cx + (float) Math.cos(ang) * rBase;
            float y0 = cy + (float) Math.sin(ang) * rBase;
            float x1 = cx + (float) Math.cos(ang) * rOuter;
            float y1 = cy + (float) Math.sin(ang) * rOuter;
            canvas.drawLine(x0, y0, x1, y1, paint);
        }

        // 2. 绘制齿轮主体圆环
        paint.setStrokeWidth(stroke);
        float rRing = size * 0.29f;
        canvas.drawCircle(cx, cy, rRing, paint);

        // 3. 绘制中心精致的 Plus (+) 符号
        float plusLen = size * 0.13f;
        canvas.drawLine(cx - plusLen, cy, cx + plusLen, cy, paint);
        canvas.drawLine(cx, cy - plusLen, cx, cy + plusLen, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
