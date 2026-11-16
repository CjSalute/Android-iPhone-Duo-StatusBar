package com.dsh.iosduo;

import android.content.Intent;
import de.robv.android.xposed.XSharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** 运行期可改参数：读 /sdcard/Download/DSH-Go/iosduo.conf 等路径，改完约 1.5s 自动生效。 */
public final class Cfg {
    public static volatile float thickness = 0.10f; // 弧线/点亮粗细（占图标边长的比例）
    public static volatile float offsetX = 0f;        // 左右偏移（dp）
    public static volatile float offsetY = 0f;        // 上下偏移（dp）
    public static volatile float size = 26f;          // 图标尺寸（dp）
    public static volatile float gap = 60f;           // 顶部缺口角度
    public static volatile float radius = 0.36f;      // 圆半径（占图标边长比例）

    // 组件级：缩放 / 左右 / 上下
    public static volatile int colorMode = 0; // 0自动 1黑 2白
    public static volatile float sigScale = 1f, sigDx = 0f, sigDy = 0f;
    public static volatile float wifiScale = 1f, wifiDx = 0f, wifiDy = 0f;
    public static volatile float netScale = 1f, netDx = 0f, netDy = 0f;
    public static volatile float numScale = 1f, numDx = 0f, numDy = 0f;
    public static volatile float arcScale = 1f, arcDx = 0f, arcDy = 0f;

    /** 诊断开关：true 时把每次隐藏原因写 logcat（默认关，靠 DUMP 广播按需输出）。 */
    public static volatile boolean debug = false;

    private static long lastCheck = 0L;
    private static long lastMod = -2L;
    private static XSharedPreferences xsp;
    public static volatile boolean liveLocked = false;

    private static final String[] PATHS = {
            "/sdcard/Download/DSH-Go/iosduo.conf",
            "/storage/emulated/0/Download/DSH-Go/iosduo.conf",
            "/sdcard/iosduo.conf",
            "/data/local/tmp/iosduo.conf"
    };

    private Cfg() {}

    public static void maybeReload() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 300) return;
        lastCheck = now;
        if (liveLocked) return; // 已用广播实时值，避免被旧配置覆盖

        // 1) 优先读设置 App 写入的 SharedPreferences（XSharedPreferences）
        boolean fromXsp = false;
        try {
            if (xsp == null) xsp = new XSharedPreferences("com.dsh.iosduo", "iosduo");
            xsp.reload();
            if (xsp.contains("colorMode")) { colorMode = (int) clamp(xsp.getFloat("colorMode", colorMode), 0f, 2f); fromXsp = true; }
            if (xsp.contains("thickness")) { thickness = clamp(xsp.getFloat("thickness", thickness), 0.02f, 0.20f); fromXsp = true; }
            if (xsp.contains("offsetX"))   { offsetX   = clamp(xsp.getFloat("offsetX", offsetX), -20f, 20f); fromXsp = true; }
            if (xsp.contains("offsetY"))   { offsetY   = clamp(xsp.getFloat("offsetY", offsetY), -20f, 20f); fromXsp = true; }
            if (xsp.contains("size"))      { size      = clamp(xsp.getFloat("size", size), 16f, 44f); fromXsp = true; }
            if (xsp.contains("gap"))       { gap       = clamp(xsp.getFloat("gap", gap), 0f, 160f); fromXsp = true; }
            if (xsp.contains("radius"))    { radius    = clamp(xsp.getFloat("radius", radius), 0.28f, 0.48f); fromXsp = true; }
            if (xsp.contains("sigScale")) { sigScale = clamp(xsp.getFloat("sigScale", sigScale), 0.4f, 2.5f); fromXsp = true; }
            if (xsp.contains("sigDx")) { sigDx = clamp(xsp.getFloat("sigDx", sigDx), -20f, 20f); fromXsp = true; }
            if (xsp.contains("sigDy")) { sigDy = clamp(xsp.getFloat("sigDy", sigDy), -20f, 20f); fromXsp = true; }
            if (xsp.contains("wifiScale")) { wifiScale = clamp(xsp.getFloat("wifiScale", wifiScale), 0.4f, 2.5f); fromXsp = true; }
            if (xsp.contains("wifiDx")) { wifiDx = clamp(xsp.getFloat("wifiDx", wifiDx), -20f, 20f); fromXsp = true; }
            if (xsp.contains("wifiDy")) { wifiDy = clamp(xsp.getFloat("wifiDy", wifiDy), -20f, 20f); fromXsp = true; }
            if (xsp.contains("netScale")) { netScale = clamp(xsp.getFloat("netScale", netScale), 0.4f, 2.5f); fromXsp = true; }
            if (xsp.contains("netDx")) { netDx = clamp(xsp.getFloat("netDx", netDx), -20f, 20f); fromXsp = true; }
            if (xsp.contains("netDy")) { netDy = clamp(xsp.getFloat("netDy", netDy), -20f, 20f); fromXsp = true; }
            if (xsp.contains("numScale")) { numScale = clamp(xsp.getFloat("numScale", numScale), 0.4f, 2.5f); fromXsp = true; }
            if (xsp.contains("numDx")) { numDx = clamp(xsp.getFloat("numDx", numDx), -20f, 20f); fromXsp = true; }
            if (xsp.contains("numDy")) { numDy = clamp(xsp.getFloat("numDy", numDy), -20f, 20f); fromXsp = true; }
            if (xsp.contains("arcScale")) { arcScale = clamp(xsp.getFloat("arcScale", arcScale), 0.4f, 2.5f); fromXsp = true; }
            if (xsp.contains("arcDx")) { arcDx = clamp(xsp.getFloat("arcDx", arcDx), -20f, 20f); fromXsp = true; }
            if (xsp.contains("arcDy")) { arcDy = clamp(xsp.getFloat("arcDy", arcDy), -20f, 20f); fromXsp = true; }
            if (xsp.contains("debug")) { debug = xsp.getFloat("debug", debug ? 1f : 0f) > 0.5f; }
        } catch (Throwable ignored) {}
        if (fromXsp) return;

        // 2) 兜底：读 conf 文件
        for (String p : PATHS) {
            try {
                File f = new File(p);
                if (!f.exists() || !f.canRead()) continue;
                long m = f.lastModified();
                if (m == lastMod) return;
                parse(f);
                lastMod = m;
                return;
            } catch (Throwable ignored) {}
        }
    }

    private static void parse(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim().toLowerCase();
                String v = line.substring(eq + 1).trim();
                int sp = v.indexOf(' '); if (sp > 0) v = v.substring(0, sp);
                float n;
                try { n = Float.parseFloat(v); } catch (Throwable t) { continue; }
                if ("thickness".equals(k)) thickness = clamp(n, 0.02f, 0.20f);
                else if ("offsetx".equals(k)) offsetX = clamp(n, -20f, 20f);
                else if ("offsety".equals(k)) offsetY = clamp(n, -20f, 20f);
                else if ("size".equals(k)) size = clamp(n, 16f, 44f);
                else if ("gap".equals(k)) gap = clamp(n, 0f, 160f);
                else if ("radius".equals(k)) radius = clamp(n, 0.28f, 0.48f);
                else if ("colormode".equals(k)) colorMode = (int) clamp(n, 0f, 2f);
                else if ("sigscale".equals(k)) sigScale = clamp(n, 0.4f, 2.5f);
                else if ("sigdx".equals(k)) sigDx = clamp(n, -20f, 20f);
                else if ("sigdy".equals(k)) sigDy = clamp(n, -20f, 20f);
                else if ("wifiscale".equals(k)) wifiScale = clamp(n, 0.4f, 2.5f);
                else if ("wifidx".equals(k)) wifiDx = clamp(n, -20f, 20f);
                else if ("wifidy".equals(k)) wifiDy = clamp(n, -20f, 20f);
                else if ("netscale".equals(k)) netScale = clamp(n, 0.4f, 2.5f);
                else if ("netdx".equals(k)) netDx = clamp(n, -20f, 20f);
                else if ("netdy".equals(k)) netDy = clamp(n, -20f, 20f);
                else if ("numscale".equals(k)) numScale = clamp(n, 0.4f, 2.5f);
                else if ("numdx".equals(k)) numDx = clamp(n, -20f, 20f);
                else if ("numdy".equals(k)) numDy = clamp(n, -20f, 20f);
                else if ("arcscale".equals(k)) arcScale = clamp(n, 0.4f, 2.5f);
                else if ("arcdx".equals(k)) arcDx = clamp(n, -20f, 20f);
                else if ("arcdy".equals(k)) arcDy = clamp(n, -20f, 20f);
                else if ("debug".equals(k)) debug = n > 0.5f;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static final String[] KEYS = {
            "thickness","radius","gap","size","offsetX","offsetY","colorMode",
            "sigScale","sigDx","sigDy","wifiScale","wifiDx","wifiDy",
            "netScale","netDx","netDy","numScale","numDx","numDy",
            "arcScale","arcDx","arcDy"
    };

    /** 直接写入（供广播实时更新）。 */
    public static void set(String k, float n) {
        if ("thickness".equals(k)) thickness = clamp(n, 0.02f, 0.20f);
        else if ("radius".equals(k)) radius = clamp(n, 0.28f, 0.48f);
        else if ("gap".equals(k)) gap = clamp(n, 0f, 160f);
        else if ("size".equals(k)) size = clamp(n, 16f, 44f);
        else if ("offsetX".equals(k)) offsetX = clamp(n, -20f, 20f);
        else if ("offsetY".equals(k)) offsetY = clamp(n, -20f, 20f);
        else if ("colorMode".equals(k)) colorMode = (int) clamp(n, 0f, 2f);
        else if ("sigScale".equals(k)) sigScale = clamp(n, 0.4f, 2.5f);
        else if ("sigDx".equals(k)) sigDx = clamp(n, -20f, 20f);
        else if ("sigDy".equals(k)) sigDy = clamp(n, -20f, 20f);
        else if ("wifiScale".equals(k)) wifiScale = clamp(n, 0.4f, 2.5f);
        else if ("wifiDx".equals(k)) wifiDx = clamp(n, -20f, 20f);
        else if ("wifiDy".equals(k)) wifiDy = clamp(n, -20f, 20f);
        else if ("netScale".equals(k)) netScale = clamp(n, 0.4f, 2.5f);
        else if ("netDx".equals(k)) netDx = clamp(n, -20f, 20f);
        else if ("netDy".equals(k)) netDy = clamp(n, -20f, 20f);
        else if ("numScale".equals(k)) numScale = clamp(n, 0.4f, 2.5f);
        else if ("numDx".equals(k)) numDx = clamp(n, -20f, 20f);
        else if ("numDy".equals(k)) numDy = clamp(n, -20f, 20f);
        else if ("arcScale".equals(k)) arcScale = clamp(n, 0.4f, 2.5f);
        else if ("arcDx".equals(k)) arcDx = clamp(n, -20f, 20f);
        else if ("arcDy".equals(k)) arcDy = clamp(n, -20f, 20f);
    }

    public static void applyFromIntent(Intent it) {
        if (it == null) return;
        liveLocked = true;
        for (String k : KEYS) {
            if (it.hasExtra(k)) {
                try { set(k, it.getFloatExtra(k, 0f)); } catch (Throwable ignored) {}
            }
        }
    }
}
