package com.dsh.iosduo;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** 三合一图标 Drawable（支持各组件缩放/偏移）。 */
public class DuoRingDrawable extends Drawable {

    private static final int C_GREEN = 0xFF34C759;
    private static final int C_RED   = 0xFFFF3B30;
    private static final int WIFI_GREEN = 0xFF35D067;
    private static final int WIFI_OFF   = 0x88999999;

    private static final float R_END = 45f;
    private static final float L_END = 135f;
    private static final float[] SIG_ANGLES = {52f, 77f, 102f, 127f}; // 四个信号点（避开弧端）

    private final StatusData data;
    private final float density;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wifi = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint net = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final RectF tmp = new RectF();

    public DuoRingDrawable(StatusData d, float density) {
        this.data = d;
        this.density = density;
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.STROKE); fill.setStrokeCap(Paint.Cap.ROUND);
        wifi.setStyle(Paint.Style.STROKE); wifi.setStrokeCap(Paint.Cap.ROUND);
        dots.setStyle(Paint.Style.FILL);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        net.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        net.setTextAlign(Paint.Align.CENTER);
    }

    @Override public void draw(Canvas cv) {
        Rect b = getBounds();
        float S = Math.min(b.width(), b.height());
        if (S <= 0) return;
        float dens = density;
        float cx = b.centerX() + Cfg.offsetX * dens;
        float cy = b.centerY() + Cfg.offsetY * dens;

        int base = (data.color == 0) ? 0xFFFFFFFF : data.color;
        int batt = Math.max(0, Math.min(100, data.battery));
        int col = data.charging ? C_GREEN : (batt < 20 ? C_RED : base);

        // ---- 电池弧线（可独立缩放/偏移）----
        float sw = S * Cfg.thickness * Cfg.arcScale;
        float R = S * Cfg.radius * Cfg.arcScale;
        float acx = cx + Cfg.arcDx * dens;
        float acy = cy + Cfg.arcDy * dens;
        oval.set(acx - R, acy - R, acx + R, acy + R);

        float gapH = Cfg.gap / 2f;
        float rtop = -90f + gapH;
        float ltop = 270f - gapH;
        float p = batt / 100f;

        // 两侧弧线底部各裁掉 1/5
        float lenAll = R_END - rtop;
        float trim = lenAll * 0.20f;
        float arcR0 = rtop, arcR1 = R_END - trim;      // 右弧
        float arcL0 = L_END + trim, arcL1 = ltop;       // 左弧
        float al = arcR1 - arcR0;

        ring.setStrokeWidth(sw); ring.setColor(withAlpha(base, 0x55));
        cv.drawArc(oval, arcR0, al, false, ring);
        cv.drawArc(oval, arcL0, arcL1 - arcL0, false, ring);
        fill.setStrokeWidth(sw); fill.setColor(col);
        float total = 2f * al;
        float L = Math.max(0f, Math.min(total, p * total));
        // 从左下方(L_END)起，沿左弧向上、再跨到右弧向下增长；电量少时只剩左下
        float segL = Math.min(L, al);
        if (segL > 0f) cv.drawArc(oval, arcL0, segL, false, fill);
        float segR = Math.max(0f, L - al);
        if (segR > 0f) cv.drawArc(oval, arcR0, segR, false, fill);

        // ---- 信号点（大小/偏移可调；填充方向与电池弧一致：从左下方开始点亮）----
        float dr = S * 0.055f * Cfg.sigScale;
        int lvl = Math.max(0, Math.min(SIG_ANGLES.length, data.signal));
        float scx = acx + Cfg.sigDx * dens;
        float scy = acy + Cfg.sigDy * dens;
        int litFrom = SIG_ANGLES.length - lvl;   // 信号弱时只亮最左那个，与弧线的“从左起充”同向
        for (int i = 0; i < SIG_ANGLES.length; i++) {
            double a = Math.toRadians(SIG_ANGLES[i]);
            float x = scx + (float) (R * Math.cos(a));
            float y = scy + (float) (R * Math.sin(a));
            dots.setColor(i >= litFrom ? base : withAlpha(base, 0x55));
            cv.drawCircle(x, y, dr, dots);
        }

        // ---- WiFi / 5G 互斥：同一位置二选一，尺寸对齐 ----
        boolean wifiOn = data.wifi >= 0;
        String nt = data.netType;
        boolean hasNet = nt != null && nt.length() > 0;

        float wcx = cx + Cfg.wifiDx * dens;
        float wcy = cy + S * 0.085f + Cfg.wifiDy * dens;

        if (wifiOn) {
            float ws = Cfg.wifiScale;
            wifi.setColor(base);
            float[] fanRad = {S * 0.095f, S * 0.175f, S * 0.255f};
            float[] fanW   = {S * 0.036f, S * 0.046f, S * 0.056f};
            for (int i = 0; i < 3; i++) {
                float rr = fanRad[i] * ws;
                wifi.setStrokeWidth(fanW[i] * ws);
                tmp.set(wcx - rr, wcy - rr, wcx + rr, wcy + rr);
                cv.drawArc(tmp, 210f, 120f, false, wifi);
            }
            dots.setColor(base);
            cv.drawCircle(wcx, wcy, S * 0.046f * ws, dots);
        } else if (hasNet) {
            // 5G/4G 文本：字号与 WiFi 扇面视觉尺寸一致，占据同一格（与 WiFi 互斥）
            float ns = S * 0.28f * Cfg.netScale;
            net.setTextSize(ns);
            net.setColor(base);
            cv.drawText(nt, wcx + Cfg.netDx * dens, wcy + ns * 0.30f + Cfg.netDy * dens, net);
        }

        // ---- 电量数字（可调）----
        float ts = S * 0.19f * Cfg.numScale;
        text.setTextSize(ts); text.setColor(col);
        cv.drawText(String.valueOf(batt), acx + Cfg.numDx * dens, acy - R + ts * 0.55f + Cfg.numDy * dens, text);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
