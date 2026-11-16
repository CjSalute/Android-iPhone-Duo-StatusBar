package com.dsh.iosduo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** 分类设置（每类一个圆角黑边卡片）；滑块实时生效；双击滑块输入精确值。 */
public class SettingsActivity extends Activity {

    private SharedPreferences sp;
    private LinearLayout root;

    // 实时下发：滑块/双击改值后合并 40ms 内多次变更，一次性广播给 SystemUI
    private final java.util.LinkedHashMap<String, Float> pending = new java.util.LinkedHashMap<String, Float>();
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean flushScheduled = false;

    private static final class Param {
        String key, label; float min, max, step; int decimals;
        Param(String k, String l, float mn, float mx, float st, int d) {
            key = k; label = l; min = mn; max = mx; step = st; decimals = d;
        }
    }

    private static final Param[] G_ARC = {
            new Param("thickness", "弧线粗细", 0.02f, 0.20f, 0.005f, 3),
            new Param("radius", "半径比例", 0.28f, 0.48f, 0.005f, 3),
            new Param("gap", "缺口角度", 0f, 160f, 2f, 0),
            new Param("arcScale", "弧线大小", 0.4f, 2.5f, 0.05f, 2),
            new Param("arcDx", "弧线左右", -20f, 20f, 0.5f, 1),
            new Param("arcDy", "弧线上下", -20f, 20f, 0.5f, 1)
    };
    private static final Param[] G_SIG = {
            new Param("sigScale", "大小", 0.4f, 2.5f, 0.05f, 2),
            new Param("sigDx", "左右", -20f, 20f, 0.5f, 1),
            new Param("sigDy", "上下", -20f, 20f, 0.5f, 1)
    };
    private static final Param[] G_WIFI = {
            new Param("wifiScale", "大小", 0.4f, 2.5f, 0.05f, 2),
            new Param("wifiDx", "左右", -20f, 20f, 0.5f, 1),
            new Param("wifiDy", "上下", -20f, 20f, 0.5f, 1)
    };
    private static final Param[] G_NET = {
            new Param("netScale", "大小", 0.4f, 2.5f, 0.05f, 2),
            new Param("netDx", "左右", -20f, 20f, 0.5f, 1),
            new Param("netDy", "上下", -20f, 20f, 0.5f, 1)
    };
    private static final Param[] G_NUM = {
            new Param("numScale", "大小", 0.4f, 2.5f, 0.05f, 2),
            new Param("numDx", "左右", -20f, 20f, 0.5f, 1),
            new Param("numDy", "上下", -20f, 20f, 0.5f, 1)
    };
    private static final Param[] G_GLOBAL = {
            new Param("size", "图标大小", 16f, 44f, 1f, 0),
            new Param("offsetX", "整体左右", -20f, 20f, 0.5f, 1),
            new Param("offsetY", "整体上下", -20f, 20f, 0.5f, 1)
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("iosduo", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        int sbh = 0;
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) sbh = getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        if (sbh <= 0) sbh = dp(28);
        final int sbhF = sbh;
        final int padL = pad;
        root.setPadding(pad, sbh + dp(24), pad, pad);
        root.setBackgroundColor(Color.WHITE);
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Android 15+ 强制 edge-to-edge：内容会顶到状态栏下面，这里按真实 insets 再让一次位
        sv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                int top = 0;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                    } else {
                        top = insets.getSystemWindowInsetTop();
                    }
                } catch (Throwable ignored) {}
                if (top <= 0) top = sbhF;
                root.setPadding(padL, top + dp(24), padL, dp(14));
                return insets;
            }
        });

        TextView title = new TextView(this);
        title.setText("参数配置");
        title.setTextSize(22); title.setTextColor(Color.BLACK); title.setGravity(Gravity.CENTER);
        root.addView(title, lp());

        addColorGroup();
        addGroup("整体", G_GLOBAL);
        addGroup("电池弧线", G_ARC);
        addGroup("信号", G_SIG);
        addGroup("WiFi", G_WIFI);
        addGroup("5G / 4G", G_NET);
        addGroup("电池数字", G_NUM);

        TextView hint = new TextView(this);
        hint.setText("双击滑块写入自定义数字");
        hint.setTextSize(14); hint.setTextColor(Color.DKGRAY); hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(14), 0, dp(8));
        root.addView(hint, lp());

        Button reset = new Button(this);
        reset.setText("恢复默认");
        root.addView(reset, lp());
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (String k : new String[]{"thickness","radius","gap","size","offsetX","offsetY"}) sp.edit().remove(k).commit();
                for (String k : new String[]{"sig","wifi","net","num","arc"}) {
                    sp.edit().remove(k + "Scale").remove(k + "Dx").remove(k + "Dy").commit();
                }
                Toast.makeText(SettingsActivity.this, "已恢复默认", Toast.LENGTH_SHORT).show();
                broadcastAll();
                recreate();
            }
        });

        setContentView(sv);
        // 本页是白底，必须声明浅色状态栏，否则系统状态栏图标（含本模块三合一）也是白的看不见
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {}
        // 打开设置页先做一次全量同步，后续改值走 40ms 合并广播
        broadcastAll();
    }

    /** 图标颜色：自动（跟随状态栏背景明暗）/ 强制黑 / 强制白。 */
    private void addColorGroup() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(2), Color.BLACK);
        card.setBackground(bg);
        int cp = dp(12);
        card.setPadding(cp, cp, cp, cp);

        TextView t = new TextView(this);
        t.setText("图标颜色");
        t.setTextSize(17);
        t.setTextColor(Color.BLACK);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(t, lp());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final String[] names = {"自动", "黑色", "白色"};
        int cur = (int) sp.getFloat("colorMode", 0f);
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            Button b = new Button(this);
            b.setText((cur == i ? "● " : "") + names[i]);
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    put("colorMode", mode);
                    Toast.makeText(SettingsActivity.this, "图标颜色：" + names[mode], Toast.LENGTH_SHORT).show();
                    recreate();
                }
            });
        }
        card.addView(row, lp());
        card.addView(hintText("自动 = 亮背景用黑、暗背景用白；白底页面弧线看不清时锁成黑色即可"), lp());

        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c.topMargin = dp(12);
        root.addView(card, c);
    }

    private TextView hintText(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(Color.GRAY);
        tv.setPadding(0, dp(6), 0, 0);
        return tv;
    }

    private void addGroup(String name, Param[] ps) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(2), Color.BLACK);
        card.setBackground(bg);
        int cp = dp(12);
        card.setPadding(cp, cp, cp, cp);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(17);
        t.setTextColor(Color.BLACK);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(t, lp());

        for (Param p : ps) addRow(card, p);

        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c.topMargin = dp(12);
        root.addView(card, c);
    }

    private void addRow(LinearLayout parent, final Param p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView label = new TextView(this);
        label.setText(p.label);
        label.setTextSize(14); label.setTextColor(Color.BLACK);
        row.addView(label, new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));

        final SeekBar sb = new SeekBar(this);
        sb.setMax(Math.round((p.max - p.min) / p.step));
        float cur = clamp(sp.getFloat(p.key, def(p.key)), p.min, p.max);
        sb.setProgress(Math.round((cur - p.min) / p.step));
        row.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(this);
        value.setTextSize(14); value.setTextColor(Color.BLACK); value.setGravity(Gravity.END);
        value.setText(fmt(roundTo(p.min + sb.getProgress() * p.step, p.decimals), p.decimals));
        row.addView(value, new LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(row, lp());

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                float val = roundTo(p.min + prog * p.step, p.decimals);
                value.setText(fmt(val, p.decimals));
                put(p.key, val);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { showInput(p, sb, value); return true; }
        });
        sb.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) { gd.onTouchEvent(ev); return false; }
        });
    }

    private void showInput(final Param p, final SeekBar sb, final TextView value) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setText(fmt(roundTo(p.min + sb.getProgress() * p.step, p.decimals), p.decimals));
        new AlertDialog.Builder(this)
                .setTitle(p.label)
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            float v = clamp(Float.parseFloat(et.getText().toString().trim()), p.min, p.max);
                            v = roundTo(v, p.decimals);
                            sb.setProgress(Math.round((v - p.min) / p.step));
                            value.setText(fmt(v, p.decimals));
                            put(p.key, v);
                        } catch (Throwable ignored) {}
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void put(String key, float v) {
        sp.edit().putFloat(key, v).commit();
        pending.put(key, v);
        if (!flushScheduled) {
            flushScheduled = true;
            ui.postDelayed(new Runnable() { @Override public void run() { flush(); } }, 40);
        }
    }

    /** 把待下发参数一次性发给 SystemUI（同一包内运行时接收器，实时生效）。 */
    private void flush() {
        flushScheduled = false;
        if (pending.isEmpty()) return;
        try {
            Intent it = new Intent("com.dsh.iosduo.CONFIG");
            it.setPackage("com.android.systemui");
            for (java.util.Map.Entry<String, Float> e : pending.entrySet()) {
                it.putExtra(e.getKey(), e.getValue());
            }
            sendBroadcast(it);
        } catch (Throwable ignored) {}
        pending.clear();
        writeMediaConf();
    }

    /** 广播全部参数给 SystemUI，实时生效。 */
    private void broadcastAll() {
        try {
            Intent it = new Intent("com.dsh.iosduo.CONFIG");
            it.setPackage("com.android.systemui");
            for (String k : Cfg.KEYS) it.putExtra(k, sp.getFloat(k, def(k)));
            sendBroadcast(it);
        } catch (Throwable ignored) {}
        writeMediaConf();
    }

    /**
     * 再写一份到 /sdcard/Android/media/com.dsh.iosduo/iosduo.conf。
     * Android 16 把 app 的 SharedPreferences 挪到了 /data/misc/apexdata（其它进程读不到），
     * XSharedPreferences 因此失效；这个目录是少数“别人能读、自己不用权限就能写”的位置。
     */
    private void writeMediaConf() {
        java.io.FileOutputStream fos = null;
        try {
            java.io.File dir = new java.io.File("/sdcard/Android/media/com.dsh.iosduo");
            if (!dir.exists()) dir.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("# IOSDuo params (auto-generated, do not edit)\n");
            for (String k : Cfg.KEYS) sb.append(k).append('=').append(sp.getFloat(k, def(k))).append('\n');
            fos = new java.io.FileOutputStream(new java.io.File(dir, "iosduo.conf"));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    private float def(String key) {
        if ("thickness".equals(key)) return 0.10f;
        if ("radius".equals(key)) return 0.36f;
        if ("size".equals(key)) return 26f;
        if ("gap".equals(key)) return 60f;
        if (key.endsWith("Scale")) return 1f;
        return 0f;
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static float roundTo(float v, int d) { double m = Math.pow(10, d); return (float) (Math.round(v * m) / m); }
    private static String fmt(float v, int d) {
        if (d == 0) return String.valueOf((int) v);
        return String.format(java.util.Locale.US, "%." + d + "f", v);
    }
    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        return p;
    }
    private int dp(int v) { return (int) (getResources().getDisplayMetrics().density * v); }
}
