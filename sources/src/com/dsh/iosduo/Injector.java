package com.dsh.iosduo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * 三合一状态栏注入器（OneUI 8.5 / Android 16）v2.1
 *
 * 结构要点：
 *  - 多宿主：每个 hook 到的状态栏根视图（NotificationShadeWindowView / PhoneStatusBarView …）
 *    各自挂一个 ViewOverlay，谁可见谁显示。桌面状态栏挂在 PhoneStatusBarView 上，
 *    锁屏状态栏由 NotificationShadeWindowView 覆盖 —— 不会因为窗口切换而整块消失。
 *  - 原生图标隐藏三重保险：slot 精确隐藏 + View.setVisibility 拦截 + onViewAdded 即时隐藏
 *    + OnPreDraw 兜底（每帧绘制前把漏网的压回 INVISIBLE）。
 *  - 位置只统计“状态栏带内 + 右半屏”，下拉面板/QS 磁贴里的同名控件不参与包围盒（不位移）。
 */
public final class Injector {

    static final String TAG = "IOSDuo";
    private static final HashSet<String> LOGGED = new HashSet<String>();

    static final StatusData data = new StatusData();

    /** 每个根视图一个 overlay drawable。 */
    private static final WeakHashMap<View, DuoRingDrawable> OVL = new WeakHashMap<View, DuoRingDrawable>();
    /** 每个根视图一份位置锁 {cx, cy, side}。 */
    private static final WeakHashMap<View, int[]> POS = new WeakHashMap<View, int[]>();
    /** 已注册 pre-draw 的根视图。 */
    private static final WeakHashMap<View, Boolean> PREDRAW = new WeakHashMap<View, Boolean>();

    private static View lastRoot;
    private static Handler handler;
    private static boolean ticking = false;
    private static boolean recvRegistered = false;
    private static int tickCount = 0;
    private static long lastDraw = 0L;

    private static final WeakHashMap<View, Boolean> TARGETS = new WeakHashMap<View, Boolean>();
    /** 所有发现过的状态栏宿主（即使当前没挂 overlay 也要持续扫描，否则让位后再也回不来）。 */
    private static final WeakHashMap<View, Boolean> HOSTS = new WeakHashMap<View, Boolean>();

    private Injector() {}

    // ---------------------------------------------------------------- ticker

    private static void startTicker() {
        if (ticking) return;
        ticking = true;
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Cfg.maybeReload();
                    data.refreshFallback();
                    ArrayList<View> roots = snapshotRoots();
                    colorRank = -1;
                    for (View r : roots) {
                        if ((tickCount % 5) == 0) scanAndDraw(r);
                        else { DuoRingDrawable d = OVL.get(r); if (d != null) d.invalidateSelf(); }
                    }
                    tickCount++;
                } catch (Throwable ignored) {}
                if (handler != null) handler.postDelayed(this, 250);
            }
        }, 500);
    }

    private static ArrayList<View> snapshotRoots() {
        ArrayList<View> out = new ArrayList<View>();
        synchronized (HOSTS) { out.addAll(HOSTS.keySet()); }
        synchronized (OVL) {
            for (View v : OVL.keySet()) if (!out.contains(v)) out.add(v);
        }
        // 高优先级宿主先处理（颜色/位置以它为准）
        ArrayList<View> sorted = new ArrayList<View>(out);
        try {
            java.util.Collections.sort(sorted, new java.util.Comparator<View>() {
                @Override public int compare(View a, View b) {
                    int ra = rankOf(a), rb = rankOf(b);
                    if (ra == rb) return 0;
                    return ra > rb ? -1 : 1;
                }
            });
        } catch (Throwable ignored) { return out; }
        return sorted;
    }

    /** 宿主优先级：真正贴在状态栏上的视图优先，窗口级兜底其次。 */
    private static int rankOf(View v) {
        try {
            String n = v.getClass().getSimpleName();
            if (n.contains("PhoneStatusBarView")) return 50;
            if (n.contains("KeyguardStatusBarView")) return 40;
            if (n.contains("StatusBarWindowView")) return 30;
            if (n.contains("CentralSurrounding")) return 25;
            if (n.contains("NotificationShadeWindowView")) return 10;
        } catch (Throwable ignored) {}
        return 5;
    }

    private static void redraw() {
        for (View r : snapshotRoots()) {
            try {
                DuoRingDrawable d = OVL.get(r);
                if (d != null) d.invalidateSelf();
                r.invalidate();
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------ hook 入口

    public static void onHook(Object thisObj, Object[] args, boolean isWifi) {
        try {
            parseState(args, isWifi);
            if (thisObj instanceof View) {
                final View v = (View) thisObj;
                String slot = slotOf(v, args);
                if (slot != null && isHiddenSlot(slot)) hide(v, "slot:" + slot);
                else if (isHiddenClass(v)) hide(v, v.getClass().getSimpleName());
                v.post(new Runnable() { @Override public void run() { requestDraw(v); } });
            }
        } catch (Throwable ignored) {}
    }

    public static void onIconView(Object o, Object[] args) {
        if (!(o instanceof View)) return;
        final View v = (View) o;
        try {
            String slot = slotOf(v, args);
            if (slot == null) slot = readString(v, "mSlot", "slot");
            if (slot != null && isHiddenSlot(slot)) {
                hide(v, "slot:" + slot);
                v.post(new Runnable() { @Override public void run() { requestDraw(v); } });
                return;
            }
            if (looksLikeIcon(v)) {
                hide(v, "desc");
                v.post(new Runnable() { @Override public void run() { requestDraw(v); } });
            }
        } catch (Throwable ignored) {}
    }

    public static void onSlot(Object o, String slot) {
        if (!(o instanceof View) || slot == null) return;
        try {
            if (isHiddenSlot(slot)) {
                View v = (View) o;
                hide(v, "slot:" + slot);
                v.post(new Runnable() { @Override public void run() { requestDraw(v); } });
            }
        } catch (Throwable ignored) {}
    }

    /** 拦截 View.setVisibility(VISIBLE)：已登记目标绝不允许被系统改回可见。 */
    public static void onSetVisibility(View v, Object[] args) {
        if (v == null || args == null || args.length == 0) return;
        try {
            Object a = args[0];
            if (!(a instanceof Integer) || ((Integer) a) != View.VISIBLE) return;
            synchronized (TARGETS) {
                if (TARGETS.containsKey(v)) { args[0] = View.INVISIBLE; return; }
            }
            if (isHiddenClass(v) && isInStatusBarArea(v)) {
                synchronized (TARGETS) { TARGETS.put(v, Boolean.TRUE); }
                args[0] = View.INVISIBLE;
            }
        } catch (Throwable ignored) {}
    }

    /** 新 view 进入容器时立刻判定，避免“先显示一帧再隐藏”。 */
    public static void onViewAdded(View child) {
        if (child == null || lastRoot == null) return;
        try {
            if (!isHiddenClass(child) && !looksLikeIcon(child)) return;
            if (!isInStatusBarArea(child)) return;
            hide(child, "added");
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------- 根视图

    public static void scanRoot(View root) { scanRoot(root, 0); }

    public static void scanRoot(View root, int rank) {
        if (root == null) return;
        lastRoot = root;
        synchronized (HOSTS) { try { HOSTS.put(root, Boolean.TRUE); } catch (Throwable ignored) {} }
        try {
            data.init(root.getContext());
            Cfg.maybeReload();
            ensureReceiver(root.getContext());
            startTicker();
            ensurePreDraw(root);
            scanAndDraw(root);
        } catch (Throwable t) {
            log(TAG + " scanRoot err " + t);
        }
    }

    /** 下拉面板展开状态（诊断用）。 */
    public static void onShade(Object o) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    String n = f.getName().toLowerCase();
                    try {
                        if (f.getType() == float.class && n.contains("expandedfraction")) {
                            f.setAccessible(true);
                            shadeFrac = ((Float) f.get(o));
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    static volatile float shadeFrac = 0f;

    private static void ensurePreDraw(final View root) {
        synchronized (PREDRAW) {
            if (PREDRAW.containsKey(root)) return;
            PREDRAW.put(root, Boolean.TRUE);
        }
        try {
            root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    try { quickHide(root); } catch (Throwable ignored) {}
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 绘制前扫一遍：任何漏网目标压回 INVISIBLE，用户看不到那一帧。 */
    private static void quickHide(View root) {
        if (root == null) return;
        int sbh = statusBarHeight(root);
        ArrayDeque<View> q = new ArrayDeque<View>();
        q.add(root);
        int n = 0;
        int[] loc = new int[2];
        while (!q.isEmpty() && n < 1200) {
            View v = q.poll(); n++;
            try {
                if (v.getVisibility() == View.VISIBLE) {
                    if (isHiddenClass(v) || looksLikeIcon(v) || isNetText(v, root)) {
                        v.getLocationInWindow(loc);
                        if (loc[1] < sbh) { hide(v, "predraw"); continue; }
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    int c = g.getChildCount();
                    for (int k = 0; k < c; k++) q.add(g.getChildAt(k));
                }
            } catch (Throwable ignored) {}
        }
    }

    // --------------------------------------------------------------- 扫描

    private static void scanAndDraw(View root) {
        if (root == null) return;
        lastRoot = root;
        try { data.init(root.getContext()); ensureReceiver(root.getContext()); } catch (Throwable ignored) {}

        int sbh = statusBarHeight(root);
        int rootW = root.getWidth();
        int[] loc = new int[2];
        int[] rl = new int[2];
        try { root.getLocationInWindow(rl); } catch (Throwable ignored) {}

        ArrayDeque<View> q = new ArrayDeque<View>();
        q.add(root);
        int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
        boolean any = false;
        int n = 0;
        while (!q.isEmpty() && n < 4000) {
            View v = q.poll(); n++;
            boolean target = isHiddenClass(v) || looksLikeIcon(v);
            boolean netText = (!target && isNetText(v, root));
            if (target || netText) {
                int vx = 0, vy = 0, vw = 0, vh = 0;
                try {
                    v.getLocationInWindow(loc);
                    vx = loc[0] - rl[0]; vy = loc[1] - rl[1];
                    vw = v.getWidth(); vh = v.getHeight();
                } catch (Throwable ignored) {}
                // 只认状态栏带内 + 右半屏的原生图标，避免下拉面板里的同名控件把包围盒拽下去
                boolean inBar = (loc[1] - rl[1]) < sbh && vx > (rootW / 2) - (rootW / 12);
                if (!inBar) {
                    if (v instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) v;
                        for (int k = 0; k < g.getChildCount(); k++) q.add(g.getChildAt(k));
                    }
                    continue;
                }
                if (target) { captureTint(v); parseDesc(v); }
                hide(v, target ? v.getClass().getSimpleName() : "netText");
                any = true;
                if (vw > 0 && vh > 0) {
                    l = Math.min(l, vx); t = Math.min(t, vy);
                    r = Math.max(r, vx + vw); b = Math.max(b, vy + vh);
                }
                continue;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int c = g.getChildCount();
                for (int k = 0; k < c; k++) q.add(g.getChildAt(k));
            }
        }
        if (!any) return;

        updateColor(root);

        // 只让当前唯一该画的宿主持有 overlay；其余宿主把 overlay 摘掉，彻底杜绝重影
        if (!canDrawHere(root)) { ovlDrop(root); return; }

        DuoRingDrawable d = ovlFor(root);
        if (d == null) return;

        if (r > l && b > t) {
            float dens = root.getResources().getDisplayMetrics().density;
            int side = (int) (Cfg.size * dens);
            if (side < 20) side = 20;
            if (sbh > 0 && side > sbh) side = sbh;      // 不允许超出状态栏高度
            int rh = root.getHeight();
            if (rh > 0 && side > rh) side = rh;
            int half = side / 2;

            int cx = ((l + r) / 2);
            int cy = ((t + b) / 2);

            int[] pos;
            synchronized (POS) {
                pos = POS.get(root);
                if (pos == null) { pos = new int[]{-1, -1, -1}; POS.put(root, pos); }
            }
            if (pos[2] == side && pos[0] >= 0) {
                if (Math.abs(cx - pos[0]) <= side && Math.abs(cy - pos[1]) <= side) {
                    cx = pos[0]; cy = pos[1];
                }
            }
            int w = (rootW > 0 ? rootW : cx + half);
            cx = Math.max(half, Math.min(cx, w - half));
            int minCy = half, maxCy = sbh - half;
            if (maxCy < minCy) cy = sbh / 2;            // 图标比状态栏还高：居中放
            else cy = Math.max(minCy, Math.min(cy, maxCy));

            if (cx != pos[0] || cy != pos[1] || pos[2] != side
                    || d.getBounds().width() != side) {
                pos[0] = cx; pos[1] = cy; pos[2] = side;
                d.setBounds(cx - half, cy - half, cx + half, cy + half);
            }
        }

        d.invalidateSelf();
        try { root.invalidate(); } catch (Throwable ignored) {}
    }

    private static void requestDraw(View v) {
        long now = System.currentTimeMillis();
        if (now - lastDraw < 120) return;
        lastDraw = now;
        try { scanAndDraw(lastRoot != null ? lastRoot : v.getRootView()); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ overlay

    /** 内层状态栏视图已经可见并挂了 overlay 时，外层根不要再画（避免重影叠色）。 */
    private static boolean canDrawHere(View root) {
        ArrayList<View> roots = snapshotRoots();
        // 锁屏：KeyguardStatusBarView 可见时它独占，其他宿主一律让位（否则锁屏重影）
        View kg = null;
        for (View v : roots) {
            if (v.getClass().getSimpleName().contains("KeyguardStatusBarView") && reallyVisible(v)) {
                kg = v;
                break;
            }
        }
        if (kg != null) return root == kg;
        // 非锁屏：按宿主优先级，最高的那个可见者画
        for (View other : roots) {
            if (other == root) return true;
            if (reallyVisible(other)) return false;
        }
        return true;
    }

    private static boolean reallyVisible(View v) {
        try {
            if (v == null) return false;
            if (v.getVisibility() != View.VISIBLE) return false;
            if (!v.isShown()) return false;
            if (v.getAlpha() <= 0.01f) return false;
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    private static void ovlDrop(View root) {
        synchronized (OVL) {
            DuoRingDrawable d = OVL.remove(root);
            if (d != null) {
                try { root.getOverlay().remove(d); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isAncestor(View ancestor, View child) {
        try {
            Object p = child.getParent();
            int guard = 0;
            while (p instanceof View && guard++ < 40) {
                if (p == ancestor) return true;
                p = ((View) p).getParent();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static DuoRingDrawable ovlFor(View root) {
        synchronized (HOSTS) { try { HOSTS.put(root, Boolean.TRUE); } catch (Throwable ignored) {} }
        synchronized (OVL) {
            DuoRingDrawable d = OVL.get(root);
            if (d != null) return d;
            try {
                d = new DuoRingDrawable(data, root.getResources().getDisplayMetrics().density);
                if (overlayAdd(root, d)) { OVL.put(root, d); return d; }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean overlayAdd(View v, Drawable d) {
        try {
            Object ov = View.class.getMethod("getOverlay").invoke(v);
            if (ov == null) return false;
            Class<?> c = Class.forName("android.view.ViewOverlay");
            c.getMethod("add", Drawable.class).invoke(ov, d);
            return true;
        } catch (Throwable t) {
            log(TAG + " overlay add err " + t);
            return false;
        }
    }

    // ------------------------------------------------------------- 判定工具

    private static int statusBarHeight(View root) {
        try {
            int id = root.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return root.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        try { return (int) (28 * root.getResources().getDisplayMetrics().density); } catch (Throwable ignored) {}
        return 100;
    }

    private static boolean isInStatusBarArea(View v) {
        try {
            View root = v.getRootView();
            if (root == null) return false;
            int[] loc = new int[2];
            int[] rl = new int[2];
            v.getLocationInWindow(loc);
            root.getLocationInWindow(rl);
            if (loc[1] < 0) return false;
            return (loc[1] - rl[1]) < statusBarHeight(root);
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isHiddenClass(View v) {
        try {
            String cn = v.getClass().getSimpleName().toLowerCase();
            return cn.contains("mobile") || cn.contains("wifi") || cn.contains("wlan")
                    || cn.contains("signal") || cn.contains("battery") || cn.contains("cellular")
                    || cn.contains("sim") || cn.contains("airplane");
        } catch (Throwable ignored) {}
        return false;
    }

    static boolean isHiddenSlot(String slot) {
        if (slot == null) return false;
        String s = slot.toLowerCase();
        return s.contains("wifi") || s.contains("wlan")
                || s.contains("mobile") || s.contains("cellular")
                || s.contains("signal") || s.contains("battery")
                || s.contains("data") || s.contains("activity")
                || s.contains("5g") || s.contains("4g") || s.contains("lte") || s.contains("nr")
                || s.contains("roam") || s.contains("volte") || s.contains("vowifi")
                || s.contains("sim") || s.contains("airplane");
    }

    private static String slotOf(View v, Object[] args) {
        String s = readString(v, "mSlot", "slot");
        if (s != null) return s;
        if (args != null) {
            for (Object a : args) {
                if (a instanceof String) {
                    String t = (String) a;
                    if (t.length() > 0 && t.length() < 40) return t;
                }
            }
        }
        return null;
    }

    private static String readString(Object o, String... names) {
        for (String n : names) {
            try {
                Class<?> c = o.getClass();
                while (c != null) {
                    try {
                        Field f = c.getDeclaredField(n);
                        f.setAccessible(true);
                        Object val = f.get(o);
                        if (val instanceof String) return (String) val;
                        break;
                    } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean looksLikeIcon(View c) {
        if (!(c instanceof ImageView)) return false;
        CharSequence cd = c.getContentDescription();
        if (cd == null) return false;
        String s = cd.toString().toLowerCase();
        if (s.contains("clock") || s.contains("时间") || s.contains("闹钟")) return false;
        return s.contains("wi-fi") || s.contains("wifi") || s.contains("wlan") || s.contains("无线")
                || s.contains("signal") || s.contains("信号") || s.contains("mobile")
                || s.contains("移动") || s.contains("cellular") || s.contains("网络")
                || s.contains("battery") || s.contains("电池") || s.contains("电量")
                || s.contains("5g") || s.contains("4g") || s.contains("lte");
    }

    private static boolean isNetText(View v, View root) {
        try {
            if (!(v instanceof android.widget.TextView)) return false;
            CharSequence c = ((android.widget.TextView) v).getText();
            if (c == null) return false;
            String s = c.toString().trim();
            String u = s.toUpperCase();
            if (s.length() == 0 || s.length() > 6) return false;
            boolean ok = u.startsWith("5G") || u.startsWith("4G") || u.startsWith("3G")
                    || u.startsWith("2G") || u.startsWith("LTE") || u.startsWith("NR")
                    || u.equals("H") || u.equals("H+") || u.equals("E") || u.equals("G");
            if (!ok) return false;
            int[] loc = new int[2];
            int[] rl = new int[2];
            v.getLocationInWindow(loc);
            root.getLocationInWindow(rl);
            return (loc[0] - rl[0]) > root.getWidth() / 2;
        } catch (Throwable ignored) {}
        return false;
    }

    private static void hide(View v, String why) {
        if (v == null) return;
        synchronized (TARGETS) { TARGETS.put(v, Boolean.TRUE); }
        try {
            if (v.getVisibility() != View.INVISIBLE) v.setVisibility(View.INVISIBLE);
        } catch (Throwable ignored) {}
        if (Cfg.debug) logOnce("hide:" + why);
    }

    private static void logOnce(String key) {
        synchronized (LOGGED) {
            if (LOGGED.contains(key)) return;
            LOGGED.add(key);
        }
        log(TAG + ": match " + key);
    }

    private static void log(String s) {
        try { XposedBridge.log(s); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ 取色

    /** LightBarController 的图标明暗模式：1=浅底深字(黑) 2=深底浅字(白) 0=未知。 */
    private static volatile int barMode = 0;
    /** 原生状态栏图标的实际 tint。 */
    private static volatile int tintColor = 0;
    private static long lastBg = 0L;
    private static int bgColor = 0;
    private static int lastLuma = -1;
    private static int lastClock = 0;
    private static String bgErr = "";

    /**
     * 颜色优先级：
     *  0. 设置页手动锁定（Cfg.colorMode = 1 强制黑 / 2 强制白 / 0 自动）
     *  1. 可见宿主里优先级最高的那个决定颜色（避免锁屏大时钟把颜色污染成白）
     *  2. 状态栏时钟文字色 → 背景亮度采样 → 原生 tint → LightBar 明暗
     */
    private static void updateColor(View root) {
        if (Cfg.colorMode == 1) { data.color = 0xFF000000; return; }
        if (Cfg.colorMode == 2) { data.color = 0xFFFFFFFF; return; }

        try { if (root.getVisibility() != View.VISIBLE) return; } catch (Throwable ignored) {}
        int rk = rankOf(root);
        if (rk < colorRank) return;

        Integer cc = findClockColor(root);
        if (cc != null) {
            colorRank = rk;
            lastClock = cc;
            data.color = cc;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBg > 1500) {
            lastBg = now;
            Integer luma = sampleBarLuma(root);
            if (luma != null) {
                lastLuma = luma;
                bgColor = (luma > 150) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        if (bgColor != 0) { colorRank = rk; data.color = bgColor; return; }
        int t = tintColor;
        if (t != 0) { colorRank = rk; data.color = t | 0xFF000000; return; }
        int m = barMode;
        if (m == 1) data.color = 0xFF000000;
        else if (m == 2) data.color = 0xFFFFFFFF;
    }

    private static int colorRank = -1;

    /** 采样状态栏背景亮度（避开左侧时钟、右上图标区）。返回 0..255，失败 null。 */
    private static Integer sampleBarLuma(View root) {
        android.graphics.Bitmap bmp = null;
        try {
            int w = root.getWidth();
            int sbh = statusBarHeight(root);
            if (w <= 0 || sbh <= 0) return null;
            bmp = screenshotBar(w, sbh + 8);
            if (bmp == null) return null;
            int[] xs = {(int) (w * 0.42f), (int) (w * 0.58f)};
            int y = Math.min(sbh / 2, bmp.getHeight() - 1);
            int sum = 0, n = 0;
            for (int x : xs) {
                if (x <= 0 || x >= bmp.getWidth()) continue;
                int px = bmp.getPixel(x, y);
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                sum += (r * 299 + g * 587 + b * 114) / 1000;
                n++;
            }
            return n == 0 ? null : (sum / n);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (bmp != null) bmp.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static android.graphics.Bitmap screenshotBar(int w, int h) {
        android.graphics.Rect r = new android.graphics.Rect(0, 0, w, h);
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            java.lang.reflect.Method m = sc.getMethod("screenshot", android.graphics.Rect.class);
            Object o = m.invoke(null, r);
            if (o instanceof android.graphics.Bitmap) { bgErr = "sc1"; return (android.graphics.Bitmap) o; }
        } catch (Throwable t) { bgErr = "sc1:" + t.getClass().getSimpleName(); }
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            java.lang.reflect.Method m = sc.getMethod("screenshot", android.graphics.Rect.class, int.class, int.class);
            Object o = m.invoke(null, r, 0, 0);
            if (o instanceof android.graphics.Bitmap) { bgErr = "sc2"; return (android.graphics.Bitmap) o; }
        } catch (Throwable t) { bgErr = bgErr + "|sc2:" + t.getClass().getSimpleName(); }
        try {
            Class<?> sc = Class.forName("android.window.ScreenCapture");
            Class<?> argsCls = Class.forName("android.window.ScreenCapture$CaptureArgs");
            Class<?> bCls = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
            Object b = bCls.getConstructor().newInstance();
            try { bCls.getMethod("setSourceCrop", android.graphics.Rect.class).invoke(b, r); } catch (Throwable ignored) {}
            try { bCls.getMethod("setFrameScale", float.class).invoke(b, 1.0f); } catch (Throwable ignored) {}
            Object args = bCls.getMethod("build").invoke(b);
            Object res = sc.getMethod("captureDisplay", argsCls).invoke(null, args);
            Object hb = res.getClass().getMethod("getHardwareBuffer").invoke(res);
            android.graphics.Bitmap hbmp = android.graphics.Bitmap.wrapHardwareBuffer(
                    (android.hardware.HardwareBuffer) hb, null);
            if (hbmp != null) {
                android.graphics.Bitmap soft = hbmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                try { hbmp.recycle(); } catch (Throwable ignored) {}
                if (soft != null) { bgErr = "sc3"; return soft; }
            }
        } catch (Throwable t) { bgErr = bgErr + "|sc3:" + t.getClass().getSimpleName(); }
        return null;
    }

    public static void onLightBar(Object o) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    String n = f.getName().toLowerCase();
                    if (f.getType() == int.class && (n.contains("statusbarmode") || n.contains("lightbarmode"))) {
                        f.setAccessible(true);
                        barMode = f.getInt(o);
                        return;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 状态解析

    private static void parseState(Object[] args, boolean isWifi) {
        if (args == null) return;
        for (Object a : args) {
            if (a == null) continue;
            Integer lv = readInt(a, "level");
            if (lv == null) lv = readInt(a, "signalLevel");
            if (lv == null) lv = readInt(a, "strength");
            if (lv == null) continue;
            int l = Math.max(0, Math.min(4, lv));
            if (isWifi) {
                Boolean conn = readBool(a, "connected");
                data.wifi = (conn != null && !conn) ? -1 : l;
            } else {
                data.signal = l;
            }
            return;
        }
    }

    private static void parseDesc(View c) {
        CharSequence cd = c.getContentDescription();
        if (cd == null) return;
        String s = cd.toString();
        String low = s.toLowerCase();
        boolean wifi = low.contains("wi-fi") || low.contains("wifi") || low.contains("wlan") || low.contains("无线");
        boolean sig = low.contains("signal") || low.contains("信号") || low.contains("mobile")
                || low.contains("移动") || low.contains("cellular") || low.contains("网络");
        if (!wifi && !sig) return;
        int digit = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') digit = ch - '0';
        }
        if (digit < 0) return;
        digit = Math.min(4, digit);
        if (wifi) data.descWifi = digit; else data.descSignal = digit;
    }

    private static Integer readInt(Object o, String name) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name); f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof Integer) return (Integer) v;
                    if (v instanceof Number) return ((Number) v).intValue();
                    return null;
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Boolean readBool(Object o, String name) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name); f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof Boolean) return (Boolean) v;
                    return null;
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void captureTint(View c) {
        Integer col = findColor(c);
        if (col != null && (col >>> 24) != 0) tintColor = col | 0xFF000000;
    }

    private static Integer findColor(View v) {
        try {
            if (v instanceof ImageView) {
                Object csl = ImageView.class.getMethod("getImageTintList").invoke(v);
                if (csl != null) {
                    Object dv = csl.getClass().getMethod("getDefaultColor").invoke(csl);
                    if (dv instanceof Integer) {
                        int c = (Integer) dv;
                        if ((c >>> 24) != 0) return c | 0xFF000000;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    Integer c = findColor(g.getChildAt(i));
                    if (c != null) return c;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 状态栏时钟文字的颜色 = 系统当前前景色。
     * 只在传入的子树里找、只认真正可见且在状态栏带内的 TextView（否则会取到锁屏大时钟的白色）。
     */
    private static Integer findClockColor(View start) {
        try {
            int sbh = statusBarHeight(start);
            int[] rl = new int[2];
            int[] loc = new int[2];
            start.getLocationInWindow(rl);
            ArrayDeque<View> q = new ArrayDeque<View>();
            q.add(start);
            int n = 0;
            while (!q.isEmpty() && n < 900) {
                View v = q.poll(); n++;
                try {
                    if (v instanceof android.widget.TextView && v.getVisibility() == View.VISIBLE) {
                        CharSequence t = ((android.widget.TextView) v).getText();
                        if (t != null) {
                            String s = t.toString();
                            if (s.contains(":") || s.contains("：")) {
                                v.getLocationInWindow(loc);
                                if ((loc[1] - rl[1]) < sbh) {
                                    int c = ((android.widget.TextView) v).getCurrentTextColor();
                                    if ((c >>> 24) != 0) return c | 0xFF000000;
                                }
                            }
                        }
                    }
                    if (v instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) v;
                        for (int k = 0; k < g.getChildCount(); k++) q.add(g.getChildAt(k));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------------------------------------------------------------- 广播

    private static void ensureReceiver(Context c) {
        if (recvRegistered || c == null) return;
        try {
            IntentFilter f = new IntentFilter("com.dsh.iosduo.CONFIG");
            f.addAction("com.dsh.iosduo.DUMP");
            BroadcastReceiver br = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent it) {
                    try {
                        String a = it == null ? null : it.getAction();
                        if ("com.dsh.iosduo.DUMP".equals(a)) { dumpTree(); return; }
                        Cfg.applyFromIntent(it);
                        // 参数变了：位置锁全部作废，让桌面/下拉/锁屏每个宿主都按新参数重算并重绘
                        try { POS.clear(); } catch (Throwable ignored) {}
                        colorRank = -1;
                        try {
                            ArrayList<View> rs = snapshotRoots();
                            if (rs.isEmpty() && lastRoot != null) rs.add(lastRoot);
                            for (View r : rs) {
                                try { scanAndDraw(r); } catch (Throwable ignored) {}
                            }
                            for (View r : rs) { r.invalidate(); }
                        } catch (Throwable ignored) {}
                        redraw();
                    } catch (Throwable ignored) {}
                }
            };
            Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(br, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(br, f);
            }
            recvRegistered = true;
            log(TAG + ": config receiver ready");
        } catch (Throwable t) {
            log(TAG + ": receiver fail " + t);
        }
    }

    /** 自检：把每个宿主的视图树写进 logcat。 */
    private static void dumpTree() {
        try {
            log(TAG + " DUMP roots=" + snapshotRoots().size()
                    + " wifi=" + data.wifi + " sig=" + data.signal + " net=" + data.netType
                    + " batt=" + data.battery + (data.charging ? " charging" : "")
                    + " color=" + Integer.toHexString(data.color)
                    + " barMode=" + barMode
                    + " tint=" + Integer.toHexString(tintColor)
                    + " mode=" + Cfg.colorMode
                    + " luma=" + lastLuma
                    + " bg=" + Integer.toHexString(bgColor)
                    + " clock=" + Integer.toHexString(lastClock)
                    + " src=" + bgErr);
            for (View root : snapshotRoots()) {
                int sbh = statusBarHeight(root);
                int[] rl = new int[2];
                root.getLocationInWindow(rl);
                log(TAG + " DUMPROOT " + root.getClass().getName()
                        + " sbh=" + sbh + " wh=" + root.getWidth() + "x" + root.getHeight()
                        + " xy=" + rl[0] + "," + rl[1]
                        + " vis=" + (root.getVisibility() == View.VISIBLE ? "V" : "I")
                        + " bounds=" + (OVL.get(root) == null ? "none" : OVL.get(root).getBounds()));
                ArrayDeque<View> q = new ArrayDeque<View>();
                q.add(root);
                int n = 0;
                int[] loc = new int[2];
                while (!q.isEmpty() && n < 600) {
                    View v = q.poll(); n++;
                    try {
                        v.getLocationInWindow(loc);
                        int relY = loc[1] - rl[1];
                        if (relY < sbh + 20) {
                            String slot = readString(v, "mSlot", "slot");
                            CharSequence cd = v.getContentDescription();
                            String tx = (v instanceof android.widget.TextView)
                                    ? String.valueOf(((android.widget.TextView) v).getText()) : "";
                            log(TAG + " D " + v.getClass().getSimpleName()
                                    + " slot=" + slot
                                    + " vis=" + (v.getVisibility() == View.VISIBLE ? "V" : (v.getVisibility() == View.INVISIBLE ? "I" : "G"))
                                    + " xy=" + (loc[0] - rl[0]) + "," + relY
                                    + " " + v.getWidth() + "x" + v.getHeight()
                                    + " desc=" + (cd == null ? "" : cd)
                                    + " text=" + tx);
                        }
                        if (v instanceof ViewGroup) {
                            ViewGroup g = (ViewGroup) v;
                            for (int k = 0; k < g.getChildCount(); k++) q.add(g.getChildAt(k));
                        }
                    } catch (Throwable ignored) {}
                }
            }
            log(TAG + " DUMP end");
            for (View root : snapshotRoots()) {
                int[] rl = new int[2];
                int[] loc = new int[2];
                root.getLocationInWindow(rl);
                ArrayDeque<View> q = new ArrayDeque<View>();
                q.add(root);
                int n = 0;
                while (!q.isEmpty() && n < 900) {
                    View v = q.poll(); n++;
                    try {
                        if (v instanceof android.widget.TextView) {
                            CharSequence t = ((android.widget.TextView) v).getText();
                            String s = t == null ? "" : t.toString();
                            if (s.contains(":") || s.contains("：")) {
                                v.getLocationInWindow(loc);
                                log(TAG + " CLK root=" + root.getClass().getSimpleName()
                                        + " text=" + s
                                        + " color=" + Integer.toHexString(((android.widget.TextView) v).getCurrentTextColor())
                                        + " vis=" + v.getVisibility() + " shown=" + v.isShown()
                                        + " xy=" + (loc[0] - rl[0]) + "," + (loc[1] - rl[1]));
                            }
                        }
                        if (v instanceof ViewGroup) {
                            ViewGroup g = (ViewGroup) v;
                            for (int k = 0; k < g.getChildCount(); k++) q.add(g.getChildAt(k));
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            log(TAG + " DUMP failed " + t);
        }
    }
}
