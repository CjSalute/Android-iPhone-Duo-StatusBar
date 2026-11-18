package com.dsh.iosduo;

import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

/**
 * 三合一状态栏 hook 入口（OneUI 8.5 / Android 16 适配版 v2.2）
 *
 * 新增：
 *  - BatteryMeterView 直接 hook（原生电池不再靠类名扫描，插拔充电器不闪）
 *  - StatusBarIconView#setSlot(String) 精确 hook（按 slot 隐藏 wifi/mobile/battery/5G）
 *  - View.setVisibility 拦截（已被隐藏的图标不可能被系统改回可见）
 *  - ViewGroup.onViewAdded 即时隐藏（新图标 add 进容器的第一帧就压掉）
 *  - 多个根视图各自挂 overlay（桌面 / 锁屏 / 下拉各用各的，不会整块消失）
 */
public class Module implements IXposedHookLoadPackage {

    static final String TAG = "IOSDuo";
    static final String VER = "2.8-rb";

    private static final String[] MOBILE = {
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView",
            "com.android.systemui.statusbar.phone.StatusBarMobileView",
            "com.android.systemui.statusbar.StatusBarMobileView",
            "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView"
    };
    private static final String[] WIFI = {
            "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView",
            "com.android.systemui.statusbar.phone.StatusBarWifiView",
            "com.android.systemui.statusbar.StatusBarWifiView"
    };
    private static final String[] BATTERY = {
            "com.android.systemui.battery.BatteryMeterView",
            "com.android.systemui.BatteryMeterView",
            "com.android.systemui.statusbar.phone.BatteryMeterView"
    };
    private static final String[] ICONVIEW = {
            "com.android.systemui.statusbar.StatusBarIconView",
            "com.android.systemui.statusbar.phone.ui.StatusBarIconView",
            "com.android.systemui.statusbar.phone.StatusBarIconView"
    };
    /** 状态栏根视图（都会尝试挂 overlay，谁可见谁显示）。 */
    private static final String[] ROOT = {
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.statusbar.phone.StatusBarWindowView",
            "com.android.systemui.statusbar.phone.CentralSurrounding",
            "com.android.systemui.keyguard.KeyguardStatusBarView",
            "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
    };
    private static final String[] LIGHTBAR = {
            "com.android.systemui.statusbar.phone.LightBarController",
            "com.android.systemui.statusbar.LightBarController"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (lp == null || lp.packageName == null) return;
        if (!"com.android.systemui".equals(lp.packageName)) return;
        XposedBridge.log(TAG + " v" + VER + " loaded in " + lp.processName);

        // Process 起来的第一时间就把作者设置读进来（不依赖 Context），
        // 否则 SystemUI 首批绘制的几秒会用默认参数。
        try { Cfg.initEarly(); } catch (Throwable ignored) {}

        for (String s : MOBILE) hookState(lp.classLoader, s, false);
        for (String s : WIFI) hookState(lp.classLoader, s, true);
        for (String s : BATTERY) hookView(lp.classLoader, s);
        for (String s : ICONVIEW) hookIconView(lp.classLoader, s);
        for (String s : ROOT) hookRoot(lp.classLoader, s);
        for (String s : LIGHTBAR) hookSimple(lp.classLoader, s, false);

        hookVisibility();
        hookViewAdded();
    }

    // ---------------------------------------------------------------- 状态类

    private static void hookState(ClassLoader cl, String name, final boolean wifi) {
        Class<?> c = find(cl, name);
        if (c == null) return;
        XposedBridge.log(TAG + ": hook " + name);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!(p.thisObject instanceof View)) return;
                    Injector.onHook(p.thisObject, p.args, wifi);
                } catch (Throwable ignored) {}
            }
        };
        try { XposedBridge.hookAllConstructors(c, cb); } catch (Throwable ignored) {}
        Method[] ms = methods(c);
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "(?i).*(state|update|apply|onlayout|setvisibility|seticon|refresh|bind|onattach).*");
        int n = 0;
        for (Method m : ms) {
            if (!pat.matcher(m.getName()).matches()) continue;
            try { XposedBridge.hookMethod(m, cb); n++; } catch (Throwable ignored) {}
        }
        if (n == 0) for (Method m : ms) { try { XposedBridge.hookMethod(m, cb); } catch (Throwable ignored) {} }
    }

    /** 电池等自绘/自带控件：构造 + 全部方法都尝试隐藏。 */
    private static void hookView(ClassLoader cl, String name) {
        Class<?> c = find(cl, name);
        if (c == null) return;
        XposedBridge.log(TAG + ": view " + name);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!(p.thisObject instanceof View)) return;
                    Injector.onHook(p.thisObject, p.args, false);
                } catch (Throwable ignored) {}
            }
        };
        try { XposedBridge.hookAllConstructors(c, cb); } catch (Throwable ignored) {}
        for (Method m : methods(c)) { try { XposedBridge.hookMethod(m, cb); } catch (Throwable ignored) {} }
    }

    private static void hookIconView(ClassLoader cl, String name) {
        Class<?> c = find(cl, name);
        if (c == null) return;
        XposedBridge.log(TAG + ": iconview " + name);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { Injector.onIconView(p.thisObject, p.args); } catch (Throwable ignored) {}
            }
        };
        try { XposedBridge.hookAllConstructors(c, cb); } catch (Throwable ignored) {}
        for (Method m : methods(c)) { try { XposedBridge.hookMethod(m, cb); } catch (Throwable ignored) {} }

        // 精确 slot hook：setSlot(String)
        for (Method m : methods(c)) {
            if (!"setSlot".equals(m.getName())) continue;
            if (m.getParameterTypes().length != 1) continue;
            if (m.getParameterTypes()[0] != String.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { Injector.onSlot(p.thisObject, (String) p.args[0]); } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log(TAG + ": setSlot hooked on " + name);
            } catch (Throwable ignored) {}
        }
    }

    // --------------------------------------------------------------- 根视图

    private static void hookRoot(ClassLoader cl, String name) {
        Class<?> c = find(cl, name);
        if (c == null) return;
        XposedBridge.log(TAG + ": root " + name);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object t = p.thisObject;
                if (!(t instanceof View)) return;
                final View v = (View) t;
                v.post(new Runnable() { @Override public void run() { Injector.scanRoot(v); } });
                v.postDelayed(new Runnable() { @Override public void run() { Injector.scanRoot(v); } }, 1200);
                v.postDelayed(new Runnable() { @Override public void run() { Injector.scanRoot(v); } }, 3500);
            }
        };
        try { XposedBridge.hookAllConstructors(c, cb); } catch (Throwable ignored) {}
    }

    private static void hookSimple(ClassLoader cl, String name, final boolean shade) {
        Class<?> c = find(cl, name);
        if (c == null) return;
        XposedBridge.log(TAG + ": " + (shade ? "shade " : "lightbar ") + name);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (shade) Injector.onShade(p.thisObject); else Injector.onLightBar(p.thisObject);
                } catch (Throwable ignored) {}
            }
        };
        try { XposedBridge.hookAllConstructors(c, cb); } catch (Throwable ignored) {}
        for (Method m : methods(c)) { try { XposedBridge.hookMethod(m, cb); } catch (Throwable ignored) {} }
    }

    // ------------------------------------------------------- framework 级拦截

    /** 任何把目标图标改回 VISIBLE 的行为都当场压回去（插电/切应用闪现的根治点）。 */
    private static void hookVisibility() {
        try {
            Method m = View.class.getDeclaredMethod("setVisibility", int.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { Injector.onSetVisibility((View) p.thisObject, p.args); } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log(TAG + ": View.setVisibility hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setVisibility hook failed " + t);
        }
    }

    /** 新控件加入容器时立刻判定，杜绝“先画一帧再隐藏”。 */
    private static void hookViewAdded() {
        try {
            Method m = ViewGroup.class.getDeclaredMethod("onViewAdded", View.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { Injector.onViewAdded((View) p.args[0]); } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log(TAG + ": ViewGroup.onViewAdded hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": onViewAdded hook failed " + t);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Class<?> find(ClassLoader cl, String name) {
        try { return XposedHelpers.findClass(name, cl); } catch (Throwable ignored) {}
        return null;
    }

    private static Method[] methods(Class<?> c) {
        try { return c.getDeclaredMethods(); } catch (Throwable t) { return new Method[0]; }
    }
}
