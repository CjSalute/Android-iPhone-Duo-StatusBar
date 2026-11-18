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
    private final android.graphics.Path airPath = new android.graphics.Path();
    private final Paint airFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint airStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 让位（另一宿主正在画）时置 true：画布上什么也不输出，但 overlay 仍在，重绘即清空旧像素。 */
    private volatile boolean hidden = false;

    public void setHidden(boolean h) { hidden = h; }

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
        if (hidden) return;
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
        boolean air = data.airplaneMode;
        if (!air) {
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
        }

        // ---- WiFi / 5G 互斥：同一位置二选一，尺寸对齐 ----
        boolean wifiOn = !air && data.wifi >= 0;
        String nt = data.netType;
        boolean hasNet = !air && nt != null && nt.length() > 0;

        float wcx = cx + Cfg.wifiDx * dens;
        float wcy = cy + S * 0.085f + Cfg.wifiDy * dens;

        if (air) {
            // 飞行模式：WiFi / 4G / 5G 全部隐藏，改成彩色飞机（机头朝右 + 持续旋转）
            drawAirplane(cv, wcx, wcy, S * 0.46f);
        } else if (wifiOn) {
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

    /**
     * 飞行模式图标：彩色飞机，机头朝右，持续自转。
     * 角度取 SystemClock.uptimeMillis()，所以只要外部按 ~60ms 重绘一次就会连续转动。
     */
    private void drawAirplane(Canvas cv, float cx, float cy, float S) {
        if (S <= 0f) return;
        float t = (android.os.SystemClock.uptimeMillis() % 1800L) / 1800f;
        float ang = t * 360f;
        float u = S * 0.5f;

        cv.save();
        cv.rotate(ang, cx, cy);

        airPath.reset();
        // 机身（机头在右）
        airPath.moveTo(cx + u * 0.94f, cy);
        airPath.lineTo(cx + u * 0.22f, cy - u * 0.085f);
        airPath.lineTo(cx - u * 0.60f, cy - u * 0.085f);
        airPath.lineTo(cx - u * 0.86f, cy - u * 0.24f);
        airPath.lineTo(cx - u * 0.72f, cy);
        airPath.lineTo(cx - u * 0.86f, cy + u * 0.24f);
        airPath.lineTo(cx - u * 0.60f, cy + u * 0.085f);
        airPath.lineTo(cx + u * 0.22f, cy + u * 0.085f);
        airPath.close();
        // 上翼（后掠）
        float wx = cx - u * 0.08f;
        airPath.moveTo(wx + u * 0.24f, cy - u * 0.06f);
        airPath.quadTo(wx - u * 0.18f, cy - u * 0.50f, wx - u * 0.50f, cy - u * 0.82f);
        airPath.quadTo(wx - u * 0.24f, cy - u * 0.36f, wx - u * 0.08f, cy - u * 0.06f);
        airPath.close();
        // 下翼
        airPath.moveTo(wx + u * 0.24f, cy + u * 0.06f);
        airPath.quadTo(wx - u * 0.18f, cy + u * 0.50f, wx - u * 0.50f, cy + u * 0.82f);
        airPath.quadTo(wx - u * 0.24f, cy + u * 0.36f, wx - u * 0.08f, cy + u * 0.06f);
        airPath.close();

        airFill.setStyle(Paint.Style.FILL);
        airFill.setShader(new android.graphics.LinearGradient(
                cx - u, cy - u, cx + u, cy + u,
                new int[]{0xFF7BE8FF, 0xFF3B9CFF, 0xFF2A55D8},
                new float[]{0f, 0.55f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
        cv.drawPath(airPath, airFill);
        airFill.setShader(null);

        airStroke.setStyle(Paint.Style.STROKE);
        airStroke.setStrokeWidth(Math.max(1f, S * 0.05f));
        airStroke.setColor(0x88FFFFFF);
        cv.drawPath(airPath, airStroke);

        cv.restore();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
