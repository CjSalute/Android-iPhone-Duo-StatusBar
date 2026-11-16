package com.dsh.iosduo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.telephony.TelephonyManager;

/**
 * 电池 / WiFi / 信号 / 网络制式 数据源。
 *
 * 设计原则：状态栏图标“该不该显示 WiFi / 5G”这类互斥判断必须由系统服务裁决，
 * 不能只依赖 hook 到的对象字段（OneUI 各机型字段名不一致，会误判成“WiFi 未连”从而
 * 一直画 5G）。hook 解析值只在系统服务拿不到时兜底。
 */
public class StatusData {

    public volatile int battery = 100;       // 0..100
    public volatile boolean charging = false;
    public volatile int wifi = -1;           // -1 无连接, 0..4 已连接
    public volatile int signal = -1;         // 0..4
    public volatile int color = 0;           // 0=未捕获
    public volatile String netType = "";     // 5G/4G/3G/2G，WiFi 已连时强制为空

    public volatile int descWifi = -1;       // 从 contentDescription 解析到的兜底值
    public volatile int descSignal = -1;

    private Context ctx;
    private long lastQuery = 0L;

    private final BroadcastReceiver recv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { readBattery(i); }
    };

    public void init(Context c) {
        if (ctx != null || c == null) return;
        try { ctx = c.getApplicationContext(); } catch (Throwable t) { ctx = c; }
        try {
            Intent s = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (s != null) readBattery(s);
        } catch (Throwable ignored) {}
        try { ctx.registerReceiver(recv, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); } catch (Throwable ignored) {}
    }

    private void readBattery(Intent i) {
        int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (lvl >= 0 && sc > 0) battery = Math.round(lvl * 100f / sc);
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
    }

    /** 由 ticker 周期调用：600ms 一次，全部走系统服务，状态永远自洽。 */
    public void refreshFallback() {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (now - lastQuery < 600) return;
        lastQuery = now;

        boolean wifiConnected = false;
        int wifiLevel = -1;

        // 1) ConnectivityManager：WiFi 是否真的连上了（含已校验连通性）
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network[] ns = cm.getAllNetworks();
                if (ns != null) {
                    for (Network n : ns) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                        if (nc == null) continue;
                        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            // 只要有 WIFI transport 就算已连接（VALIDATED 在部分网络下不置位）
                            wifiConnected = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2) WifiManager：信号等级（0..4）
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getNetworkId() != -1) {
                    wifiConnected = true;
                    wifiLevel = WifiManager.calculateSignalLevel(wi.getRssi(), 5);
                }
            }
        } catch (Throwable ignored) {}

        if (wifiConnected) {
            if (wifiLevel < 0) wifiLevel = (descWifi >= 0 ? descWifi : 3);
            wifi = Math.max(0, Math.min(4, wifiLevel));
        } else {
            wifi = -1;
        }

        // 3) 移动网络：等级 + 制式。WiFi 已连接时不显示 5G（互斥）。
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                Object ss = tm.getClass().getMethod("getSignalStrength").invoke(tm);
                Object lv = ss == null ? null : ss.getClass().getMethod("getLevel").invoke(ss);
                int l = (lv instanceof Integer) ? (Integer) lv : -1;
                if (l < 0 && descSignal >= 0) l = descSignal;
                signal = Math.max(0, Math.min(4, l));

                String nt = "";
                try {
                    Object ty = tm.getClass().getMethod("getDataNetworkType").invoke(tm);
                    if (ty instanceof Integer) nt = mapNet((Integer) ty);
                } catch (Throwable ignored) {}
                if (nt.length() == 0 && descSignal >= 0) nt = netType;
                // WiFi 连接时强制清空，避免 “WiFi 图标 + 5G” 同时出现
                netType = wifiConnected ? "" : nt;
            }
        } catch (Throwable ignored) {}
    }

    static String mapNet(int t) {
        switch (t) {
            case 20: return "5G";
            case 13: return "4G";
            case 15: case 12: case 10: case 9: case 3: return "3G";
            case 2: case 1: case 16: return "2G";
            default: return "";
        }
    }
}
