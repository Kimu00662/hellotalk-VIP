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

public class SettingsActivity extends Activity {

    // hook 端（HelloTalk 进程）直接读这个文件，故放在双方都可读的 /data/local/tmp。
    public static final String CONFIG_PATH = "/data/local/tmp/htvip_config.txt";
    public static final String KEY_FAKE_VIP = "fake_vip";
    public static final String KEY_PERF_DIAG = "perf_diag";

    private static final String PREFS = "htvip";

    private Switch swFakeVip;
    private Switch swPerfDiag;

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

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad / 2, 0, pad / 2);

        TextView label = new TextView(this);
        label.setText("假VIP（6.0.90，解锁高级筛选等VIP功能）");
        label.setTextSize(15f);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        swFakeVip = new Switch(this);
        swFakeVip.setChecked(readConfig(KEY_FAKE_VIP));
        row.addView(swFakeVip);
        root.addView(row);

        // 性能诊断（临时）
        LinearLayout rowPerf = new LinearLayout(this);
        rowPerf.setOrientation(LinearLayout.HORIZONTAL);
        rowPerf.setGravity(Gravity.CENTER_VERTICAL);
        rowPerf.setPadding(0, pad / 2, 0, pad / 2);

        TextView labelPerf = new TextView(this);
        labelPerf.setText("性能诊断（记录卡顿/发热现场，临时用）");
        labelPerf.setTextSize(15f);
        labelPerf.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rowPerf.addView(labelPerf);

        swPerfDiag = new Switch(this);
        swPerfDiag.setChecked(readConfig(KEY_PERF_DIAG));
        rowPerf.addView(swPerfDiag);
        root.addView(rowPerf);

        TextView hint = new TextView(this);
        hint.setText("关闭后对 HelloTalk 零修改。改动需保存并重启 HelloTalk 生效（保存需要 root）。");
        hint.setTextSize(13f);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, 0, 0, pad);
        root.addView(hint);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> {
            boolean fakeVip = swFakeVip.isChecked();
            boolean perfDiag = swPerfDiag.isChecked();
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_FAKE_VIP, fakeVip)
                    .putBoolean(KEY_PERF_DIAG, perfDiag)
                    .apply();
            if (writeConfig(fakeVip, perfDiag)) {
                Toast.makeText(this, "已保存，重启 HelloTalk 生效", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存失败：未获取到 root 权限", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save);

        setContentView(root);
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

    private boolean writeConfig(boolean fakeVip, boolean perfDiag) {
        String cmd = "printf '" + KEY_FAKE_VIP + "=%s\\n" + KEY_PERF_DIAG + "=%s\\n' "
                + fakeVip + " " + perfDiag + " > " + CONFIG_PATH
                + " && chmod 644 " + CONFIG_PATH;

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (r.readLine() != null) {
            }
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
