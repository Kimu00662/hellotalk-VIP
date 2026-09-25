package com.hellotalk.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

public class SettingsActivity extends Activity {

    // hook 端（HelloTalk 进程）直接读这个文件，故放在双方都可读的 /data/local/tmp。
    public static final String CONFIG_PATH = "/data/local/tmp/htvip_config.txt";

    public static final String KEY_FAKE_VIP = "fake_vip";
    public static final String KEY_PERF_DIAG = "perf_diag";
    public static final String KEY_BLOCK_ADS = "block_ads";
    public static final String KEY_BLOCK_LIVE = "block_live";
    public static final String KEY_BLOCK_ANALYTICS = "block_analytics";
    public static final String KEY_BLOCK_CRASH = "block_crash";

    private static final String PREFS = "htvip";

    // 开关 key -> 界面文案（顺序即显示顺序）
    private static final Map<String, String> TOGGLES = new LinkedHashMap<>();

    static {
        TOGGLES.put(KEY_FAKE_VIP, "假VIP（6.0.90，解锁高级筛选等VIP功能）");
        TOGGLES.put(KEY_BLOCK_ADS, "屏蔽广告（AdMob / Facebook Audience）");
        TOGGLES.put(KEY_BLOCK_LIVE, "屏蔽直播/视频SDK（腾讯播放器，省电省内存）");
        TOGGLES.put(KEY_BLOCK_ANALYTICS, "屏蔽统计与归因（AppsFlyer / 埋点上报）");
        TOGGLES.put(KEY_BLOCK_CRASH, "屏蔽崩溃上报（Bugly / 火山日志）");
        TOGGLES.put(KEY_PERF_DIAG, "性能诊断（记录卡顿/发热现场，临时用）");
    }

    private final Map<String, Switch> switches = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (getResources().getDisplayMetrics().density * 16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("HelloTalk Hook 设置");
        title.setTextSize(20f);
        title.setPadding(0, 0, 0, pad);
        root.addView(title);

        for (Map.Entry<String, String> e : TOGGLES.entrySet()) {
            root.addView(buildRow(e.getKey(), e.getValue(), pad));
        }

        TextView hint = new TextView(this);
        hint.setText("关闭后对 HelloTalk 零修改。改动需保存并重启 HelloTalk 生效（保存需要 root）。"
                + " 屏蔽类开关建议逐个开启、观察，哪个出问题就单独关掉。");
        hint.setTextSize(13f);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, 0, 0, pad);
        root.addView(hint);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> {
            Map<String, Boolean> values = new LinkedHashMap<>();
            SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            for (Map.Entry<String, Switch> e : switches.entrySet()) {
                boolean on = e.getValue().isChecked();
                values.put(e.getKey(), on);
                editor.putBoolean(e.getKey(), on);
            }
            editor.apply();
            if (writeConfig(values)) {
                Toast.makeText(this, "已保存，重启 HelloTalk 生效", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存失败：未获取到 root 权限", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save);

        setContentView(root);
    }

    private LinearLayout buildRow(String key, String text, int pad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad / 2, 0, pad / 2);

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15f);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        Switch sw = new Switch(this);
        sw.setChecked(readConfig(key));
        row.addView(sw);
        switches.put(key, sw);

        return row;
    }

    private boolean readConfig(String key) {
        try {
            File f = new File(CONFIG_PATH);
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith(key + "=")) {
                        r.close();
                        return "true".equalsIgnoreCase(
                                line.substring(key.length() + 1).trim());
                    }
                }
                r.close();
            }
        } catch (Throwable ignored) {
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getBoolean(key, false);
    }

    private boolean writeConfig(Map<String, Boolean> values) {
        StringBuilder fmt = new StringBuilder("printf '");
        StringBuilder args = new StringBuilder();
        for (Map.Entry<String, Boolean> e : values.entrySet()) {
            fmt.append(e.getKey()).append("=%s\\n");
            args.append(' ').append(e.getValue());
        }
        fmt.append("'").append(args).append(" > ").append(CONFIG_PATH)
                .append(" && chmod 644 ").append(CONFIG_PATH);

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", fmt.toString()});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (r.readLine() != null) {
            }
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
